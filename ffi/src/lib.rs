//! UniFFI bindings over `ferry-core` for the Swift (Mac) and Kotlin (Android) shells.

use std::fmt::Write as _;
use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::Arc;

use ferry_core::file::{AcceptFn, EventSink, FileEvent};
use ferry_core::node::{ConfirmFn, Node, NodeConfig, PairingPrompt};
use ferry_core::trust::TrustedPeer;

uniffi::setup_scaffolding!();

#[derive(Debug, uniffi::Error)]
pub enum FerryError {
    Failed { message: String },
}

impl std::fmt::Display for FerryError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let Self::Failed { message } = self;
        f.write_str(message)
    }
}

impl std::error::Error for FerryError {}

impl From<anyhow::Error> for FerryError {
    fn from(e: anyhow::Error) -> Self {
        Self::Failed { message: format!("{e:#}") }
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
}

#[derive(Debug, Clone, uniffi::Enum)]
pub enum FfiFileEvent {
    Offered { transfer_id: String, names: Vec<String>, total_bytes: u64 },
    Progress { transfer_id: String, index: u32, done: u64, total: u64 },
    Saved { transfer_id: String, index: u32, path: String },
    Failed { transfer_id: String, index: u32, error: String },
}

impl From<FileEvent> for FfiFileEvent {
    fn from(e: FileEvent) -> Self {
        match e {
            FileEvent::Offered { transfer_id, names, total_bytes } => {
                Self::Offered { transfer_id: hex(&transfer_id), names, total_bytes }
            }
            FileEvent::Progress { transfer_id, index, done, total } => {
                Self::Progress { transfer_id: hex(&transfer_id), index, done, total }
            }
            FileEvent::Saved { transfer_id, index, path } => Self::Saved {
                transfer_id: hex(&transfer_id),
                index,
                path: path.to_string_lossy().into_owned(),
            },
            FileEvent::Failed { transfer_id, index, error } => {
                Self::Failed { transfer_id: hex(&transfer_id), index, error }
            }
        }
    }
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().fold(String::with_capacity(bytes.len() * 2), |mut s, b| {
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
        let peer = self.node.pair_with_ticket(&ticket, confirm_fn(self.delegate.clone())).await?;
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
            .map(|a| a.parse::<SocketAddr>().map_err(|e| anyhow::anyhow!("bad address {a:?}: {e}")))
            .collect::<anyhow::Result<Vec<_>>>()?;
        Ok(self.node.add_peer_addrs(&peer_id, addrs)?)
    }

    pub fn presence_txt(&self) -> Vec<FfiTxt> {
        self.node.presence_txt().into_iter().map(|(key, value)| FfiTxt { key, value }).collect()
    }

    pub fn port(&self) -> u16 {
        self.node.port()
    }

    pub async fn send_files(&self, peer_id: String, paths: Vec<String>) -> Result<FfiSendReport> {
        let paths: Vec<PathBuf> = paths.into_iter().map(PathBuf::from).collect();
        let r = self.node.send_files(&peer_id, &paths).await?;
        Ok(FfiSendReport {
            files_sent: r.files_sent,
            bytes_sent: r.bytes_sent,
            elapsed_ms: r.elapsed.as_millis() as u64,
        })
    }

    pub async fn shutdown(&self) {
        self.node.shutdown().await
    }
}

#[uniffi::export]
pub fn core_version() -> String {
    format!("{} (protocol {})", env!("CARGO_PKG_VERSION"), ferry_core::PROTOCOL_VERSION)
}

#[cfg(test)]
mod tests {
    #[test]
    fn hex_encodes() {
        assert_eq!(super::hex(&[0x00, 0xab, 0x0f]), "00ab0f");
    }
}
