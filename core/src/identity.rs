//! This device's long-term identity: an Ed25519 key (iroh `SecretKey`). The public key is
//! the Device ID. Also holds the 32-byte presence key we hand to peers at pairing.
//! Phase 0 stores both in files under the data dir (0600); apps will move them into
//! Keychain / Android Keystore via the FFI layer.
