//! A user cancel mid-transfer, from either side, stops the transfer on both devices
//! promptly: both report `Cancelled` (not a failure), the receiver deletes its partial
//! files, and the session stays usable for the next send.

mod common;

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use ferry_core::file::{Cancelled, FileEvent, PARTIAL_DIR};
use ferry_core::node::{Node, NodeConfig};
use tokio::sync::mpsc;
use tokio::time::timeout;

type Events = mpsc::UnboundedReceiver<FileEvent>;

async fn node(root: &Path, name: &str) -> (Arc<Node>, Events) {
    let (tx, rx) = mpsc::unbounded_channel();
    let node = Node::start(NodeConfig {
        events: Arc::new(move |e| {
            let _ = tx.send(e);
        }),
        ..common::config(root, name)
    })
    .await
    .unwrap();
    (node, rx)
}

async fn paired(root: &Path) -> ((Arc<Node>, Events), (Arc<Node>, Events)) {
    let (mac, phone) = (node(root, "mac").await, node(root, "phone").await);
    common::pair(&mac.0, &phone.0).await;
    (mac, phone)
}

/// Waits for the first event matching `f`.
async fn until(rx: &mut Events, f: impl Fn(&FileEvent) -> bool) -> FileEvent {
    timeout(Duration::from_secs(20), async {
        loop {
            let e = rx.recv().await.expect("event sink open");
            if f(&e) {
                return e;
            }
        }
    })
    .await
    .expect("event arrives in time")
}

fn big_files(root: &Path) -> Vec<PathBuf> {
    // two files so cancel also stops an item that hasn't started or finished
    ["one.bin", "two.bin"]
        .iter()
        .map(|n| {
            let p = root.join(n);
            std::fs::write(&p, vec![7u8; 256 << 20]).unwrap();
            p
        })
        .collect()
}

fn leftovers(dl: &Path) -> Vec<String> {
    let mut v: Vec<String> = std::fs::read_dir(dl)
        .into_iter()
        .flatten()
        .flatten()
        .map(|e| e.file_name().to_string_lossy().into_owned())
        .filter(|n| n != PARTIAL_DIR)
        .collect();
    v.extend(
        (std::fs::read_dir(dl.join(PARTIAL_DIR))
            .into_iter()
            .flatten()
            .flatten())
        .map(|e| format!("{PARTIAL_DIR}/{}", e.file_name().to_string_lossy())),
    );
    v
}

/// `from_sender`: who presses Cancel.
async fn cancel_mid_transfer(from_sender: bool) {
    let root = tempfile::tempdir().unwrap();
    let ((mac, mut mac_rx), (phone, mut phone_rx)) = paired(root.path()).await;
    let files: Vec<_> = big_files(root.path())
        .into_iter()
        .map(|p| (p, None))
        .collect();
    let tid: [u8; 16] = rand::random();

    let send = {
        let (phone, mac_id, files) = (phone.clone(), mac.device_id(), files.clone());
        tokio::spawn(async move { phone.send_named(&mac_id, &files, Some(tid)).await })
    };
    let sending = until(&mut phone_rx, |e| matches!(e, FileEvent::Sending { .. })).await;
    assert!(
        matches!(sending, FileEvent::Sending { transfer_id, ref names, .. }
        if transfer_id == tid && names == &["one.bin", "two.bin"])
    );
    // mid-transfer: the cancelling side has seen data move
    let (canceller, rx) = if from_sender {
        (&phone, &mut phone_rx)
    } else {
        (&mac, &mut mac_rx)
    };
    until(
        rx,
        |e| matches!(e, FileEvent::Progress { done, .. } if *done > 0),
    )
    .await;
    assert!(canceller.cancel_transfer(&tid));

    let err = timeout(Duration::from_secs(5), send)
        .await
        .expect("send stops promptly")
        .unwrap()
        .expect_err("cancelled send fails");
    assert_eq!(
        err.downcast_ref::<Cancelled>(),
        Some(&Cancelled {
            by_peer: !from_sender
        }),
        "{err:#}"
    );
    let ended = |e: &FileEvent| {
        matches!(
            e,
            FileEvent::Cancelled { .. } | FileEvent::Saved { .. } | FileEvent::Failed { .. }
        )
    };
    for (rx, by_peer) in [(&mut phone_rx, !from_sender), (&mut mac_rx, from_sender)] {
        let e = until(rx, ended).await;
        assert!(
            matches!(e, FileEvent::Cancelled { transfer_id, by_peer: b } if transfer_id == tid && b == by_peer),
            "{e:?}"
        );
    }
    assert!(
        !phone.cancel_transfer(&tid) && !mac.cancel_transfer(&tid),
        "still registered"
    );

    // partials go once the receiver's last handler lets go of the transfer
    let dl = root.path().join("mac").join("dl");
    let mut left = leftovers(&dl);
    for _ in 0..100 {
        if left.is_empty() {
            break;
        }
        tokio::time::sleep(Duration::from_millis(20)).await;
        left = leftovers(&dl);
    }
    assert!(left.is_empty(), "left behind: {left:?}");

    // the session survives: a small file goes through right after
    let small = root.path().join("small.txt");
    std::fs::write(&small, b"after cancel").unwrap();
    let r = phone.send_files(&mac.device_id(), &[small]).await.unwrap();
    assert_eq!(r.files_sent, 1);
    assert_eq!(
        std::fs::read(dl.join("small.txt")).unwrap(),
        b"after cancel"
    );

    for n in [mac, phone] {
        n.shutdown().await;
    }
}

#[tokio::test(flavor = "multi_thread")]
async fn sender_cancels_mid_transfer() {
    cancel_mid_transfer(true).await;
}

#[tokio::test(flavor = "multi_thread")]
async fn receiver_cancels_mid_transfer() {
    cancel_mid_transfer(false).await;
}
