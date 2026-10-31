use super::*;
use crate::ALPN_SESSION;
use crate::channel::read_prefix;
use iroh::endpoint::presets;
use iroh::{Endpoint, EndpointAddr, RelayMode};
use rand::RngExt;
use tempfile::TempDir;

struct Harness {
    conn: Connection,
    dest: TempDir,
    src: TempDir,
    events: Arc<Mutex<Vec<FileEvent>>>,
    _endpoints: (Endpoint, Endpoint),
}

async fn endpoint() -> Endpoint {
    Endpoint::builder(presets::Minimal)
        .relay_mode(RelayMode::Disabled)
        .alpns(vec![ALPN_SESSION.to_vec()])
        .bind_addr("127.0.0.1:0")
        .unwrap()
        .bind()
        .await
        .unwrap()
}

/// Two loopback endpoints with a minimal node-style router feeding a Receiver.
async fn harness(accept: AcceptFn) -> Harness {
    let (server, client) = (endpoint().await, endpoint().await);
    let dest = TempDir::new().unwrap();
    let events = Arc::new(Mutex::new(Vec::new()));
    let sink: EventSink = {
        let events = events.clone();
        Arc::new(move |e| events.lock().unwrap().push(e))
    };
    let receiver = Receiver::new(dest.path().to_owned(), accept, sink);
    let srv = server.clone();
    tokio::spawn(async move {
        while let Some(incoming) = srv.accept().await {
            let conn = incoming.await.unwrap();
            let rx = receiver.clone();
            tokio::spawn(async move {
                loop {
                    tokio::select! {
                        Ok((send, mut recv)) = conn.accept_bi() => {
                            let rx = rx.clone();
                            tokio::spawn(async move {
                                assert_eq!(read_prefix(&mut recv).await.unwrap(), CH_FILE);
                                let _ = rx.handle_control(send, recv).await;
                            });
                        }
                        Ok(mut recv) = conn.accept_uni() => {
                            let rx = rx.clone();
                            tokio::spawn(async move {
                                assert_eq!(read_prefix(&mut recv).await.unwrap(), CH_FILE);
                                let _ = rx.handle_data(recv).await;
                            });
                        }
                        else => break,
                    }
                }
            });
        }
    });
    let addr = EndpointAddr::new(server.id()).with_ip_addr(server.bound_sockets()[0]);
    let conn = client.connect(addr, ALPN_SESSION).await.unwrap();
    Harness { conn, dest, src: TempDir::new().unwrap(), events, _endpoints: (server, client) }
}

fn accept_all() -> AcceptFn {
    Arc::new(|o: &Offer| vec![true; o.items.len()])
}

fn noop() -> EventSink {
    Arc::new(|_| {})
}

fn random_bytes(len: usize) -> Vec<u8> {
    let mut v = vec![0u8; len];
    rand::rng().fill(&mut v[..]);
    v
}

impl Harness {
    fn write_src(&self, name: &str, data: &[u8]) -> PathBuf {
        let p = self.src.path().join(name);
        fs::write(&p, data).unwrap();
        p
    }

    async fn send(&self, paths: &[PathBuf]) -> Result<SendReport> {
        send_files(&self.conn, paths, noop()).await
    }

    fn dest_files(&self) -> Vec<String> {
        let mut v: Vec<String> = (fs::read_dir(self.dest.path()).unwrap())
            .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
            .filter(|n| n != PARTIAL_DIR)
            .collect();
        v.sort();
        v
    }

    fn partials(&self) -> usize {
        fs::read_dir(self.dest.path().join(PARTIAL_DIR)).map_or(0, |d| d.count())
    }
}

#[tokio::test(flavor = "multi_thread")]
async fn sends_big_and_many_small_files() {
    let h = harness(accept_all()).await;
    let big = random_bytes(64 << 20);
    let mut paths = vec![h.write_src("big.bin", &big), h.write_src("empty.txt", b"")];
    let mut small = Vec::new();
    for i in 0..500 {
        let data = random_bytes(rand::rng().random_range(1..50_000));
        paths.push(h.write_src(&format!("small-{i:03}.dat"), &data));
        small.push(data);
    }
    let total: u64 = paths.iter().map(|p| fs::metadata(p).unwrap().len()).sum();

    let report = h.send(&paths).await.unwrap();
    assert_eq!(report.files_sent, 502);
    assert_eq!(report.bytes_sent, total);
    assert_eq!(fs::read(h.dest.path().join("big.bin")).unwrap(), big);
    assert_eq!(fs::read(h.dest.path().join("empty.txt")).unwrap(), b"");
    for (i, data) in small.iter().enumerate() {
        assert_eq!(&fs::read(h.dest.path().join(format!("small-{i:03}.dat"))).unwrap(), data);
    }
    assert_eq!(h.dest_files().len(), 502);
    assert_eq!(h.partials(), 0);
    let saved = h.events.lock().unwrap().iter().filter(|e| matches!(e, FileEvent::Saved { .. })).count();
    assert_eq!(saved, 502);
}

#[tokio::test]
async fn name_conflict_keeps_both() {
    let h = harness(accept_all()).await;
    fs::write(h.dest.path().join("photo.jpg"), b"user's own file").unwrap();
    let p = h.write_src("photo.jpg", b"incoming");
    h.send(std::slice::from_ref(&p)).await.unwrap();
    h.send(std::slice::from_ref(&p)).await.unwrap();
    assert_eq!(h.dest_files(), ["photo (1).jpg", "photo (2).jpg", "photo.jpg"]);
    assert_eq!(fs::read(h.dest.path().join("photo.jpg")).unwrap(), b"user's own file");
    assert_eq!(fs::read(h.dest.path().join("photo (2).jpg")).unwrap(), b"incoming");
}

#[tokio::test]
async fn resumes_from_partial() {
    let h = harness(accept_all()).await;
    let data = random_bytes(4 << 20);
    let p = h.write_src("video.mp4", &data);
    let part_dir = h.dest.path().join(PARTIAL_DIR);
    fs::create_dir_all(&part_dir).unwrap();
    let item = file_item(&p).await.unwrap();
    fs::write(part_dir.join(part_name(&item)), &data[..data.len() / 2]).unwrap();

    let report = h.send(&[p]).await.unwrap();
    assert_eq!(report.bytes_sent, (data.len() - data.len() / 2) as u64);
    assert_eq!(fs::read(h.dest.path().join("video.mp4")).unwrap(), data);
    assert_eq!(h.partials(), 0);
}

#[tokio::test]
async fn corrupt_partial_fails_then_restarts() {
    let h = harness(accept_all()).await;
    let data = random_bytes(1 << 20);
    let p = h.write_src("doc.pdf", &data);
    let part_dir = h.dest.path().join(PARTIAL_DIR);
    fs::create_dir_all(&part_dir).unwrap();
    let part = part_dir.join(part_name(&file_item(&p).await.unwrap()));
    fs::write(&part, vec![0xAA; data.len() / 2]).unwrap();

    let err = h.send(std::slice::from_ref(&p)).await.unwrap_err();
    assert!(format!("{err:#}").contains("BLAKE3 mismatch"), "{err:#}");
    assert!(!part.exists());
    assert!(h.dest_files().is_empty());
    assert!(h.events.lock().unwrap().iter().any(|e| matches!(e, FileEvent::Failed { .. })));

    let report = h.send(&[p]).await.unwrap();
    assert_eq!(report.bytes_sent, data.len() as u64);
    assert_eq!(fs::read(h.dest.path().join("doc.pdf")).unwrap(), data);
}

#[tokio::test]
async fn declined_items_are_not_sent() {
    let h = harness(Arc::new(|o: &Offer| o.items.iter().map(|i| !i.name.starts_with("skip")).collect())).await;
    let paths = [h.write_src("keep.txt", b"keep"), h.write_src("skip.bin", &random_bytes(100_000))];
    let report = h.send(&paths).await.unwrap();
    assert_eq!((report.files_sent, report.bytes_sent), (1, 4));
    assert_eq!(h.dest_files(), ["keep.txt"]);
}

#[tokio::test]
async fn directories_are_rejected() {
    let h = harness(accept_all()).await;
    let err = h.send(&[h.src.path().to_owned()]).await.unwrap_err();
    assert!(err.to_string().contains("only regular files"), "{err}");
}

/// Hand-rolled sender that lies about names and ids.
#[tokio::test]
async fn hostile_sender_stays_inside_dest() {
    let h = harness(accept_all()).await;
    let body = b"root::0:0".to_vec();
    let id = [7u8; 16];
    let (mut send, mut recv) = h.conn.open_bi().await.unwrap();
    write_prefix(&mut send, CH_FILE).await.unwrap();
    let item = FileItem { name: "../../etc/passwd".into(), size: body.len() as u64, mtime_unix_ms: 0 };
    write_msg(&mut send, &Offer { transfer_id: id.to_vec(), items: vec![item] }).await.unwrap();
    let reply: OfferReply = read_msg(&mut recv).await.unwrap();
    assert!(reply.items[0].accepted);

    // Wrong offset and unknown index are refused without touching the transfer.
    for (index, offset) in [(0, 5), (3, 0)] {
        let mut s = h.conn.open_uni().await.unwrap();
        write_prefix(&mut s, CH_FILE).await.unwrap();
        write_msg(&mut s, &DataHeader { transfer_id: id.to_vec(), index, offset }).await.unwrap();
        s.write_all(&body).await.unwrap();
        s.finish().unwrap();
    }
    let mut s = h.conn.open_uni().await.unwrap();
    write_prefix(&mut s, CH_FILE).await.unwrap();
    write_msg(&mut s, &DataHeader { transfer_id: id.to_vec(), index: 0, offset: 0 }).await.unwrap();
    s.write_all(&body).await.unwrap();
    s.finish().unwrap();
    let hash = blake3::hash(&body);
    write_msg(&mut send, &ItemDone { index: 0, blake3: hash.as_bytes().to_vec() }).await.unwrap();
    let result: ItemResult = read_msg(&mut recv).await.unwrap();
    assert!(result.ok, "{}", result.error);
    assert_eq!(result.saved_as, "passwd");
    assert_eq!(fs::read(h.dest.path().join("passwd")).unwrap(), body);
    send.finish().unwrap();

    // A malformed transfer id is rejected outright.
    let (mut send, mut recv) = h.conn.open_bi().await.unwrap();
    write_prefix(&mut send, CH_FILE).await.unwrap();
    write_msg(&mut send, &Offer { transfer_id: vec![1, 2, 3], items: vec![] }).await.unwrap();
    assert!(read_msg::<_, OfferReply>(&mut recv).await.is_err());
}

/// `cargo test --release -p ferry-core -- --ignored small_files_throughput --nocapture`
#[tokio::test(flavor = "multi_thread")]
#[ignore]
async fn small_files_throughput() {
    let h = harness(accept_all()).await;
    let bulk = h.write_src("bulk.bin", &random_bytes(250 << 20));
    let small: Vec<PathBuf> = (0..5000).map(|i| h.write_src(&format!("s{i}"), &random_bytes(50_000))).collect();
    let rate = |r: SendReport| r.bytes_sent as f64 / r.elapsed.as_secs_f64() / 1e6;
    let bulk_rate = rate(h.send(&[bulk]).await.unwrap());
    let small_rate = rate(h.send(&small).await.unwrap());
    println!("bulk {bulk_rate:.0} MB/s, 5000x50KB {small_rate:.0} MB/s ({:.0}%)", 100.0 * small_rate / bulk_rate);
    assert!(small_rate >= 0.6 * bulk_rate);
}
