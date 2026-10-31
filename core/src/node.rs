//! The running device: iroh endpoint + trust store + channel routing.
//!
//! Relays and n0 DNS lookups are disabled (no traffic leaves the LAN). Peer addresses
//! come from mDNS (feature `mdns`), from native discovery via [`Node::add_peer_addrs`],
//! or from the last addresses stored in the trust record. USB bridge addresses live in a
//! separate lookup ([`crate::usb`]) so they come and go without touching the others.

use std::collections::HashMap;
use std::future::Future;
use std::net::SocketAddr;
use std::path::PathBuf;
use std::pin::Pin;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use anyhow::{Context, Result, bail, ensure};
use iroh::address_lookup::memory::MemoryLookup;
use iroh::endpoint::{
    Connection, Incoming, IncomingAddr, PortmapperConfig, RecvStream, SendStream, presets,
};
use iroh::{Endpoint, EndpointAddr, EndpointId, RelayMode, TransportAddr};
use tokio::time::timeout;
use tracing::{debug, info, warn};

use crate::channel::{
    CH_CONTROL, CH_EVENTS, CH_FILE, read_msg, read_prefix, write_msg, write_prefix,
};
use crate::file::{self, AcceptFn, EventSink, Receiver, SendReport};
use crate::pairing::{self, Me, PairedPeer, TICKET_TTL, Ticket};
use crate::proto::{Event, Hello};
use crate::trust::{TrustStore, TrustedPeer};
use crate::{ALPN_PAIR, ALPN_SESSION, PROTOCOL_VERSION, discovery, identity};

/// Shown to the user on both devices during pairing; they confirm the codes match.
#[derive(Debug, Clone)]
pub struct PairingPrompt {
    pub peer_name: String,
    pub code: String, // 6 digits
}

pub type ConfirmFn =
    Arc<dyn Fn(PairingPrompt) -> Pin<Box<dyn Future<Output = bool> + Send>> + Send + Sync>;

pub struct NodeConfig {
    pub data_dir: PathBuf,
    pub device_name: String,
    pub download_dir: PathBuf,
    /// Which items of an incoming offer to accept.
    pub accept: AcceptFn,
    pub events: EventSink,
    /// Asked on the ticket-showing side when someone scans our QR.
    pub confirm_pairing: ConfirmFn,
    /// Events from paired peers: (peer id, event).
    pub on_event: EventFn,
}

pub type EventFn = Arc<dyn Fn(String, Event) + Send + Sync>;

const NET_TIMEOUT: Duration = Duration::from_secs(10);
/// Connection close codes (application level).
const ERR_NOT_PAIRED: u32 = 0x01;
const ERR_HELLO: u32 = 0x02;
/// Stream stop/reset code for a channel we don't speak.
const ERR_UNKNOWN_CHANNEL: u32 = 0x03;
const ERR_REPLACED: u32 = 0x04;
const ERR_FORGOTTEN: u32 = 0x05;
const MAX_LAST_ADDRS: usize = 8;
const MAX_TICKET_ADDRS: usize = 6;

pub struct Node {
    pub(crate) endpoint: Endpoint,
    lookup: MemoryLookup,
    /// Only live USB shim addresses; iroh merges it with `lookup`.
    pub(crate) usb_lookup: MemoryLookup,
    pub(crate) usb: Mutex<crate::usb::UsbState>,
    name: String,
    presence_key: [u8; 32],
    presence_nonce: Mutex<([u8; 8], Instant)>,
    trust: Mutex<TrustStore>,
    /// The one live pairing ticket: (secret, expiry). A new ticket replaces the old one.
    ticket: Mutex<Option<([u8; 32], Instant)>>,
    confirm: ConfirmFn,
    receiver: Arc<Receiver>,
    events: EventSink,
    /// The live incoming session per peer. A reconnecting peer (e.g. after a crash) replaces
    /// its old one at once instead of waiting for the idle timeout, which frees its partial files.
    sessions: Mutex<HashMap<EndpointId, Connection>>,
    /// Serialises dialing so concurrent sends to one peer share a single new session.
    dial: tokio::sync::Mutex<()>,
    on_event: EventFn,
    #[cfg(feature = "mdns")]
    mdns: Mutex<Option<discovery::Mdns>>,
}

impl Node {
    /// Load identity + trust store, bind the endpoint, start the accept loop
    /// (and mDNS advertise/browse when the `mdns` feature is on).
    pub async fn start(cfg: NodeConfig) -> Result<Arc<Node>> {
        let ident = identity::load_or_create(&cfg.data_dir)?;
        let trust = TrustStore::load(&cfg.data_dir)?;
        let lookup = MemoryLookup::new();
        let usb_lookup = MemoryLookup::with_provenance("usb");
        for p in trust.list() {
            if let Ok(id) = identity::parse_device_id(&p.id) {
                lookup.add_endpoint_info(addr_of(id, &p.last_addrs));
            }
        }
        // Minimal = crypto provider only: no n0 DNS/pkarr lookup; relays and UPnP off.
        // Default IP transports bind 0.0.0.0:0 and [::]:0 (v6 may fail harmlessly).
        let endpoint = Endpoint::builder(presets::Minimal)
            .secret_key(ident.secret_key)
            .alpns(vec![ALPN_SESSION.to_vec(), ALPN_PAIR.to_vec()])
            .relay_mode(RelayMode::Disabled)
            .portmapper_config(PortmapperConfig::Disabled)
            .address_lookup(lookup.clone())
            .address_lookup(usb_lookup.clone())
            .bind()
            .await
            .context("bind endpoint")?;
        let node = Arc::new(Node {
            endpoint,
            lookup,
            usb_lookup,
            usb: Mutex::default(),
            name: pairing::clean_name(&cfg.device_name),
            presence_key: ident.presence_key,
            presence_nonce: Mutex::new((rand::random(), Instant::now())),
            trust: Mutex::new(trust),
            ticket: Mutex::new(None),
            confirm: cfg.confirm_pairing,
            receiver: Receiver::new(cfg.download_dir, cfg.accept, cfg.events.clone()),
            events: cfg.events,
            sessions: Mutex::default(),
            dial: tokio::sync::Mutex::default(),
            on_event: cfg.on_event,
            #[cfg(feature = "mdns")]
            mdns: Mutex::new(None),
        });

        let n = node.clone();
        tokio::spawn(async move {
            // ends when the endpoint is closed (shutdown)
            while let Some(inc) = n.endpoint.accept().await {
                let n = n.clone();
                tokio::spawn(async move {
                    if let Err(e) = n.handle_incoming(inc).await {
                        warn!("incoming connection: {e:#}");
                    }
                });
            }
        });

        #[cfg(feature = "mdns")]
        match discovery::Mdns::start(node.clone()) {
            Ok(m) => *node.mdns.lock().unwrap() = Some(m),
            Err(e) => warn!("mDNS unavailable: {e:#}"),
        }
        info!(id = %node.device_id(), port = node.port(), "node started");
        Ok(node)
    }

    /// This device's ID (iroh public key, z-base-32 string form).
    pub fn device_id(&self) -> String {
        self.endpoint.id().to_z32()
    }

    pub fn device_name(&self) -> String {
        self.name.clone()
    }

    /// A one-use `ferry://pair?...` ticket valid for 10 minutes, for display as a QR code.
    pub fn new_pairing_ticket(&self) -> Result<String> {
        let secret: [u8; 32] = rand::random();
        *self.ticket.lock().unwrap() = Some((secret, Instant::now() + TICKET_TTL));
        let mut addrs: Vec<SocketAddr> = self
            .endpoint
            .addr()
            .ip_addrs()
            .copied()
            .filter(|a| !a.ip().is_loopback() && !is_link_local_v6(a))
            .collect();
        addrs.sort_by_key(|a| a.is_ipv6()); // v4 first: shorter and what phones usually reach
        addrs.truncate(MAX_TICKET_ADDRS);
        Ok(Ticket {
            id: self.endpoint.id(),
            secret,
            name: self.name.clone(),
            addrs,
        }
        .to_string())
    }

    /// Scanner side: pair with the device that showed `ticket`.
    pub async fn pair_with_ticket(&self, ticket: &str, confirm: ConfirmFn) -> Result<TrustedPeer> {
        let t: Ticket = ticket.parse()?;
        ensure!(
            t.id != self.endpoint.id(),
            "that is this device's own ticket"
        );
        // the USB bridge (if any) learned t.id from its hello: pairing needs no shared Wi-Fi
        let mut addrs = t.addrs.clone();
        addrs.extend(self.usb_addr(t.id));
        let conn = timeout(
            NET_TIMEOUT,
            self.endpoint.connect(addr_of(t.id, &addrs), ALPN_PAIR),
        )
        .await
        .context("connecting to the other device timed out")?
        .context("connect to the other device")?;
        let peer = pairing::scan(&conn, &t, &self.me(), &confirm).await?;
        self.store_peer(t.id, peer, t.addrs)
    }

    pub fn peers(&self) -> Vec<TrustedPeer> {
        self.trust.lock().unwrap().list().to_vec()
    }

    pub fn forget_peer(&self, peer_id: &str) -> Result<()> {
        let id = identity::parse_device_id(peer_id)?;
        ensure!(
            self.trust.lock().unwrap().remove(&id.to_z32())?,
            "not a paired device: {peer_id}"
        );
        self.lookup.remove_endpoint_info(id);
        if let Some(c) = self.sessions.lock().unwrap().remove(&id) {
            c.close(ERR_FORGOTTEN.into(), b"forgotten");
        }
        Ok(())
    }

    /// Addresses found by native discovery (Bonjour / NsdManager) for a paired peer.
    pub fn add_peer_addrs(&self, peer_id: &str, addrs: Vec<SocketAddr>) -> Result<()> {
        let id = self.trusted(peer_id)?;
        self.lookup.add_endpoint_info(addr_of(id, &addrs));
        self.remember_addrs(id, &addrs);
        Ok(())
    }

    /// Which paired peer (if any) a natively discovered `_ferry._udp` TXT record belongs to.
    pub fn match_presence(&self, txt: &[(String, String)]) -> Option<String> {
        let get = |k: &str| {
            txt.iter()
                .find(|(key, _)| key == k)
                .map(|(_, v)| v.as_str())
        };
        discovery::match_txt(self.trust.lock().unwrap().list(), get)
    }

    /// Our current presence tag for native mDNS/BLE adverts: (txt key, value) pairs.
    /// Rotates every [`discovery::ROTATE_EVERY`]; re-query and re-advertise on that cadence.
    pub fn presence_txt(&self) -> Vec<(String, String)> {
        let mut p = self.presence_nonce.lock().unwrap();
        if p.1.elapsed() >= discovery::ROTATE_EVERY {
            *p = (rand::random(), Instant::now());
        }
        discovery::presence_txt(&self.presence_key, &p.0)
    }

    #[cfg(feature = "mdns")]
    pub(crate) fn rotate_presence(&self) {
        *self.presence_nonce.lock().unwrap() = (rand::random(), Instant::now());
    }

    /// The UDP port our endpoint is bound to (for native mDNS registration).
    pub fn port(&self) -> u16 {
        self.endpoint
            .bound_sockets()
            .iter()
            .find(|a| a.is_ipv4())
            .map_or(0, |a| a.port())
    }

    pub async fn send_files(
        self: &Arc<Self>,
        peer_id: &str,
        paths: &[PathBuf],
    ) -> Result<SendReport> {
        let files: Vec<(PathBuf, Option<String>)> =
            paths.iter().map(|p| (p.clone(), None)).collect();
        self.send_named(peer_id, &files).await
    }

    /// [`Node::send_files`] with an optional display name per file (see [`file::send_named`]).
    pub async fn send_named(
        self: &Arc<Self>,
        peer_id: &str,
        files: &[(PathBuf, Option<String>)],
    ) -> Result<SendReport> {
        let id = self.trusted(peer_id)?;
        let conn = self.session(id).await?;
        match file::send_named(&conn, files, self.events.clone()).await {
            // the cached session died under us (peer restarted): one retry on a fresh one
            Err(_) if conn.close_reason().is_some() => {
                let conn = self.session(id).await?;
                file::send_named(&conn, files, self.events.clone()).await
            }
            r => r,
        }
    }

    /// Send one event (notification, clipboard, ...) to a paired peer.
    pub async fn send_event(self: &Arc<Self>, peer_id: &str, ev: &Event) -> Result<()> {
        let id = self.trusted(peer_id)?;
        let conn = self.session(id).await?;
        match send_event_on(&conn, ev).await {
            Err(_) if conn.close_reason().is_some() => {
                send_event_on(&self.session(id).await?, ev).await
            }
            r => r,
        }
    }

    /// The live session with `id` (either direction), or a newly dialed one.
    async fn session(self: &Arc<Self>, id: EndpointId) -> Result<Connection> {
        let _dialing = self.dial.lock().await;
        if let Some(c) = self.sessions.lock().unwrap().get(&id)
            && c.close_reason().is_none()
        {
            return Ok(c.clone());
        }
        let conn = timeout(
            NET_TIMEOUT,
            // iroh skips the lookup when it already has a path; name the USB one explicitly
            self.endpoint.connect(
                addr_of(id, &Vec::from_iter(self.usb_addr(id))),
                ALPN_SESSION,
            ),
        )
        .await
        .context("connecting timed out (is the other device on this network?)")?
        .context("connect")?;
        let control = hello_initiate(&conn, self.hello()).await.with_context(|| {
            match conn.close_reason() {
                Some(r) => format!("session refused: {r}"),
                None => "session handshake".into(),
            }
        })?;
        self.adopt(id, &conn);
        let node = self.clone();
        let c = conn.clone();
        tokio::spawn(async move {
            let _control = control; // keep the control stream open for the session's life
            node.serve_streams(&c).await;
            node.drop_session(id, &c);
        });
        Ok(conn)
    }

    /// Makes `conn` the session for `id`, closing any older one.
    fn adopt(&self, id: EndpointId, conn: &Connection) {
        if let Some(old) = self.sessions.lock().unwrap().insert(id, conn.clone())
            && old.stable_id() != conn.stable_id()
        {
            old.close(ERR_REPLACED.into(), b"replaced by a new session");
        }
    }

    fn drop_session(&self, id: EndpointId, conn: &Connection) {
        let mut sessions = self.sessions.lock().unwrap();
        if sessions
            .get(&id)
            .is_some_and(|c| c.stable_id() == conn.stable_id())
        {
            sessions.remove(&id);
        }
    }

    pub async fn shutdown(&self) {
        self.usb_shutdown();
        #[cfg(feature = "mdns")]
        if let Some(m) = self.mdns.lock().unwrap().take() {
            m.shutdown();
        }
        self.endpoint.close().await;
    }

    fn me(&self) -> Me<'_> {
        Me {
            name: &self.name,
            presence_key: &self.presence_key,
        }
    }

    fn hello(&self) -> Hello {
        Hello {
            protocol_version: PROTOCOL_VERSION,
            device_name: self.name.clone(),
            channels: vec![CH_CONTROL.into(), CH_FILE.into(), CH_EVENTS.into()],
        }
    }

    /// Parses `peer_id` and checks it is paired.
    fn trusted(&self, peer_id: &str) -> Result<EndpointId> {
        let id = identity::parse_device_id(peer_id)?;
        ensure!(
            self.trust.lock().unwrap().get(&id.to_z32()).is_some(),
            "not a paired device: {peer_id}"
        );
        Ok(id)
    }

    fn store_peer(
        &self,
        id: EndpointId,
        p: PairedPeer,
        addrs: Vec<SocketAddr>,
    ) -> Result<TrustedPeer> {
        let peer = TrustedPeer {
            id: id.to_z32(),
            name: p.name,
            presence_key: hex::encode(p.presence_key),
            paired_at_unix: std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map_or(0, |d| d.as_secs() as i64),
            last_addrs: addrs,
        };
        self.trust.lock().unwrap().upsert(peer.clone())?;
        self.lookup.add_endpoint_info(addr_of(id, &peer.last_addrs));
        info!(peer = %peer.id, name = %peer.name, "paired");
        Ok(peer)
    }

    /// Newest addresses first; persisted only when the list changes.
    fn remember_addrs(&self, id: EndpointId, new: &[SocketAddr]) {
        let mut trust = self.trust.lock().unwrap();
        let Some(mut p) = trust.get(&id.to_z32()).cloned() else {
            return;
        };
        let mut merged = new.to_vec();
        merged.extend(p.last_addrs.iter().filter(|a| !new.contains(a)));
        merged.truncate(MAX_LAST_ADDRS);
        if merged != p.last_addrs {
            p.last_addrs = merged;
            if let Err(e) = trust.upsert(p) {
                warn!("save peer addresses: {e:#}");
            }
        }
    }

    async fn handle_incoming(self: Arc<Self>, inc: Incoming) -> Result<()> {
        let from = match inc.remote_addr() {
            IncomingAddr::Ip(a) => Some(a),
            _ => None,
        };
        let conn = inc.await.context("handshake")?;
        if conn.alpn() == ALPN_PAIR {
            self.handle_pair(conn, from).await
        } else if conn.alpn() == ALPN_SESSION {
            self.handle_session(conn, from).await
        } else {
            bail!("unexpected ALPN") // TLS only negotiates the ALPNs we configured
        }
    }

    async fn handle_pair(&self, conn: Connection, from: Option<SocketAddr>) -> Result<()> {
        if self.ticket.lock().unwrap().is_none() {
            conn.close(pairing::ERR_PAIR_REJECTED.into(), b"not pairing");
            bail!("pairing attempt while no ticket is shown");
        }
        let take = || {
            let t = self.ticket.lock().unwrap().take();
            t.filter(|(_, exp)| Instant::now() < *exp).map(|(s, _)| s)
        };
        let peer = pairing::show(&conn, take, &self.me(), &self.confirm).await?;
        // a USB shim address is per bridge: never persist it
        let from = from.filter(|a| !self.is_usb_addr(*a));
        self.store_peer(conn.remote_id(), peer, from.into_iter().collect())?;
        Ok(())
    }

    /// Session access control: the remote key is authenticated by TLS; it must be in the
    /// trust store or the connection is closed before any stream is read.
    async fn handle_session(&self, conn: Connection, from: Option<SocketAddr>) -> Result<()> {
        let id = conn.remote_id();
        if self.trust.lock().unwrap().get(&id.to_z32()).is_none() {
            conn.close(ERR_NOT_PAIRED.into(), b"not paired");
            debug!(remote = %id.fmt_short(), "refused session from unpaired device");
            return Ok(());
        }
        if let Some(a) = from.filter(|a| !self.is_usb_addr(*a)) {
            self.remember_addrs(id, &[a]);
        }
        let _control = match hello_respond(&conn, self.hello()).await {
            Ok(c) => c,
            Err(e) => {
                conn.close(ERR_HELLO.into(), b"bad hello");
                return Err(e.context("session hello"));
            }
        };
        self.adopt(id, &conn);
        self.serve_streams(&conn).await;
        self.drop_session(id, &conn);
        Ok(())
    }

    /// Routes the peer's streams until the connection ends. Both directions run this, so
    /// either side can push files or events over one session.
    async fn serve_streams(&self, conn: &Connection) {
        let peer = conn.remote_id().to_z32();
        loop {
            tokio::select! {
                bi = conn.accept_bi() => {
                    let Ok((send, recv)) = bi else { break };
                    tokio::spawn(route_bi(self.receiver.clone(), send, recv));
                }
                uni = conn.accept_uni() => {
                    let Ok(recv) = uni else { break };
                    tokio::spawn(route_uni(self.receiver.clone(), self.on_event.clone(), peer.clone(), recv));
                }
            }
        }
        debug!(remote = %peer, reason = ?conn.close_reason(), "session ended");
    }
}

async fn send_event_on(conn: &Connection, ev: &Event) -> Result<()> {
    let mut s = timeout(NET_TIMEOUT, conn.open_uni()).await??;
    write_prefix(&mut s, CH_EVENTS).await?;
    write_msg(&mut s, ev).await?;
    s.finish()?;
    // shortcut: one stream per event, so two events sent back to back may arrive out of
    // order; use a single ordered stream per session if that ever matters (notif add/remove).
    Ok(())
}

/// Unknown channels are refused per stream, never per connection, so newer peers keep working.
async fn route_bi(rx: Arc<Receiver>, mut send: SendStream, mut recv: RecvStream) {
    match timeout(NET_TIMEOUT, read_prefix(&mut recv)).await {
        Ok(Ok(CH_FILE)) => {
            if let Err(e) = rx.handle_control(send, recv).await {
                warn!("file control stream: {e:#}");
            }
        }
        other => {
            debug!("refusing bi stream: {other:?}");
            let _ = recv.stop(ERR_UNKNOWN_CHANNEL.into());
            let _ = send.reset(ERR_UNKNOWN_CHANNEL.into());
        }
    }
}

async fn route_uni(rx: Arc<Receiver>, on_event: EventFn, peer: String, mut recv: RecvStream) {
    match timeout(NET_TIMEOUT, read_prefix(&mut recv)).await {
        Ok(Ok(CH_FILE)) => {
            if let Err(e) = rx.handle_data(recv).await {
                warn!("file data stream: {e:#}");
            }
        }
        Ok(Ok(CH_EVENTS)) => match timeout(NET_TIMEOUT, read_msg::<_, Event>(&mut recv)).await {
            // an Event without a kind is a variant this version doesn't know: ignore it
            Ok(Ok(ev)) if ev.kind.is_some() => on_event(peer, ev),
            Ok(Ok(_)) => {}
            other => warn!("event stream: {other:?}"),
        },
        other => {
            debug!("refusing uni stream: {other:?}");
            let _ = recv.stop(ERR_UNKNOWN_CHANNEL.into());
        }
    }
}

/// Initiator opens the long-lived control stream. Keep the returned streams alive for
/// the life of the session.
async fn hello_initiate(conn: &Connection, ours: Hello) -> Result<(SendStream, RecvStream)> {
    let (mut send, mut recv) = timeout(NET_TIMEOUT, conn.open_bi()).await??;
    write_prefix(&mut send, CH_CONTROL).await?;
    write_msg(&mut send, &ours).await?;
    check_hello(timeout(NET_TIMEOUT, read_msg(&mut recv)).await??)?;
    Ok((send, recv))
}

async fn hello_respond(conn: &Connection, ours: Hello) -> Result<(SendStream, RecvStream)> {
    let (mut send, mut recv) = timeout(NET_TIMEOUT, conn.accept_bi()).await??;
    ensure!(
        timeout(NET_TIMEOUT, read_prefix(&mut recv)).await?? == CH_CONTROL,
        "first stream is not control"
    );
    check_hello(timeout(NET_TIMEOUT, read_msg(&mut recv)).await??)?;
    write_msg(&mut send, &ours).await?;
    Ok((send, recv))
}

fn check_hello(h: Hello) -> Result<()> {
    ensure!(
        h.protocol_version == PROTOCOL_VERSION,
        "peer speaks protocol {}",
        h.protocol_version
    );
    Ok(())
}

pub(crate) fn addr_of(id: EndpointId, addrs: &[SocketAddr]) -> EndpointAddr {
    EndpointAddr::from_parts(id, addrs.iter().copied().map(TransportAddr::Ip))
}

fn is_link_local_v6(a: &SocketAddr) -> bool {
    matches!(a.ip(), std::net::IpAddr::V6(v6) if v6.segments()[0] & 0xffc0 == 0xfe80)
}
