//! QR-ticket pairing over ALPN_PAIR, bound to the TLS session via keying-material export,
//! with a 6-digit code both users compare.
//!
//! The scanner dials the key in the ticket, so TLS already authenticates the shower. Each
//! side then proves it knows the ticket secret by MACing *this* session's TLS exporter with
//! it, so a proof can't be replayed on another connection. The 6-digit code (also from the
//! exporter) catches a leaked ticket: an attacker who has it gets a different code.
//!
//! Wire (one bi stream, prefix CH_CONTROL): scanner→shower PairHello · shower→scanner
//! PairHello · both PairConfirm. Only if both confirm does either side store the peer.

use std::fmt::{self, Write as _};
use std::net::SocketAddr;
use std::str::FromStr;
use std::time::Duration;

use anyhow::{Context, Result, anyhow, bail, ensure};
use iroh::EndpointId;
use iroh::endpoint::{Connection, RecvStream, SendStream};
use tokio::time::timeout;

use crate::channel::{CH_CONTROL, read_msg, read_prefix, write_msg, write_prefix};
use crate::node::{ConfirmFn, PairingPrompt};
use crate::proto::{PairConfirm, PairHello};

pub const TICKET_TTL: Duration = Duration::from_mins(10);
const NET_TIMEOUT: Duration = Duration::from_secs(10);
const CONFIRM_TIMEOUT: Duration = Duration::from_mins(2);
/// Connection close code: the scanner's proof didn't match a live ticket.
pub const ERR_PAIR_REJECTED: u32 = 0x10;

const LABEL_SCANNER: &[u8] = b"ferry pair scanner";
const LABEL_SHOWER: &[u8] = b"ferry pair shower";
const LABEL_SAS: &[u8] = b"ferry pair sas";
const MAX_NAME_CHARS: usize = 64;
const MAX_TICKET_ADDRS: usize = 16;

/// `ferry://pair?v=1&id=<endpoint id>&s=<secret hex>&n=<percent-encoded name>&a=<ip:port,...>`
#[derive(Debug, Clone, PartialEq)]
pub struct Ticket {
    pub id: EndpointId,
    pub secret: [u8; 32],
    pub name: String,
    pub addrs: Vec<SocketAddr>,
}

const TICKET_PREFIX: &str = "ferry://pair?";

impl fmt::Display for Ticket {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let addrs: Vec<String> = self.addrs.iter().map(ToString::to_string).collect();
        write!(
            f,
            "{TICKET_PREFIX}v=1&id={}&s={}&n={}&a={}",
            self.id.to_z32(),
            hex::encode(self.secret),
            pct_encode(&self.name),
            addrs.join(",")
        )
    }
}

impl FromStr for Ticket {
    type Err = anyhow::Error;

    /// Tickets come from a camera, so everything is validated; unknown keys are ignored.
    fn from_str(s: &str) -> Result<Self> {
        let query = s
            .trim()
            .strip_prefix(TICKET_PREFIX)
            .context("not a ferry pairing ticket")?;
        let (mut v, mut id, mut secret, mut name, mut addrs) = (None, None, None, None, None);
        for pair in query.split('&') {
            let (k, val) = pair.split_once('=').context("malformed ticket field")?;
            match k {
                "v" => v = Some(val),
                "id" => id = Some(val),
                "s" => secret = Some(val),
                "n" => name = Some(val),
                "a" => addrs = Some(val),
                _ => {}
            }
        }
        ensure!(v == Some("1"), "unsupported ticket version");
        let id = crate::identity::parse_device_id(id.context("ticket has no id")?)
            .context("bad ticket id")?;
        let mut sec = [0u8; 32];
        hex::decode_to_slice(secret.context("ticket has no secret")?, &mut sec)
            .map_err(|_| anyhow!("bad ticket secret"))?;
        let name = clean_name(&pct_decode(name.unwrap_or(""))?);
        let addrs: Vec<SocketAddr> = match addrs.unwrap_or("") {
            "" => Vec::new(),
            a => a
                .split(',')
                .map(|x| x.parse().map_err(|_| anyhow!("bad ticket address")))
                .collect::<Result<_>>()?,
        };
        ensure!(addrs.len() <= MAX_TICKET_ADDRS, "too many ticket addresses");
        Ok(Ticket {
            id,
            secret: sec,
            name,
            addrs,
        })
    }
}

/// Percent-encode everything but RFC 3986 unreserved characters.
fn pct_encode(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        if b.is_ascii_alphanumeric() || b"-._~".contains(&b) {
            out.push(b as char);
        } else {
            let _ = write!(out, "%{b:02X}");
        }
    }
    out
}

fn pct_decode(s: &str) -> Result<String> {
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        if b[i] == b'%' {
            let hex = b.get(i + 1..i + 3).context("truncated percent escape")?;
            let hex = std::str::from_utf8(hex)
                .ok()
                .and_then(|h| u8::from_str_radix(h, 16).ok());
            out.push(hex.context("bad percent escape")?);
            i += 3;
        } else {
            out.push(b[i]);
            i += 1;
        }
    }
    String::from_utf8(out).context("ticket name is not UTF-8")
}

/// Peer-supplied device names: no control characters, bounded length.
pub(crate) fn clean_name(s: &str) -> String {
    let name: String = s
        .chars()
        .filter(|c| !c.is_control())
        .take(MAX_NAME_CHARS)
        .collect();
    match name.trim() {
        "" => "Unnamed device".into(),
        n => n.into(),
    }
}

/// What we learn about the other device from a successful pairing.
pub(crate) struct PairedPeer {
    pub name: String,
    pub presence_key: [u8; 32],
}

/// Our side's details sent in PairHello.
pub(crate) struct Me<'a> {
    pub name: &'a str,
    pub presence_key: &'a [u8; 32],
}

fn exporter(conn: &Connection, label: &[u8]) -> Result<[u8; 32]> {
    let mut out = [0u8; 32];
    conn.export_keying_material(&mut out, label, b"")
        .map_err(|e| anyhow!("TLS exporter: {e:?}"))?;
    Ok(out)
}

fn proof(secret: &[u8; 32], conn: &Connection, label: &[u8]) -> Result<blake3::Hash> {
    Ok(blake3::keyed_hash(secret, &exporter(conn, label)?))
}

/// Constant-time (blake3::Hash's `==` is constant-time).
fn proof_ok(got: &[u8], want: &blake3::Hash) -> bool {
    <[u8; 32]>::try_from(got).is_ok_and(|g| blake3::Hash::from_bytes(g) == *want)
}

fn sas_code(conn: &Connection) -> Result<String> {
    let e = exporter(conn, LABEL_SAS)?;
    Ok(format!(
        "{:06}",
        u32::from_le_bytes([e[0], e[1], e[2], e[3]]) % 1_000_000
    ))
}

fn hello(me: &Me<'_>, proof: blake3::Hash) -> PairHello {
    PairHello {
        device_name: me.name.into(),
        proof: proof.as_bytes().to_vec(),
        presence_key: me.presence_key.to_vec(),
    }
}

fn peer_from(h: &PairHello) -> Result<PairedPeer> {
    let presence_key =
        <[u8; 32]>::try_from(h.presence_key.as_slice()).map_err(|_| anyhow!("bad presence key"))?;
    Ok(PairedPeer {
        name: clean_name(&h.device_name),
        presence_key,
    })
}

/// Scanner (phone) side, on a fresh ALPN_PAIR connection to the ticket's id.
pub(crate) async fn scan(
    conn: &Connection,
    ticket: &Ticket,
    me: &Me<'_>,
    confirm: &ConfirmFn,
) -> Result<PairedPeer> {
    let res = async {
        let (mut send, mut recv) = timeout(NET_TIMEOUT, conn.open_bi())
            .await
            .context("open stream timed out")??;
        write_prefix(&mut send, CH_CONTROL).await?;
        write_msg(
            &mut send,
            &hello(me, proof(&ticket.secret, conn, LABEL_SCANNER)?),
        )
        .await?;
        let reply: PairHello = match timeout(NET_TIMEOUT, read_msg(&mut recv)).await {
            Ok(Ok(r)) => r,
            _ if conn.close_reason().is_some() => {
                bail!(
                    "the other device rejected the ticket (expired, already used, or not current)"
                )
            }
            Ok(Err(e)) => return Err(e),
            Err(_) => bail!("no pairing reply"),
        };
        ensure!(
            proof_ok(&reply.proof, &proof(&ticket.secret, conn, LABEL_SHOWER)?),
            "the other device could not prove it holds the ticket"
        );
        let peer = peer_from(&reply)?;
        confirm_both(conn, send, recv, &peer.name, confirm).await?;
        Ok(peer)
    }
    .await;
    conn.close(0u32.into(), b"done");
    res
}

/// Shower (Mac) side. `take_secret` consumes the live ticket: any attempt burns it.
pub(crate) async fn show(
    conn: &Connection,
    take_secret: impl FnOnce() -> Option<[u8; 32]>,
    me: &Me<'_>,
    confirm: &ConfirmFn,
) -> Result<PairedPeer> {
    let res = async {
        let (mut send, mut recv) = timeout(NET_TIMEOUT, conn.accept_bi())
            .await
            .context("no pairing stream")??;
        ensure!(
            timeout(NET_TIMEOUT, read_prefix(&mut recv)).await?? == CH_CONTROL,
            "wrong pairing channel"
        );
        let h: PairHello = timeout(NET_TIMEOUT, read_msg(&mut recv)).await??;
        let secret = take_secret();
        let valid = secret
            .map(|s| proof(&s, conn, LABEL_SCANNER).map(|p| proof_ok(&h.proof, &p)))
            .transpose()?;
        let Some((secret, true)) = secret.zip(valid) else {
            conn.close(ERR_PAIR_REJECTED.into(), b"invalid ticket");
            bail!("pairing attempt with an invalid, expired or used ticket");
        };
        let peer = peer_from(&h)?;
        write_msg(&mut send, &hello(me, proof(&secret, conn, LABEL_SHOWER)?)).await?;
        confirm_both(conn, send, recv, &peer.name, confirm).await?;
        Ok(peer)
    }
    .await;
    // the scanner closes once it has read everything; give it a moment
    let _ = timeout(NET_TIMEOUT, conn.closed()).await;
    conn.close(0u32.into(), b"done");
    res
}

async fn confirm_both(
    conn: &Connection,
    mut send: SendStream,
    mut recv: RecvStream,
    peer_name: &str,
    confirm: &ConfirmFn,
) -> Result<()> {
    let code = sas_code(conn)?;
    let mine = timeout(
        CONFIRM_TIMEOUT,
        confirm(PairingPrompt {
            peer_name: peer_name.into(),
            code,
        }),
    )
    .await
    .unwrap_or(false);
    write_msg(&mut send, &PairConfirm { accepted: mine }).await?;
    send.finish()?;
    let theirs: PairConfirm = timeout(CONFIRM_TIMEOUT + NET_TIMEOUT, read_msg(&mut recv))
        .await
        .context("timed out waiting for the other device")?
        .context("the other device went away")?;
    // make sure our answer was delivered before anyone closes the connection
    let _ = timeout(NET_TIMEOUT, send.stopped()).await;
    ensure!(mine, "pairing declined on this device");
    ensure!(theirs.accepted, "pairing declined on the other device");
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ticket() -> Ticket {
        Ticket {
            id: iroh::SecretKey::generate().public(),
            secret: [7; 32],
            name: "Vara's MacBook Pro & co/ü".into(),
            addrs: vec![
                "192.168.1.5:4433".parse().unwrap(),
                "[fe80::1]:4433".parse().unwrap(),
            ],
        }
    }

    #[test]
    fn ticket_roundtrip() {
        let t = ticket();
        let s = t.to_string();
        assert!(s.starts_with("ferry://pair?v=1&id="));
        assert!(!s.contains(' '));
        assert_eq!(s.parse::<Ticket>().unwrap(), t);
        let no_addrs = Ticket { addrs: vec![], ..t };
        assert_eq!(no_addrs.to_string().parse::<Ticket>().unwrap(), no_addrs);
    }

    #[test]
    fn garbage_tickets_rejected() {
        let good = ticket().to_string();
        let bad = [
            "".to_string(),
            "https://example.com".into(),
            good.replace("v=1", "v=2"),
            good.replace("&s=", "&s=zz"),
            good.replace(&hex::encode([7u8; 32]), &hex::encode([7u8; 31])),
            good.replace("&id=", "&id=x"),
            good.replace("&n=", "&n=%G1"),
            good.replace("&n=", "&n=%F"),
            good.replace("&n=", "&n=%FF"), // not UTF-8
            good.replace("192.168.1.5:4433", "192.168.1.5"),
            format!("{good},{}", vec!["1.2.3.4:5"; 16].join(",")),
            good.replace("&n=", "&n"),
        ];
        for b in bad {
            assert!(b.parse::<Ticket>().is_err(), "accepted {b}");
        }
    }

    #[test]
    fn names_are_cleaned() {
        assert_eq!(clean_name("a\u{0}b\nc"), "abc");
        assert_eq!(clean_name("  "), "Unnamed device");
        assert_eq!(clean_name(&"x".repeat(500)).len(), MAX_NAME_CHARS);
    }

    // --- two in-process nodes over loopback ---

    use std::sync::{Arc, Mutex};

    use crate::node::{Node, NodeConfig};

    struct Dev {
        node: Arc<Node>,
        codes: Arc<Mutex<Vec<String>>>,
        _dir: tempfile::TempDir,
    }

    fn confirm_fn(answer: bool, codes: Arc<Mutex<Vec<String>>>) -> ConfirmFn {
        Arc::new(move |p: PairingPrompt| {
            codes.lock().unwrap().push(p.code);
            Box::pin(async move { answer })
        })
    }

    async fn dev(name: &str, answer: bool) -> Dev {
        let dir = tempfile::tempdir().unwrap();
        let codes = Arc::new(Mutex::new(Vec::new()));
        let node = Node::start(NodeConfig {
            data_dir: dir.path().join("data"),
            device_name: name.into(),
            download_dir: dir.path().join("dl"),
            accept: Arc::new(|o| vec![true; o.items.len()]),
            events: Arc::new(|_| {}),
            confirm_pairing: confirm_fn(answer, codes.clone()),
            on_screen: Arc::new(|_, _| {}),
            on_event: Arc::new(|_, _| {}),
        })
        .await
        .unwrap();
        Dev {
            node,
            codes,
            _dir: dir,
        }
    }

    /// Tickets leave out loopback; tests dial it explicitly.
    fn local_ticket(d: &Dev) -> Ticket {
        let mut t: Ticket = d.node.new_pairing_ticket().unwrap().parse().unwrap();
        t.addrs = vec![SocketAddr::from(([127, 0, 0, 1], d.node.port()))];
        t
    }

    async fn wait_peers(n: &Node, want: usize) -> usize {
        for _ in 0..50 {
            if n.peers().len() == want {
                break;
            }
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
        n.peers().len()
    }

    #[tokio::test]
    async fn pair_accept_then_ticket_is_spent() {
        let (mac, phone) = (dev("Mac", true).await, dev("Phone", true).await);
        let t = local_ticket(&mac).to_string();
        let codes = Arc::new(Mutex::new(Vec::new()));
        let peer = phone
            .node
            .pair_with_ticket(&t, confirm_fn(true, codes.clone()))
            .await
            .unwrap();
        assert_eq!(peer.id, mac.node.device_id());
        assert_eq!(peer.name, "Mac");
        assert_eq!(wait_peers(&mac.node, 1).await, 1);
        assert_eq!(mac.node.peers()[0].id, phone.node.device_id());
        assert_eq!(mac.node.peers()[0].name, "Phone");
        let (a, b) = (
            codes.lock().unwrap().clone(),
            mac.codes.lock().unwrap().clone(),
        );
        assert_eq!(a.len(), 1);
        assert_eq!(a, b, "both sides must show the same code");
        assert!(a[0].len() == 6 && a[0].bytes().all(|c| c.is_ascii_digit()));

        // single use
        let err = phone
            .node
            .pair_with_ticket(&t, confirm_fn(true, codes))
            .await
            .unwrap_err();
        assert!(format!("{err:#}").contains("rejected"), "{err:#}");
    }

    #[tokio::test]
    async fn declined_on_either_side_stores_nothing() {
        for (mac_says, phone_says) in [(false, true), (true, false)] {
            let (mac, phone) = (dev("Mac", mac_says).await, dev("Phone", true).await);
            let t = local_ticket(&mac).to_string();
            let codes = Arc::new(Mutex::new(Vec::new()));
            let err = phone
                .node
                .pair_with_ticket(&t, confirm_fn(phone_says, codes))
                .await
                .unwrap_err();
            assert!(format!("{err:#}").contains("declined"), "{err:#}");
            tokio::time::sleep(Duration::from_millis(200)).await;
            assert!(phone.node.peers().is_empty());
            assert!(mac.node.peers().is_empty());
        }
    }

    #[tokio::test]
    async fn wrong_secret_fails_and_burns_the_ticket() {
        let (mac, phone) = (dev("Mac", true).await, dev("Phone", true).await);
        let good = local_ticket(&mac);
        let bad = Ticket {
            secret: [0; 32],
            ..good.clone()
        };
        let codes = Arc::new(Mutex::new(Vec::new()));
        assert!(
            phone
                .node
                .pair_with_ticket(&bad.to_string(), confirm_fn(true, codes.clone()))
                .await
                .is_err()
        );
        assert!(
            codes.lock().unwrap().is_empty() && mac.codes.lock().unwrap().is_empty(),
            "no code shown"
        );
        // the real ticket was consumed by the failed attempt
        assert!(
            phone
                .node
                .pair_with_ticket(&good.to_string(), confirm_fn(true, codes))
                .await
                .is_err()
        );
        assert!(mac.node.peers().is_empty() && phone.node.peers().is_empty());
    }
}
