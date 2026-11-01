//! This device's long-term identity: an Ed25519 key (iroh `SecretKey`). The public key is
//! the Device ID. Also holds the 32-byte presence key we hand to peers at pairing.
//! Phase 0 stores both in files under the data dir (0600); apps will move them into
//! Keychain / Android Keystore via the FFI layer.

use std::io::Write;
use std::path::Path;

use anyhow::{Context, Result, anyhow};
use iroh::SecretKey;

pub struct Identity {
    pub secret_key: SecretKey,
    pub presence_key: [u8; 32],
}

pub fn load_or_create(data_dir: &Path) -> Result<Identity> {
    std::fs::create_dir_all(data_dir).with_context(|| format!("create {}", data_dir.display()))?;
    let secret = load_or_create_key(&data_dir.join("identity.key"))?;
    let presence_key = load_or_create_key(&data_dir.join("presence.key"))?;
    Ok(Identity {
        secret_key: SecretKey::from_bytes(&secret),
        presence_key,
    })
}

fn load_or_create_key(path: &Path) -> Result<[u8; 32]> {
    match std::fs::read(path) {
        // never silently replace a damaged key: that would change the Device ID
        Ok(bytes) => <[u8; 32]>::try_from(bytes.as_slice()).map_err(|_| {
            anyhow!(
                "{} is corrupt ({} bytes, want 32)",
                path.display(),
                bytes.len()
            )
        }),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
            let key: [u8; 32] = rand::random();
            write_private_atomic(path, &key)?;
            Ok(key)
        }
        Err(e) => Err(e).with_context(|| format!("read {}", path.display())),
    }
}

/// Device IDs are shown as z-base-32; hex is accepted too. (iroh's `FromStr` reads
/// *standard* base32, which would silently decode a z32 string to a different key.)
pub fn parse_device_id(s: &str) -> Result<iroh::EndpointId> {
    let s = s.trim();
    let id = if s.len() == 64 {
        s.parse().ok()
    } else {
        iroh::EndpointId::from_z32(s).ok()
    };
    id.with_context(|| format!("not a device id: {s}"))
}

/// Write via temp file + rename so a crash never leaves a half-written file; mode 0600.
pub(crate) fn write_private_atomic(path: &Path, bytes: &[u8]) -> Result<()> {
    let tmp = path.with_extension("tmp");
    let mut opts = std::fs::OpenOptions::new();
    opts.write(true).create(true).truncate(true);
    #[cfg(unix)]
    std::os::unix::fs::OpenOptionsExt::mode(&mut opts, 0o600);
    let mut f = opts
        .open(&tmp)
        .with_context(|| format!("create {}", tmp.display()))?;
    f.write_all(bytes)?;
    f.sync_all()?;
    drop(f);
    std::fs::rename(&tmp, path).with_context(|| format!("rename to {}", path.display()))?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stable_across_loads() {
        let dir = tempfile::tempdir().unwrap();
        let a = load_or_create(dir.path()).unwrap();
        let b = load_or_create(dir.path()).unwrap();
        assert_eq!(a.secret_key.public(), b.secret_key.public());
        assert_eq!(a.presence_key, b.presence_key);
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = std::fs::metadata(dir.path().join("identity.key"))
                .unwrap()
                .permissions()
                .mode();
            assert_eq!(mode & 0o777, 0o600);
        }
        let id = a.secret_key.public();
        assert_eq!(parse_device_id(&id.to_z32()).unwrap(), id);
        assert_eq!(parse_device_id(&id.to_string()).unwrap(), id);
        assert!(parse_device_id("nope").is_err());
        std::fs::write(dir.path().join("presence.key"), b"short").unwrap();
        assert!(load_or_create(dir.path()).is_err());
    }
}
