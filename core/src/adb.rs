//! Mac side of the USB link: find adb, watch `adb devices`, forward + attach phones, and
//! push pairing tickets. Path discovery, device parsing and shell quoting are adapted from
//! Pico's adb module (MIT, same author).

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::Arc;
use std::time::{Duration, Instant};

use anyhow::{Context, Result, bail, ensure};
use tokio::process::Command;
use tokio::sync::Notify;
use tokio::time::{sleep, timeout};
use tracing::{debug, info, warn};

use crate::node::Node;
use crate::usb::USB_PORT;

const ADB_TIMEOUT: Duration = Duration::from_secs(15); // first call may start the adb server
const TICK: Duration = Duration::from_secs(2);
/// When every phone is attached: adb track-devices and bridge closes wake us, so this is
/// only a safety net. Polling `adb devices` every 2 s forever would waste the Mac's battery.
const TICK_IDLE: Duration = Duration::from_mins(1);
const TICK_NO_ADB: Duration = Duration::from_secs(10);
const RETRY_MIN: Duration = Duration::from_secs(2);
const RETRY_MAX: Duration = Duration::from_secs(10);
const ACTIVITY: &str = "dev.ferry.android/.MainActivity";

/// Explicit path → `$ANDROID_HOME` → `$ANDROID_SDK_ROOT` → Homebrew command-line tools →
/// `PATH` → spots a GUI app's minimal PATH misses (Android Studio SDK, Homebrew bin).
pub fn find_adb(explicit: Option<&Path>) -> Result<PathBuf> {
    if let Some(p) = explicit {
        ensure!(p.is_file(), "adb not found at {}", p.display());
        return Ok(p.into());
    }
    let tools = |root: PathBuf| root.join("platform-tools/adb");
    let mut candidates: Vec<PathBuf> = ["ANDROID_HOME", "ANDROID_SDK_ROOT"]
        .iter()
        .filter_map(std::env::var_os)
        .map(|r| tools(r.into()))
        .collect();
    candidates.push("/opt/homebrew/share/android-commandlinetools/platform-tools/adb".into());
    if let Some(path) = std::env::var_os("PATH") {
        candidates.extend(std::env::split_paths(&path).map(|d| d.join("adb")));
    }
    if let Some(home) = std::env::var_os("HOME") {
        candidates.push(tools(PathBuf::from(home).join("Library/Android/sdk")));
    }
    candidates.extend(["/opt/homebrew/bin/adb".into(), "/usr/local/bin/adb".into()]);
    candidates
        .into_iter()
        .find(|p| p.is_file())
        .context("adb not found (install Android platform-tools or set ANDROID_HOME)")
}

/// Runs adb with a timeout; stdout on success, an error carrying adb's message otherwise.
pub async fn run(adb: &Path, args: &[&str]) -> Result<String> {
    let cmd = format!("adb {}", args.join(" "));
    let out = timeout(
        ADB_TIMEOUT,
        Command::new(adb)
            .args(args)
            .stdin(Stdio::null())
            .kill_on_drop(true)
            .output(),
    )
    .await
    .with_context(|| format!("{cmd} timed out"))?
    .with_context(|| format!("run {}", adb.display()))?;
    let stdout = String::from_utf8_lossy(&out.stdout).into_owned();
    if !out.status.success() {
        let err = String::from_utf8_lossy(&out.stderr);
        let msg = if err.trim().is_empty() {
            &stdout
        } else {
            &*err
        };
        bail!("{cmd} failed: {}", msg.trim());
    }
    Ok(stdout)
}

/// `adb devices` → (serial, state), e.g. "device", "unauthorized", "offline",
/// "no permissions (...)".
pub fn parse_devices(out: &str) -> Vec<(String, String)> {
    out.lines()
        .map(str::trim)
        .filter(|l| !l.is_empty() && !l.starts_with("List of devices") && !l.starts_with('*'))
        .filter_map(|l| {
            let (serial, state) = l.split_once(['\t', ' '])?;
            Some((serial.to_owned(), state.trim().to_owned()))
        })
        .filter(|(_, state)| !state.is_empty())
        .collect()
}

/// A phone adb reaches over Wi-Fi ("192.168.1.5:5555", "adb-…._adb-tls-connect._tcp"), not a cable:
/// the USB bridge leaves those alone.
pub fn is_wireless(serial: &str) -> bool {
    serial.contains(':') || serial.contains("._adb-tls-connect.")
}

/// [`parse_devices`] without the Wi-Fi ones.
fn usb_devices(out: &str) -> Vec<(String, String)> {
    let mut d = parse_devices(out);
    d.retain(|(s, _)| !is_wireless(s));
    d
}

/// Quotes one literal argument for the Android POSIX shell: `adb shell` joins its
/// arguments into one command line that the device shell re-parses.
pub fn quote_shell_arg(value: &str) -> Result<String> {
    ensure!(
        !value.contains('\0'),
        "shell arguments must not contain NUL"
    );
    Ok(format!("'{}'", value.replace('\'', r#"'"'"'"#)))
}

struct Phone {
    fwd: Option<u16>, // our `adb forward` local port, while attached or attaching
    attached: bool,
    next_try: Instant,
    backoff: Duration,
    failures: u32,
}

pub(crate) fn watch(node: &Arc<Node>, explicit: Option<PathBuf>) -> Result<()> {
    if let Some(p) = &explicit {
        find_adb(Some(p))?;
    }
    let mut usb = node.usb.lock().unwrap();
    ensure!(!usb.watching, "already watching adb");
    (usb.watching, usb.adb) = (true, explicit.clone());
    // both are aborted on shutdown; the tracker's adb child dies with it (kill_on_drop)
    let track = tokio::spawn(track_devices(explicit.clone(), usb.kick.clone()));
    let watch = tokio::spawn(watch_loop(node.clone(), explicit, usb.kick.clone()));
    usb.tasks
        .extend([track.abort_handle(), watch.abort_handle()]);
    Ok(())
}

async fn watch_loop(node: Arc<Node>, explicit: Option<PathBuf>, kick: Arc<Notify>) {
    let mut phones: HashMap<String, Phone> = HashMap::new();
    let mut states: HashMap<String, String> = HashMap::new();
    let mut last_err: Option<String> = None; // adb trouble is logged once per change
    loop {
        match tick(&node, explicit.as_deref(), &mut phones, &mut states).await {
            Ok(()) if last_err.take().is_some() => info!("adb available again"),
            Ok(()) => {}
            Err(e) => {
                let e = format!("{e:#}");
                if last_err.as_ref() != Some(&e) {
                    warn!("USB watch: {e} (retrying)");
                    last_err = Some(e);
                }
            }
        }
        let wait = if last_err.is_some() {
            TICK_NO_ADB
        } else if states.values().all(|st| st != "device") || phones.values().all(|p| p.attached) {
            TICK_IDLE // nothing to retry: wait for adb or a bridge to tell us something changed
        } else {
            TICK
        };
        tokio::select! {
            _ = sleep(wait) => {}
            _ = kick.notified() => {
                for p in phones.values_mut() {
                    p.next_try = Instant::now();
                }
            }
        }
    }
}

/// Runs `adb track-devices` (one long-lived process that prints on every plug/unplug or
/// state change) and wakes the watcher on each change. Restarts it if adb goes away.
async fn track_devices(explicit: Option<PathBuf>, kick: Arc<Notify>) {
    use tokio::io::AsyncReadExt;
    loop {
        if let Ok(adb) = find_adb(explicit.as_deref())
            && let Ok(mut child) = Command::new(&adb)
                .arg("track-devices")
                .stdin(Stdio::null())
                .stdout(Stdio::piped())
                .stderr(Stdio::null())
                .kill_on_drop(true)
                .spawn()
            && let Some(mut out) = child.stdout.take()
        {
            let mut buf = [0u8; 4096];
            while matches!(out.read(&mut buf).await, Ok(n) if n > 0) {
                kick.notify_one();
            }
        }
        sleep(TICK_NO_ADB).await;
    }
}

async fn tick(
    node: &Arc<Node>,
    explicit: Option<&Path>,
    phones: &mut HashMap<String, Phone>,
    states: &mut HashMap<String, String>,
) -> Result<()> {
    let adb = find_adb(explicit)?;
    let now: HashMap<String, String> = usb_devices(&run(&adb, &["devices"]).await?)
        .into_iter()
        .collect();
    for (s, state) in &now {
        if states.get(s) != Some(state) {
            match state.as_str() {
                "device" => info!(serial = %s, "adb device ready"),
                "unauthorized" => {
                    info!(serial = %s, "adb device unauthorized: accept USB debugging on the phone")
                }
                _ => info!(serial = %s, state, "adb device not usable"),
            }
        }
    }
    *states = now;
    let now = &*states;

    // unplugged, offline or unauthorized: drop the link and its forward
    let gone: Vec<(String, Phone)> = phones
        .extract_if(|s, _| now.get(s).map(String::as_str) != Some("device"))
        .collect();
    for (s, p) in gone {
        node.usb_detach(&s);
        if let Some(port) = p.fwd {
            remove_forward(&adb, &s, port).await; // adb also drops it when the device goes
        }
        if p.attached {
            info!(serial = %s, "USB phone detached");
        }
    }

    let links = node.usb_links();
    for (s, _) in now.iter().filter(|(_, st)| *st == "device") {
        let p = phones.entry(s.clone()).or_insert_with(|| Phone {
            fwd: None,
            attached: false,
            next_try: Instant::now(),
            backoff: RETRY_MIN,
            failures: 0,
        });
        if links.iter().any(|l| l.serial == *s) {
            continue;
        }
        if let Some(port) = p.fwd.take() {
            remove_forward(&adb, s, port).await;
        }
        if std::mem::take(&mut p.attached) {
            info!(serial = %s, "USB bridge closed; will reattach");
        }
        if Instant::now() < p.next_try {
            continue;
        }
        match attach(node, &adb, s).await {
            Ok((port, peer)) => {
                let name = node.peers().into_iter().find(|x| x.id == peer);
                let name = name.map_or("unpaired device".into(), |x| x.name);
                info!(serial = %s, peer, "USB phone attached: {name}");
                (p.fwd, p.attached, p.backoff, p.failures) = (Some(port), true, RETRY_MIN, 0);
            }
            Err(e) => {
                if p.failures == 0 {
                    info!(serial = %s, "USB phone not reachable yet: {e:#}");
                } else {
                    debug!(serial = %s, "USB attach retry failed: {e:#}");
                }
                p.failures += 1;
                p.next_try = Instant::now() + p.backoff;
                p.backoff = (p.backoff * 2).min(RETRY_MAX);
            }
        }
    }
    Ok(())
}

/// `adb forward tcp:0 tcp:USB_PORT` + bridge hello; removes the forward on failure.
async fn attach(node: &Arc<Node>, adb: &Path, serial: &str) -> Result<(u16, String)> {
    let remote = format!("tcp:{USB_PORT}");
    let out = run(adb, &["-s", serial, "forward", "tcp:0", &remote]).await?;
    let port: u16 = out
        .trim()
        .parse()
        .with_context(|| format!("unexpected adb forward output {:?}", out.trim()))?;
    match node.usb_attach(port, serial.into()).await {
        Ok(peer) => Ok((port, peer)),
        Err(e) => {
            remove_forward(adb, serial, port).await;
            Err(e)
        }
    }
}

async fn remove_forward(adb: &Path, serial: &str, port: u16) {
    let local = format!("tcp:{port}");
    if let Err(e) = run(adb, &["-s", serial, "forward", "--remove", &local]).await {
        debug!("{e:#}");
    }
}

pub(crate) async fn push_ticket(node: &Node, serial: Option<String>) -> Result<()> {
    let (explicit, watching, kick) = {
        let usb = node.usb.lock().unwrap();
        (usb.adb.clone(), usb.watching, usb.kick.clone())
    };
    let adb = find_adb(explicit.as_deref())?;
    let serial = match serial {
        Some(s) => s,
        None => pick_serial(node, &adb).await?,
    };
    let attached = || node.usb_links().iter().any(|l| l.serial == serial);
    if watching && !attached() {
        // launch the app first so its bridge is up before it dials us back with the ticket
        am_start(&adb, &serial, &[]).await?;
        kick.notify_one();
        for _ in 0..30 {
            if attached() {
                break;
            }
            sleep(Duration::from_millis(500)).await;
        }
        if !attached() {
            warn!(
                serial,
                "phone not attached over USB; pairing relies on the network"
            );
        }
    }
    let ticket = quote_shell_arg(&node.new_pairing_ticket()?)?;
    let view = ["-a", "android.intent.action.VIEW", "-d", &ticket];
    am_start(&adb, &serial, &view).await
}

async fn am_start(adb: &Path, serial: &str, extra: &[&str]) -> Result<()> {
    let mut args = vec!["-s", serial, "shell", "am", "start", "-n", ACTIVITY];
    args.extend_from_slice(extra);
    let out = run(adb, &args)
        .await
        .with_context(|| format!("open the Ferry app on {serial} (is it installed?)"))?;
    // older Android exits 0 even when the activity is missing
    if let Some(e) = out.lines().find(|l| l.starts_with("Error")) {
        bail!("open the Ferry app on {serial}: {e} (is it installed?)");
    }
    Ok(())
}

/// The only attached phone, else the only `device`-state one.
async fn pick_serial(node: &Node, adb: &Path) -> Result<String> {
    if let [l] = &node.usb_links()[..] {
        return Ok(l.serial.clone());
    }
    let devices = usb_devices(&run(adb, &["devices"]).await?);
    let ready: Vec<&String> = devices
        .iter()
        .filter(|d| d.1 == "device")
        .map(|d| &d.0)
        .collect();
    if let [s] = &ready[..] {
        return Ok((*s).clone());
    }
    let list: Vec<String> = devices
        .iter()
        .map(|(s, st)| format!("{s} ({st})"))
        .collect();
    if ready.is_empty() {
        bail!(
            "no phone ready over USB (plug it in and allow USB debugging){}",
            if list.is_empty() {
                String::new()
            } else {
                format!("; adb sees: {}", list.join(", "))
            }
        );
    }
    bail!(
        "several phones connected, pick one with a serial: {}",
        list.join(", ")
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_devices() {
        let out = "* daemon not running; starting now at tcp:5037\n\
                   * daemon started successfully\n\
                   List of devices attached\n\
                   RF8RC1L57RY\tdevice\n\
                   192.168.1.5:5555\toffline\n\
                   ABCDEF123456\tunauthorized\n\
                   0123456789\tno permissions (missing udev rules?)\n\
                   \n";
        assert_eq!(
            parse_devices(out),
            [
                ("RF8RC1L57RY", "device"),
                ("192.168.1.5:5555", "offline"),
                ("ABCDEF123456", "unauthorized"),
                ("0123456789", "no permissions (missing udev rules?)"),
            ]
            .map(|(a, b)| (a.to_owned(), b.to_owned()))
        );
        assert!(parse_devices("List of devices attached\n\n").is_empty());
        assert!(parse_devices("garbage-without-state\n").is_empty());
        assert_eq!(usb_devices(out).len(), 3);
        assert!(is_wireless("adb-10BD6T20PF000JM-Xy1._adb-tls-connect._tcp"));
        assert!(!is_wireless("10BD6T20PF000JM"));
    }

    #[test]
    fn quotes_for_the_device_shell() {
        let t = "ferry://pair?v=1&id=abc&n=Bob's%20Mac&a=1.2.3.4:5";
        assert_eq!(
            quote_shell_arg(t).unwrap(),
            "'ferry://pair?v=1&id=abc&n=Bob'\"'\"'s%20Mac&a=1.2.3.4:5'"
        );
        assert!(quote_shell_arg("a\0b").is_err());
    }

    #[test]
    fn explicit_adb_must_exist() {
        assert!(find_adb(Some(Path::new("/nonexistent/adb"))).is_err());
    }
}
