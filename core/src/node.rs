//! The running device: iroh endpoint + trust store + channel routing.
//!
//! Relays and n0 DNS lookups are disabled (no traffic leaves the LAN). Peer addresses
//! come from mDNS (feature `mdns`), from native discovery via [`Node::add_peer_addrs`],
//! or from the last addresses stored in the trust record.

use std::future::Future;
use std::net::SocketAddr;
use std::path::PathBuf;
use std::pin::Pin;
use std::sync::Arc;

use anyhow::Result;

use crate::file::{AcceptFn, EventSink, SendReport};
use crate::trust::TrustedPeer;

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
}

pub struct Node {
    // filled in by implementation
}

impl Node {
    /// Load identity + trust store, bind the endpoint, start the accept loop
    /// (and mDNS advertise/browse when the `mdns` feature is on).
    pub async fn start(cfg: NodeConfig) -> Result<Arc<Node>> {
        todo!()
    }
    /// This device's ID (iroh public key, z-base-32 string form).
    pub fn device_id(&self) -> String {
        todo!()
    }
    pub fn device_name(&self) -> String {
        todo!()
    }
    /// A one-use `ferry://pair?...` ticket valid for 10 minutes, for display as a QR code.
    pub fn new_pairing_ticket(&self) -> Result<String> {
        todo!()
    }
    /// Scanner side: pair with the device that showed `ticket`.
    pub async fn pair_with_ticket(&self, ticket: &str, confirm: ConfirmFn) -> Result<TrustedPeer> {
        todo!()
    }
    pub fn peers(&self) -> Vec<TrustedPeer> {
        todo!()
    }
    pub fn forget_peer(&self, peer_id: &str) -> Result<()> {
        todo!()
    }
    /// Addresses found by native discovery (Bonjour / NsdManager) for a paired peer.
    pub fn add_peer_addrs(&self, peer_id: &str, addrs: Vec<SocketAddr>) -> Result<()> {
        todo!()
    }
    /// Our current presence tag for native mDNS/BLE adverts: (txt key, value) pairs.
    pub fn presence_txt(&self) -> Vec<(String, String)> {
        todo!()
    }
    /// The UDP port our endpoint is bound to (for native mDNS registration).
    pub fn port(&self) -> u16 {
        todo!()
    }
    pub async fn send_files(&self, peer_id: &str, paths: &[PathBuf]) -> Result<SendReport> {
        todo!()
    }
    pub async fn shutdown(&self) {
        todo!()
    }
}
