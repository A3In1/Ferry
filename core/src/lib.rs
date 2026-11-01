//! Ferry core: identity, pairing, sessions and channels shared by the Mac and Android apps.

#[cfg(not(target_os = "android"))]
pub mod adb;
pub mod channel;
pub mod discovery;
pub mod file;
pub mod identity;
pub mod node;
pub mod pairing;
pub mod sanitize;
pub mod trust;
pub mod usb;

pub use ferry_proto::v1 as proto;

/// ALPN for a session between already-paired devices.
pub const ALPN_SESSION: &[u8] = b"ferry/1";
/// ALPN for the one-time pairing exchange.
pub const ALPN_PAIR: &[u8] = b"ferry/pair/1";
pub const PROTOCOL_VERSION: u32 = 1;
/// mDNS service type, also what the native apps register with Bonjour/NsdManager.
pub const MDNS_SERVICE: &str = "_ferry._udp.local.";
