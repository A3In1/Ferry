//! Phase 4: the Mac opens a CH_SCREEN bi stream; the phone gets it via `on_screen` and
//! bytes flow both ways (the FSP1 payload itself is the apps' business).

mod common;

use std::path::Path;
use std::sync::Arc;
use std::time::Duration;

use ferry_core::node::{Node, NodeConfig, Screen};
use tokio::sync::mpsc;
use tokio::time::timeout;

async fn node(root: &Path, name: &str) -> (Arc<Node>, mpsc::UnboundedReceiver<(String, Screen)>) {
    let (tx, rx) = mpsc::unbounded_channel();
    let node = Node::start(NodeConfig {
        on_screen: Arc::new(move |peer, s| {
            let _ = tx.send((peer, s));
        }),
        ..common::config(root, name)
    })
    .await
    .unwrap();
    (node, rx)
}

#[tokio::test]
async fn screen_stream_both_ways() {
    let root = tempfile::tempdir().unwrap();
    let ((mac, _), (phone, mut phone_rx)) = (
        node(root.path(), "mac").await,
        node(root.path(), "phone").await,
    );
    common::pair(&mac, &phone).await;

    // the phone dials once so the Mac has a session to open the stream on
    phone.check_in(&mac.device_id()).await.unwrap();
    let mut m = mac.open_screen(&phone.device_id()).await.unwrap();
    m.send.write_all(b"FSP1start").await.unwrap();

    let (from, mut p) = timeout(Duration::from_secs(10), phone_rx.recv())
        .await
        .expect("phone gets the stream")
        .unwrap();
    assert_eq!(from, mac.device_id());
    let mut buf = [0u8; 9];
    p.recv.read_exact(&mut buf).await.unwrap();
    assert_eq!(&buf, b"FSP1start");

    p.send.write_all(b"FSP1info").await.unwrap();
    p.send.finish().unwrap();
    let back = timeout(Duration::from_secs(10), m.recv.read_to_end(1024))
        .await
        .unwrap()
        .unwrap();
    assert_eq!(back, b"FSP1info");

    for n in [mac, phone] {
        n.shutdown().await;
    }
}
