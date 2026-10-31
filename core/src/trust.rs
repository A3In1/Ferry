//! Paired devices, persisted as JSON in the data dir. Only peers in this store may open
//! a session (ALPN_SESSION).

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct TrustedPeer {
    pub id: String,           // iroh public key string
    pub name: String,
    pub presence_key: String, // hex, 32 bytes: recognises the peer's presence tags
    pub paired_at_unix: i64,
    pub last_addrs: Vec<std::net::SocketAddr>,
}
