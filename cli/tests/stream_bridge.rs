//! Bluetooth-style bridge: two nodes joined only by an in-memory byte stream (standing in
//! for an L2CAP channel) pair and exchange events with no network addresses at all.

mod common;

use std::path::Path;
use std::sync::Arc;
use std::time::Duration;

use ferry_core::node::{Node, NodeConfig};
use ferry_core::pairing::Ticket;
use ferry_core::proto::{Event, Ring, event::Kind};
use tokio::sync::mpsc;

async fn node(root: &Path, name: &str) -> (Arc<Node>, mpsc::UnboundedReceiver<Event>) {
    let (tx, rx) = mpsc::unbounded_channel();
    let n = Node::start(NodeConfig {
        on_event: Arc::new(move |_, ev| {
            let _ = tx.send(ev);
        }),
        ..common::config(root, name)
    })
    .await
    .unwrap();
    (n, rx)
}

#[tokio::test]
async fn pair_and_events_over_byte_stream_only() {
    let root = tempfile::tempdir().unwrap();
    let ((mac, _), (phone, mut phone_rx)) = (
        node(root.path(), "mac").await,
        node(root.path(), "phone").await,
    );

    let (a, b) = tokio::io::duplex(64 * 1024);
    let (m, p) = (mac.clone(), phone.clone());
    let (ma, pa) = tokio::join!(
        m.attach_stream(a, "ble:test".into()),
        p.attach_stream(b, "ble:test".into())
    );
    assert_eq!(ma.unwrap(), phone.device_id());
    assert_eq!(pa.unwrap(), mac.device_id());

    // no addresses in the ticket: the stream is the only path
    let mut t: Ticket = mac.new_pairing_ticket().unwrap().parse().unwrap();
    t.addrs.clear();
    phone
        .pair_with_ticket(&t.to_string(), Arc::new(|_| Box::pin(async { true })))
        .await
        .unwrap();
    while mac.peers().is_empty() {
        tokio::time::sleep(Duration::from_millis(20)).await; // the Mac stores its side just after
    }

    let ring = Event {
        kind: Some(Kind::Ring(Ring { start: true })),
    };
    mac.send_event(&phone.device_id(), &ring).await.unwrap();
    let got = tokio::time::timeout(Duration::from_secs(10), phone_rx.recv())
        .await
        .unwrap();
    assert_eq!(got, Some(ring));

    // a Bluetooth bridge never replaces a live USB one
    let (c, d) = tokio::io::duplex(1024);
    let (m, p) = (mac.clone(), phone.clone());
    let usb = tokio::spawn(async move { m.attach_stream(c, "usb-serial".into()).await });
    let _ = p.attach_stream(d, "phone-side".into()).await;
    usb.await.unwrap().unwrap();
    let (e, f) = tokio::io::duplex(1024);
    let p = phone.clone();
    tokio::spawn(async move { p.attach_stream(f, "ble:x".into()).await });
    assert!(mac.attach_stream(e, "ble:again".into()).await.is_err());
    let links = mac.usb_links();
    assert!(
        links
            .iter()
            .any(|l| l.serial == "usb-serial" && l.peer_id == phone.device_id()),
        "USB link kept: {links:?}"
    );

    for n in [mac, phone] {
        n.shutdown().await;
    }
}
