//! The running device: iroh endpoint + trust store + channel routing.
//!
//! Relays and n0 DNS lookups are disabled (no traffic leaves the LAN). Peer addresses
//! come from mDNS (feature `mdns`), from native discovery via [`Node::add_peer_addrs`],
//! or from the last addresses stored in the trust record.

use std::collections::HashMap;
use std::future::Future;
use std::net::SocketAddr;
use std::path::PathBuf;
use std::pin::Pin;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use anyhow::{Context, Result, bail, ensure};
use iroh::address_lookup::memory::MemoryLookup;
use iroh::endpoint::{Connection, Incoming, IncomingAddr, PortmapperConfig, RecvStream, SendStream, presets};
use iroh::{Endpoint, EndpointAddr, EndpointId, RelayMode, TransportAddr};
use tokio::time::timeout;
use tracing::{debug, info, warn};

use crate::channel::{CH_CONTROL, CH_FILE, read_msg, read_prefix, write_msg, write_prefix};
use crate::file::{self, AcceptFn, EventSink, Receiver, SendReport};
use crate::pairing::{self, Me, PairedPeer, TICKET_TTL, Ticket};
use crate::proto::Hello;
use crate::trust::{TrustStore, TrustedPeer};
use crate::{ALPN_PAIR, ALPN_SESSION, PROTOCOL_VERSION, discovery, identity};

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

const NET_TIMEOUT: Duration = Duration::from_secs(10);
/// Connection close codes (application level).
const ERR_NOT_PAIRED: u32 = 0x01;
const ERR_HELLO: u32 = 0x02;
/// Stream stop/reset code for a channel we don't speak.
const ERR_UNKNOWN_CHANNEL: u32 = 0x03;
const ERR_REPLACED: u32 = 0x04;
const ERR_FORGOTTEN: u32 = 0x05;
const MAX_LAST_ADDRS: usize = 8;
const MAX_TICKET_ADDRS: usize = 6;

pub struct Node {
    endpoint: Endpoint,
    lookup: MemoryLookup,
    name: String,
    presence_key: [u8; 32],
    presence_nonce: Mutex<([u8; 8], Instant)>,
    trust: Mutex<TrustStore>,
    /// The one live pairing ticket: (secret, expiry). A new ticket replaces the old one.
    ticket: Mutex<Option<([u8; 32], Instant)>>,
    confirm: ConfirmFn,
    receiver: Arc<Receiver>,
    events: EventSink,
    /// The live incoming session per peer. A reconnecting peer (e.g. after a crash) replaces
    /// its old one at once instead of waiting for the idle timeout, which frees its partial files.
    sessions: Mutex<HashMap<EndpointId, Connection>>,
    #[cfg(feature = "mdns")]
    mdns: Mutex<Option<discovery::Mdns>>,
}

impl Node {
    /// Load identity + trust store, bind the endpoint, start the accept loop
    /// (and mDNS advertise/browse when the `mdns` feature is on).
    pub async fn start(cfg: NodeConfig) -> Result<Arc<Node>> {
        let ident = identity::load_or_create(&cfg.data_dir)?;
        let trust = TrustStore::load(&cfg.data_dir)?;
        let lookup = MemoryLookup::new();
        for p in trust.list() {
            if let Ok(id) = identity::parse_device_id(&p.id) {
                lookup.add_endpoint_info(addr_of(id, &p.last_addrs));
            }
        }
        // Minimal = crypto provider only: no n0 DNS/pkarr lookup; relays and UPnP off.
        // Default IP transports bind 0.0.0.0:0 and [::]:0 (v6 may fail harmlessly).
        let endpoint = Endpoint::builder(presets::Minimal)
            .secret_key(ident.secret_key)
            .alpns(vec![ALPN_SESSION.to_vec(), ALPN_PAIR.to_vec()])
            .relay_mode(RelayMode::Disabled)
            .portmapper_config(PortmapperConfig::Disabled)
            .address_lookup(lookup.clone())
            .bind()
            .await
            .context("bind endpoint")?;
        let node = Arc::new(Node {
            endpoint,
            lookup,
            name: pairing::clean_name(&cfg.device_name),
            presence_key: ident.presence_key,
            presence_nonce: Mutex::new((rand::random(), Instant::now())),
            trust: Mutex::new(trust),
            ticket: Mutex::new(None),
            confirm: cfg.confirm_pairing,
            receiver: Receiver::new(cfg.download_dir, cfg.accept, cfg.events.clone()),
            events: cfg.events,
            sessions: Mutex::default(),
            #[cfg(feature = "mdns")]
            mdns: Mutex::new(None),
        });

        let n = node.clone();
        tokio::spawn(async move {
            // ends when the endpoint is closed (shutdown)
            while let Some(inc) = n.endpoint.accept().await {
                let n = n.clone();
                tokio::spawn(async move {
                    if let Err(e) = n.handle_incoming(inc).await {
                        warn!("incoming connection: {e:#}");
                    }
                });
            }
        });

        #[cfg(feature = "mdns")]
        match discovery::Mdns::start(node.clone()) {
            Ok(m) => *node.mdns.lock().unwrap() = Some(m),
            Err(e) => warn!("mDNS unavailable: {e:#}"),
        }
        info!(id = %node.device_id(), port = node.port(), "node started");
        Ok(node)
    }

    /// This device's ID (iroh public key, z-base-32 string form).
    pub fn device_id(&self) -> String {
        self.endpoint.id().to_z32()
    }

    pub fn device_name(&self) -> String {
        self.name.clone()
    }

    /// A one-use `ferry://pair?...` ticket valid for 10 minutes, for display as a QR code.
    pub fn new_pairing_ticket(&self) -> Result<String> {
        let secret: [u8; 32] = rand::random();
        *self.ticket.lock().unwrap() = Some((secret, Instant::now() + TICKET_TTL));
        let mut addrs: Vec<SocketAddr> = self
            .endpoint
            .addr()
            .ip_addrs()
            .copied()
            .filter(|a| !a.ip().is_loopback() && !is_link_local_v6(a))
            .collect();
        addrs.sort_by_key(|a| a.is_ipv6()); // v4 first: shorter and what phones usually reach
        addrs.truncate(MAX_TICKET_ADDRS);
        Ok(Ticket { id: self.endpoint.id(), secret, name: self.name.clone(), addrs }.to_string())
    }

    /// Scanner side: pair with the device that showed `ticket`.
    pub async fn pair_with_ticket(&self, ticket: &str, confirm: ConfirmFn) -> Result<TrustedPeer> {
        let t: Ticket = ticket.parse()?;
        ensure!(t.id != self.endpoint.id(), "that is this device's own ticket");
        let conn = timeout(NET_TIMEOUT, self.endpoint.connect(addr_of(t.id, &t.addrs), ALPN_PAIR))
            .await
            .context("connecting to the other device timed out")?
            .context("connect to the other device")?;
        let peer = pairing::scan(&conn, &t, &self.me(), &confirm).await?;
        self.store_peer(t.id, peer, t.addrs)
    }

    pub fn peers(&self) -> Vec<TrustedPeer> {
        self.trust.lock().unwrap().list().to_vec()
    }

    pub fn forget_peer(&self, peer_id: &str) -> Result<()> {
        let id = identity::parse_device_id(peer_id)?;
        ensure!(self.trust.lock().unwrap().remove(&id.to_z32())?, "not a paired device: {peer_id}");
        self.lookup.remove_endpoint_info(id);
        if let Some(c) = self.sessions.lock().unwrap().remove(&id) {
            c.close(ERR_FORGOTTEN.into(), b"forgotten");
        }
        Ok(())
    }

    /// Addresses found by native discovery (Bonjour / NsdManager) for a paired peer.
    pub fn add_peer_addrs(&self, peer_id: &str, addrs: Vec<SocketAddr>) -> Result<()> {
        let id = self.trusted(peer_id)?;
        self.lookup.add_endpoint_info(addr_of(id, &addrs));
        self.remember_addrs(id, &addrs);
        Ok(())
    }

    /// Our current presence tag for native mDNS/BLE adverts: (txt key, value) pairs.
    /// Rotates every [`discovery::ROTATE_EVERY`]; re-query and re-advertise on that cadence.
    pub fn presence_txt(&self) -> Vec<(String, String)> {
        let mut p = self.presence_nonce.lock().unwrap();
        if p.1.elapsed() >= discovery::ROTATE_EVERY {
            *p = (rand::random(), Instant::now());
        }
        discovery::presence_txt(&self.presence_key, &p.0)
    }

    #[cfg(feature = "mdns")]
    pub(crate) fn rotate_presence(&self) {
        *self.presence_nonce.lock().unwrap() = (rand::random(), Instant::now());
    }

    /// The UDP port our endpoint is bound to (for native mDNS registration).
    pub fn port(&self) -> u16 {
        self.endpoint.bound_sockets().iter().find(|a| a.is_ipv4()).map_or(0, |a| a.port())
    }

    pub async fn send_files(&self, peer_id: &str, paths: &[PathBuf]) -> Result<SendReport> {
        let id = self.trusted(peer_id)?;
        let conn = timeout(NET_TIMEOUT, self.endpoint.connect(EndpointAddr::new(id), ALPN_SESSION))
            .await
            .context("connecting timed out (is the other device on this network?)")?
            .context("connect")?;
        let res = async {
            let _control = hello_initiate(&conn, self.hello())
                .await
                .with_context(|| match conn.close_reason() {
                    Some(r) => format!("session refused: {r}"),
                    None => "session handshake".into(),
                })?;
            file::send_files(&conn, paths, self.events.clone()).await
        }
        .await;
        // send_files returns only after every ItemResult arrived, so nothing is cut off
        conn.close(0u32.into(), b"done");
        res
    }

    pub async fn shutdown(&self) {
        #[cfg(feature = "mdns")]
        if let Some(m) = self.mdns.lock().unwrap().take() {
            m.shutdown();
        }
        self.endpoint.close().await;
    }

    fn me(&self) -> Me<'_> {
        Me { name: &self.name, presence_key: &self.presence_key }
    }

    fn hello(&self) -> Hello {
        Hello {
            protocol_version: PROTOCOL_VERSION,
            device_name: self.name.clone(),
            channels: vec![CH_CONTROL.into(), CH_FILE.into()],
        }
    }

    /// Parses `peer_id` and checks it is paired.
    fn trusted(&self, peer_id: &str) -> Result<EndpointId> {
        let id = identity::parse_device_id(peer_id)?;
        ensure!(self.trust.lock().unwrap().get(&id.to_z32()).is_some(), "not a paired device: {peer_id}");
        Ok(id)
    }

    fn store_peer(&self, id: EndpointId, p: PairedPeer, addrs: Vec<SocketAddr>) -> Result<TrustedPeer> {
        let peer = TrustedPeer {
            id: id.to_z32(),
            name: p.name,
            presence_key: hex::encode(p.presence_key),
            paired_at_unix: std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map_or(0, |d| d.as_secs() as i64),
            last_addrs: addrs,
        };
        self.trust.lock().unwrap().upsert(peer.clone())?;
        self.lookup.add_endpoint_info(addr_of(id, &peer.last_addrs));
        info!(peer = %peer.id, name = %peer.name, "paired");
        Ok(peer)
    }

    /// Newest addresses first; persisted only when the list changes.
    fn remember_addrs(&self, id: EndpointId, new: &[SocketAddr]) {
        let mut trust = self.trust.lock().unwrap();
        let Some(mut p) = trust.get(&id.to_z32()).cloned() else { return };
        let mut merged = new.to_vec();
        merged.extend(p.last_addrs.iter().filter(|a| !new.contains(a)));
        merged.truncate(MAX_LAST_ADDRS);
        if merged != p.last_addrs {
            p.last_addrs = merged;
            if let Err(e) = trust.upsert(p) {
                warn!("save peer addresses: {e:#}");
            }
        }
    }

    async fn handle_incoming(self: Arc<Self>, inc: Incoming) -> Result<()> {
        let from = match inc.remote_addr() {
            IncomingAddr::Ip(a) => Some(a),
            _ => None,
        };
        let conn = inc.await.context("handshake")?;
        if conn.alpn() == ALPN_PAIR {
            self.handle_pair(conn, from).await
        } else if conn.alpn() == ALPN_SESSION {
            self.handle_session(conn, from).await
        } else {
            bail!("unexpected ALPN") // TLS only negotiates the ALPNs we configured
        }
    }

    async fn handle_pair(&self, conn: Connection, from: Option<SocketAddr>) -> Result<()> {
        if self.ticket.lock().unwrap().is_none() {
            conn.close(pairing::ERR_PAIR_REJECTED.into(), b"not pairing");
            bail!("pairing attempt while no ticket is shown");
        }
        let take = || {
            let t = self.ticket.lock().unwrap().take();
            t.filter(|(_, exp)| Instant::now() < *exp).map(|(s, _)| s)
        };
        let peer = pairing::show(&conn, take, &self.me(), &self.confirm).await?;
        self.store_peer(conn.remote_id(), peer, from.into_iter().collect())?;
        Ok(())
    }

    /// Session access control: the remote key is authenticated by TLS; it must be in the
    /// trust store or the connection is closed before any stream is read.
    async fn handle_session(&self, conn: Connection, from: Option<SocketAddr>) -> Result<()> {
        let id = conn.remote_id();
        if self.trust.lock().unwrap().get(&id.to_z32()).is_none() {
            conn.close(ERR_NOT_PAIRED.into(), b"not paired");
            debug!(remote = %id.fmt_short(), "refused session from unpaired device");
            return Ok(());
        }
        if let Some(a) = from {
            self.remember_addrs(id, &[a]);
        }
        if let Some(old) = self.sessions.lock().unwrap().insert(id, conn.clone()) {
            old.close(ERR_REPLACED.into(), b"replaced by a new session");
        }
        let res = self.serve_session(&conn).await;
        let mut sessions = self.sessions.lock().unwrap();
        if sessions.get(&id).is_some_and(|c| c.stable_id() == conn.stable_id()) {
            sessions.remove(&id);
        }
        res
    }

    async fn serve_session(&self, conn: &Connection) -> Result<()> {
        let id = conn.remote_id();
        let _control = match hello_respond(conn, self.hello()).await {
            Ok(c) => c,
            Err(e) => {
                conn.close(ERR_HELLO.into(), b"bad hello");
                return Err(e.context("session hello"));
            }
        };
        loop {
            tokio::select! {
                bi = conn.accept_bi() => {
                    let Ok((send, recv)) = bi else { break };
                    tokio::spawn(route_bi(self.receiver.clone(), send, recv));
                }
                uni = conn.accept_uni() => {
                    let Ok(recv) = uni else { break };
                    tokio::spawn(route_uni(self.receiver.clone(), recv));
                }
            }
        }
        debug!(remote = %id.fmt_short(), reason = ?conn.close_reason(), "session ended");
        Ok(())
    }
}

/// Unknown channels are refused per stream, never per connection, so newer peers keep working.
async fn route_bi(rx: Arc<Receiver>, mut send: SendStream, mut recv: RecvStream) {
    match timeout(NET_TIMEOUT, read_prefix(&mut recv)).await {
        Ok(Ok(CH_FILE)) => {
            if let Err(e) = rx.handle_control(send, recv).await {
                warn!("file control stream: {e:#}");
            }
        }
        other => {
            debug!("refusing bi stream: {other:?}");
            let _ = recv.stop(ERR_UNKNOWN_CHANNEL.into());
            let _ = send.reset(ERR_UNKNOWN_CHANNEL.into());
        }
    }
}

async fn route_uni(rx: Arc<Receiver>, mut recv: RecvStream) {
    match timeout(NET_TIMEOUT, read_prefix(&mut recv)).await {
        Ok(Ok(CH_FILE)) => {
            if let Err(e) = rx.handle_data(recv).await {
                warn!("file data stream: {e:#}");
            }
        }
        other => {
            debug!("refusing uni stream: {other:?}");
            let _ = recv.stop(ERR_UNKNOWN_CHANNEL.into());
        }
    }
}

/// Initiator opens the long-lived control stream. Keep the returned streams alive for
/// the life of the session.
async fn hello_initiate(conn: &Connection, ours: Hello) -> Result<(SendStream, RecvStream)> {
    let (mut send, mut recv) = timeout(NET_TIMEOUT, conn.open_bi()).await??;
    write_prefix(&mut send, CH_CONTROL).await?;
    write_msg(&mut send, &ours).await?;
    check_hello(timeout(NET_TIMEOUT, read_msg(&mut recv)).await??)?;
    Ok((send, recv))
}

async fn hello_respond(conn: &Connection, ours: Hello) -> Result<(SendStream, RecvStream)> {
    let (mut send, mut recv) = timeout(NET_TIMEOUT, conn.accept_bi()).await??;
    ensure!(timeout(NET_TIMEOUT, read_prefix(&mut recv)).await?? == CH_CONTROL, "first stream is not control");
    check_hello(timeout(NET_TIMEOUT, read_msg(&mut recv)).await??)?;
    write_msg(&mut send, &ours).await?;
    Ok((send, recv))
}

fn check_hello(h: Hello) -> Result<()> {
    ensure!(h.protocol_version == PROTOCOL_VERSION, "peer speaks protocol {}", h.protocol_version);
    Ok(())
}

fn addr_of(id: EndpointId, addrs: &[SocketAddr]) -> EndpointAddr {
    EndpointAddr::from_parts(id, addrs.iter().copied().map(TransportAddr::Ip))
}

fn is_link_local_v6(a: &SocketAddr) -> bool {
    matches!(a.ip(), std::net::IpAddr::V6(v6) if v6.segments()[0] & 0xffc0 == 0xfe80)
}
