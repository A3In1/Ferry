//! Paired devices, persisted as JSON in the data dir. Only peers in this store may open
//! a session (ALPN_SESSION).

use std::path::{Path, PathBuf};

use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct TrustedPeer {
    pub id: String, // iroh public key string
    pub name: String,
    pub presence_key: String, // hex, 32 bytes: recognises the peer's presence tags
    pub paired_at_unix: i64,
    pub last_addrs: Vec<std::net::SocketAddr>,
    /// The peer's internet relay URL, learned from its Hello (Phase 7).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub last_relay: Option<String>,
}

/// `<data_dir>/peers.json`. Every mutation is saved immediately (atomic replace).
pub struct TrustStore {
    path: PathBuf,
    peers: Vec<TrustedPeer>,
}

impl TrustStore {
    /// A missing file is an empty store; a corrupt one is an error (never drop pairings silently).
    pub fn load(data_dir: &Path) -> Result<Self> {
        let path = data_dir.join("peers.json");
        let peers = match std::fs::read(&path) {
            Ok(b) => {
                serde_json::from_slice(&b).with_context(|| format!("parse {}", path.display()))?
            }
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Vec::new(),
            Err(e) => return Err(e).with_context(|| format!("read {}", path.display())),
        };
        Ok(Self { path, peers })
    }

    pub fn get(&self, id: &str) -> Option<&TrustedPeer> {
        self.peers.iter().find(|p| p.id == id)
    }

    pub fn list(&self) -> &[TrustedPeer] {
        &self.peers
    }

    /// Insert or replace by id (re-pairing a known key just updates its record).
    pub fn upsert(&mut self, peer: TrustedPeer) -> Result<()> {
        match self.peers.iter_mut().find(|p| p.id == peer.id) {
            Some(p) => *p = peer,
            None => self.peers.push(peer),
        }
        self.save()
    }

    /// Returns whether the peer existed.
    pub fn remove(&mut self, id: &str) -> Result<bool> {
        let before = self.peers.len();
        self.peers.retain(|p| p.id != id);
        if self.peers.len() == before {
            return Ok(false);
        }
        self.save()?;
        Ok(true)
    }

    fn save(&self) -> Result<()> {
        crate::identity::write_private_atomic(&self.path, &serde_json::to_vec_pretty(&self.peers)?)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn persist_upsert_remove() {
        let dir = tempfile::tempdir().unwrap();
        let mut s = TrustStore::load(dir.path()).unwrap();
        assert!(s.list().is_empty());
        let mut p = TrustedPeer {
            id: "a".into(),
            name: "Phone".into(),
            presence_key: "00".repeat(32),
            paired_at_unix: 1,
            last_addrs: vec!["192.168.1.2:4000".parse().unwrap()],
            last_relay: Some("https://relay.example.com/".into()),
        };
        s.upsert(p.clone()).unwrap();
        p.name = "Renamed".into();
        s.upsert(p.clone()).unwrap();
        let s2 = TrustStore::load(dir.path()).unwrap();
        assert_eq!(s2.list(), &[p]);
        let mut s2 = s2;
        assert!(s2.remove("a").unwrap());
        assert!(!s2.remove("a").unwrap());
        assert!(TrustStore::load(dir.path()).unwrap().list().is_empty());
    }
}
