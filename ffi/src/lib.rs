//! UniFFI bindings over `ferry-core` for the Swift (Mac) and Kotlin (Android) shells.

use std::fmt::Write as _;
use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::Arc;

use ferry_core::file::{AcceptFn, EventSink, FileEvent};
use ferry_core::node::{ConfirmFn, Node, NodeConfig, PairingPrompt};
use ferry_core::proto;
use ferry_core::trust::TrustedPeer;

uniffi::setup_scaffolding!();

#[derive(Debug, uniffi::Error)]
pub enum FerryError {
    Failed { reason: String },
}

impl std::fmt::Display for FerryError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let Self::Failed { reason } = self;
        f.write_str(reason)
    }
}

impl std::error::Error for FerryError {}

impl From<anyhow::Error> for FerryError {
    fn from(e: anyhow::Error) -> Self {
        Self::Failed {
            reason: format!("{e:#}"),
        }
    }
}

type Result<T> = std::result::Result<T, FerryError>;

/// Implemented by the app. Called from Rust worker threads, never the main thread.
#[uniffi::export(foreign)]
#[async_trait::async_trait]
pub trait FerryDelegate: Send + Sync {
    fn on_file_event(&self, event: FfiFileEvent);
    /// Show `code` and ask the user whether it matches the other device.
    async fn confirm_pairing(&self, peer_name: String, code: String) -> bool;
    /// A Phase 2 event (notification, clipboard, media, battery, ring, link) from a paired peer.
    fn on_event(&self, peer_id: String, event: FfiEvent);
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct FfiNotifButton {
    pub id: String,
    pub title: String,
    /// Takes text input (a reply action).
    pub reply: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, uniffi::Enum)]
pub enum FfiMediaCmd {
    PlayPause,
    Next,
    Previous,
}

/// Mirror of the protobuf `Event` oneof (proto/ferry.proto) for Swift and Kotlin.
#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum FfiEvent {
    /// phone → Mac; the same key again is an update
    Notification {
        key: String,
        app_name: String,
        package: String,
        title: String,
        text: String,
        posted_unix_ms: i64,
        buttons: Vec<FfiNotifButton>,
    },
    NotificationRemoved {
        key: String,
    },
    /// Mac → phone: press a button (with optional reply text) or dismiss
    NotificationAction {
        key: String,
        button_id: String,
        reply_text: String,
        dismiss: bool,
    },
    Clipboard {
        text: String,
    },
    /// phone → Mac; active = false clears it
    NowPlaying {
        active: bool,
        app_name: String,
        title: String,
        artist: String,
        playing: bool,
        duration_ms: i64,
        position_ms: i64,
    },
    MediaCommand {
        cmd: FfiMediaCmd,
    },
    Battery {
        percent: u32,
        charging: bool,
    },
    /// Mac → phone: find my phone (start = false stops ringing)
    Ring {
        start: bool,
    },
    Link {
        url: String,
    },
}

impl FfiEvent {
    /// None for event kinds this version doesn't know.
    fn from_proto(e: proto::Event) -> Option<Self> {
        use proto::event::Kind;
        Some(match e.kind? {
            Kind::Notification(n) => Self::Notification {
                key: n.key,
                app_name: n.app_name,
                package: n.package,
                title: n.title,
                text: n.text,
                posted_unix_ms: n.posted_unix_ms,
                buttons: (n.buttons.into_iter())
                    .map(|b| FfiNotifButton {
                        id: b.id,
                        title: b.title,
                        reply: b.reply,
                    })
                    .collect(),
            },
            Kind::NotificationRemoved(n) => Self::NotificationRemoved { key: n.key },
            Kind::NotificationAction(a) => Self::NotificationAction {
                key: a.key,
                button_id: a.button_id,
                reply_text: a.reply_text,
                dismiss: a.dismiss,
            },
            Kind::Clipboard(c) => Self::Clipboard { text: c.text },
            Kind::NowPlaying(n) => Self::NowPlaying {
                active: n.active,
                app_name: n.app_name,
                title: n.title,
                artist: n.artist,
                playing: n.playing,
                duration_ms: n.duration_ms,
                position_ms: n.position_ms,
            },
            Kind::MediaCommand(m) => Self::MediaCommand {
                cmd: match proto::media_command::Cmd::try_from(m.cmd).ok()? {
                    proto::media_command::Cmd::PlayPause => FfiMediaCmd::PlayPause,
                    proto::media_command::Cmd::Next => FfiMediaCmd::Next,
                    proto::media_command::Cmd::Previous => FfiMediaCmd::Previous,
                },
            },
            Kind::Battery(b) => Self::Battery {
                percent: b.percent,
                charging: b.charging,
            },
            Kind::Ring(r) => Self::Ring { start: r.start },
            Kind::Link(l) => Self::Link { url: l.url },
        })
    }

    fn into_proto(self) -> proto::Event {
        use proto::event::Kind;
        let kind = match self {
            Self::Notification {
                key,
                app_name,
                package,
                title,
                text,
                posted_unix_ms,
                buttons,
            } => Kind::Notification(proto::Notification {
                key,
                app_name,
                package,
                title,
                text,
                posted_unix_ms,
                buttons: (buttons.into_iter())
                    .map(|b| proto::NotifButton {
                        id: b.id,
                        title: b.title,
                        reply: b.reply,
                    })
                    .collect(),
            }),
            Self::NotificationRemoved { key } => {
                Kind::NotificationRemoved(proto::NotificationRemoved { key })
            }
            Self::NotificationAction {
                key,
                button_id,
                reply_text,
                dismiss,
            } => Kind::NotificationAction(proto::NotificationAction {
                key,
                button_id,
                reply_text,
                dismiss,
            }),
            Self::Clipboard { text } => Kind::Clipboard(proto::Clipboard { text }),
            Self::NowPlaying {
                active,
                app_name,
                title,
                artist,
                playing,
                duration_ms,
                position_ms,
            } => Kind::NowPlaying(proto::NowPlaying {
                active,
                app_name,
                title,
                artist,
                playing,
                duration_ms,
                position_ms,
            }),
            Self::MediaCommand { cmd } => Kind::MediaCommand(proto::MediaCommand {
                cmd: match cmd {
                    FfiMediaCmd::PlayPause => proto::media_command::Cmd::PlayPause,
                    FfiMediaCmd::Next => proto::media_command::Cmd::Next,
                    FfiMediaCmd::Previous => proto::media_command::Cmd::Previous,
                }
                .into(),
            }),
            Self::Battery { percent, charging } => {
                Kind::Battery(proto::Battery { percent, charging })
            }
            Self::Ring { start } => Kind::Ring(proto::Ring { start }),
            Self::Link { url } => Kind::Link(proto::Link { url }),
        };
        proto::Event { kind: Some(kind) }
    }
}

#[derive(Debug, Clone, uniffi::Enum)]
pub enum FfiFileEvent {
    Offered {
        transfer_id: String,
        names: Vec<String>,
        total_bytes: u64,
    },
    Progress {
        transfer_id: String,
        index: u32,
        done: u64,
        total: u64,
    },
    Saved {
        transfer_id: String,
        index: u32,
        path: String,
    },
    Failed {
        transfer_id: String,
        index: u32,
        error: String,
    },
}

impl From<FileEvent> for FfiFileEvent {
    fn from(e: FileEvent) -> Self {
        match e {
            FileEvent::Offered {
                transfer_id,
                names,
                total_bytes,
            } => Self::Offered {
                transfer_id: hex(&transfer_id),
                names,
                total_bytes,
            },
            FileEvent::Progress {
                transfer_id,
                index,
                done,
                total,
            } => Self::Progress {
                transfer_id: hex(&transfer_id),
                index,
                done,
                total,
            },
            FileEvent::Saved {
                transfer_id,
                index,
                path,
            } => Self::Saved {
                transfer_id: hex(&transfer_id),
                index,
                path: path.to_string_lossy().into_owned(),
            },
            FileEvent::Failed {
                transfer_id,
                index,
                error,
            } => Self::Failed {
                transfer_id: hex(&transfer_id),
                index,
                error,
            },
        }
    }
}

fn hex(bytes: &[u8]) -> String {
    bytes
        .iter()
        .fold(String::with_capacity(bytes.len() * 2), |mut s, b| {
            let _ = write!(s, "{b:02x}");
            s
        })
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiPeer {
    pub id: String,
    pub name: String,
    pub paired_at_unix: i64,
    pub last_addrs: Vec<String>,
}

impl From<TrustedPeer> for FfiPeer {
    fn from(p: TrustedPeer) -> Self {
        Self {
            id: p.id,
            name: p.name,
            paired_at_unix: p.paired_at_unix,
            last_addrs: p.last_addrs.iter().map(ToString::to_string).collect(),
        }
    }
}

const STREAM_BUF: usize = 256 * 1024;

/// Implemented by the app around its socket (e.g. Bluetooth L2CAP).
#[uniffi::export(foreign)]
pub trait ByteSink: Send + Sync {
    /// Write all of `data` to the socket; false if it's closed.
    fn write(&self, data: Vec<u8>) -> bool;
    /// The bridge ended: close the socket.
    fn finish(&self);
}

#[derive(uniffi::Object)]
pub struct StreamBridge {
    to_core: tokio::sync::Mutex<Option<tokio::io::WriteHalf<tokio::io::DuplexStream>>>,
    peer: tokio::sync::Mutex<
        Option<tokio::sync::oneshot::Receiver<std::result::Result<String, String>>>,
    >,
}

#[uniffi::export(async_runtime = "tokio")]
impl StreamBridge {
    /// Bytes read from the socket. Errors once the bridge has ended.
    pub async fn push(&self, data: Vec<u8>) -> Result<()> {
        use tokio::io::AsyncWriteExt;
        let mut w = self.to_core.lock().await;
        let w = w
            .as_mut()
            .ok_or_else(|| FerryError::from(anyhow::anyhow!("bridge closed")))?;
        w.write_all(&data)
            .await
            .map_err(|e| FerryError::from(anyhow::Error::from(e)))
    }

    /// The socket closed (or the app is done): ends the bridge.
    pub async fn finish(&self) {
        self.to_core.lock().await.take();
    }

    /// The paired device on the other end, once the bridge hello completes (first call only).
    pub async fn wait_peer(&self) -> Result<String> {
        let rx = self
            .peer
            .lock()
            .await
            .take()
            .ok_or_else(|| FerryError::from(anyhow::anyhow!("already awaited")))?;
        rx.await
            .map_err(|_| FerryError::from(anyhow::anyhow!("bridge task ended")))?
            .map_err(|e| FerryError::from(anyhow::anyhow!(e)))
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiUsbLink {
    pub serial: String,
    pub peer_id: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiSource {
    pub path: String,
    pub name: Option<String>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiTxt {
    pub key: String,
    pub value: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiSendReport {
    pub files_sent: u32,
    pub bytes_sent: u64,
    pub elapsed_ms: u64,
}

fn confirm_fn(delegate: Arc<dyn FerryDelegate>) -> ConfirmFn {
    Arc::new(move |p: PairingPrompt| {
        let d = delegate.clone();
        Box::pin(async move { d.confirm_pairing(p.peer_name, p.code).await })
    })
}

#[derive(uniffi::Object)]
pub struct FerryNode {
    node: Arc<Node>,
    delegate: Arc<dyn FerryDelegate>,
}

#[uniffi::export(async_runtime = "tokio")]
impl FerryNode {
    #[uniffi::constructor]
    pub async fn start(
        data_dir: String,
        device_name: String,
        download_dir: String,
        delegate: Arc<dyn FerryDelegate>,
    ) -> Result<Arc<Self>> {
        let d = delegate.clone();
        let events: EventSink = Arc::new(move |e| d.on_file_event(e.into()));
        // Phase 0: accept every item; the core only lets paired peers open a session.
        let accept: AcceptFn = Arc::new(|offer| vec![true; offer.items.len()]);
        let node = Node::start(NodeConfig {
            data_dir: data_dir.into(),
            device_name,
            download_dir: download_dir.into(),
            accept,
            events,
            confirm_pairing: confirm_fn(delegate.clone()),
            on_event: {
                let d = delegate.clone();
                Arc::new(move |peer, ev| {
                    if let Some(ev) = FfiEvent::from_proto(ev) {
                        d.on_event(peer, ev)
                    }
                })
            },
        })
        .await?;
        Ok(Arc::new(Self { node, delegate }))
    }

    pub fn device_id(&self) -> String {
        self.node.device_id()
    }

    pub fn device_name(&self) -> String {
        self.node.device_name()
    }

    /// `ferry://pair?...` ticket for a QR code; valid 10 minutes, single use.
    pub fn new_pairing_ticket(&self) -> Result<String> {
        Ok(self.node.new_pairing_ticket()?)
    }

    /// Scanner side. The delegate's `confirm_pairing` is asked to compare codes.
    pub async fn pair_with_ticket(&self, ticket: String) -> Result<FfiPeer> {
        let peer = self
            .node
            .pair_with_ticket(&ticket, confirm_fn(self.delegate.clone()))
            .await?;
        Ok(peer.into())
    }

    pub fn peers(&self) -> Vec<FfiPeer> {
        self.node.peers().into_iter().map(Into::into).collect()
    }

    pub fn forget_peer(&self, peer_id: String) -> Result<()> {
        Ok(self.node.forget_peer(&peer_id)?)
    }

    /// `addrs` are "ip:port" strings from Bonjour / NsdManager.
    pub fn add_peer_addrs(&self, peer_id: String, addrs: Vec<String>) -> Result<()> {
        let addrs = addrs
            .iter()
            .map(|a| {
                a.parse::<SocketAddr>()
                    .map_err(|e| anyhow::anyhow!("bad address {a:?}: {e}"))
            })
            .collect::<anyhow::Result<Vec<_>>>()?;
        Ok(self.node.add_peer_addrs(&peer_id, addrs)?)
    }

    pub fn presence_txt(&self) -> Vec<FfiTxt> {
        self.node
            .presence_txt()
            .into_iter()
            .map(|(key, value)| FfiTxt { key, value })
            .collect()
    }

    pub fn port(&self) -> u16 {
        self.node.port()
    }

    /// Bluetooth service UUID to advertise (Mac); rotates every 15 min, so re-read it then.
    pub fn presence_ble_uuid(&self) -> String {
        self.node.presence_ble_uuid()
    }

    /// Paired peer that advertised this Bluetooth service UUID (phone scan results).
    pub fn match_presence_ble(&self, uuid: String) -> Option<String> {
        self.node.match_presence_ble(&uuid)
    }

    /// Fixed first 4 bytes of every Ferry Bluetooth UUID, as hex: the phone's scan-filter mask.
    pub fn ble_prefix(&self) -> String {
        hex::encode(ferry_core::discovery::BLE_PREFIX)
    }

    /// Paired peer a natively discovered TXT record belongs to (Android NsdManager).
    pub fn match_presence(&self, txt: Vec<FfiTxt>) -> Option<String> {
        let txt: Vec<(String, String)> = txt.into_iter().map(|t| (t.key, t.value)).collect();
        self.node.match_presence(&txt)
    }

    pub async fn send_files(&self, peer_id: String, paths: Vec<String>) -> Result<FfiSendReport> {
        let files = paths
            .into_iter()
            .map(|p| FfiSource {
                path: p,
                name: None,
            })
            .collect();
        self.send_named(peer_id, files).await
    }

    /// Send with display names (Android: path `/proc/self/fd/N` + the document's name).
    /// Carry the session over an app-owned byte stream (Bluetooth L2CAP). Feed bytes read from
    /// the socket to `push`; the core writes outgoing bytes through `sink`. `label` e.g. "ble:<addr>".
    /// Async so it runs on the tokio runtime: it spawns the bridge tasks.
    pub async fn open_stream_bridge(
        &self,
        label: String,
        sink: Arc<dyn ByteSink>,
    ) -> Arc<StreamBridge> {
        let (core_end, app_end) = tokio::io::duplex(STREAM_BUF);
        let (mut from_core, to_core) = tokio::io::split(app_end);
        let (done_tx, done_rx) = tokio::sync::oneshot::channel();
        let node = self.node.clone();
        tokio::spawn(async move {
            let _ = done_tx.send(
                node.attach_stream(core_end, label)
                    .await
                    .map_err(|e| format!("{e:#}")),
            );
        });
        tokio::spawn(async move {
            use tokio::io::AsyncReadExt;
            let mut buf = vec![0u8; 16 * 1024];
            // core → socket; the sink may block on the radio, so call it off the async workers
            while let Ok(n) = from_core.read(&mut buf).await {
                let s = sink.clone();
                let chunk = buf[..n].to_vec();
                if n == 0
                    || !tokio::task::spawn_blocking(move || s.write(chunk))
                        .await
                        .unwrap_or(false)
                {
                    break;
                }
            }
            sink.finish();
        });
        Arc::new(StreamBridge {
            to_core: tokio::sync::Mutex::new(Some(to_core)),
            peer: tokio::sync::Mutex::new(Some(done_rx)),
        })
    }

    /// Re-announce our addresses to a paired peer after a network change (fresh session).
    pub async fn check_in(&self, peer_id: String) -> Result<()> {
        Ok(self.node.check_in(&peer_id).await?)
    }

    /// Send one event to a paired peer over the shared session.
    pub async fn send_event(&self, peer_id: String, event: FfiEvent) -> Result<()> {
        Ok(self.node.send_event(&peer_id, &event.into_proto()).await?)
    }

    pub async fn send_named(
        &self,
        peer_id: String,
        files: Vec<FfiSource>,
    ) -> Result<FfiSendReport> {
        let files: Vec<(PathBuf, Option<String>)> = files
            .into_iter()
            .map(|f| (PathBuf::from(f.path), f.name))
            .collect();
        let r = self.node.send_named(&peer_id, &files).await?;
        Ok(FfiSendReport {
            files_sent: r.files_sent,
            bytes_sent: r.bytes_sent,
            elapsed_ms: r.elapsed.as_millis() as u64,
        })
    }

    /// Phone side: listen for the Mac's USB bridge (127.0.0.1:47100 through adb forward).
    pub async fn usb_serve(&self) -> Result<()> {
        Ok(self.node.usb_serve(ferry_core::usb::USB_PORT).await?)
    }

    /// Mac side: watch adb for plugged-in phones and attach them. `adb_path` empty = auto.
    /// Async so it runs on the tokio runtime: the watcher spawns tasks.
    pub async fn usb_watch(&self, adb_path: Option<String>) -> Result<()> {
        Ok(self.node.usb_watch(adb_path.map(PathBuf::from))?)
    }

    pub fn usb_links(&self) -> Vec<FfiUsbLink> {
        let links = self.node.usb_links();
        links
            .into_iter()
            .map(|l| FfiUsbLink {
                serial: l.serial,
                peer_id: l.peer_id,
            })
            .collect()
    }

    /// Mac side: open a fresh pairing ticket on the USB phone (no QR scan).
    pub async fn usb_push_ticket(&self, serial: Option<String>) -> Result<()> {
        Ok(self.node.usb_push_ticket(serial).await?)
    }

    pub async fn shutdown(&self) {
        self.node.shutdown().await
    }
}

#[uniffi::export]
pub fn core_version() -> String {
    format!(
        "{} (protocol {})",
        env!("CARGO_PKG_VERSION"),
        ferry_core::PROTOCOL_VERSION
    )
}

#[cfg(test)]
mod tests {
    #[test]
    fn hex_encodes() {
        assert_eq!(super::hex(&[0x00, 0xab, 0x0f]), "00ab0f");
    }
}

#[cfg(test)]
mod event_tests {
    use super::*;

    #[test]
    fn every_event_round_trips_through_proto() {
        let all = [
            FfiEvent::Notification {
                key: "k".into(),
                app_name: "Chat".into(),
                package: "com.chat".into(),
                title: "Ann".into(),
                text: "hi".into(),
                posted_unix_ms: 7,
                buttons: vec![FfiNotifButton {
                    id: "r".into(),
                    title: "Reply".into(),
                    reply: true,
                }],
            },
            FfiEvent::NotificationRemoved { key: "k".into() },
            FfiEvent::NotificationAction {
                key: "k".into(),
                button_id: "r".into(),
                reply_text: "ok".into(),
                dismiss: false,
            },
            FfiEvent::Clipboard { text: "x".into() },
            FfiEvent::NowPlaying {
                active: true,
                app_name: "Music".into(),
                title: "Song".into(),
                artist: "Band".into(),
                playing: true,
                duration_ms: 1000,
                position_ms: 10,
            },
            FfiEvent::MediaCommand {
                cmd: FfiMediaCmd::Next,
            },
            FfiEvent::Battery {
                percent: 42,
                charging: true,
            },
            FfiEvent::Ring { start: true },
            FfiEvent::Link {
                url: "https://example.com".into(),
            },
        ];
        for e in all {
            assert_eq!(FfiEvent::from_proto(e.clone().into_proto()), Some(e));
        }
        assert_eq!(FfiEvent::from_proto(proto::Event { kind: None }), None);
    }
}
