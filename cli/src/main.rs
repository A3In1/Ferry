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
    /// Create a random file of <gib> GiB (for throughput tests)
    BenchFile { path: PathBuf, gib: f64 },
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
        Cmd::Listen { dir, yes } => listen(&data_dir, &name, dir, yes, false).await?,
        Cmd::PairShow { dir, yes } => listen(&data_dir, &name, dir, yes, true).await?,
        Cmd::Pair { ticket, yes } => {
            let node = start(&data_dir, &name, PathBuf::from("received"), yes).await?;
            let res = node.pair_with_ticket(&ticket, confirm_fn(yes)).await;
            node.shutdown().await;
            let p = res?;
            println!("paired with {} ({})", p.name, p.id);
        }
        Cmd::Send { peer, files } => send(&data_dir, &name, &peer, &files).await?,
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
    })
    .await
}

async fn listen(data_dir: &Path, name: &str, dir: PathBuf, yes: bool, show_ticket: bool) -> Result<()> {
    std::fs::create_dir_all(&dir).with_context(|| format!("create {}", dir.display()))?;
    let node = start(data_dir, name, dir.clone(), yes).await?;
    println!("{}  {}  port {}", node.device_id(), node.device_name(), node.port());
    if show_ticket {
        let ticket = node.new_pairing_ticket()?;
        let qr = qrcode::QrCode::new(ticket.as_bytes())?
            .render::<qrcode::render::unicode::Dense1x2>()
            .dark_color(qrcode::render::unicode::Dense1x2::Light)
            .light_color(qrcode::render::unicode::Dense1x2::Dark)
            .build();
        println!("\n{qr}\n\nPairing ticket (one use, valid 10 min):\n{ticket}\n");
    }
    println!("receiving into {} — Ctrl-C to stop", dir.display());
    tokio::signal::ctrl_c().await?;
    node.shutdown().await;
    Ok(())
}

async fn send(data_dir: &Path, name: &str, peer: &str, files: &[PathBuf]) -> Result<()> {
    let node = start(data_dir, name, PathBuf::from("received"), false).await?;
    let res = async {
        let peers = node.peers();
        let target = match identity::parse_device_id(peer) {
            Ok(id) => peers.iter().find(|p| p.id == id.to_z32()),
            Err(_) => {
                let named: Vec<_> = peers.iter().filter(|p| p.name.eq_ignore_ascii_case(peer)).collect();
                if named.len() > 1 {
                    bail!("several paired devices are called {peer}; use the id");
                }
                named.first().copied()
            }
        };
        let id = target.with_context(|| format!("not a paired device: {peer} (see `ferry peers`)"))?.id.clone();

        // Give mDNS a moment: up to 5 s if we know no address, 1.5 s if we have old ones.
        let initial = node.peers().into_iter().find(|p| p.id == id).map(|p| p.last_addrs).unwrap_or_default();
        let t0 = Instant::now();
        while t0.elapsed() < Duration::from_secs(5) {
            let now = node.peers().into_iter().find(|p| p.id == id).map(|p| p.last_addrs).unwrap_or_default();
            if now != initial || (!initial.is_empty() && t0.elapsed() > Duration::from_millis(1500)) {
                break;
            }
            tokio::time::sleep(Duration::from_millis(100)).await;
        }

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
            bail!("{} of {} files were declined", files.len() - r.files_sent as usize, files.len());
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
                println!("pairing with {}: code {} (auto-confirmed)", p.peer_name, p.code);
                return true;
            }
            tokio::task::spawn_blocking(move || {
                print!("Pairing with {}: code {} — does it match on the other device? [y/N] ", p.peer_name, p.code);
                let _ = std::io::stdout().flush();
                let mut line = String::new();
                std::io::stdin().read_line(&mut line).is_ok() && line.trim().eq_ignore_ascii_case("y")
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
        FileEvent::Offered { names, total_bytes, .. } => {
            println!("incoming: {} file(s), {:.1} MiB", names.len(), total_bytes as f64 / MIB);
        }
        FileEvent::Progress { transfer_id, index, done, total } => {
            let Some(t0) = started.lock().unwrap().get(&(transfer_id, index)).copied() else {
                // first event only starts the clock; a rate over ~0 s is noise
                started.lock().unwrap().insert((transfer_id, index), Instant::now());
                return;
            };
            let rate = done as f64 / MIB / t0.elapsed().as_secs_f64().max(1e-3);
            print!("\r  #{index}: {:.0}/{:.0} MiB  {rate:.1} MiB/s   ", done as f64 / MIB, total as f64 / MIB);
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
    println!("wrote {} ({:.2} GiB)", path.display(), total as f64 / (1024.0 * MIB));
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
