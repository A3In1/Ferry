//! USB link: the same QUIC session, carried over an `adb forward` TCP tunnel.
//!
//! adb tunnels TCP only, so each side runs a small shim: QUIC datagrams from the local
//! endpoint are length-framed into the TCP stream and replayed as UDP on the far side.
//! iroh just sees one more UDP path (a 127.0.0.1 address), so encryption, pairing and
//! channels are identical to Wi-Fi.
//!
//! Phone: [`Node::usb_serve`] listens on 127.0.0.1:[`USB_PORT`]. Mac: [`Node::usb_watch`]
//! polls `adb devices`, runs `adb forward tcp:0 tcp:USB_PORT` and attaches.
//!
//! Bridge protocol (TCP, both directions, version 1):
//! - hello: `b"FRY1"` + the sender's 32-byte endpoint public key. Both sides send at once
//!   and read the peer's within 5 s. adb forward accepts the TCP connect even when nothing
//!   listens on the phone, so EOF/timeout here means "Ferry app not running".
//! - then frames: `u16 big-endian length (1..=65507)` + one UDP datagram.
//!
//! The hello id is only a routing hint (which peer this path leads to). TLS still
//! authenticates the peer and the trust store still decides access; a lying hello can
//! at worst misroute encrypted packets, i.e. deny service.
//!
//! Each bridge owns a UDP shim socket on 127.0.0.1:0. Datagrams our endpoint sends to it
//! go into the TCP stream; frames from TCP are sent from it to our endpoint. The shim
//! address is registered as an address of the remote device in the node's USB lookup
//! while the bridge lives, and removed when it dies.

use std::collections::HashMap;
use std::io::ErrorKind;
use std::net::{Ipv4Addr, SocketAddr};
use std::path::PathBuf;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

use anyhow::{Context, Result, ensure};
use iroh::EndpointId;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, BufReader};
use tokio::net::{TcpListener, TcpStream, UdpSocket};
use tokio::sync::Notify;
use tokio::task::AbortHandle;
use tokio::time::timeout;
use tracing::{debug, info, warn};

use crate::node::{Node, addr_of};

/// TCP port the phone app listens on (loopback only, reachable through adb forward).
pub const USB_PORT: u16 = 47100;

const MAGIC: &[u8; 4] = b"FRY1";
const HELLO_LEN: usize = 4 + 32;
const HELLO_TIMEOUT: Duration = Duration::from_secs(5);
/// Largest IPv4 UDP payload.
const MAX_DATAGRAM: usize = 65_507;
/// One TCP write carries up to this many bytes of queued datagrams.
const BATCH: usize = 64 << 10;
/// A local app on the phone can open bridges too; cap what it can make us allocate.
const MAX_BRIDGES: usize = 8;

/// One phone currently attached over USB.
#[derive(Debug, Clone)]
pub struct UsbLink {
    pub serial: String,  // adb serial
    pub peer_id: String, // the phone's device id (learned from the bridge hello)
}

#[derive(Default)]
#[cfg_attr(target_os = "android", allow(dead_code))] // adb fields: Mac side only
pub(crate) struct UsbState {
    bridges: HashMap<EndpointId, Bridge>,
    /// usb_serve / usb_watch loops, aborted on shutdown.
    pub(crate) tasks: Vec<AbortHandle>,
    /// usb_watch is running, with this explicit adb binary (reused by usb_push_ticket).
    pub(crate) watching: bool,
    pub(crate) adb: Option<PathBuf>,
    /// Wakes the watcher early (e.g. after we launched the app on the phone).
    pub(crate) kick: Arc<Notify>,
}

impl UsbState {
    fn is_shim(&self, a: SocketAddr) -> bool {
        self.bridges.values().any(|b| b.shim == a)
    }
}

struct Bridge {
    serial: String,
    shim: SocketAddr,
    task: AbortHandle,
    #[cfg_attr(not(test), allow(dead_code))] // read by tests; the task logs its own copy
    bytes: Arc<AtomicU64>, // both directions
}

impl Node {
    /// Phone side: accept USB bridges on 127.0.0.1:`port` (returns once listening).
    pub async fn usb_serve(self: &Arc<Self>, port: u16) -> Result<()> {
        // loopback only: adb forward delivers here; never expose the bridge to the LAN
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, port))
            .await
            .with_context(|| format!("USB listener on 127.0.0.1:{port}"))?;
        let n = self.clone();
        let task = tokio::spawn(async move {
            loop {
                let (tcp, from) = match listener.accept().await {
                    Ok(c) => c,
                    Err(e) => {
                        warn!("USB accept: {e}");
                        tokio::time::sleep(Duration::from_millis(200)).await;
                        continue;
                    }
                };
                let n = n.clone();
                tokio::spawn(async move {
                    if let Err(e) = n.start_bridge(tcp, String::new()).await {
                        debug!(%from, "USB bridge refused: {e:#}");
                    }
                });
            }
        });
        self.usb.lock().unwrap().tasks.push(task.abort_handle());
        info!(port, "USB bridge listening");
        Ok(())
    }

    /// Mac side: attach a bridge to an already-forwarded TCP port; returns the phone's id.
    pub async fn usb_attach(self: &Arc<Self>, tcp_port: u16, serial: String) -> Result<String> {
        let tcp = timeout(
            HELLO_TIMEOUT,
            TcpStream::connect((Ipv4Addr::LOCALHOST, tcp_port)),
        )
        .await
        .context("USB bridge connect timed out")?
        .with_context(|| format!("connect to 127.0.0.1:{tcp_port}"))?;
        Ok(self.start_bridge(tcp, serial).await?.to_z32())
    }

    /// Mac side: background task that attaches/detaches phones as they are plugged in.
    /// `adb` = explicit adb binary, else found on PATH / ANDROID_HOME / Homebrew.
    pub fn usb_watch(self: &Arc<Self>, adb: Option<PathBuf>) -> Result<()> {
        #[cfg(not(target_os = "android"))]
        return crate::adb::watch(self, adb);
        #[cfg(target_os = "android")]
        {
            let _ = adb;
            anyhow::bail!("USB watching runs on the Mac (adb); the phone uses usb_serve")
        }
    }

    /// Phones attached right now.
    pub fn usb_links(&self) -> Vec<UsbLink> {
        let usb = self.usb.lock().unwrap();
        usb.bridges
            .iter()
            .map(|(id, b)| UsbLink {
                serial: b.serial.clone(),
                peer_id: id.to_z32(),
            })
            .collect()
    }

    /// Mac side: show a fresh pairing ticket on the USB phone (`serial`, or the only one)
    /// by opening the app's `ferry://pair` deep link via adb. No QR scan needed.
    pub async fn usb_push_ticket(&self, serial: Option<String>) -> Result<()> {
        #[cfg(not(target_os = "android"))]
        return crate::adb::push_ticket(self, serial).await;
        #[cfg(target_os = "android")]
        {
            let _ = serial;
            anyhow::bail!("pushing a ticket over USB runs on the Mac (adb)")
        }
    }

    /// The live shim address leading to `id`, if a bridge to it is up.
    pub(crate) fn usb_addr(&self, id: EndpointId) -> Option<SocketAddr> {
        self.usb.lock().unwrap().bridges.get(&id).map(|b| b.shim)
    }

    pub(crate) fn is_usb_addr(&self, a: SocketAddr) -> bool {
        self.usb.lock().unwrap().is_shim(a)
    }

    /// Drops the bridge attached under `serial` (device unplugged or went offline).
    #[cfg_attr(target_os = "android", allow(dead_code))]
    pub(crate) fn usb_detach(&self, serial: &str) {
        let mut usb = self.usb.lock().unwrap();
        usb.bridges.retain(|id, b| {
            let keep = b.serial != serial;
            if !keep {
                b.task.abort();
                self.usb_lookup.remove_endpoint_info(*id);
            }
            keep
        });
    }

    pub(crate) fn usb_shutdown(&self) {
        let mut usb = self.usb.lock().unwrap();
        for t in usb.tasks.drain(..) {
            t.abort();
        }
        for (id, b) in usb.bridges.drain() {
            b.task.abort();
            self.usb_lookup.remove_endpoint_info(id);
        }
    }

    async fn start_bridge(
        self: &Arc<Self>,
        mut tcp: TcpStream,
        serial: String,
    ) -> Result<EndpointId> {
        tcp.set_nodelay(true)?;
        let peer = hello(&mut tcp, self.endpoint.id()).await?;
        let port = self.port();
        ensure!(port != 0, "endpoint has no IPv4 socket");
        let endpoint = SocketAddr::from((Ipv4Addr::LOCALHOST, port));
        let udp = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await?;
        let shim = udp.local_addr()?;
        let bytes = Arc::new(AtomicU64::new(0));

        let mut usb = self.usb.lock().unwrap();
        ensure!(
            usb.bridges.len() < MAX_BRIDGES || usb.bridges.contains_key(&peer),
            "too many USB bridges"
        );
        let (n, b) = (self.clone(), bytes.clone());
        // spawned under the lock, so its cleanup can't run before the insert below
        let task = tokio::spawn(async move {
            let (r, w) = tcp.split();
            let res = tokio::select! {
                r = tcp_to_udp(r, &udp, endpoint, &b) => r,
                r = udp_to_tcp(&udp, endpoint, w, &b) => r,
            };
            n.bridge_closed(peer, shim, res, b.load(Ordering::Relaxed));
        });
        let old = usb.bridges.insert(
            peer,
            Bridge {
                serial: serial.clone(),
                shim,
                task: task.abort_handle(),
                bytes,
            },
        );
        if let Some(old) = old {
            old.task.abort();
        }
        self.usb_lookup.set_endpoint_info(addr_of(peer, &[shim]));
        info!(peer = %peer.fmt_short(), serial, %shim, "USB bridge up");
        Ok(peer)
    }

    fn bridge_closed(&self, peer: EndpointId, shim: SocketAddr, res: Result<()>, bytes: u64) {
        let mut usb = self.usb.lock().unwrap();
        // a newer bridge for the same peer may have replaced this one
        if usb.bridges.get(&peer).is_some_and(|b| b.shim == shim) {
            usb.bridges.remove(&peer);
            self.usb_lookup.remove_endpoint_info(peer);
        }
        let mib = bytes as f64 / (1 << 20) as f64;
        match res {
            Ok(()) => info!(peer = %peer.fmt_short(), "USB bridge closed after {mib:.1} MiB"),
            Err(e) => {
                info!(peer = %peer.fmt_short(), "USB bridge failed after {mib:.1} MiB: {e:#}")
            }
        }
    }

    #[cfg(test)]
    fn usb_bytes(&self, id: EndpointId) -> u64 {
        let usb = self.usb.lock().unwrap();
        usb.bridges[&id].bytes.load(Ordering::Relaxed)
    }
}

/// Sends our hello and reads the peer's. The returned id is a routing hint only.
async fn hello<S: AsyncRead + AsyncWrite + Unpin>(
    s: &mut S,
    ours: EndpointId,
) -> Result<EndpointId> {
    let mut out = [0u8; HELLO_LEN];
    out[..4].copy_from_slice(MAGIC);
    out[4..].copy_from_slice(ours.as_bytes());
    let mut got = [0u8; HELLO_LEN];
    timeout(HELLO_TIMEOUT, async {
        s.write_all(&out).await?;
        s.read_exact(&mut got).await
    })
    .await
    .context("no USB bridge hello within 5 s (is the Ferry app running on the phone?)")?
    .context("no USB bridge hello (is the Ferry app running on the phone?)")?;
    parse_hello(&got, ours)
}

fn parse_hello(b: &[u8; HELLO_LEN], ours: EndpointId) -> Result<EndpointId> {
    ensure!(&b[..4] == MAGIC, "not a Ferry USB bridge");
    let id = EndpointId::from_bytes(b[4..].try_into().unwrap())
        .context("bad device id in USB bridge hello")?;
    ensure!(id != ours, "USB bridge loops back to this device");
    Ok(id)
}

/// Next frame into `buf`; `None` on clean EOF between frames.
async fn read_frame<R: AsyncRead + Unpin>(r: &mut R, buf: &mut [u8]) -> Result<Option<usize>> {
    let n = match r.read_u16().await {
        Ok(n) => n as usize,
        Err(e) if e.kind() == ErrorKind::UnexpectedEof => return Ok(None),
        Err(e) => return Err(e.into()),
    };
    ensure!((1..=MAX_DATAGRAM).contains(&n), "bad USB frame length {n}");
    r.read_exact(&mut buf[..n])
        .await
        .context("truncated USB frame")?;
    Ok(Some(n))
}

async fn tcp_to_udp<R: AsyncRead + Unpin>(
    r: R,
    udp: &UdpSocket,
    endpoint: SocketAddr,
    bytes: &AtomicU64,
) -> Result<()> {
    let mut r = BufReader::with_capacity(4 * BATCH, r);
    let mut buf = vec![0u8; MAX_DATAGRAM];
    while let Some(n) = read_frame(&mut r, &mut buf).await? {
        // UDP semantics: a failed send (e.g. ENOBUFS) drops the datagram, QUIC resends
        if let Err(e) = udp.send_to(&buf[..n], endpoint).await {
            debug!("USB shim send: {e}");
        }
        bytes.fetch_add(n as u64, Ordering::Relaxed);
    }
    Ok(())
}

async fn udp_to_tcp<W: AsyncWrite + Unpin>(
    udp: &UdpSocket,
    endpoint: SocketAddr,
    mut w: W,
    bytes: &AtomicU64,
) -> Result<()> {
    let mut buf = vec![0u8; BATCH + 2 + 65_535];
    loop {
        udp.readable().await?;
        // drain everything queued into one TCP write
        let mut len = 0;
        while len < BATCH {
            match udp.try_recv_from(&mut buf[len + 2..]) {
                // only our own endpoint may inject into the tunnel
                Ok((n, from)) if from == endpoint && (1..=MAX_DATAGRAM).contains(&n) => {
                    buf[len..len + 2].copy_from_slice(&(n as u16).to_be_bytes());
                    len += 2 + n;
                }
                Ok((_, from)) => debug!(%from, "USB shim: dropped datagram"),
                Err(e) if e.kind() == ErrorKind::WouldBlock => break,
                Err(e) => return Err(e.into()),
            }
        }
        if len > 0 {
            w.write_all(&buf[..len]).await?;
            bytes.fetch_add(len as u64, Ordering::Relaxed);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::node::{ConfirmFn, NodeConfig};
    use crate::pairing::Ticket;

    fn key() -> EndpointId {
        iroh::SecretKey::from_bytes(&rand::random()).public()
    }

    #[tokio::test]
    async fn hello_exchange_and_rejects() {
        let (a, b) = (key(), key());
        let (mut x, mut y) = tokio::io::duplex(1024);
        let (ra, rb) = tokio::join!(hello(&mut x, a), hello(&mut y, b));
        assert_eq!((ra.unwrap(), rb.unwrap()), (b, a));

        let mut bad = [0u8; HELLO_LEN];
        bad[..4].copy_from_slice(b"HTTP");
        assert!(parse_hello(&bad, a).is_err(), "wrong magic");
        let mut own = [0u8; HELLO_LEN];
        own[..4].copy_from_slice(MAGIC);
        own[4..].copy_from_slice(a.as_bytes());
        assert!(parse_hello(&own, a).is_err(), "own id");

        // EOF before a full hello = app not running behind adb forward
        let (mut x, y) = tokio::io::duplex(1024);
        drop(y);
        assert!(hello(&mut x, a).await.is_err());
    }

    #[tokio::test]
    async fn frames() {
        let mut buf = vec![0u8; MAX_DATAGRAM];
        let mut ok: &[u8] = &[0, 3, 1, 2, 3, 0, 1, 9];
        assert_eq!(read_frame(&mut ok, &mut buf).await.unwrap(), Some(3));
        assert_eq!(&buf[..3], &[1, 2, 3]);
        assert_eq!(read_frame(&mut ok, &mut buf).await.unwrap(), Some(1));
        assert_eq!(read_frame(&mut ok, &mut buf).await.unwrap(), None);

        let mut zero: &[u8] = &[0, 0];
        assert!(read_frame(&mut zero, &mut buf).await.is_err());
        let mut huge: &[u8] = &[0xff, 0xff];
        assert!(read_frame(&mut huge, &mut buf).await.is_err());
        let mut short: &[u8] = &[0, 5, 1, 2];
        assert!(read_frame(&mut short, &mut buf).await.is_err());
    }

    fn yes() -> ConfirmFn {
        Arc::new(|_| Box::pin(async { true }))
    }

    async fn node(root: &std::path::Path, name: &str) -> Arc<Node> {
        Node::start(NodeConfig {
            data_dir: root.join(name).join("data"),
            device_name: name.into(),
            download_dir: root.join(name).join("dl"),
            accept: Arc::new(|o| vec![true; o.items.len()]),
            events: Arc::new(|_| {}),
            confirm_pairing: yes(),
        })
        .await
        .unwrap()
    }

    async fn until(what: &str, f: impl Fn() -> bool) {
        for _ in 0..100 {
            if f() {
                return;
            }
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
        panic!("timed out waiting for {what}");
    }

    fn free_port() -> u16 {
        std::net::TcpListener::bind("127.0.0.1:0")
            .unwrap()
            .local_addr()
            .unwrap()
            .port()
    }

    struct Rig {
        root: tempfile::TempDir,
        mac: Arc<Node>,
        phone: Arc<Node>,
    }

    impl Rig {
        /// Fresh endpoints (same identities) bridged over loopback TCP. On one machine the
        /// nodes also reach each other directly, and a new connection takes whichever path
        /// answers first; with no earlier direct paths known, that is the bridge, and iroh's
        /// sticky path selection then keeps it over the direct paths hole punching finds.
        async fn bridged(root: tempfile::TempDir) -> Rig {
            let (mac, phone) = (
                node(root.path(), "mac").await,
                node(root.path(), "phone").await,
            );
            let port = free_port();
            phone.usb_serve(port).await.unwrap();
            let id = mac.usb_attach(port, "test".into()).await.unwrap();
            assert_eq!(id, phone.device_id());
            until("phone side of the bridge", || phone.usb_links().len() == 1).await;
            Rig { root, mac, phone }
        }

        async fn restart(self) -> Rig {
            self.mac.shutdown().await;
            self.phone.shutdown().await;
            Rig::bridged(self.root).await
        }

        /// Bytes the mac's bridge carried while `f` ran.
        async fn carried<T>(&self, f: impl Future<Output = T>) -> (T, u64) {
            let id = self.phone.endpoint.id();
            let before = self.mac.usb_bytes(id);
            let t = f.await;
            (t, self.mac.usb_bytes(id) - before)
        }
    }

    /// Paired through the bridge with an address-less ticket, then restarted.
    async fn rig() -> Rig {
        let r = Rig::bridged(tempfile::tempdir().unwrap()).await;
        // no addresses: the bridge is the only way the phone can reach the mac
        let mut t: Ticket = r.mac.new_pairing_ticket().unwrap().parse().unwrap();
        t.addrs.clear();
        r.phone
            .pair_with_ticket(&t.to_string(), yes())
            .await
            .unwrap();
        until("mac stores the pairing", || r.mac.peers().len() == 1).await;
        assert!(
            r.mac.peers()[0].last_addrs.is_empty() && r.phone.peers()[0].last_addrs.is_empty(),
            "shim addresses must not be persisted"
        );
        r.restart().await
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn pair_and_send_over_bridge() {
        let r = rig().await;
        let src = r.root.path().join("big.bin");
        let big: Vec<u8> = (0..6_000_000u32).map(|i| (i * 7 + i / 251) as u8).collect();
        std::fs::write(&src, &big).unwrap();
        let files = std::slice::from_ref(&src);

        let (sent, carried) = r
            .carried(r.phone.send_files(&r.mac.device_id(), files))
            .await;
        assert_eq!(sent.unwrap().bytes_sent, big.len() as u64);
        assert!(
            carried >= big.len() as u64,
            "bridge carried only {carried} bytes"
        );
        let dl = r.root.path().join("mac/dl/big.bin");
        assert_eq!(std::fs::read(dl).unwrap(), big);

        let r = r.restart().await;
        let (sent, carried) = r
            .carried(r.mac.send_files(&r.phone.device_id(), files))
            .await;
        assert_eq!(sent.unwrap().bytes_sent, big.len() as u64);
        assert!(
            carried >= big.len() as u64,
            "bridge carried only {carried} bytes"
        );
        let dl = r.root.path().join("phone/dl/big.bin");
        assert_eq!(std::fs::read(dl).unwrap(), big);

        // phone goes away: its bridge closes, the mac drops the link and the address
        let phone_id = r.phone.endpoint.id();
        r.phone.shutdown().await;
        until("mac drops the link", || r.mac.usb_links().is_empty()).await;
        assert!(r.mac.usb_lookup.get_endpoint_info(phone_id).is_none());
        r.mac.shutdown().await;
    }

    /// `cargo test --release -p ferry-core -- --ignored usb_bridge_throughput --nocapture`
    /// Prints the bridge rate next to a direct-UDP loopback baseline.
    #[tokio::test(flavor = "multi_thread")]
    #[ignore]
    async fn usb_bridge_throughput() {
        let rate = |r: crate::file::SendReport| {
            r.bytes_sent as f64 / (1 << 20) as f64 / r.elapsed.as_secs_f64()
        };
        let r = rig().await;
        let src = r.root.path().join("bench.bin");
        let mut data = vec![0u8; 256 << 20];
        rand::fill(&mut data[..]);
        std::fs::write(&src, &data).unwrap();
        let files = std::slice::from_ref(&src);
        let (sent, carried) = r
            .carried(r.phone.send_files(&r.mac.device_id(), files))
            .await;
        let sent = sent.unwrap();
        assert!(carried >= sent.bytes_sent);
        let usb = rate(sent);
        r.mac.shutdown().await;
        r.phone.shutdown().await;

        let d = tempfile::tempdir().unwrap();
        let (mac, phone) = (node(d.path(), "mac").await, node(d.path(), "phone").await);
        let mut t: Ticket = mac.new_pairing_ticket().unwrap().parse().unwrap();
        t.addrs = vec![SocketAddr::from((Ipv4Addr::LOCALHOST, mac.port()))];
        phone.pair_with_ticket(&t.to_string(), yes()).await.unwrap();
        until("mac stores the pairing", || mac.peers().len() == 1).await;
        let direct = rate(phone.send_files(&mac.device_id(), &[src]).await.unwrap());
        println!("256 MiB: USB bridge {usb:.1} MiB/s, direct UDP {direct:.1} MiB/s");
        for n in [mac, phone] {
            n.shutdown().await;
        }
    }
}
