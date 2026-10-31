//! `ferry`: headless Ferry peer for Mac/Linux — the test bench for pairing and transfers.

use std::collections::HashMap;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use anyhow::{Context, Result, bail};
use clap::{Parser, Subcommand};
use ferry_core::file::FileEvent;
use ferry_core::node::{ConfirmFn, Node, NodeConfig, PairingPrompt};
use ferry_core::proto::Event;
use ferry_core::{identity, trust::TrustStore};

#[derive(Parser)]
#[command(name = "ferry", about = "Ferry headless peer")]
struct Cli {
    /// Identity and paired devices live here [default: ~/.ferry-cli]
    #[arg(long, global = true)]
    data_dir: Option<PathBuf>,
    /// Device name shown to peers [default: hostname]
    #[arg(long, global = true)]
    name: Option<String>,
    #[command(subcommand)]
    cmd: Cmd,
}

#[derive(Subcommand)]
enum Cmd {
    /// Print this device's id and name
    Id,
    /// Receive files from paired devices until Ctrl-C
    Listen {
        #[arg(long, default_value = "received")]
        dir: PathBuf,
        /// Accept pairing codes without asking
        #[arg(long)]
        yes: bool,
    },
    /// Show a pairing ticket (text + QR), then keep listening
    PairShow {
        #[arg(long, default_value = "received")]
        dir: PathBuf,
        #[arg(long)]
        yes: bool,
    },
    /// Pair with the device that shows <ticket>
    Pair {
        ticket: String,
        #[arg(long)]
        yes: bool,
    },
    /// List paired devices
    Peers,
    /// Unpair a device
    Forget { id: String },
    /// Send files to a paired device (id or name)
    Send {
        peer: String,
        #[arg(required = true)]
        files: Vec<PathBuf>,
    },
    /// Send one Phase 2 event: clip <text> | link <url> | ring | stop-ring | play-pause | next | prev
    Event {
        peer: String,
        kind: String,
        arg: Option<String>,
    },
    /// Create a random file of <gib> GiB (for throughput tests)
    BenchFile { path: PathBuf, gib: f64 },
    /// Mac side of the USB link: attach phones plugged in via adb, receive until Ctrl-C
    Usb {
        #[arg(long, default_value = "received")]
        dir: PathBuf,
        #[arg(long)]
        yes: bool,
        /// adb binary [default: ANDROID_HOME, Homebrew, PATH]
        #[arg(long)]
        adb: Option<PathBuf>,
    },
    /// Phone role of the USB link (testing on one machine): bridge listener + receive
    UsbServe {
        #[arg(long, default_value_t = ferry_core::usb::USB_PORT)]
        port: u16,
        #[arg(long, default_value = "received")]
        dir: PathBuf,
        #[arg(long)]
        yes: bool,
    },
    /// Attach to a `usb-serve` bridge on 127.0.0.1:<tcp_port> without adb, then receive
    UsbAttach {
        tcp_port: u16,
        #[arg(long, default_value = "received")]
        dir: PathBuf,
        #[arg(long)]
        yes: bool,
    },
    /// Open a pairing ticket on the USB phone and wait until it pairs
    UsbPair {
        /// adb serial [default: the only connected phone]
        #[arg(long)]
        serial: Option<String>,
        #[arg(long)]
        yes: bool,
        #[arg(long)]
        adb: Option<PathBuf>,
    },
}

/// What `listen` does over USB besides receiving.
enum Usb {
    Off,
    Watch(Option<PathBuf>),
    Serve(u16),
    Attach(u16),
}

const MIB: f64 = 1024.0 * 1024.0;

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "warn".into()),
        )
        .with_writer(std::io::stderr)
        .init();
    let cli = Cli::parse();
    let data_dir = match cli.data_dir {
        Some(d) => d,
        None => PathBuf::from(std::env::var("HOME").context("HOME not set")?).join(".ferry-cli"),
    };
    let name = cli.name.unwrap_or_else(hostname);

    match cli.cmd {
        Cmd::Id => {
            let id = identity::load_or_create(&data_dir)?;
            println!("{}  {name}", id.secret_key.public().to_z32());
        }
        Cmd::Peers => {
            let store = TrustStore::load(&data_dir)?;
            if store.list().is_empty() {
                println!("no paired devices");
            }
            for p in store.list() {
                println!("{}  {}  {:?}", p.id, p.name, p.last_addrs);
            }
        }
        Cmd::Forget { id } => {
            let mut store = TrustStore::load(&data_dir)?;
            let id = identity::parse_device_id(&id)?.to_z32();
            if !store.remove(&id)? {
                bail!("not a paired device: {id}");
            }
            println!("forgot {id}");
        }
        Cmd::Listen { dir, yes } => listen(&data_dir, &name, dir, yes, false, Usb::Off).await?,
        Cmd::PairShow { dir, yes } => listen(&data_dir, &name, dir, yes, true, Usb::Off).await?,
        Cmd::Usb { dir, yes, adb } => {
            listen(&data_dir, &name, dir, yes, false, Usb::Watch(adb)).await?
        }
        Cmd::UsbServe { port, dir, yes } => {
            listen(&data_dir, &name, dir, yes, false, Usb::Serve(port)).await?
        }
        Cmd::UsbAttach { tcp_port, dir, yes } => {
            listen(&data_dir, &name, dir, yes, false, Usb::Attach(tcp_port)).await?
        }
        Cmd::UsbPair { serial, yes, adb } => {
            let node = start(&data_dir, &name, PathBuf::from("received"), yes).await?;
            let res = usb_pair(&node, serial, adb).await;
            node.shutdown().await;
            res?
        }
        Cmd::Pair { ticket, yes } => {
            let node = start(&data_dir, &name, PathBuf::from("received"), yes).await?;
            let res = node.pair_with_ticket(&ticket, confirm_fn(yes)).await;
            node.shutdown().await;
            let p = res?;
            println!("paired with {} ({})", p.name, p.id);
        }
        Cmd::Send { peer, files } => send(&data_dir, &name, &peer, Ok(&files)).await?,
        Cmd::Event { peer, kind, arg } => {
            send(&data_dir, &name, &peer, Err(event(&kind, arg)?)).await?
        }
        Cmd::BenchFile { path, gib } => bench_file(&path, gib)?,
    }
    Ok(())
}

async fn start(data_dir: &Path, name: &str, download_dir: PathBuf, yes: bool) -> Result<Arc<Node>> {
    Node::start(NodeConfig {
        data_dir: data_dir.into(),
        device_name: name.into(),
        download_dir,
        accept: Arc::new(|o| vec![true; o.items.len()]), // only paired peers get this far
        events: event_printer(),
        confirm_pairing: confirm_fn(yes),
        // Phase 2 events from the phone, printed for testing
        on_screen: Arc::new(|_, _| {}),
        on_event: Arc::new(|peer, ev| println!("event from {}: {:?}", &peer[..10], ev.kind)),
    })
    .await
}

async fn listen(
    data_dir: &Path,
    name: &str,
    dir: PathBuf,
    yes: bool,
    show_ticket: bool,
    usb: Usb,
) -> Result<()> {
    std::fs::create_dir_all(&dir).with_context(|| format!("create {}", dir.display()))?;
    let node = start(data_dir, name, dir.clone(), yes).await?;
    println!(
        "{}  {}  port {}",
        node.device_id(),
        node.device_name(),
        node.port()
    );
    if show_ticket {
        let ticket = node.new_pairing_ticket()?;
        let qr = qrcode::QrCode::new(ticket.as_bytes())?
            .render::<qrcode::render::unicode::Dense1x2>()
            .dark_color(qrcode::render::unicode::Dense1x2::Light)
            .light_color(qrcode::render::unicode::Dense1x2::Dark)
            .build();
        println!("\n{qr}\n\nPairing ticket (one use, valid 10 min):\n{ticket}\n");
    }
    let usb_on = !matches!(usb, Usb::Off);
    match usb {
        Usb::Off => {}
        Usb::Watch(adb) => node.usb_watch(adb)?,
        Usb::Serve(port) => {
            node.usb_serve(port).await?;
            println!("USB bridge listening on 127.0.0.1:{port}");
        }
        Usb::Attach(port) => {
            node.usb_attach(port, format!("tcp:{port}")).await?;
        }
    }
    if usb_on {
        tokio::spawn(print_usb_links(node.clone()));
    }
    println!("receiving into {} — Ctrl-C to stop", dir.display());
    tokio::signal::ctrl_c().await?;
    node.shutdown().await;
    Ok(())
}

/// Prints phones (or Macs) as their USB bridges come and go.
async fn print_usb_links(node: Arc<Node>) {
    let mut shown: Vec<(String, String)> = Vec::new();
    loop {
        let now: Vec<(String, String)> = node
            .usb_links()
            .into_iter()
            .map(|l| (l.serial, l.peer_id))
            .collect();
        for (serial, id) in now.iter().filter(|l| !shown.contains(l)) {
            let name = node.peers().into_iter().find(|p| p.id == *id);
            let name = name.map_or("unpaired device".into(), |p| p.name);
            println!("USB attached: {name} ({id}) {serial}");
        }
        for (serial, id) in shown.iter().filter(|l| !now.contains(l)) {
            println!("USB detached: {id} {serial}");
        }
        shown = now;
        tokio::time::sleep(Duration::from_millis(500)).await;
    }
}

async fn usb_pair(node: &Arc<Node>, serial: Option<String>, adb: Option<PathBuf>) -> Result<()> {
    node.usb_watch(adb)?;
    let before = node.peers();
    node.usb_push_ticket(serial).await?;
    println!("pairing ticket opened on the phone — compare the codes on both devices");
    let t0 = Instant::now();
    while t0.elapsed() < ferry_core::pairing::TICKET_TTL {
        if let Some(p) = node.peers().into_iter().find(|p| !before.contains(p)) {
            println!("paired with {} ({})", p.name, p.id);
            return Ok(());
        }
        tokio::select! {
            _ = tokio::time::sleep(Duration::from_millis(200)) => {}
            _ = tokio::signal::ctrl_c() => bail!("cancelled"),
        }
    }
    bail!("the phone did not pair within 10 minutes")
}

fn event(kind: &str, arg: Option<String>) -> Result<Event> {
    use ferry_core::proto::{event::Kind, media_command::Cmd, *};
    let text = || {
        arg.clone()
            .with_context(|| format!("`{kind}` needs a text argument"))
    };
    let media = |cmd: Cmd| Kind::MediaCommand(MediaCommand { cmd: cmd.into() });
    let kind = match kind {
        "clip" => Kind::Clipboard(Clipboard { text: text()? }),
        "link" => Kind::Link(Link { url: text()? }),
        "ring" => Kind::Ring(Ring { start: true }),
        "stop-ring" => Kind::Ring(Ring { start: false }),
        "play-pause" => media(Cmd::PlayPause),
        "next" => media(Cmd::Next),
        "prev" => media(Cmd::Previous),
        _ => bail!("unknown event `{kind}`"),
    };
    Ok(Event { kind: Some(kind) })
}

/// Sends files (Ok) or one event (Err) to a paired device.
async fn send(
    data_dir: &Path,
    name: &str,
    peer: &str,
    what: Result<&[PathBuf], Event>,
) -> Result<()> {
    let node = start(data_dir, name, PathBuf::from("received"), false).await?;
    let res = async {
        let peers = node.peers();
        let target = match identity::parse_device_id(peer) {
            Ok(id) => peers.iter().find(|p| p.id == id.to_z32()),
            Err(_) => {
                let named: Vec<_> = peers
                    .iter()
                    .filter(|p| p.name.eq_ignore_ascii_case(peer))
                    .collect();
                if named.len() > 1 {
                    bail!("several paired devices are called {peer}; use the id");
                }
                named.first().copied()
            }
        };
        let id = target
            .with_context(|| format!("not a paired device: {peer} (see `ferry peers`)"))?
            .id
            .clone();

        // Give mDNS a moment: up to 5 s if we know no address, 1.5 s if we have old ones.
        let initial = node
            .peers()
            .into_iter()
            .find(|p| p.id == id)
            .map(|p| p.last_addrs)
            .unwrap_or_default();
        // A phone on USB attaches within a second or two; no adb is fine (Wi-Fi only).
        let _ = node.usb_watch(None);
        let t0 = Instant::now();
        while t0.elapsed() < Duration::from_secs(5) {
            if node.usb_links().iter().any(|l| l.peer_id == id) {
                break;
            }
            let now = node
                .peers()
                .into_iter()
                .find(|p| p.id == id)
                .map(|p| p.last_addrs)
                .unwrap_or_default();
            if now != initial || (!initial.is_empty() && t0.elapsed() > Duration::from_millis(1500))
            {
                break;
            }
            tokio::time::sleep(Duration::from_millis(100)).await;
        }

        let files = match what {
            Ok(files) => files,
            Err(ev) => {
                node.send_event(&id, &ev).await?;
                println!("sent {:?}", ev.kind);
                return Ok(());
            }
        };
        let r = node.send_files(&id, files).await?;
        let secs = r.elapsed.as_secs_f64();
        println!(
            "sent {} files, {:.1} MiB in {:.2} s = {:.1} MiB/s",
            r.files_sent,
            r.bytes_sent as f64 / MIB,
            secs,
            r.bytes_sent as f64 / MIB / secs.max(1e-9)
        );
        if (r.files_sent as usize) < files.len() {
            bail!(
                "{} of {} files were declined",
                files.len() - r.files_sent as usize,
                files.len()
            );
        }
        Ok(())
    }
    .await;
    node.shutdown().await;
    res
}

fn confirm_fn(yes: bool) -> ConfirmFn {
    Arc::new(move |p: PairingPrompt| {
        Box::pin(async move {
            if yes {
                println!(
                    "pairing with {}: code {} (auto-confirmed)",
                    p.peer_name, p.code
                );
                return true;
            }
            tokio::task::spawn_blocking(move || {
                print!(
                    "Pairing with {}: code {} — does it match on the other device? [y/N] ",
                    p.peer_name, p.code
                );
                let _ = std::io::stdout().flush();
                let mut line = String::new();
                std::io::stdin().read_line(&mut line).is_ok()
                    && line.trim().eq_ignore_ascii_case("y")
            })
            .await
            .unwrap_or(false)
        })
    })
}

/// Prints progress (with rate since the item's first event) and results.
fn event_printer() -> ferry_core::file::EventSink {
    let started: Mutex<HashMap<([u8; 16], u32), Instant>> = Mutex::default();
    Arc::new(move |e| match e {
        FileEvent::Offered {
            names, total_bytes, ..
        } => {
            println!(
                "incoming: {} file(s), {:.1} MiB",
                names.len(),
                total_bytes as f64 / MIB
            );
        }
        FileEvent::Progress {
            transfer_id,
            index,
            done,
            total,
        } => {
            let Some(t0) = started.lock().unwrap().get(&(transfer_id, index)).copied() else {
                // first event only starts the clock; a rate over ~0 s is noise
                started
                    .lock()
                    .unwrap()
                    .insert((transfer_id, index), Instant::now());
                return;
            };
            let rate = done as f64 / MIB / t0.elapsed().as_secs_f64().max(1e-3);
            print!(
                "\r  #{index}: {:.0}/{:.0} MiB  {rate:.1} MiB/s   ",
                done as f64 / MIB,
                total as f64 / MIB
            );
            let _ = std::io::stdout().flush();
        }
        FileEvent::Saved { index, path, .. } => println!("\r  #{index}: saved {}", path.display()),
        FileEvent::Failed { index, error, .. } => eprintln!("\r  #{index}: failed: {error}"),
    })
}

fn bench_file(path: &Path, gib: f64) -> Result<()> {
    let total = (gib * 1024.0 * MIB) as u64;
    let mut buf = vec![0u8; 8 << 20];
    rand::fill(&mut buf[..]);
    let mut f = std::io::BufWriter::new(std::fs::File::create(path)?);
    let mut left = total;
    let mut n = 0u64;
    while left > 0 {
        // stamp each chunk so no two chunks are identical
        buf[..8].copy_from_slice(&n.to_le_bytes());
        let k = left.min(buf.len() as u64) as usize;
        f.write_all(&buf[..k])?;
        left -= k as u64;
        n += 1;
    }
    f.flush()?;
    println!(
        "wrote {} ({:.2} GiB)",
        path.display(),
        total as f64 / (1024.0 * MIB)
    );
    Ok(())
}

fn hostname() -> String {
    std::process::Command::new("hostname")
        .output()
        .ok()
        .and_then(|o| String::from_utf8(o.stdout).ok())
        .map(|s| s.trim().trim_end_matches(".local").to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "ferry-cli".into())
}
