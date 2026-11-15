//! Shared by the integration tests; each test binary uses its own subset.
#![allow(dead_code)]

use std::net::SocketAddr;
use std::path::Path;
use std::sync::Arc;
use std::time::Duration;

use ferry_core::node::{Node, NodeConfig};
use ferry_core::pairing::Ticket;
use ferry_core::proto::{Clipboard, Event, event::Kind};

/// A node under `root/name` that accepts everything and ignores every callback; tests override what they watch.
pub fn config(root: &Path, name: &str) -> NodeConfig {
    NodeConfig {
        data_dir: root.join(name).join("data"),
        device_name: name.into(),
        download_dir: root.join(name).join("dl"),
        accept: Arc::new(|o| vec![true; o.items.len()]),
        events: Arc::new(|_| {}),
        confirm_pairing: Arc::new(|_| Box::pin(async { true })),
        on_screen: Arc::new(|_, _| {}),
        on_event: Arc::new(|_, _| {}),
    }
}

/// Pairs over loopback and waits until the ticket-showing side has stored the peer too.
pub async fn pair(shower: &Node, scanner: &Node) {
    let mut t: Ticket = shower.new_pairing_ticket().unwrap().parse().unwrap();
    t.addrs = vec![SocketAddr::from(([127, 0, 0, 1], shower.port()))];
    scanner
        .pair_with_ticket(&t.to_string(), Arc::new(|_| Box::pin(async { true })))
        .await
        .unwrap();
    while shower.peers().is_empty() {
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
}

pub fn clip(text: &str) -> Event {
    Event {
        kind: Some(Kind::Clipboard(Clipboard { text: text.into() })),
    }
}
