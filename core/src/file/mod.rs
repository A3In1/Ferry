//! File channel: offer → accept (with resume offsets) → one data stream per file
//! → whole-file BLAKE3 check → atomic rename into the destination folder.
//!
//! Wire flow (control = one bi stream, prefix CH_FILE):
//!   S→R Offer · R→S OfferReply · per file S opens a uni stream (prefix CH_FILE,
//!   DataHeader, raw bytes from `offset` to end) · S→R ItemDone · R→S ItemResult.
//! Partial files survive in `<dest>/.ferry-partial/` so a retried offer resumes.
//!
//! Cancel: either side resets every stream it writes and stops every stream it reads with
//! [`ERR_CANCELLED`]; the other side reads that code as "cancelled on the other device".
//! A cancelled transfer's partial files are deleted (a lost connection keeps them for resume).
//!
//! The receiver registers a transfer before sending OfferReply, so a data stream
//! can never arrive for a transfer it does not know yet.

use std::collections::{HashMap, HashSet};
use std::fs;
use std::io::ErrorKind;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, UNIX_EPOCH};

use anyhow::{Context, Result, anyhow, bail};
use iroh::endpoint::{
    Connection, ReadError, ReadExactError, RecvStream, SendStream, StoppedError, VarInt, WriteError,
};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWriteExt};
use tokio::sync::watch;
use tokio::task::JoinSet;
use tokio_util::sync::CancellationToken;

use crate::channel::{CH_FILE, read_msg, write_msg, write_prefix};
use crate::proto::{DataHeader, FileItem, ItemAccept, ItemDone, ItemResult, Offer, OfferReply};
use crate::sanitize;

/// Progress and results, for CLI output and app UI.
#[derive(Debug, Clone)]
pub enum FileEvent {
    /// Receiver: a peer offered files.
    Offered {
        transfer_id: [u8; 16],
        names: Vec<String>,
        total_bytes: u64,
    },
    Progress {
        transfer_id: [u8; 16],
        index: u32,
        done: u64,
        total: u64,
    },
    Saved {
        transfer_id: [u8; 16],
        index: u32,
        path: PathBuf,
    },
    Failed {
        transfer_id: [u8; 16],
        index: u32,
        error: String,
    },
    /// Sender: we are about to offer these files (sizes as stat'ed).
    Sending {
        transfer_id: [u8; 16],
        names: Vec<String>,
        total_bytes: u64,
    },
    /// Either side: the transfer stopped because a user cancelled it, here or on the peer.
    /// Last event of the transfer; items not yet saved won't be.
    Cancelled {
        transfer_id: [u8; 16],
        by_peer: bool,
    },
}

/// Stream reset / stop code (application level): the user cancelled the transfer.
pub const ERR_CANCELLED: u32 = 0x07;

/// Error of a send (or receive) that a user cancelled.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Cancelled {
    pub by_peer: bool,
}

impl std::fmt::Display for Cancelled {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(if self.by_peer {
            "cancelled on the other device"
        } else {
            "cancelled"
        })
    }
}

impl std::error::Error for Cancelled {}

/// One live transfer's cancel switch. The first trigger decides who cancelled.
#[derive(Default)]
pub struct Cancel {
    token: CancellationToken,
    by_peer: Mutex<Option<bool>>,
}

impl Cancel {
    fn trigger(&self, by_peer: bool) {
        let mut who = self.by_peer.lock().unwrap();
        if who.is_none() {
            *who = Some(by_peer);
            self.token.cancel();
        }
    }

    /// `Some(by_peer)` once cancelled.
    fn who(&self) -> Option<bool> {
        *self.by_peer.lock().unwrap()
    }
}

/// Live transfers in both directions, by id, for [`cancel`].
pub type Cancels = Arc<Mutex<HashMap<[u8; 16], Arc<Cancel>>>>;

/// Cancels a live transfer (either direction); false if none has this id.
pub fn cancel(cancels: &Cancels, id: &[u8; 16]) -> bool {
    let c = cancels.lock().unwrap().get(id).cloned();
    c.inspect(|c| c.trigger(false)).is_some()
}

/// Registers a transfer's cancel switch for as long as it lives.
struct CancelEntry(Cancels, [u8; 16], Arc<Cancel>);

impl CancelEntry {
    fn new(cancels: &Cancels, id: [u8; 16]) -> Result<Self> {
        let c = Arc::new(Cancel::default());
        let mut map = cancels.lock().unwrap();
        if map.contains_key(&id) {
            bail!("transfer {} is already in progress", hex::encode(id));
        }
        map.insert(id, c.clone());
        Ok(Self(cancels.clone(), id, c))
    }
}

impl Drop for CancelEntry {
    fn drop(&mut self) {
        self.0.lock().unwrap().remove(&self.1);
    }
}

/// True if `e` came from the peer cancelling (a stream reset or stopped with [`ERR_CANCELLED`]).
fn peer_cancelled(e: &anyhow::Error) -> bool {
    fn coded(e: &(dyn std::error::Error + 'static)) -> bool {
        let code = match (e.downcast_ref(), e.downcast_ref(), e.downcast_ref()) {
            (Some(ReadError::Reset(c)), _, _)
            | (_, Some(ReadExactError::ReadError(ReadError::Reset(c))), _)
            | (_, _, Some(WriteError::Stopped(c))) => *c,
            _ => return false,
        };
        code == VarInt::from_u32(ERR_CANCELLED)
    }
    e.chain().any(|c| {
        c.downcast_ref::<Cancelled>().is_some_and(|c| c.by_peer)
            || coded(c)
            // tokio's AsyncRead/AsyncWrite wrap the stream errors in io::Error
            || (c.downcast_ref::<std::io::Error>())
                .and_then(|io| io.get_ref())
                .is_some_and(|inner| coded(inner))
    })
}

/// Resolves only if the peer stopped our stream with [`ERR_CANCELLED`].
async fn stopped_by_cancel(
    stopped: impl Future<Output = std::result::Result<Option<VarInt>, StoppedError>>,
) {
    if stopped.await.ok().flatten() != Some(VarInt::from_u32(ERR_CANCELLED)) {
        std::future::pending::<()>().await;
    }
}

pub type EventSink = Arc<dyn Fn(FileEvent) + Send + Sync>;
/// Decides which offered items to accept (one bool per item). Phase 0 CLI accepts all.
pub type AcceptFn = Arc<dyn Fn(&Offer) -> Vec<bool> + Send + Sync>;

#[derive(Debug, Clone, Default)]
pub struct SendReport {
    pub files_sent: u32,
    pub bytes_sent: u64, // bytes that crossed the network (excludes resumed prefixes)
    pub elapsed: std::time::Duration,
}

pub const PARTIAL_DIR: &str = ".ferry-partial";
/// Concurrent data streams; hides per-file latency when sending many small files.
const PARALLEL_STREAMS: usize = 8;
const CHUNK: usize = 1 << 20;
const MAX_ITEMS: usize = 100_000;
const MAX_NAME_LEN: usize = 4096;
const PROGRESS_BYTES: u64 = 8 << 20;
const PROGRESS_EVERY: Duration = Duration::from_millis(250);

/// Receiving side of the file channel for one destination folder. Shared by all
/// connections; the node routes CH_FILE streams here.
pub struct Receiver {
    dest_dir: PathBuf,
    accept: AcceptFn,
    events: EventSink,
    state: Mutex<State>,
    parts_in_use: PartsInUse,
    cancels: Cancels,
}

#[derive(Default)]
struct State {
    transfers: HashMap<[u8; 16], Arc<Transfer>>,
}

/// Partial files owned by a live transfer; two writers on one file would corrupt it.
type PartsInUse = Arc<Mutex<HashSet<PathBuf>>>;

/// How long a new offer waits for a partial still held by a dying transfer (e.g. the
/// sender crashed and reconnected) before falling back to a private, non-resumable one.
const PART_WAIT: Duration = Duration::from_secs(5);

struct Transfer {
    id: [u8; 16],
    slots: Vec<Option<Slot>>, // None = declined
    parts_in_use: PartsInUse,
    cancel: Arc<Cancel>,
}

/// Held by the control handler and every data handler, so a partial is released only
/// once nothing can still write to it. A cancelled transfer's partials go with it.
impl Drop for Transfer {
    fn drop(&mut self) {
        let cancelled = self.cancel.who().is_some();
        let mut parts = self.parts_in_use.lock().unwrap();
        for slot in self.slots.iter().flatten() {
            if cancelled {
                let _ = fs::remove_file(&slot.part); // gone already if the item was saved
            }
            parts.remove(&slot.part);
        }
    }
}

struct Slot {
    item: FileItem,
    offset: u64,
    part: PathBuf,
    data: watch::Sender<Data>,
}

#[derive(Clone)]
enum Data {
    Waiting,
    Receiving,
    Done(Result<blake3::Hash, String>),
}

/// Unregisters a transfer however handle_control exits (including cancellation).
struct Registered(Arc<Receiver>, Arc<Transfer>);

impl Drop for Registered {
    fn drop(&mut self) {
        self.0.state.lock().unwrap().transfers.remove(&self.1.id);
    }
}

impl Receiver {
    pub fn new(dest_dir: PathBuf, accept: AcceptFn, events: EventSink) -> Arc<Self> {
        Arc::new(Self {
            dest_dir,
            accept,
            events,
            state: Mutex::default(),
            parts_in_use: PartsInUse::default(),
            cancels: Cancels::default(),
        })
    }

    /// Live transfers in both directions; pass to [`send_named`] so [`cancel`] finds sends too.
    pub fn cancels(&self) -> &Cancels {
        &self.cancels
    }

    /// Handle a CH_FILE bi stream (prefix already consumed by the node).
    pub async fn handle_control(
        self: Arc<Self>,
        mut send: SendStream,
        mut recv: RecvStream,
    ) -> Result<()> {
        let offer: Offer = read_msg(&mut recv).await.context("read offer")?;
        let id = transfer_id(&offer.transfer_id)?;
        if offer.items.len() > MAX_ITEMS {
            bail!(
                "offer has {} items, limit is {MAX_ITEMS}",
                offer.items.len()
            );
        }
        if offer.items.iter().any(|i| i.name.len() > MAX_NAME_LEN) {
            bail!("offer contains a file name longer than {MAX_NAME_LEN} bytes");
        }
        (self.events)(FileEvent::Offered {
            transfer_id: id,
            names: offer
                .items
                .iter()
                .map(|i| sanitize::file_name(&i.name))
                .collect(),
            total_bytes: offer
                .items
                .iter()
                .fold(0u64, |a, i| a.saturating_add(i.size)),
        });

        let entry = CancelEntry::new(&self.cancels, id)?;
        let cancel = entry.2.clone();
        // The accept callback may block on a UI prompt; preparing touches the disk.
        let this = self.clone();
        let c = cancel.clone();
        let transfer = tokio::task::spawn_blocking(move || {
            let accepted = (this.accept)(&offer);
            this.prepare(id, offer.items, &accepted, c)
        })
        .await??;
        let _registered = Registered(self.clone(), transfer.clone());

        let peer_stop = stopped_by_cancel(send.stopped());
        let r = tokio::select! {
            r = self.run_control(&transfer, &mut send, &mut recv) => r,
            () = cancel.token.cancelled() => Err(anyhow!("cancelled")),
            () = peer_stop => Err(Cancelled { by_peer: true }.into()),
        };
        if let Err(e) = &r
            && peer_cancelled(e)
        {
            cancel.trigger(true);
        }
        match (r, cancel.who()) {
            (Err(_), Some(by_peer)) => {
                let _ = send.reset(ERR_CANCELLED.into());
                let _ = recv.stop(ERR_CANCELLED.into());
                drop(entry); // cancel_transfer is false once the event is out
                (self.events)(FileEvent::Cancelled {
                    transfer_id: id,
                    by_peer,
                });
                tracing::info!(
                    "transfer {} cancelled{}",
                    hex::encode(&id[..4]),
                    if by_peer { " by the sender" } else { "" }
                );
                Ok(())
            }
            (r, _) => r,
        }
    }

    /// Reply to the offer, then verify and place each item as the sender reports it done.
    async fn run_control(
        &self,
        transfer: &Transfer,
        send: &mut SendStream,
        recv: &mut RecvStream,
    ) -> Result<()> {
        let id = transfer.id;
        let reply = OfferReply {
            items: (transfer.slots.iter())
                .map(|s| ItemAccept {
                    accepted: s.is_some(),
                    offset: s.as_ref().map_or(0, |s| s.offset),
                })
                .collect(),
        };
        write_msg(send, &reply).await?;

        let mut finished = vec![false; transfer.slots.len()];
        let mut pending = transfer.slots.iter().flatten().count();
        while pending > 0 {
            let done: ItemDone = read_msg(recv)
                .await
                .context("control stream ended with items unfinished")?;
            let i = done.index as usize;
            let Some(Some(slot)) = transfer.slots.get(i) else {
                bail!("ItemDone for unknown or declined item {i}")
            };
            if std::mem::replace(&mut finished[i], true) {
                bail!("duplicate ItemDone for item {i}");
            }
            pending -= 1;
            let result = match self.finish_item(slot, &done.blake3, send).await {
                Ok(path) => {
                    let saved_as = path
                        .file_name()
                        .unwrap_or_default()
                        .to_string_lossy()
                        .into_owned();
                    (self.events)(FileEvent::Saved {
                        transfer_id: id,
                        index: done.index,
                        path,
                    });
                    ItemResult {
                        index: done.index,
                        ok: true,
                        error: String::new(),
                        saved_as,
                    }
                }
                Err(e) => {
                    // a reset data stream, not a bad file
                    if peer_cancelled(&e) || transfer.cancel.who().is_some() {
                        return Err(e);
                    }
                    let error = format!("{e:#}");
                    (self.events)(FileEvent::Failed {
                        transfer_id: id,
                        index: done.index,
                        error: error.clone(),
                    });
                    ItemResult {
                        index: done.index,
                        ok: false,
                        error,
                        saved_as: String::new(),
                    }
                }
            };
            write_msg(send, &result).await?;
        }
        let _ = send.finish();
        Ok(())
    }

    /// Handle a CH_FILE uni stream (prefix already consumed by the node).
    pub async fn handle_data(self: Arc<Self>, mut recv: RecvStream) -> Result<()> {
        let h: DataHeader = read_msg(&mut recv).await.context("read data header")?;
        let id = transfer_id(&h.transfer_id)?;
        let transfer = self
            .state
            .lock()
            .unwrap()
            .transfers
            .get(&id)
            .cloned()
            .context("data for unknown transfer")?;
        let Some(Some(slot)) = transfer.slots.get(h.index as usize) else {
            bail!("data for unknown or declined item {}", h.index)
        };
        if h.offset != slot.offset {
            bail!(
                "data for item {} starts at {}, expected {}",
                h.index,
                h.offset,
                slot.offset
            );
        }
        let claimed = slot.data.send_if_modified(|d| match d {
            Data::Waiting => {
                *d = Data::Receiving;
                true
            }
            _ => false,
        });
        if !claimed {
            bail!("second data stream for item {}", h.index);
        }
        let res = tokio::select! {
            r = self.receive(id, h.index, slot, &mut recv) => r,
            () = transfer.cancel.token.cancelled() => Err(anyhow!("cancelled")),
        };
        if let Err(e) = &res {
            if peer_cancelled(e) {
                // before Done, so the control handler sees a cancel rather than a failed item
                transfer.cancel.trigger(true);
            }
            if transfer.cancel.who().is_some() {
                let _ = recv.stop(ERR_CANCELLED.into());
            }
        }
        slot.data.send_replace(Data::Done(
            res.as_ref().map(|h| *h).map_err(|e| format!("{e:#}")),
        ));
        res.map(drop)
    }

    /// Chooses partial files and resume offsets, then registers the transfer.
    fn prepare(
        &self,
        id: [u8; 16],
        items: Vec<FileItem>,
        accepted: &[bool],
        cancel: Arc<Cancel>,
    ) -> Result<Arc<Transfer>> {
        let partial_dir = self.dest_dir.join(PARTIAL_DIR);
        if accepted.iter().any(|&a| a) {
            fs::create_dir_all(&partial_dir)
                .with_context(|| format!("create {}", partial_dir.display()))?;
        }
        if self.state.lock().unwrap().transfers.contains_key(&id) {
            bail!("transfer {} is already in progress", hex::encode(id));
        }
        let mut transfer = Transfer {
            id,
            slots: Vec::with_capacity(items.len()),
            parts_in_use: self.parts_in_use.clone(),
            cancel,
        };
        // one wait for the whole offer: per item, a big retried offer could wait for hours
        let deadline = Instant::now() + PART_WAIT;
        for (i, item) in items.into_iter().enumerate() {
            if !accepted.get(i).copied().unwrap_or(false) {
                transfer.slots.push(None);
                continue;
            }
            let part = self.claim_part(partial_dir.join(part_name(&item)), deadline, || {
                // Same file still busy: the duplicate gets a private, non-resumable partial.
                partial_dir.join(format!("{}-{i}.part", hex::encode(id)))
            });
            // A partial longer than the file can't be a prefix of it; receive() truncates it.
            let offset =
                fs::metadata(&part).map_or(0, |m| if m.len() <= item.size { m.len() } else { 0 });
            transfer.slots.push(Some(Slot {
                item,
                offset,
                part,
                data: watch::Sender::new(Data::Waiting),
            }));
        }
        let transfer = Arc::new(transfer);
        let mut st = self.state.lock().unwrap();
        if st.transfers.contains_key(&id) {
            bail!("transfer {} is already in progress", hex::encode(id));
        }
        st.transfers.insert(id, transfer.clone());
        Ok(transfer)
    }

    /// Claims `part`, waiting until `deadline` if another transfer still holds it.
    /// Runs on a blocking thread (see handle_control), so sleeping is fine.
    fn claim_part(
        &self,
        part: PathBuf,
        deadline: Instant,
        fallback: impl FnOnce() -> PathBuf,
    ) -> PathBuf {
        loop {
            let mut parts = self.parts_in_use.lock().unwrap();
            if !parts.contains(&part) {
                parts.insert(part.clone());
                return part;
            }
            if Instant::now() >= deadline {
                let alt = fallback();
                parts.insert(alt.clone());
                return alt;
            }
            drop(parts);
            std::thread::sleep(Duration::from_millis(50));
        }
    }

    /// Appends the stream to the partial file; returns the hash of the whole file.
    async fn receive(
        &self,
        id: [u8; 16],
        index: u32,
        slot: &Slot,
        recv: &mut RecvStream,
    ) -> Result<blake3::Hash> {
        let size = slot.item.size;
        let mut file = tokio::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(false)
            .open(&slot.part)
            .await
            .with_context(|| format!("open {}", slot.part.display()))?;
        file.set_len(slot.offset).await?;
        let mut hasher = blake3::Hasher::new();
        let mut buf = vec![0u8; chunk_len(size)];
        hash_prefix(&mut file, slot.offset, &mut hasher, &mut buf).await?;

        let mut progress = Progress::new(&self.events, id, index, size);
        let mut done = slot.offset;
        loop {
            let n = fill(recv, &mut buf).await?;
            if n == 0 {
                break;
            }
            if done.checked_add(n as u64).is_none_or(|d| d > size) {
                bail!("peer sent more than the declared {size} bytes");
            }
            hasher.update(&buf[..n]);
            file.write_all(&buf[..n]).await?;
            done += n as u64;
            progress.update(done);
        }
        file.flush().await?;
        if done != size {
            bail!("data stream ended after {done} of {size} bytes");
        }
        // on disk before it's renamed into place and reported saved (power loss, phone reboot)
        file.sync_data().await?;
        Ok(hasher.finalize())
    }

    /// Waits for the item's data, verifies it and moves it to its final name.
    async fn finish_item(
        &self,
        slot: &Slot,
        expected: &[u8],
        send: &SendStream,
    ) -> Result<PathBuf> {
        let mut rx = slot.data.subscribe();
        // The data stream may still be in flight; give up if the connection dies first.
        let data = tokio::select! {
            d = rx.wait_for(|d| matches!(d, Data::Done(_))) => d.map(|d| d.clone())?,
            s = send.stopped() => {
                if s.ok().flatten() == Some(VarInt::from_u32(ERR_CANCELLED)) {
                    return Err(Cancelled { by_peer: true }.into());
                }
                bail!("connection lost before data arrived")
            }
        };
        let Data::Done(hash) = data else {
            unreachable!()
        };
        let hash = hash.map_err(|e| anyhow!(e))?;
        if hash.as_bytes()[..] != *expected {
            // Start from zero next time instead of resuming from bad bytes.
            let _ = fs::remove_file(&slot.part);
            bail!("BLAKE3 mismatch, partial data discarded");
        }
        let (part, dir) = (slot.part.clone(), self.dest_dir.clone());
        let (name, mtime) = (
            sanitize::file_name(&slot.item.name),
            slot.item.mtime_unix_ms,
        );
        tokio::task::spawn_blocking(move || place(&part, &dir, &name, mtime)).await?
    }
}

/// Send regular files over an established session connection, with an optional display
/// name per file. Android passes shared `content://` files as `/proc/self/fd/N`, whose own
/// name means nothing to the receiver. `id` is the transfer id (16 random bytes; the app
/// may pick it, to cancel the send via [`cancel`] before any event arrives).
pub async fn send_named(
    conn: &Connection,
    files: &[(PathBuf, Option<String>)],
    id: [u8; 16],
    events: EventSink,
    cancels: &Cancels,
) -> Result<SendReport> {
    let start = Instant::now();
    let mut items = Vec::with_capacity(files.len());
    for (p, name) in files {
        let mut item = file_item(p).await?;
        if let Some(n) = name {
            item.name.clone_from(n);
        }
        items.push(item);
    }
    let entry = CancelEntry::new(cancels, id)?;
    let cancel = entry.2.clone();
    (events)(FileEvent::Sending {
        transfer_id: id,
        names: items.iter().map(|i| sanitize::file_name(&i.name)).collect(),
        total_bytes: items.iter().fold(0u64, |a, i| a.saturating_add(i.size)),
    });
    let (mut send, mut recv) = tokio::select! {
        s = conn.open_bi() => s?,
        () = cancel.token.cancelled() => {
            drop(entry);
            (events)(FileEvent::Cancelled { transfer_id: id, by_peer: false });
            return Err(Cancelled { by_peer: false }.into());
        }
    };
    let mut tasks = JoinSet::new();
    let peer_stop = stopped_by_cancel(send.stopped());
    let job = SendCtx {
        conn,
        files,
        id,
        events: &events,
        cancel: &cancel,
    };
    let r = tokio::select! {
        r = job.run(items, &mut send, &mut recv, &mut tasks) => r,
        () = cancel.token.cancelled() => Err(anyhow!("cancelled")),
        () = peer_stop => Err(Cancelled { by_peer: true }.into()),
    };
    if let Err(e) = &r
        && peer_cancelled(e)
    {
        cancel.trigger(true);
    }
    let r = match (r, cancel.who()) {
        (Err(_), Some(by_peer)) => {
            let _ = send.reset(ERR_CANCELLED.into());
            let _ = recv.stop(ERR_CANCELLED.into());
            // data tasks see the token and reset their streams (dropping one would finish it)
            while tasks.join_next().await.is_some() {}
            drop(entry);
            (events)(FileEvent::Cancelled {
                transfer_id: id,
                by_peer,
            });
            return Err(Cancelled { by_peer }.into());
        }
        (r, _) => r?,
    };
    let (files_sent, bytes_sent) = r;
    Ok(SendReport {
        files_sent,
        bytes_sent,
        elapsed: start.elapsed(),
    })
}

struct SendCtx<'a> {
    conn: &'a Connection,
    files: &'a [(PathBuf, Option<String>)],
    id: [u8; 16],
    events: &'a EventSink,
    cancel: &'a Arc<Cancel>,
}

impl SendCtx<'_> {
    /// Offer, stream the accepted items, collect results; returns (files sent, bytes sent).
    async fn run(
        &self,
        items: Vec<FileItem>,
        send: &mut SendStream,
        recv: &mut RecvStream,
        tasks: &mut JoinSet<Result<(u32, blake3::Hash, u64)>>,
    ) -> Result<(u32, u64)> {
        let (id, events, files) = (self.id, self.events, self.files);
        write_prefix(send, CH_FILE).await?;
        let offer = Offer {
            transfer_id: id.to_vec(),
            items,
        };
        write_msg(send, &offer).await?;
        let items = offer.items;
        let reply: OfferReply = read_msg(recv).await.context("read offer reply")?;
        if reply.items.len() != items.len() {
            bail!(
                "peer replied for {} items, offered {}",
                reply.items.len(),
                items.len()
            );
        }
        let mut accepted = Vec::new();
        for (i, a) in reply.items.iter().enumerate().filter(|(_, a)| a.accepted) {
            if a.offset > items[i].size {
                bail!("peer asked to resume {} past its end", items[i].name);
            }
            accepted.push((i, a.offset));
        }

        let data = async {
            let mut queue = accepted.iter();
            let mut bytes = 0;
            loop {
                while tasks.len() < PARALLEL_STREAMS {
                    let Some(&(i, offset)) = queue.next() else {
                        break;
                    };
                    let job = (
                        self.conn.clone(),
                        files[i].0.clone(),
                        id,
                        i as u32,
                        offset,
                        items[i].size,
                        events.clone(),
                        self.cancel.token.clone(),
                    );
                    tasks.spawn(send_item(job));
                }
                let Some(joined) = tasks.join_next().await else {
                    break;
                };
                let (index, hash, n) = joined??;
                bytes += n;
                write_msg(
                    send,
                    &ItemDone {
                        index,
                        blake3: hash.as_bytes().to_vec(),
                    },
                )
                .await?;
            }
            anyhow::Ok(bytes)
        };
        let results = async {
            let (mut ok, mut failed) = (0u32, Vec::new());
            for _ in 0..accepted.len() {
                let r: ItemResult = read_msg(recv).await.context("read item result")?;
                if r.index as usize >= items.len() {
                    bail!("result for unknown item {}", r.index);
                }
                if r.ok {
                    ok += 1;
                    (events)(FileEvent::Saved {
                        transfer_id: id,
                        index: r.index,
                        path: r.saved_as.into(),
                    });
                } else {
                    failed.push(format!("{}: {}", items[r.index as usize].name, r.error));
                    (events)(FileEvent::Failed {
                        transfer_id: id,
                        index: r.index,
                        error: r.error,
                    });
                }
            }
            anyhow::Ok((ok, failed))
        };
        let (bytes_sent, (files_sent, failed)) = tokio::try_join!(data, results)?;
        let _ = send.finish();
        if !failed.is_empty() {
            bail!(
                "{} of {} files failed: {}",
                failed.len(),
                accepted.len(),
                failed.join("; ")
            );
        }
        Ok((files_sent, bytes_sent))
    }
}

type SendJob = (
    Connection,
    PathBuf,
    [u8; 16],
    u32,
    u64,
    u64,
    EventSink,
    CancellationToken,
);

/// Streams one file from `offset`; returns (index, whole-file hash, bytes sent).
async fn send_item(
    (conn, path, id, index, offset, size, events, cancel): SendJob,
) -> Result<(u32, blake3::Hash, u64)> {
    let mut file = tokio::fs::File::open(&path)
        .await
        .with_context(|| format!("open {}", path.display()))?;
    let mut hasher = blake3::Hasher::new();
    let mut buf = vec![0u8; chunk_len(size)];
    hash_prefix(&mut file, offset, &mut hasher, &mut buf).await?;

    let mut s = conn.open_uni().await?;
    write_prefix(&mut s, CH_FILE).await?;
    write_msg(
        &mut s,
        &DataHeader {
            transfer_id: id.to_vec(),
            index,
            offset,
        },
    )
    .await?;
    let stream = async {
        let mut progress = Progress::new(&events, id, index, size);
        let mut done = offset;
        while done < size {
            let want = (size - done).min(buf.len() as u64) as usize;
            let n = file.read(&mut buf[..want]).await?;
            if n == 0 {
                bail!("{} shrank while sending", path.display());
            }
            hasher.update(&buf[..n]);
            s.write_all(&buf[..n]).await?;
            done += n as u64;
            progress.update(done);
        }
        anyhow::Ok(())
    };
    tokio::select! {
        r = stream => r?,
        () = cancel.cancelled() => {
            let _ = s.reset(ERR_CANCELLED.into());
            bail!("cancelled");
        }
    }
    s.finish()?;
    Ok((index, hasher.finalize(), size - offset))
}

/// Offer entry for a local file; public so callers/tests can predict resume keys.
pub async fn file_item(path: &Path) -> Result<FileItem> {
    let md = tokio::fs::metadata(path)
        .await
        .with_context(|| format!("stat {}", path.display()))?;
    if !md.is_file() {
        bail!(
            "{}: only regular files can be sent (folders are not supported yet)",
            path.display()
        );
    }
    let name = path
        .file_name()
        .context("path has no file name")?
        .to_string_lossy()
        .into_owned();
    let mtime_unix_ms = (md.modified().ok())
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .map_or(0, |d| i64::try_from(d.as_millis()).unwrap_or(i64::MAX));
    Ok(FileItem {
        name,
        size: md.len(),
        mtime_unix_ms,
    })
}

/// Resume key: stable across retries (which get a new transfer id) of the same file version.
pub fn part_name(item: &FileItem) -> String {
    let mut h = blake3::Hasher::new();
    h.update(&(item.name.len() as u64).to_le_bytes());
    h.update(item.name.as_bytes());
    h.update(&item.size.to_le_bytes());
    h.update(&item.mtime_unix_ms.to_le_bytes());
    format!("{}.part", &h.finalize().to_hex()[..32])
}

/// Moves `part` into `dir` as `name`, or `name (n)` if taken. Never replaces an existing file:
/// `hard_link` fails atomically if the target exists (unlike `rename`, which overwrites).
fn place(part: &Path, dir: &Path, name: &str, mtime_ms: i64) -> Result<PathBuf> {
    if mtime_ms > 0 {
        let t = UNIX_EPOCH + Duration::from_millis(mtime_ms as u64);
        let _ = fs::File::options()
            .write(true)
            .open(part)
            .and_then(|f| f.set_modified(t)); // cosmetic
    }
    for n in 0..10_000 {
        let target = dir.join(if n == 0 {
            name.to_owned()
        } else {
            sanitize::numbered(name, n)
        });
        match fs::hard_link(part, &target) {
            Ok(()) => {
                let _ = fs::remove_file(part);
                return Ok(target);
            }
            Err(e) if e.kind() == ErrorKind::AlreadyExists => {}
            // No hard links (FAT, some Android storage): reserve the name, then replace only
            // our own empty placeholder.
            Err(_) => match fs::File::create_new(&target) {
                Ok(_) => {
                    return match fs::rename(part, &target) {
                        Ok(()) => Ok(target),
                        Err(e) => {
                            let _ = fs::remove_file(&target);
                            Err(e).with_context(|| format!("move into {}", target.display()))
                        }
                    };
                }
                Err(e) if e.kind() == ErrorKind::AlreadyExists => {}
                Err(e) => return Err(e).with_context(|| format!("create {}", target.display())),
            },
        }
    }
    bail!("no free file name for {name}")
}

fn transfer_id(b: &[u8]) -> Result<[u8; 16]> {
    b.try_into()
        .map_err(|_| anyhow!("transfer_id must be 16 bytes, got {}", b.len()))
}

/// Small files don't need a 1 MiB buffer each.
fn chunk_len(size: u64) -> usize {
    size.clamp(4096, CHUNK as u64) as usize
}

async fn hash_prefix<R: AsyncRead + Unpin>(
    r: &mut R,
    len: u64,
    hasher: &mut blake3::Hasher,
    buf: &mut [u8],
) -> Result<()> {
    let mut left = len;
    while left > 0 {
        let want = left.min(buf.len() as u64) as usize;
        let n = r.read(&mut buf[..want]).await?;
        if n == 0 {
            bail!("file is shorter than its resume offset");
        }
        hasher.update(&buf[..n]);
        left -= n as u64;
    }
    Ok(())
}

/// Reads until `buf` is full or the stream ends, so disk writes stay large.
async fn fill(r: &mut RecvStream, buf: &mut [u8]) -> Result<usize> {
    let mut n = 0;
    while n < buf.len() {
        match r.read(&mut buf[n..]).await? {
            Some(k) => n += k,
            None => break,
        }
    }
    Ok(n)
}

struct Progress<'a> {
    events: &'a EventSink,
    id: [u8; 16],
    index: u32,
    total: u64,
    last: u64,
    at: Instant,
}

impl<'a> Progress<'a> {
    fn new(events: &'a EventSink, id: [u8; 16], index: u32, total: u64) -> Self {
        Self {
            events,
            id,
            index,
            total,
            last: 0,
            at: Instant::now(),
        }
    }

    fn update(&mut self, done: u64) {
        if done == self.total
            || done - self.last >= PROGRESS_BYTES
            || self.at.elapsed() >= PROGRESS_EVERY
        {
            (self.events)(FileEvent::Progress {
                transfer_id: self.id,
                index: self.index,
                done,
                total: self.total,
            });
            (self.last, self.at) = (done, Instant::now());
        }
    }
}

#[cfg(test)]
mod tests;
