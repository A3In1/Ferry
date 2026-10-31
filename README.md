# Ferry

Android ↔ Mac continuity: our own protocol, our own apps. Design and decisions live in
`../MarkdownFiles/AndroidContinuity/` (`plans/Build_Plan.md`, `DECISIONS.md`).

```
proto/   wire schema (ferry.proto, protobuf via prost + protox, no system protoc)
core/    Rust core: identity, pairing, trust, discovery, node (sessions), channel framing, file engine
cli/     `ferry` headless peer: test bench, benchmarks, interop
ffi/     UniFFI bindings (Swift now, Kotlin later)
mac/     SwiftUI menu-bar app (see mac/README.md)
scripts/ build-apple.sh, bundle-mac.sh, udp-baseline.py
```

## Build and test

```sh
cargo test --workspace --release        # unit + integration tests (incl. a real SIGKILL crash/resume test)
cargo clippy --workspace --all-targets
cargo build --release -p ferry-cli      # → target/release/ferry
scripts/build-apple.sh && scripts/bundle-mac.sh && open mac/build/Ferry.app
```

## Try it: two CLI peers on one Mac

```sh
F=target/release/ferry
$F --data-dir /tmp/ferryA --name MacA pair-show --dir /tmp/recvA      # terminal 1: prints QR + ticket, keeps listening
$F --data-dir /tmp/ferryB --name PhoneB pair 'ferry://pair?...'       # terminal 2: confirm the same 6-digit code in both
$F --data-dir /tmp/ferryB bench-file /tmp/big.bin 4                   # 4 GiB random file
$F --data-dir /tmp/ferryB send MacA /tmp/big.bin                      # prints MiB/s; Ctrl-C halfway and rerun to see resume
```
On one machine, edit the ticket's `a=` to `127.0.0.1:<port>`. Over Wi-Fi, use the ticket as printed.

## Phase 0 status (foundation)

| Exit check | Result |
|---|---|
| Two CLI peers pair from a ticket (6-digit code, bound to the TLS session) | ✅ automated tests + manual run |
| 4 GiB transfer, byte-identical | ✅ loopback, `cmp` identical |
| Resume after the sender is killed | ✅ `cli/tests/crash_resume.rs` (fails on the pre-fix code, passes now) |
| Throughput ≥ 80% of raw UDP on the same path | ✅ 37.7 MiB/s vs 43 MiB/s raw UDP = 88%. **This Mac's UDP is capped by socket-filter extensions (Cisco Secure Client, Tailscale)**: `scripts/udp-baseline.py` shows the ceiling. Measure again over Wi-Fi with the phone in Phase 1 |
| 5,000 × 50 KB at ≥ 60% of bulk | ✅ 83% (`cargo test --release -p ferry-core small_files_throughput -- --ignored`) |
| Hostile input (names, offsets, sizes, oversized messages) | ✅ unit tests + 20k randomized names |
| Mac app runs the core, advertises `_ferry._udp` | ✅ (pairing with the CLI is a manual GUI check, see below) |
| Android skeleton | ✅ superseded by the Phase 1 app |

**Manual check: Mac app ↔ CLI**
1. `open mac/build/Ferry.app`, then menu bar → **Pair a device…** and copy the ticket text.
2. `target/release/ferry --data-dir /tmp/ferryB --name PhoneB pair '<ticket>'` and confirm the code on both sides.
3. `target/release/ferry --data-dir /tmp/ferryB send <Mac name> some-file`: it lands in `~/Downloads`.
4. In the app: Paired devices → PhoneB → **Send files…** while `ferry --data-dir /tmp/ferryB listen` runs.

## Phase 1 status (pairing + file sharing, tested on a vivo V2222, Android 15)

| Check | Result |
|---|---|
| Rust core starts on Android; NSD advertises `_ferry._udp` | ✅ |
| QR pairing Mac app ↔ phone over Wi-Fi | ✅ (campus Wi-Fi blocks mDNS discovery, but direct unicast works) |
| USB link: adb auto-attach, ticket pushed to the phone, code confirmed | ✅ |
| 100 MB Mac → phone over USB | ✅ 29.5 MiB/s, SHA-256 identical, saved in Download/Ferry |
| 100 MB phone → Mac over USB (share/picker) | ✅ SHA-256 identical |
| First USB pairing attempt saved on the Mac only | ⚠️ not reproduced on retry; watch for it |
| Wi-Fi throughput benchmark on a network that allows device-to-device traffic | ⏳ |

Android: `android/README.md`. Install over USB: `adb install -r android/app/build/outputs/apk/full/debug/app-full-debug.apk`.

## Phase 2 status (everyday sync, tested on the vivo V2222 over USB and Wi-Fi)

| Feature | Result |
|---|---|
| Phone notifications on the Mac; dismissing on the Mac clears the phone | ✅ (needs macOS notification permission for Ferry) |
| Find my phone (ring on silent, stop from either side) | ✅ |
| Clipboard Mac → phone (automatic with sync on, or one-shot) | ✅ |
| Clipboard phone → Mac | ✅ via in-app button, Quick Settings tile or Share. Android 10+ blocks background clipboard reads; automatic needs a Ferry keyboard (not built) |
| Links, media controls, battery | ✅ |
| Cable-free on campus Wi-Fi (mDNS blocked) | ✅ stable ports + addresses exchanged in the handshake + check-in on network change |
| Battery | idle sessions close after ~30 s; phone discovery/multicast lock 1 min per 15 min; Mac uses `adb track-devices` instead of polling |

vivo notes: app Info logs are hidden (`log.tag=M`), so Kotlin diagnostics use `Log.w`.
