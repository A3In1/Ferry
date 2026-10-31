//! LAN presence: rotating presence tags in the `_ferry._udp` mDNS TXT record, so only
//! paired devices can recognise us. Resolved addresses feed iroh's MemoryLookup.
//!
//! TXT: `v=1`, `n=<hex 8-byte nonce>`, `t=<hex first 8 bytes of blake3_keyed(presence_key, nonce)>`.
//! Nothing else identifying: the instance and host names are random and rotate with the nonce.

use std::time::Duration;

use crate::trust::TrustedPeer;

/// How often the nonce (and with it tag and instance name) changes.
pub const ROTATE_EVERY: Duration = Duration::from_secs(15 * 60);

pub fn tag(presence_key: &[u8; 32], nonce: &[u8; 8]) -> [u8; 8] {
    let h = blake3::keyed_hash(presence_key, nonce);
    h.as_bytes()[..8].try_into().expect("8 bytes")
}

pub fn presence_txt(presence_key: &[u8; 32], nonce: &[u8; 8]) -> Vec<(String, String)> {
    vec![
        ("v".into(), "1".into()),
        ("n".into(), hex::encode(nonce)),
        ("t".into(), hex::encode(tag(presence_key, nonce))),
    ]
}

/// Which trusted peer (if any) produced `tag` for `nonce`.
pub fn match_tag(peers: &[TrustedPeer], nonce: &[u8; 8], tag_: &[u8; 8]) -> Option<String> {
    peers
        .iter()
        .find(|p| {
            let mut key = [0u8; 32];
            hex::decode_to_slice(&p.presence_key, &mut key).is_ok() && tag(&key, nonce) == *tag_
        })
        .map(|p| p.id.clone())
}

/// [`match_tag`] on raw TXT values (from mdns-sd, Bonjour or NsdManager); junk → None.
pub fn match_txt<'a>(peers: &[TrustedPeer], get: impl Fn(&str) -> Option<&'a str>) -> Option<String> {
    if get("v")? != "1" {
        return None;
    }
    let (mut nonce, mut t) = ([0u8; 8], [0u8; 8]);
    hex::decode_to_slice(get("n")?, &mut nonce).ok()?;
    hex::decode_to_slice(get("t")?, &mut t).ok()?;
    match_tag(peers, &nonce, &t)
}

#[cfg(feature = "mdns")]
pub(crate) use mdns::Mdns;

#[cfg(feature = "mdns")]
mod mdns {
    use std::net::{IpAddr, SocketAddr};
    use std::sync::Arc;

    use anyhow::Result;
    use mdns_sd::{ServiceDaemon, ServiceEvent, ServiceInfo};

    use super::{ROTATE_EVERY, match_txt};
    use crate::MDNS_SERVICE;
    use crate::node::Node;

    pub(crate) struct Mdns {
        daemon: ServiceDaemon,
        task: tokio::task::JoinHandle<()>,
    }

    impl Mdns {
        pub(crate) fn start(node: Arc<Node>) -> Result<Self> {
            let daemon = ServiceDaemon::new()?;
            let browse = daemon.browse(MDNS_SERVICE)?;
            let d = daemon.clone();
            let task = tokio::spawn(async move {
                // shortcut: rotation = unregister + re-register on a timer (brief gap in
                // visibility each 15 min); upgrade to an in-place TXT update if that gap matters.
                let mut timer = tokio::time::interval(ROTATE_EVERY);
                let mut ours: Option<String> = None;
                loop {
                    tokio::select! {
                        _ = timer.tick() => {
                            if let Some(old) = ours.take() {
                                let _ = d.unregister(&old);
                            }
                            node.rotate_presence();
                            match register(&d, &node) {
                                Ok(full) => ours = Some(full),
                                Err(e) => tracing::warn!("mDNS register failed: {e:#}"),
                            }
                        }
                        ev = browse.recv_async() => match ev {
                            Ok(ServiceEvent::ServiceResolved(s)) if Some(&s.fullname) != ours.as_ref() => {
                                let Some(id) = match_txt(&node.peers(), |k| s.get_property_val_str(k)) else {
                                    continue;
                                };
                                let addrs: Vec<SocketAddr> = s
                                    .addresses
                                    .iter()
                                    .map(|ip| ip.to_ip_addr())
                                    // link-local v6 needs a scope id iroh can't take from here
                                    .filter(|ip| !matches!(ip, IpAddr::V6(v6) if v6.segments()[0] & 0xffc0 == 0xfe80))
                                    .map(|ip| SocketAddr::new(ip, s.port))
                                    .collect();
                                if addrs.is_empty() {
                                    continue;
                                }
                                tracing::debug!(peer = %id, ?addrs, "mDNS found paired peer");
                                if let Err(e) = node.add_peer_addrs(&id, addrs) {
                                    tracing::warn!("add mDNS addrs: {e:#}");
                                }
                            }
                            Ok(_) => {}
                            Err(_) => break,
                        },
                    }
                }
            });
            Ok(Self { daemon, task })
        }

        pub(crate) fn shutdown(&self) {
            self.task.abort();
            let _ = self.daemon.shutdown();
        }
    }

    /// Returns the registered fullname.
    fn register(d: &ServiceDaemon, node: &Node) -> Result<String> {
        let instance = format!("ferry-{}", hex::encode(rand::random::<[u8; 6]>()));
        let host = format!("{instance}.local.");
        let txt = node.presence_txt();
        let info = ServiceInfo::new(MDNS_SERVICE, &instance, &host, (), node.port(), &txt[..])?.enable_addr_auto();
        let full = info.get_fullname().to_string();
        d.register(info)?;
        Ok(full)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tags_match_only_the_right_peer() {
        let (k1, k2) = ([1u8; 32], [2u8; 32]);
        let peer = |id: &str, k: &[u8; 32]| TrustedPeer {
            id: id.into(),
            name: id.into(),
            presence_key: hex::encode(k),
            paired_at_unix: 0,
            last_addrs: vec![],
        };
        let peers = vec![peer("one", &k1), peer("two", &k2)];
        let nonce = [9u8; 8];
        let txt = presence_txt(&k2, &nonce);
        let get = |k: &str| txt.iter().find(|(kk, _)| kk == k).map(|(_, v)| v.as_str());
        assert_eq!(match_txt(&peers, get), Some("two".into()));
        assert_eq!(match_tag(&peers, &[8u8; 8], &tag(&k2, &nonce)), None);
        assert_eq!(match_tag(&peers, &nonce, &tag(&[3u8; 32], &nonce)), None);
        assert_eq!(match_txt(&peers, |k| if k == "v" { Some("1") } else { Some("zz") }), None);
    }
}
