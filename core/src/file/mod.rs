//! File channel: offer → accept (with resume offsets) → one data stream per file
//! → whole-file BLAKE3 check → atomic rename into the destination folder.
//!
//! Wire flow (control = one bi stream, prefix CH_FILE):
//!   S→R Offer · R→S OfferReply · per file S opens a uni stream (prefix CH_FILE,
//!   DataHeader, raw bytes from `offset` to end) · S→R ItemDone · R→S ItemResult.
//! Partial files survive in `<dest>/.ferry-partial/` so a retried offer resumes.

use std::path::PathBuf;
use std::sync::Arc;

use anyhow::Result;
use iroh::endpoint::{Connection, RecvStream, SendStream};

use crate::proto::Offer;

/// Progress and results, for CLI output and app UI.
#[derive(Debug, Clone)]
pub enum FileEvent {
    Offered { transfer_id: [u8; 16], names: Vec<String>, total_bytes: u64 },
    Progress { transfer_id: [u8; 16], index: u32, done: u64, total: u64 },
    Saved { transfer_id: [u8; 16], index: u32, path: PathBuf },
    Failed { transfer_id: [u8; 16], index: u32, error: String },
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

/// Receiving side of the file channel for one destination folder. Shared by all
/// connections; the node routes CH_FILE streams here.
pub struct Receiver {
    // filled in by implementation
}

impl Receiver {
    pub fn new(dest_dir: PathBuf, accept: AcceptFn, events: EventSink) -> Arc<Self> {
        todo!()
    }

    /// Handle a CH_FILE bi stream (prefix already consumed by the node).
    pub async fn handle_control(self: Arc<Self>, send: SendStream, recv: RecvStream) -> Result<()> {
        todo!()
    }

    /// Handle a CH_FILE uni stream (prefix already consumed by the node).
    pub async fn handle_data(self: Arc<Self>, recv: RecvStream) -> Result<()> {
        todo!()
    }
}

/// Send `paths` (regular files only) over an established session connection.
pub async fn send_files(conn: &Connection, paths: &[PathBuf], events: EventSink) -> Result<SendReport> {
    todo!()
}
