//! Phase 7: two paired devices that know no direct address of each other still connect,
//! through a relay whose TLS certificate is verified against a trusted root (relay-ca.pem).

use std::net::{Ipv4Addr, SocketAddr};
use std::path::Path;
use std::sync::Arc;
use std::time::Duration;

use ferry_core::node::{Node, NodeConfig};
use ferry_core::pairing::Ticket;
use ferry_core::proto::{Clipboard, Event, event::Kind};
use iroh_relay::server::{CertConfig, RelayConfig, Server, ServerConfig, TlsConfig};
use tokio::sync::mpsc;

async fn node(data: &Path, name: &str) -> (Arc<Node>, mpsc::UnboundedReceiver<Event>) {
    let (tx, rx) = mpsc::unbounded_channel();
    let node = Node::start(NodeConfig {
        data_dir: data.to_path_buf(),
        device_name: name.into(),
        download_dir: data.join("dl"),
        accept: Arc::new(|o| vec![true; o.items.len()]),
        events: Arc::new(|_| {}),
        confirm_pairing: Arc::new(|_| Box::pin(async { true })),
        on_screen: Arc::new(|_, _| {}),
        on_event: Arc::new(move |_, ev| {
            let _ = tx.send(ev);
        }),
    })
    .await
    .unwrap();
    (node, rx)
}

#[tokio::test(flavor = "multi_thread")]
async fn paired_devices_meet_through_the_relay() {
    let (certs, server_config) = iroh_relay::server::testing::self_signed_tls_certs_and_config();
    let mut relay = RelayConfig::new((Ipv4Addr::LOCALHOST, 0));
    relay.tls = Some(TlsConfig::new(
        (Ipv4Addr::LOCALHOST, 0),
        CertConfig::Manual { server_config },
    ));
    let mut config = ServerConfig::default();
    config.relay = Some(relay);
    let server = Server::spawn(config).await.unwrap();
    let url = format!("https://{}", server.https_addr().unwrap());

    let root = tempfile::tempdir().unwrap();
    let (mac_dir, phone_dir) = (root.path().join("mac"), root.path().join("phone"));
    for d in [&mac_dir, &phone_dir] {
        std::fs::create_dir_all(d).unwrap();
        std::fs::write(d.join("relay-ca.pem"), certs[0].as_ref()).unwrap(); // DER is accepted too
    }
    let (mac, _) = node(&mac_dir, "mac").await;
    let (phone, _) = node(&phone_dir, "phone").await;
    // the setting is saved, and only used after a restart
    mac.set_relay_url(Some(&url)).unwrap();
    phone.set_relay_url(Some(&url)).unwrap();
    assert!(mac.set_relay_url(Some("http://plain.example")).is_err());
    for n in [mac, phone] {
        n.shutdown().await;
    }
    let (mac, mut mac_rx) = node(&mac_dir, "mac").await;
    let (phone, _) = node(&phone_dir, "phone").await;
    assert!(mac.relay_url().is_some());

    // pair over loopback; the first session's Hello teaches each side the other's relay
    let mut t: Ticket = mac.new_pairing_ticket().unwrap().parse().unwrap();
    t.addrs = vec![SocketAddr::from(([127, 0, 0, 1], mac.port()))];
    phone
        .pair_with_ticket(&t.to_string(), Arc::new(|_| Box::pin(async { true })))
        .await
        .unwrap();
    phone.check_in(&mac.device_id()).await.unwrap();
    let mac_id = mac.device_id();
    let learned = phone.peers().into_iter().find(|p| p.id == mac_id).unwrap();
    assert!(learned.last_relay.is_some(), "relay learned from Hello");
    phone.shutdown().await;

    // forget every direct address: the relay is the only way left to find the Mac
    let peers = phone_dir.join("peers.json");
    let mut v: serde_json::Value = serde_json::from_slice(&std::fs::read(&peers).unwrap()).unwrap();
    for p in v.as_array_mut().unwrap() {
        p["last_addrs"] = serde_json::json!([]);
    }
    std::fs::write(&peers, serde_json::to_vec(&v).unwrap()).unwrap();
    let (phone, _) = node(&phone_dir, "phone").await;

    let clip = Event {
        kind: Some(Kind::Clipboard(Clipboard {
            text: "via relay".into(),
        })),
    };
    phone.send_event(&mac_id, &clip).await.unwrap();
    let got = tokio::time::timeout(Duration::from_secs(10), mac_rx.recv())
        .await
        .expect("event arrives")
        .unwrap();
    assert_eq!(got, clip);

    for n in [mac, phone] {
        n.shutdown().await;
    }
    server.shutdown().await.unwrap();
}
