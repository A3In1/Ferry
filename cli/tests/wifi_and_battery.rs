//! Cable-free and battery behaviour: stable ports, LAN addresses learned in the session
//! handshake, and idle sessions closed so the phone's radio can sleep.

mod common;

use std::path::Path;
use std::sync::Arc;
use std::time::Duration;

use ferry_core::node::Node;

async fn node(root: &Path, name: &str) -> Arc<Node> {
    Node::start(common::config(root, name)).await.unwrap()
}

async fn paired(root: &Path) -> (Arc<Node>, Arc<Node>) {
    let (mac, phone) = (node(root, "mac").await, node(root, "phone").await);
    common::pair(&mac, &phone).await;
    (mac, phone)
}

#[tokio::test]
async fn port_survives_restart() {
    let root = tempfile::tempdir().unwrap();
    let a = node(root.path(), "a").await;
    let port = a.port();
    a.shutdown().await;
    drop(a);
    let a = node(root.path(), "a").await;
    assert_eq!(a.port(), port, "same UDP port after restart");
    a.shutdown().await;
}

#[tokio::test]
async fn handshake_teaches_lan_addresses() {
    let root = tempfile::tempdir().unwrap();
    let (mac, phone) = paired(root.path()).await;
    // the phone dialed the Mac's loopback address; its Hello still carries its LAN addresses
    phone
        .send_event(&mac.device_id(), &common::clip("x"))
        .await
        .unwrap();
    // a check-in replaces the live session with a fresh one (new handshake, new addresses)
    phone.check_in(&mac.device_id()).await.unwrap();
    assert_eq!(phone.session_count(), 1);
    phone
        .send_event(&mac.device_id(), &common::clip("x"))
        .await
        .unwrap();
    let learned = mac.peers()[0].last_addrs.clone();
    let lan: Vec<_> = learned.iter().filter(|a| !a.ip().is_loopback()).collect();
    if lan.is_empty() {
        eprintln!("no LAN interface on this machine; skipping the address check");
    } else {
        assert!(lan.iter().all(|a| a.port() == phone.port()), "{learned:?}");
    }
    for n in [mac, phone] {
        n.shutdown().await;
    }
}

#[tokio::test]
#[ignore = "takes ~45 s: cargo test --release -p ferry-cli --test wifi_and_battery -- --ignored"]
async fn idle_sessions_close_and_redial() {
    let root = tempfile::tempdir().unwrap();
    let (mac, phone) = paired(root.path()).await;
    phone
        .send_event(&mac.device_id(), &common::clip("x"))
        .await
        .unwrap();
    assert_eq!(phone.session_count(), 1);
    tokio::time::sleep(Duration::from_secs(50)).await;
    assert_eq!(phone.session_count(), 0, "idle session closed on the phone");
    assert_eq!(mac.session_count(), 0, "and on the Mac");
    phone
        .send_event(&mac.device_id(), &common::clip("x"))
        .await
        .unwrap(); // redials on demand
    for n in [mac, phone] {
        n.shutdown().await;
    }
}
