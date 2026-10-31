//! Two in-process nodes on loopback: pair via ticket (auto-confirm), then send files.
//! No mDNS reliance: the ticket and trust record carry 127.0.0.1 addresses.

use std::net::SocketAddr;
use std::path::Path;
use std::sync::Arc;

use ferry_core::node::{ConfirmFn, Node, NodeConfig};
use ferry_core::pairing::Ticket;

fn yes() -> ConfirmFn {
    Arc::new(|_| Box::pin(async { true }))
}

async fn node(root: &Path, name: &str) -> Arc<Node> {
    Node::start(NodeConfig {
        data_dir: root.join(name).join("data"),
        device_name: name.into(),
        download_dir: root.join(name).join("dl"),
        accept: Arc::new(|o| vec![true; o.items.len()]),
        events: Arc::new(|_| {}),
        confirm_pairing: yes(),
        on_event: Arc::new(|_, _| {}),
    })
    .await
    .unwrap()
}

#[tokio::test]
async fn pair_then_send_both_ways() {
    let root = tempfile::tempdir().unwrap();
    let (mac, phone) = (
        node(root.path(), "mac").await,
        node(root.path(), "phone").await,
    );

    let mut t: Ticket = mac.new_pairing_ticket().unwrap().parse().unwrap();
    t.addrs = vec![SocketAddr::from(([127, 0, 0, 1], mac.port()))];
    phone.pair_with_ticket(&t.to_string(), yes()).await.unwrap();
    for _ in 0..50 {
        if !mac.peers().is_empty() {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(50)).await;
    }
    assert_eq!(mac.peers().len(), 1);

    let src = root.path().join("src");
    std::fs::create_dir_all(&src).unwrap();
    let big: Vec<u8> = (0..5_000_000u32).map(|i| (i * 7 + i / 251) as u8).collect();
    std::fs::write(src.join("big.bin"), &big).unwrap();
    std::fs::write(src.join("note.txt"), b"hello ferry").unwrap();
    let files = vec![src.join("big.bin"), src.join("note.txt")];

    // phone -> mac (mac learned no address for phone except the pairing connection's)
    let r = phone.send_files(&mac.device_id(), &files).await.unwrap();
    assert_eq!(r.files_sent, 2);
    assert_eq!(r.bytes_sent, big.len() as u64 + 11);
    let dl = root.path().join("mac/dl");
    assert_eq!(std::fs::read(dl.join("big.bin")).unwrap(), big);
    assert_eq!(std::fs::read(dl.join("note.txt")).unwrap(), b"hello ferry");

    // mac -> phone, dialled via the address remembered from the phone's connections
    phone
        .add_peer_addrs(
            &mac.device_id(),
            vec![SocketAddr::from(([127, 0, 0, 1], mac.port()))],
        )
        .unwrap();
    mac.add_peer_addrs(
        &phone.device_id(),
        vec![SocketAddr::from(([127, 0, 0, 1], phone.port()))],
    )
    .unwrap();
    let r = mac
        .send_files(&phone.device_id(), &files[1..])
        .await
        .unwrap();
    assert_eq!(r.files_sent, 1);
    assert_eq!(
        std::fs::read(root.path().join("phone/dl/note.txt")).unwrap(),
        b"hello ferry"
    );

    // an unpaired device is refused at session level
    let stranger = node(root.path(), "stranger").await;
    assert!(
        stranger.send_files(&mac.device_id(), &files).await.is_err(),
        "not paired locally"
    );

    // forgetting removes access on the forgetting side
    mac.forget_peer(&phone.device_id()).unwrap();
    let err = phone
        .send_files(&mac.device_id(), &files[1..])
        .await
        .unwrap_err();
    assert!(format!("{err:#}").contains("refused"), "{err:#}");

    for n in [mac, phone, stranger] {
        n.shutdown().await;
    }
}
