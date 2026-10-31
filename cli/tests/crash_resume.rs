//! A sender killed mid-transfer (no QUIC close reaches the receiver) and restarted at once
//! must resume from the partial file, not resend everything. Uses the real binary so the
//! kill is a true crash.

use std::io::{BufRead, BufReader};
use std::path::Path;
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

const BIN: &str = env!("CARGO_BIN_EXE_ferry");
const SIZE: usize = 256 << 20;

/// Kills the child even when an assertion fails, so no listener outlives the test.
struct Kill(std::process::Child);
impl Drop for Kill {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

fn ferry(data: &Path) -> Command {
    let mut c = Command::new(BIN);
    c.arg("--data-dir").arg(data);
    c
}

#[test]
fn killed_sender_resumes() {
    let root = tempfile::tempdir().unwrap();
    let (a, b, recv) = (
        root.path().join("a"),
        root.path().join("b"),
        root.path().join("recv"),
    );

    let mut listener = Kill(
        ferry(&a)
            .args(["--name", "MacA", "pair-show", "--yes", "--dir"])
            .arg(&recv)
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .spawn()
            .unwrap(),
    );
    let mut lines = BufReader::new(listener.0.stdout.take().unwrap())
        .lines()
        .map_while(Result::ok);
    let ticket = (lines.by_ref())
        .find_map(|l| {
            l.split_whitespace()
                .find(|w| w.starts_with("ferry://pair?"))
                .map(str::to_owned)
        })
        .expect("listener printed a ticket");
    // keep reading, or the listener's next println! hits a closed pipe and panics
    std::thread::spawn(move || lines.for_each(drop));
    // loopback only: the ticket advertises LAN addresses
    let port = ticket.rsplit(':').next().unwrap().to_owned();
    let ticket = format!(
        "{}a=127.0.0.1:{port}",
        &ticket[..ticket.find("a=").unwrap()]
    );
    let ok = ferry(&b)
        .args(["--name", "PhoneB", "pair", "--yes", &ticket])
        .status()
        .unwrap();
    assert!(ok.success(), "pairing failed");

    let src = root.path().join("big.bin");
    let data: Vec<u8> = (0..SIZE as u32).map(|i| (i ^ (i >> 11)) as u8).collect();
    std::fs::write(&src, &data).unwrap();

    let mut sender = ferry(&b)
        .args(["send", "MacA"])
        .arg(&src)
        .stdout(Stdio::null())
        .spawn()
        .unwrap();
    let partial_dir = recv.join(".ferry-partial");
    let deadline = Instant::now() + Duration::from_secs(60);
    let partial_len = || {
        std::fs::read_dir(&partial_dir).map_or(0, |d| {
            d.flatten().map(|e| e.metadata().unwrap().len()).sum()
        })
    };
    while partial_len() < SIZE as u64 / 4 {
        assert!(Instant::now() < deadline, "transfer never got going");
        std::thread::sleep(Duration::from_millis(20));
    }
    sender.kill().unwrap(); // SIGKILL: the receiver's connection stays open until idle timeout
    sender.wait().unwrap();

    let out = ferry(&b).args(["send", "MacA"]).arg(&src).output().unwrap();
    let stdout = String::from_utf8_lossy(&out.stdout);
    assert!(
        out.status.success(),
        "resend failed: {stdout} {}",
        String::from_utf8_lossy(&out.stderr)
    );
    let summary = stdout
        .lines()
        .find_map(|l| l.find("sent ").map(|i| &l[i..]))
        .expect("summary line");
    let mib: f64 = summary.split_whitespace().nth(3).unwrap().parse().unwrap();
    assert!(mib < (SIZE >> 20) as f64 * 0.8, "did not resume: {summary}");

    assert!(
        std::fs::read(recv.join("big.bin")).unwrap() == data,
        "file differs"
    );
    assert_eq!(
        std::fs::read_dir(&partial_dir).unwrap().count(),
        0,
        "partial left behind"
    );
}
