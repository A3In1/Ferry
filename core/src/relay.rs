//! Optional internet relay (Phase 7): off unless `<data_dir>/relay` holds an https URL.
//!
//! The relay forwards QUIC packets between paired devices on different networks; they are
//! end-to-end encrypted (TLS 1.3 with the devices' own keys), so the relay never sees content
//! or keys. The relay's own TLS certificate is always verified: public CAs, plus an optional
//! extra root in `<data_dir>/relay-ca.pem` (PEM or DER) for a self-hosted relay.

use std::io::ErrorKind;
use std::path::Path;

use anyhow::{Context, Result, ensure};
use iroh::RelayUrl;
use iroh::tls::CaTlsConfig;
use rustls_pki_types::CertificateDer;
use rustls_pki_types::pem::PemObject;

const URL_FILE: &str = "relay";
const CA_FILE: &str = "relay-ca.pem";

pub fn parse(url: &str) -> Result<RelayUrl> {
    let u: RelayUrl = url.trim().parse().context("relay URL")?;
    ensure!(
        u.scheme() == "https",
        "the relay URL must start with https://"
    );
    Ok(u)
}

/// The configured relay and how to verify its certificate; `None` = relay off.
pub fn load(data_dir: &Path) -> Result<Option<(RelayUrl, CaTlsConfig)>> {
    let url = match std::fs::read_to_string(data_dir.join(URL_FILE)) {
        Ok(s) if s.trim().is_empty() => return Ok(None),
        Ok(s) => parse(&s)?,
        Err(e) if e.kind() == ErrorKind::NotFound => return Ok(None),
        Err(e) => return Err(e).context("read relay setting"),
    };
    let ca = match std::fs::read(data_dir.join(CA_FILE)) {
        Ok(b) => CaTlsConfig::default().with_extra_roots(certs(&b)?),
        Err(e) if e.kind() == ErrorKind::NotFound => CaTlsConfig::default(),
        Err(e) => return Err(e).context("read relay-ca.pem"),
    };
    Ok(Some((url, ca)))
}

/// Saves (or with `None`/empty, clears) the relay URL. Takes effect when the node restarts.
pub fn save(data_dir: &Path, url: Option<&str>) -> Result<()> {
    let path = data_dir.join(URL_FILE);
    match url.map(str::trim).filter(|u| !u.is_empty()) {
        Some(u) => std::fs::write(&path, parse(u)?.to_string())?,
        None => match std::fs::remove_file(&path) {
            Err(e) if e.kind() != ErrorKind::NotFound => return Err(e.into()),
            _ => {}
        },
    }
    Ok(())
}

fn certs(b: &[u8]) -> Result<Vec<CertificateDer<'static>>> {
    if b.starts_with(b"-----BEGIN") {
        (CertificateDer::pem_slice_iter(b).collect::<Result<Vec<_>, _>>())
            .context("parse relay-ca.pem")
    } else {
        Ok(vec![CertificateDer::from(b.to_vec())])
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn https_only_and_round_trip() {
        assert!(parse("http://relay.example.com").is_err());
        assert!(parse("not a url").is_err());
        let dir = tempfile::tempdir().unwrap();
        assert!(load(dir.path()).unwrap().is_none());
        save(dir.path(), Some(" https://relay.example.com ")).unwrap();
        let (u, _) = load(dir.path()).unwrap().unwrap();
        assert_eq!(u, parse("https://relay.example.com").unwrap());
        assert!(save(dir.path(), Some("http://x")).is_err());
        save(dir.path(), None).unwrap();
        assert!(load(dir.path()).unwrap().is_none());
        save(dir.path(), Some("")).unwrap(); // clearing twice is fine
    }
}
