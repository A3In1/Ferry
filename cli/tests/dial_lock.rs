//! Dialing is locked per peer: a send to a paired phone that is gone (dial waits for the
//! connect timeout) must not hold up a send to another phone that is reachable.

mod common;

use std::path::Path;
use std::sync::Arc;
use std::time::{Duration, Instant};

use ferry_core::node::{Node, NodeConfig};
use ferry_core::proto::Event;
use tokio::sync::mpsc;

async fn node(root: &Path, name: &str) -> (Arc<Node>, mpsc::UnboundedReceiver<Event>) {
    let (tx, rx) = mpsc::unbounded_channel();
    let node = Node::start(NodeConfig {
        on_event: Arc::new(move |_, ev| {
            let _ = tx.send(ev);
        }),
        ..common::config(root, name)
    })
    .await
    .unwrap();
    (node, rx)
}

/// `scanner` pairs with `shower` over loopback.
#[tokio::test(flavor = "multi_thread")]
async fn unreachable_peer_does_not_block_others() {
    let root = tempfile::tempdir().unwrap();
    let (mac, _) = node(root.path(), "mac").await;
    let (gone, _) = node(root.path(), "gone").await;
    let (live, mut live_rx) = node(root.path(), "live").await;
    common::pair(&gone, &mac).await;
    common::pair(&live, &mac).await;
    let gone_id = gone.device_id();
    gone.shutdown().await;
    drop(gone);

    let dialing = {
        let mac = mac.clone();
        tokio::spawn(async move {
            let t = Instant::now();
            let r = mac.send_event(&gone_id, &common::clip("lost")).await;
            (r, t.elapsed())
        })
    };
    tokio::time::sleep(Duration::from_millis(300)).await;

    let t = Instant::now();
    mac.send_event(&live.device_id(), &common::clip("hi"))
        .await
        .unwrap();
    let took = t.elapsed();
    let ev = tokio::time::timeout(Duration::from_secs(5), live_rx.recv())
        .await
        .unwrap()
        .unwrap();
    assert_eq!(ev, common::clip("hi"));

    let (r, gone_took) = dialing.await.unwrap();
    assert!(r.is_err(), "the shut-down peer can't have answered");
    // only meaningful if the dead dial really was slow (it waits for the connect timeout)
    assert!(gone_took > Duration::from_secs(3), "{gone_took:?}");
    assert!(
        took < Duration::from_secs(3),
        "send to live peer took {took:?}"
    );

    for n in [mac, live] {
        n.shutdown().await;
    }
}

/// Concurrent sends to one peer still share a single new session.
#[tokio::test(flavor = "multi_thread")]
async fn concurrent_sends_share_one_session() {
    let root = tempfile::tempdir().unwrap();
    let (mac, _) = node(root.path(), "mac").await;
    let (phone, mut phone_rx) = node(root.path(), "phone").await;
    common::pair(&phone, &mac).await;

    let id = phone.device_id();
    let sends = (0..8).map(|i| {
        let (mac, id) = (mac.clone(), id.clone());
        tokio::spawn(async move { mac.send_event(&id, &common::clip(&i.to_string())).await })
    });
    for s in sends.collect::<Vec<_>>() {
        s.await.unwrap().unwrap();
    }
    for _ in 0..8 {
        tokio::time::timeout(Duration::from_secs(5), phone_rx.recv())
            .await
            .unwrap()
            .unwrap();
    }
    assert_eq!(mac.session_count(), 1);
    assert_eq!(phone.session_count(), 1);

    for n in [mac, phone] {
        n.shutdown().await;
    }
}

/// Both sides dial each other at the same moment: they must agree on one session instead of
/// each keeping the one the other just closed as "replaced".
#[tokio::test(flavor = "multi_thread")]
async fn simultaneous_dials_keep_one_session() {
    let root = tempfile::tempdir().unwrap();
    for round in 0..6 {
        let (mac, mut mac_rx) = node(root.path(), &format!("mac{round}")).await;
        let (phone, mut phone_rx) = node(root.path(), &format!("phone{round}")).await;
        common::pair(&phone, &mac).await;
        // the phone learns the mac's address from this first session; then drop it
        mac.send_event(&phone.device_id(), &common::clip("hello"))
            .await
            .unwrap();
        phone_rx.recv().await.unwrap();
        mac.check_in(&phone.device_id()).await.unwrap(); // fresh, so both sides know each other
        for n in [&mac, &phone] {
            n.close_sessions();
        }
        tokio::time::sleep(Duration::from_millis(200)).await;
        let (mac_id, phone_id, m, p) = (
            mac.device_id(),
            phone.device_id(),
            common::clip("m"),
            common::clip("p"),
        );
        let (a, b) = tokio::join!(mac.send_event(&phone_id, &m), phone.send_event(&mac_id, &p));
        a.unwrap();
        b.unwrap();
        tokio::time::timeout(Duration::from_secs(5), mac_rx.recv())
            .await
            .unwrap();
        tokio::time::sleep(Duration::from_millis(500)).await;
        assert_eq!(
            (mac.session_count(), phone.session_count()),
            (1, 1),
            "round {round}"
        );
        for n in [mac, phone] {
            n.shutdown().await;
        }
    }
}

/// Sends queued behind a dial that fails share its failure instead of each waiting out
/// another connect timeout (copies piling up for an unreachable phone).
#[tokio::test(flavor = "multi_thread")]
async fn queued_sends_to_unreachable_peer_fail_together() {
    let root = tempfile::tempdir().unwrap();
    let (mac, _) = node(root.path(), "mac").await;
    let (gone, _) = node(root.path(), "gone").await;
    common::pair(&gone, &mac).await;
    let gone_id = gone.device_id();
    gone.shutdown().await;
    drop(gone);

    let t = Instant::now();
    let sends: Vec<_> = (0..4)
        .map(|i| {
            let (mac, id) = (mac.clone(), gone_id.clone());
            tokio::spawn(async move { mac.send_event(&id, &common::clip(&i.to_string())).await })
        })
        .collect();
    for s in sends {
        assert!(s.await.unwrap().is_err());
    }
    let took = t.elapsed();
    assert!(
        took < Duration::from_secs(15),
        "4 queued sends took {took:?}"
    );
    mac.shutdown().await;
}
