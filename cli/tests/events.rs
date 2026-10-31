//! Phase 2 events flow both ways over one long-lived session: the dialed side pushes
//! events back on the connection the other side opened.

use std::net::SocketAddr;
use std::path::Path;
use std::sync::Arc;
use std::time::Duration;

use ferry_core::node::{Node, NodeConfig};
use ferry_core::pairing::Ticket;
use ferry_core::proto::{Clipboard, Event, Ring, event::Kind};
use tokio::sync::mpsc;

async fn node(root: &Path, name: &str) -> (Arc<Node>, mpsc::UnboundedReceiver<(String, Event)>) {
    let (tx, rx) = mpsc::unbounded_channel();
    let node = Node::start(NodeConfig {
        data_dir: root.join(name).join("data"),
        device_name: name.into(),
        download_dir: root.join(name).join("dl"),
        accept: Arc::new(|o| vec![true; o.items.len()]),
        events: Arc::new(|_| {}),
        confirm_pairing: Arc::new(|_| Box::pin(async { true })),
        on_event: Arc::new(move |peer, ev| {
            let _ = tx.send((peer, ev));
        }),
    })
    .await
    .unwrap();
    (node, rx)
}

async fn next(rx: &mut mpsc::UnboundedReceiver<(String, Event)>) -> (String, Event) {
    tokio::time::timeout(Duration::from_secs(10), rx.recv())
        .await
        .expect("event arrives")
        .unwrap()
}

#[tokio::test]
async fn events_both_ways_on_one_session() {
    let root = tempfile::tempdir().unwrap();
    let ((mac, mut mac_rx), (phone, mut phone_rx)) = (
        node(root.path(), "mac").await,
        node(root.path(), "phone").await,
    );

    let mut t: Ticket = mac.new_pairing_ticket().unwrap().parse().unwrap();
    t.addrs = vec![SocketAddr::from(([127, 0, 0, 1], mac.port()))];
    phone
        .pair_with_ticket(&t.to_string(), Arc::new(|_| Box::pin(async { true })))
        .await
        .unwrap();
    while mac.peers().is_empty() {
        tokio::time::sleep(Duration::from_millis(20)).await;
    }

    // phone dials; the mac never learned a dialable phone address except via this session
    let clip = Event {
        kind: Some(Kind::Clipboard(Clipboard {
            text: "hello".into(),
        })),
    };
    phone.send_event(&mac.device_id(), &clip).await.unwrap();
    let (from, ev) = next(&mut mac_rx).await;
    assert_eq!((from, ev), (phone.device_id(), clip));

    let ring = Event {
        kind: Some(Kind::Ring(Ring { start: true })),
    };
    mac.send_event(&phone.device_id(), &ring).await.unwrap();
    assert_eq!(next(&mut phone_rx).await, (mac.device_id(), ring));

    // an Event with an unknown (here: empty) kind is ignored, not fatal to the session
    phone
        .send_event(&mac.device_id(), &Event { kind: None })
        .await
        .unwrap();
    let ring2 = Event {
        kind: Some(Kind::Ring(Ring { start: false })),
    };
    phone.send_event(&mac.device_id(), &ring2).await.unwrap();
    assert_eq!(next(&mut mac_rx).await.1, ring2);

    for n in [mac, phone] {
        n.shutdown().await;
    }
}
