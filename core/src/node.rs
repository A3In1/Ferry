//! The running device: iroh endpoint + trust store + channel routing.
//!
//! Relays and n0 DNS lookups are disabled (no traffic leaves the LAN). Peer addresses
//! come from mDNS (feature `mdns`), from native discovery via [`Node::add_peer_addrs`],
//! or from the last addresses stored in the trust record. USB bridge addresses live in a
//! separate lookup ([`crate::usb`]) so they come and go without touching the others.

use std::collections::HashMap;
use std::future::Future;
use std::net::SocketAddr;
use std::path::PathBuf;
use std::pin::Pin;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use anyhow::{Context, Result, bail, ensure};
use iroh::address_lookup::memory::MemoryLookup;
use iroh::endpoint::{Connection, Incoming, IncomingAddr, PortmapperConfig, presets};
pub use iroh::endpoint::{RecvStream, SendStream};
use iroh::{Endpoint, EndpointAddr, EndpointId, RelayMode, TransportAddr};
use tokio::time::timeout;
use tracing::{debug, info, warn};

use crate::channel::{
    CH_CONTROL, CH_EVENTS, CH_FILE, CH_SCREEN, read_msg, read_prefix, write_msg, write_prefix,
};
use crate::file::{self, AcceptFn, EventSink, Receiver, SendReport};
use crate::pairing::{self, Me, PairedPeer, TICKET_TTL, Ticket};
use crate::proto::{Event, Hello};
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
    /// Events from paired peers: (peer id, event).
    pub on_event: EventFn,
    /// A paired peer opened a screen stream (phone side): (peer id, stream).
    pub on_screen: ScreenFn,
}

pub type EventFn = Arc<dyn Fn(String, Event) + Send + Sync>;
pub type ScreenFn = Arc<dyn Fn(String, Screen) + Send + Sync>;

/// A CH_SCREEN bi stream carrying FSP1 bytes (after the channel prefix).
/// While any is alive the idle reaper leaves sessions open (cursor sharing can be silent).
pub struct Screen {
    pub send: SendStream,
    pub recv: RecvStream,
    pub live: ScreenLive,
}

pub struct ScreenLive(Arc<AtomicUsize>);

impl ScreenLive {
    fn new(count: &Arc<AtomicUsize>) -> Self {
        count.fetch_add(1, Ordering::Relaxed);
        Self(count.clone())
    }
}

impl Drop for ScreenLive {
    fn drop(&mut self) {
        self.0.fetch_sub(1, Ordering::Relaxed);
    }
}

const NET_TIMEOUT: Duration = Duration::from_secs(10);
const EVENT_ACK_TIMEOUT: Duration = Duration::from_secs(3);
/// Connection close codes (application level).
const ERR_NOT_PAIRED: u32 = 0x01;
const ERR_HELLO: u32 = 0x02;
/// Stream stop/reset code for a channel we don't speak.
const ERR_UNKNOWN_CHANNEL: u32 = 0x03;
const ERR_REPLACED: u32 = 0x04;
const ERR_FORGOTTEN: u32 = 0x05;
const ERR_IDLE: u32 = 0x06;
const MAX_LAST_ADDRS: usize = 8;
/// Two sessions with a peer starting this close together count as a simultaneous dial.
const DIAL_RACE: Duration = Duration::from_secs(3);
const MAX_TICKET_ADDRS: usize = 6;

/// A live session and how it started (see [`Node::adopt`]).
struct Session {
    conn: Connection,
    dialed: bool, // by us
    since: Instant,
}

pub struct Node {
    pub(crate) endpoint: Endpoint,
    lookup: MemoryLookup,
    /// Only live USB shim addresses; iroh merges it with `lookup`.
    pub(crate) usb_lookup: MemoryLookup,
    pub(crate) usb: Mutex<crate::usb::UsbState>,
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
    sessions: Mutex<HashMap<EndpointId, Session>>,
    /// Per-peer dial locks, so concurrent sends to one peer share a single new session while
    /// a slow dial to one peer never holds up the others. Entries go when their last user does.
    dials: DialLocks,
    on_event: EventFn,
    on_screen: ScreenFn,
    /// Open screen streams (see [`Screen`]).
    screens: Arc<AtomicUsize>,
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
        let usb_lookup = MemoryLookup::with_provenance("usb");
        for p in trust.list() {
            if let Ok(id) = identity::parse_device_id(&p.id) {
                lookup.add_endpoint_info(addr_of(id, &p.last_addrs));
            }
        }
        // Minimal = crypto provider only: no n0 DNS/pkarr lookup; relays and UPnP off.
        // Default IP transports bind 0.0.0.0 and [::] (v6 may fail harmlessly).
        let builder = |port: u16| -> Result<iroh::endpoint::Builder> {
            Ok(Endpoint::builder(presets::Minimal)
                .secret_key(ident.secret_key.clone())
                .alpns(vec![ALPN_SESSION.to_vec(), ALPN_PAIR.to_vec()])
                .relay_mode(RelayMode::Disabled)
                .portmapper_config(PortmapperConfig::Disabled)
                .address_lookup(lookup.clone())
                .address_lookup(usb_lookup.clone())
                .bind_addr(SocketAddr::from(([0, 0, 0, 0], port)))?)
        };
        // Keep the same UDP port across restarts so addresses peers remember stay valid
        // (networks that block mDNS have nothing else); fall back to any port if it's taken.
        let port_file = cfg.data_dir.join("port");
        let saved = std::fs::read_to_string(&port_file)
            .ok()
            .and_then(|s| s.trim().parse::<u16>().ok())
            .filter(|&p| p != 0);
        let endpoint = match saved {
            Some(p) => match builder(p)?.bind().await {
                Ok(e) => e,
                Err(e) => {
                    warn!("port {p} unavailable ({e}), using a new one");
                    builder(0)?.bind().await.context("bind endpoint")?
                }
            },
            None => builder(0)?.bind().await.context("bind endpoint")?,
        };
        let node = Arc::new(Node {
            endpoint,
            lookup,
            usb_lookup,
            usb: Mutex::default(),
            name: pairing::clean_name(&cfg.device_name),
            presence_key: ident.presence_key,
            presence_nonce: Mutex::new((rand::random(), Instant::now())),
            trust: Mutex::new(trust),
            ticket: Mutex::new(None),
            confirm: cfg.confirm_pairing,
            receiver: Receiver::new(cfg.download_dir, cfg.accept, cfg.events.clone()),
            events: cfg.events,
            sessions: Mutex::default(),
            dials: Mutex::default(),
            on_event: cfg.on_event,
            on_screen: cfg.on_screen,
            screens: Arc::default(),
            #[cfg(feature = "mdns")]
            mdns: Mutex::new(None),
        });

        if saved != Some(node.port())
            && let Err(e) = std::fs::write(&port_file, node.port().to_string())
        {
            warn!("save port: {e:#}");
        }
        tokio::spawn(reap_idle_sessions(Arc::downgrade(&node)));

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
        let addrs = self.lan_addrs();
        Ok(Ticket {
            id: self.endpoint.id(),
            secret,
            name: self.name.clone(),
            addrs,
        }
        .to_string())
    }

    /// Scanner side: pair with the device that showed `ticket`.
    pub async fn pair_with_ticket(&self, ticket: &str, confirm: ConfirmFn) -> Result<TrustedPeer> {
        let t: Ticket = ticket.parse()?;
        ensure!(
            t.id != self.endpoint.id(),
            "that is this device's own ticket"
        );
        // the USB bridge (if any) learned t.id from its hello: pairing needs no shared Wi-Fi
        let mut addrs = t.addrs.clone();
        addrs.extend(self.usb_addr(t.id));
        let conn = timeout(
            NET_TIMEOUT,
            self.endpoint.connect(addr_of(t.id, &addrs), ALPN_PAIR),
        )
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
        ensure!(
            self.trust.lock().unwrap().remove(&id.to_z32())?,
            "not a paired device: {peer_id}"
        );
        self.lookup.remove_endpoint_info(id);
        if let Some(s) = self.sessions.lock().unwrap().remove(&id) {
            s.conn.close(ERR_FORGOTTEN.into(), b"forgotten");
        }
        Ok(())
    }

    /// Addresses found by native discovery (Bonjour / NsdManager) for a paired peer.
    pub fn add_peer_addrs(&self, peer_id: &str, addrs: Vec<SocketAddr>) -> Result<()> {
        let id = self.trusted(peer_id)?;
        self.remember_addrs(id, &addrs);
        Ok(())
    }

    /// Our Bluetooth presence UUID (advertised by the Mac app); rotates like [`Node::presence_txt`].
    pub fn presence_ble_uuid(&self) -> String {
        discovery::ble_uuid(&self.presence_key, &self.nonce())
    }

    /// Which paired peer advertised this Bluetooth service UUID (phone-side scan results).
    pub fn match_presence_ble(&self, uuid: &str) -> Option<String> {
        discovery::match_ble_uuid(self.trust.lock().unwrap().list(), uuid)
    }

    /// Which paired peer (if any) a natively discovered `_ferry._udp` TXT record belongs to.
    pub fn match_presence(&self, txt: &[(String, String)]) -> Option<String> {
        let get = |k: &str| {
            txt.iter()
                .find(|(key, _)| key == k)
                .map(|(_, v)| v.as_str())
        };
        discovery::match_txt(self.trust.lock().unwrap().list(), get)
    }

    /// Our current presence tag for native mDNS/BLE adverts: (txt key, value) pairs.
    /// Rotates every [`discovery::ROTATE_EVERY`]; re-query and re-advertise on that cadence.
    pub fn presence_txt(&self) -> Vec<(String, String)> {
        discovery::presence_txt(&self.presence_key, &self.nonce())
    }

    /// The current presence nonce, rotated first if due.
    fn nonce(&self) -> [u8; 8] {
        let mut p = self.presence_nonce.lock().unwrap();
        if p.1.elapsed() >= discovery::ROTATE_EVERY {
            *p = (rand::random(), Instant::now());
        }
        p.0
    }

    #[cfg(feature = "mdns")]
    pub(crate) fn rotate_presence(&self) {
        *self.presence_nonce.lock().unwrap() = (rand::random(), Instant::now());
    }

    /// Live sessions (tests, diagnostics).
    pub fn session_count(&self) -> usize {
        self.sessions.lock().unwrap().len()
    }

    /// Closes every session (tests); sends redial on demand.
    #[doc(hidden)]
    pub fn close_sessions(&self) {
        for (_, s) in self.sessions.lock().unwrap().drain() {
            s.conn.close(ERR_REPLACED.into(), b"closed");
        }
    }

    /// The UDP port our endpoint is bound to (for native mDNS registration).
    pub fn port(&self) -> u16 {
        self.endpoint
            .bound_sockets()
            .iter()
            .find(|a| a.is_ipv4())
            .map_or(0, SocketAddr::port)
    }

    pub async fn send_files(
        self: &Arc<Self>,
        peer_id: &str,
        paths: &[PathBuf],
    ) -> Result<SendReport> {
        let files: Vec<(PathBuf, Option<String>)> =
            paths.iter().map(|p| (p.clone(), None)).collect();
        self.send_named(peer_id, &files, None).await
    }

    /// [`Node::send_files`] with an optional display name per file (see [`file::send_named`]),
    /// and optionally the transfer id to use (random if `None`), for [`Node::cancel_transfer`].
    pub async fn send_named(
        self: &Arc<Self>,
        peer_id: &str,
        files: &[(PathBuf, Option<String>)],
        transfer_id: Option<[u8; 16]>,
    ) -> Result<SendReport> {
        let id = self.trusted(peer_id)?;
        let tid = transfer_id.unwrap_or_else(rand::random);
        let cancels = self.receiver.cancels();
        let conn = self.session(id).await?;
        match file::send_named(&conn, files, tid, self.events.clone(), cancels).await {
            // the cached session died under us (peer restarted): one retry on a fresh one
            Err(e)
                if conn.close_reason().is_some()
                    && e.downcast_ref::<file::Cancelled>().is_none() =>
            {
                let conn = self.session(id).await?;
                file::send_named(&conn, files, tid, self.events.clone(), cancels).await
            }
            r => r,
        }
    }

    /// Cancels a live transfer in either direction (both sides then report it cancelled and
    /// the receiver deletes its partial files). False if no transfer has this id.
    pub fn cancel_transfer(&self, transfer_id: &[u8; 16]) -> bool {
        file::cancel(self.receiver.cancels(), transfer_id)
    }

    /// Opens a fresh session to `peer_id`, replacing any current one, so the handshake tells
    /// the peer our current addresses. Call after our network changes (e.g. joined Wi-Fi):
    /// otherwise the peer can't reach us until we happen to send it something.
    pub async fn check_in(self: &Arc<Self>, peer_id: &str) -> Result<()> {
        let id = self.trusted(peer_id)?;
        if let Some(old) = self.sessions.lock().unwrap().remove(&id) {
            old.conn.close(ERR_REPLACED.into(), b"network changed");
        }
        self.session(id).await.map(drop)
    }

    /// Send one event (notification, clipboard, ...) to a paired peer.
    pub async fn send_event(self: &Arc<Self>, peer_id: &str, ev: &Event) -> Result<()> {
        let id = self.trusted(peer_id)?;
        let conn = self.session(id).await?;
        match send_event_on(&conn, ev).await {
            // no ack: the session is dead (e.g. the peer restarted) even if QUIC hasn't noticed
            Err(_) => {
                conn.close(ERR_REPLACED.into(), b"no ack");
                send_event_on(&self.session(id).await?, ev).await
            }
            r => r,
        }
    }

    /// Mac side: open a screen stream to a paired peer (see docs/screen-protocol.md).
    pub async fn open_screen(self: &Arc<Self>, peer_id: &str) -> Result<Screen> {
        let id = self.trusted(peer_id)?;
        let conn = self.session(id).await?;
        let (mut send, recv) = timeout(NET_TIMEOUT, conn.open_bi()).await??;
        write_prefix(&mut send, CH_SCREEN).await?;
        Ok(Screen {
            send,
            recv,
            live: ScreenLive::new(&self.screens),
        })
    }

    /// The live session with `id` (either direction), or a newly dialed one.
    async fn session(self: &Arc<Self>, id: EndpointId) -> Result<Connection> {
        let slot = DialSlot::new(&self.dials, id);
        let queued = Instant::now();
        let mut failed = slot.lock.lock().await;
        // re-checked under the lock: a concurrent caller may have just dialed
        if let Some(c) = self.live_session(id) {
            return Ok(c);
        }
        // The dial we queued behind failed: don't spend another timeout finding out again
        // (e.g. clipboard copies piling up for an unreachable phone).
        if let Some((at, why)) = &*failed
            && *at > queued
        {
            bail!("{why}");
        }
        let r = self.dial(id).await;
        if let Err(e) = &r {
            *failed = Some((Instant::now(), format!("{e:#}")));
        }
        r
    }

    /// Dials `id` and runs the new session (call with its dial lock held).
    async fn dial(self: &Arc<Self>, id: EndpointId) -> Result<Connection> {
        let conn = timeout(
            NET_TIMEOUT,
            // iroh skips the lookup when it already has a path; name the USB one explicitly
            self.endpoint
                .connect(addr_of(id, self.usb_addr(id).as_slice()), ALPN_SESSION),
        )
        .await
        .context("connecting timed out (is the other device on this network?)")?
        .context("connect")?;
        let (control, theirs) = match hello_initiate(&conn, self.hello()).await {
            Ok(h) => h,
            // the peer dialed us at the same moment and kept its own connection: use that one
            Err(_) if let Some(c) = self.live_session(id) => return Ok(c),
            Err(e) => {
                return Err(e.context(match conn.close_reason() {
                    Some(r) => format!("session refused: {r}"),
                    None => "session handshake".into(),
                }));
            }
        };
        self.learn_addrs(id, &theirs);
        if let Err(winner) = self.adopt(id, &conn, true) {
            return Ok(winner);
        }
        let node = self.clone();
        let c = conn.clone();
        tokio::spawn(async move {
            let _control = control; // keep the control stream open for the session's life
            node.serve_streams(&c).await;
            node.drop_session(id, &c);
        });
        Ok(conn)
    }

    fn live_session(&self, id: EndpointId) -> Option<Connection> {
        let sessions = self.sessions.lock().unwrap();
        (sessions.get(&id))
            .filter(|s| s.conn.close_reason().is_none())
            .map(|s| s.conn.clone())
    }

    /// Makes `conn` (`dialed` by us, or accepted) the session for `id`, closing any older one.
    ///
    /// When both sides dial each other at once, each would keep the other's connection and
    /// close its own, which is the one the other kept: both die. So within [`DIAL_RACE`] of
    /// the older one starting, both sides keep the connection dialed by the larger id. Err:
    /// `conn` lost that race and is closed; use the returned winner instead.
    fn adopt(&self, id: EndpointId, conn: &Connection, dialed: bool) -> Result<(), Connection> {
        let mut sessions = self.sessions.lock().unwrap();
        if let Some(old) = sessions.get(&id)
            && old.conn.stable_id() != conn.stable_id()
            && old.dialed != dialed
            && old.since.elapsed() < DIAL_RACE
            && old.conn.close_reason().is_none()
            && old.dialed == (self.endpoint.id() > id)
        {
            conn.close(ERR_REPLACED.into(), b"lost a simultaneous dial");
            return Err(old.conn.clone());
        }
        let new = Session {
            conn: conn.clone(),
            dialed,
            since: Instant::now(),
        };
        if let Some(old) = sessions.insert(id, new)
            && old.conn.stable_id() != conn.stable_id()
        {
            old.conn
                .close(ERR_REPLACED.into(), b"replaced by a new session");
        }
        Ok(())
    }

    fn drop_session(&self, id: EndpointId, conn: &Connection) {
        let mut sessions = self.sessions.lock().unwrap();
        if sessions
            .get(&id)
            .is_some_and(|s| s.conn.stable_id() == conn.stable_id())
        {
            sessions.remove(&id);
        }
    }

    pub async fn shutdown(&self) {
        self.usb_shutdown();
        #[cfg(feature = "mdns")]
        if let Some(m) = self.mdns.lock().unwrap().take() {
            m.shutdown();
        }
        self.endpoint.close().await;
    }

    fn me(&self) -> Me<'_> {
        Me {
            name: &self.name,
            presence_key: &self.presence_key,
        }
    }

    fn hello(&self) -> Hello {
        Hello {
            protocol_version: PROTOCOL_VERSION,
            device_name: self.name.clone(),
            channels: vec![
                CH_CONTROL.into(),
                CH_FILE.into(),
                CH_EVENTS.into(),
                CH_SCREEN.into(),
            ],
            addrs: self.lan_addrs().iter().map(ToString::to_string).collect(),
        }
    }

    /// Our current directly reachable addresses (as in tickets: no loopback/link-local v6).
    fn lan_addrs(&self) -> Vec<SocketAddr> {
        let mut addrs: Vec<SocketAddr> = (self.endpoint.addr().ip_addrs().copied())
            .filter(|a| !a.ip().is_loopback() && !is_link_local_v6(a.ip()))
            .collect();
        addrs.sort_by_key(SocketAddr::is_ipv6); // v4 first: shorter and what phones usually reach
        addrs.truncate(MAX_TICKET_ADDRS);
        addrs
    }

    /// Records the LAN addresses a peer announced in its Hello, for later Wi-Fi dialing.
    fn learn_addrs(&self, id: EndpointId, h: &Hello) {
        let addrs: Vec<SocketAddr> = (h.addrs.iter().take(MAX_TICKET_ADDRS))
            .filter_map(|a| a.parse().ok())
            .filter(|a: &SocketAddr| !a.ip().is_loopback() && !a.ip().is_unspecified())
            .collect();
        if !addrs.is_empty() {
            self.remember_addrs(id, &addrs);
        }
    }

    /// Parses `peer_id` and checks it is paired.
    fn trusted(&self, peer_id: &str) -> Result<EndpointId> {
        let id = identity::parse_device_id(peer_id)?;
        ensure!(
            self.trust.lock().unwrap().get(&id.to_z32()).is_some(),
            "not a paired device: {peer_id}"
        );
        Ok(id)
    }

    fn store_peer(
        &self,
        id: EndpointId,
        p: PairedPeer,
        addrs: Vec<SocketAddr>,
    ) -> Result<TrustedPeer> {
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

    /// Newest addresses first; persisted only when the list changes. The dial lookup gets the
    /// same capped list (iroh's add_endpoint_info only ever adds, so it would grow with every
    /// network the devices meet on, and each connect would probe every stale address).
    fn remember_addrs(&self, id: EndpointId, new: &[SocketAddr]) {
        let mut trust = self.trust.lock().unwrap();
        let Some(mut p) = trust.get(&id.to_z32()).cloned() else {
            return;
        };
        let mut merged = new.to_vec();
        merged.extend(p.last_addrs.iter().filter(|a| !new.contains(a)));
        merged.truncate(MAX_LAST_ADDRS);
        self.lookup.set_endpoint_info(addr_of(id, &merged));
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
        // a USB shim address is per bridge: never persist it
        let from = from.filter(|a| !self.is_usb_addr(*a));
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
        if let Some(a) = from.filter(|a| !self.is_usb_addr(*a)) {
            self.remember_addrs(id, &[a]);
        }
        let _control = match hello_respond(&conn, self.hello()).await {
            Ok((c, theirs)) => {
                self.learn_addrs(id, &theirs);
                c
            }
            Err(e) => {
                conn.close(ERR_HELLO.into(), b"bad hello");
                return Err(e.context("session hello"));
            }
        };
        if self.adopt(id, &conn, false).is_err() {
            return Ok(());
        }
        self.serve_streams(&conn).await;
        self.drop_session(id, &conn);
        Ok(())
    }

    /// Routes the peer's streams until the connection ends. Both directions run this, so
    /// either side can push files or events over one session.
    async fn serve_streams(&self, conn: &Connection) {
        let peer = conn.remote_id().to_z32();
        loop {
            tokio::select! {
                bi = conn.accept_bi() => {
                    let Ok((send, recv)) = bi else { break };
                    let screen = (self.on_screen.clone(), peer.clone(), self.screens.clone());
                    tokio::spawn(route_bi(self.receiver.clone(), screen, send, recv));
                }
                uni = conn.accept_uni() => {
                    let Ok(recv) = uni else { break };
                    tokio::spawn(route_uni(self.receiver.clone(), self.on_event.clone(), peer.clone(), recv));
                }
            }
        }
        debug!(remote = %peer, reason = ?conn.close_reason(), "session ended");
    }
}

/// Per peer: the dial lock, holding when and why the last dial failed.
type DialLock = tokio::sync::Mutex<Option<(Instant, String)>>;
type DialLocks = Mutex<HashMap<EndpointId, Arc<DialLock>>>;

/// One caller's share of a peer's dial lock; the map entry is removed when the last one drops
/// (also when the dialing future is cancelled).
struct DialSlot<'a> {
    dials: &'a DialLocks,
    id: EndpointId,
    lock: Arc<DialLock>,
}

impl<'a> DialSlot<'a> {
    fn new(dials: &'a DialLocks, id: EndpointId) -> Self {
        let lock = dials.lock().unwrap().entry(id).or_default().clone();
        Self { dials, id, lock }
    }
}

impl Drop for DialSlot<'_> {
    fn drop(&mut self) {
        let mut dials = self.dials.lock().unwrap();
        // clones are only taken under this map lock, so 2 = the map's and ours
        if Arc::strong_count(&self.lock) == 2
            && dials
                .get(&self.id)
                .is_some_and(|l| Arc::ptr_eq(l, &self.lock))
        {
            dials.remove(&self.id);
        }
    }
}

/// Closes sessions with no stream traffic for two checks in a row (~30 s): iroh pings an
/// open path every 5 s, which keeps a phone's Wi-Fi radio awake. Sends redial on demand.
async fn reap_idle_sessions(node: std::sync::Weak<Node>) {
    const EVERY: Duration = Duration::from_secs(15);
    // stable_id -> (stream frames seen at last check, consecutive idle checks)
    let mut seen: HashMap<usize, (u64, u8)> = HashMap::new();
    loop {
        tokio::time::sleep(EVERY).await;
        let Some(node) = node.upgrade() else { return };
        if node.screens.load(Ordering::Relaxed) > 0 {
            seen.clear();
            continue;
        }
        let conns: Vec<Connection> = (node.sessions.lock().unwrap().values())
            .map(|s| s.conn.clone())
            .collect();
        let mut next = HashMap::new();
        for c in conns {
            let st = c.stats();
            let frames = st.frame_tx.stream + st.frame_rx.stream;
            let idle = match seen.get(&c.stable_id()) {
                Some(&(f, n)) if f == frames => n + 1,
                _ => 0,
            };
            if idle >= 2 {
                debug!(remote = %c.remote_id().fmt_short(), "closing idle session");
                c.close(ERR_IDLE.into(), b"idle");
            } else {
                next.insert(c.stable_id(), (frames, idle));
            }
        }
        seen = next;
    }
}

async fn send_event_on(conn: &Connection, ev: &Event) -> Result<()> {
    let heard = conn.stats().udp_rx.datagrams;
    let mut s = timeout(NET_TIMEOUT, conn.open_uni()).await??;
    write_prefix(&mut s, CH_EVENTS).await?;
    write_msg(&mut s, ev).await?;
    s.finish()?;
    // Wait for the peer's ack: finish() only buffers, so a dead session would drop it silently.
    let acked = s.stopped();
    tokio::pin!(acked);
    if let Ok(r) = timeout(EVENT_ACK_TIMEOUT, &mut acked).await {
        r?;
    } else {
        // Still hearing from the peer: alive, just slow (a busy Bluetooth link queues seconds
        // behind a transfer). Keep waiting; the caller's redial would kill that transfer.
        ensure!(
            conn.stats().udp_rx.datagrams > heard,
            "event not acknowledged"
        );
        timeout(NET_TIMEOUT, acked)
            .await
            .context("event not acknowledged")??;
    }
    // shortcut: one stream per event, so two events sent back to back may arrive out of
    // order; use a single ordered stream per session if that ever matters (notif add/remove).
    Ok(())
}

/// Unknown channels are refused per stream, never per connection, so newer peers keep working.
async fn route_bi(
    rx: Arc<Receiver>,
    (on_screen, peer, screens): (ScreenFn, String, Arc<AtomicUsize>),
    mut send: SendStream,
    mut recv: RecvStream,
) {
    match timeout(NET_TIMEOUT, read_prefix(&mut recv)).await {
        Ok(Ok(CH_SCREEN)) => on_screen(
            peer,
            Screen {
                send,
                recv,
                live: ScreenLive::new(&screens),
            },
        ),
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

async fn route_uni(rx: Arc<Receiver>, on_event: EventFn, peer: String, mut recv: RecvStream) {
    match timeout(NET_TIMEOUT, read_prefix(&mut recv)).await {
        Ok(Ok(CH_FILE)) => {
            if let Err(e) = rx.handle_data(recv).await {
                warn!("file data stream: {e:#}");
            }
        }
        Ok(Ok(CH_EVENTS)) => match timeout(NET_TIMEOUT, read_msg::<_, Event>(&mut recv)).await {
            // an Event without a kind is a variant this version doesn't know: ignore it
            Ok(Ok(ev)) if ev.kind.is_some() => on_event(peer, ev),
            Ok(Ok(_)) => {}
            other => warn!("event stream: {other:?}"),
        },
        other => {
            debug!("refusing uni stream: {other:?}");
            let _ = recv.stop(ERR_UNKNOWN_CHANNEL.into());
        }
    }
}

/// Initiator opens the long-lived control stream. Keep the returned streams alive for
/// the life of the session.
async fn hello_initiate(
    conn: &Connection,
    ours: Hello,
) -> Result<((SendStream, RecvStream), Hello)> {
    let (mut send, mut recv) = timeout(NET_TIMEOUT, conn.open_bi()).await??;
    write_prefix(&mut send, CH_CONTROL).await?;
    write_msg(&mut send, &ours).await?;
    let theirs = check_hello(timeout(NET_TIMEOUT, read_msg(&mut recv)).await??)?;
    Ok(((send, recv), theirs))
}

async fn hello_respond(
    conn: &Connection,
    ours: Hello,
) -> Result<((SendStream, RecvStream), Hello)> {
    let (mut send, mut recv) = timeout(NET_TIMEOUT, conn.accept_bi()).await??;
    ensure!(
        timeout(NET_TIMEOUT, read_prefix(&mut recv)).await?? == CH_CONTROL,
        "first stream is not control"
    );
    let theirs = check_hello(timeout(NET_TIMEOUT, read_msg(&mut recv)).await??)?;
    write_msg(&mut send, &ours).await?;
    Ok(((send, recv), theirs))
}

fn check_hello(h: Hello) -> Result<Hello> {
    ensure!(
        h.protocol_version == PROTOCOL_VERSION,
        "peer speaks protocol {}",
        h.protocol_version
    );
    Ok(h)
}

pub(crate) fn addr_of(id: EndpointId, addrs: &[SocketAddr]) -> EndpointAddr {
    EndpointAddr::from_parts(id, addrs.iter().copied().map(TransportAddr::Ip))
}

/// Link-local v6 needs a scope id, which neither tickets nor iroh's lookup carry.
pub(crate) fn is_link_local_v6(ip: std::net::IpAddr) -> bool {
    matches!(ip, std::net::IpAddr::V6(v6) if v6.is_unicast_link_local())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn dial_slots_clean_up() {
        let dials = DialLocks::default();
        let id = iroh::SecretKey::from_bytes(&[7; 32]).public();
        let a = DialSlot::new(&dials, id);
        let held = a.lock.clone().lock_owned().await;
        let b = DialSlot::new(&dials, id);
        assert!(Arc::ptr_eq(&a.lock, &b.lock), "same peer shares one lock");
        assert!(b.lock.try_lock().is_err());
        drop(held);
        drop(a);
        assert_eq!(dials.lock().unwrap().len(), 1, "b still uses it");
        drop(b);
        assert!(dials.lock().unwrap().is_empty());
    }
}
