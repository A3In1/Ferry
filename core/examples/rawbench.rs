//! Raw iroh stream throughput on loopback, to separate transport limits from our code.
use iroh::{Endpoint, EndpointAddr, RelayMode, TransportAddr, endpoint::presets};
use std::time::Instant;

async fn ep(tune: bool) -> Endpoint {
    let mut b = Endpoint::builder(presets::Minimal)
        .relay_mode(RelayMode::Disabled)
        .alpns(vec![b"bench".to_vec()]);
    if tune {
        let t = iroh::endpoint::QuicTransportConfig::builder()
            .stream_receive_window((64u32 << 20).into())
            .send_window(256 << 20)
            .build();
        b = b.transport_config(t);
    }
    b.bind().await.unwrap()
}

#[tokio::main]
async fn main() {
    let tune = std::env::args().any(|a| a == "tune");
    let (a, b) = (ep(tune).await, ep(tune).await);
    let port = b
        .bound_sockets()
        .into_iter()
        .find(|s| s.is_ipv4())
        .unwrap()
        .port();
    let addr = EndpointAddr::from_parts(b.id(), [TransportAddr::Ip(([127, 0, 0, 1], port).into())]);
    let srv = tokio::spawn(async move {
        let c = b.accept().await.unwrap().await.unwrap();
        let mut r = c.accept_uni().await.unwrap();
        let mut n = 0u64;
        while let Some(ch) = r.read_chunk(1 << 20).await.unwrap() {
            n += ch.len() as u64;
        }
        n
    });
    let c = a.connect(addr, b"bench").await.unwrap();
    let mut s = c.open_uni().await.unwrap();
    let buf = vec![7u8; 1 << 20];
    let t = Instant::now();
    for _ in 0..1024 {
        s.write_all(&buf).await.unwrap();
    }
    s.finish().unwrap();
    let n = srv.await.unwrap();
    let e = t.elapsed().as_secs_f64();
    println!(
        "tune={tune}: {} MiB in {e:.2}s = {:.0} MiB/s",
        n >> 20,
        (n >> 20) as f64 / e
    );
}
