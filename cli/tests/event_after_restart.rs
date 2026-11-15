//! A long-running node (the phone app) keeps a cached session. When the other side is
//! SIGKILLed and restarted, QUIC hasn't noticed the old session is dead; the next event
//! must still arrive (ack timeout → redial), not vanish into the dead connection.

mod common;

use std::io::{BufRead, BufReader};
use std::net::SocketAddr;
use std::path::Path;
use std::process::{Child, Command, Stdio};
use std::sync::{Arc, mpsc};
use std::time::Duration;

use ferry_core::node::{Node, NodeConfig};

const BIN: &str = env!("CARGO_BIN_EXE_ferry");

/// Kills the child even when an assertion fails.
struct Kill(Child);
impl Drop for Kill {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

/// Starts `ferry listen` (or pair-show) and streams its stdout lines into a channel.
fn mac(data: &Path, pair: bool) -> (Kill, mpsc::Receiver<String>) {
    let mut child = Command::new(BIN)
        .arg("--data-dir")
        .arg(data)
        .args([
            "--name",
            "MacA",
            if pair { "pair-show" } else { "listen" },
            "--yes",
        ])
        .arg("--dir")
        .arg(data.join("recv"))
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .unwrap();
    let out = child.stdout.take().unwrap();
    let (tx, rx) = mpsc::channel();
    std::thread::spawn(move || {
        for l in BufReader::new(out).lines().map_while(Result::ok) {
            let _ = tx.send(l);
        }
    });
    (Kill(child), rx)
}

fn wait_for(rx: &mpsc::Receiver<String>, pred: impl Fn(&str) -> bool) -> String {
    loop {
        let l = rx
            .recv_timeout(Duration::from_secs(15))
            .expect("expected output from ferry");
        if pred(&l) {
            return l;
        }
    }
}

/// First line of listen/pair-show is "<id>  <name>  port <n>".
fn port(first: &str) -> u16 {
    first.rsplit(' ').next().unwrap().parse().unwrap()
}

#[tokio::test(flavor = "multi_thread")]
async fn event_reaches_restarted_peer() {
    let root = tempfile::tempdir().unwrap();
    let mac_dir = root.path().join("mac");
    let phone = Node::start(NodeConfig {
        device_name: "PhoneB".into(),
        ..common::config(root.path(), "phone")
    })
    .await
    .unwrap();

    let (first, rx) = mac(&mac_dir, true);
    let first_line = wait_for(&rx, |l| l.contains(" port "));
    let ticket = wait_for(&rx, |l| l.starts_with("ferry://pair?"));
    let ticket = format!(
        "{}a=127.0.0.1:{}",
        &ticket[..ticket.find("a=").unwrap()],
        port(&first_line)
    );
    let peer = phone
        .pair_with_ticket(&ticket, Arc::new(|_| Box::pin(async { true })))
        .await
        .unwrap();

    phone
        .send_event(&peer.id, &common::clip("one"))
        .await
        .unwrap();
    wait_for(&rx, |l| l.contains("event from") && l.contains("one"));

    drop(first); // SIGKILL: no QUIC close reaches the phone; its cached session looks alive
    let (_second, rx) = mac(&mac_dir, false);
    let p = port(&wait_for(&rx, |l| l.contains(" port ")));
    phone
        .add_peer_addrs(&peer.id, vec![SocketAddr::from(([127, 0, 0, 1], p))])
        .unwrap();

    phone
        .send_event(&peer.id, &common::clip("two"))
        .await
        .unwrap();
    wait_for(&rx, |l| l.contains("event from") && l.contains("two"));
    phone.shutdown().await;
}
