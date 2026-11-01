# Protocol review

A layer-by-layer look at Ferry's wire choices (November 2026, protocol 1): whether each is
right, and which changes are worth making. Nothing here changes the wire format incompatibly;
items that need both sides to agree go behind feature negotiation (item 1).

## Ranked by value for effort

| # | Change | Layer | Value | Effort | Status |
|---|---|---|---|---|---|
| 1 | Feature list in `Hello` (field 5, `repeated string features`); absent = none | session | enables 2, 3 and later changes without a protocol bump | S | proposed |
| 2 | One ordered events stream per session (fallback: one stream per event) | framing | fixes notification add/remove reordering; one stream per session instead of per event | M | proposed |
| 3 | QUIC datagrams for pointer moves (CURSOR/TOUCH move, SCROLL) | screen | moves are dropped, not delayed, on Wi-Fi loss: smoother cursor crossing | M | proposed |
| 4 | Bluetooth bridge queueing: measure, then cap the queue (drop-tail in the shim) | BLE | events and acks stay fast while a file crawls over Bluetooth | S–M | measure first |
| 5 | Try BBR instead of the default congestion controller on Wi-Fi | transport | maybe higher throughput on lossy 2.4 GHz; unknown until measured | S | measure first |
| 6 | Pin field numbers in tests | proto | a renumbered field fails CI instead of breaking installed apps | S | **done** |
| 7 | Session races: simultaneous dials, per-peer dial lock, queued dials share a failure | session | no dropped sends when both sides dial at once or a phone is gone | S | **done** (see git log) |
| 8 | Keep a slow-but-alive session on event-ack timeout | session | an event during a Bluetooth transfer no longer kills the transfer | S | **done** |
| 9 | Chunk-verified resume (BLAKE3/bao outboard) | file | resume after a bad chunk instead of from zero | M | not worth it on a LAN |
| 10 | In-place mDNS TXT update instead of unregister/register | discovery | removes a brief invisibility every 15 min | S | low value |

S = under a day, M = a few days including both apps.

## Transport: iroh 1.4 QUIC, relays and DNS off

**Right choice.** TLS 1.3 with raw public keys gives mutual authentication by device key for free,
streams give per-channel flow control, and the same session runs over Wi-Fi, the USB shim and the
Bluetooth bridge because each just looks like one more UDP path. Relays and n0 DNS are off, so
nothing leaves the LAN.

- **Keepalive / idle:** iroh pings an open path every few seconds and noq's idle timeout is 30 s;
  Ferry adds its own reaper that closes sessions after ~30 s without stream frames, so a phone's
  radio isn't kept awake by pings. That's the right trade; no change. If long screen sessions show
  up in battery stats, a longer path keep-alive on the phone while a screen stream is open is the
  knob to try.
- **Congestion control (item 5):** the default controller is fine on a clean 5 GHz LAN. On lossy
  2.4 GHz, BBR often holds throughput better. It's a one-line `TransportConfig` change, but it
  needs a before/after measurement on real Wi-Fi (a 1 GB `ferry send` both ways) before it ships.
- **ECN:** noq handles ECN when the OS reports it; nothing to do in Ferry.
- **Datagrams:** not enabled today. Needed for item 3.

## Discovery: mDNS `_ferry._udp` with rotating presence tags, BLE service UUID

**Right choice.** The TXT record carries only a nonce and a keyed tag (64 bits), random rotating
instance and host names, so only paired devices recognise each other and a passive observer can't
track a device across rotations. BLE uses the same tag in a 128-bit service UUID. Last-known
addresses in the trust store plus the addresses each side announces in `Hello` cover networks that
block multicast.

- The dial lookup now holds the same capped list as the trust store (8 addresses, newest first):
  iroh's `add_endpoint_info` only ever adds, so it used to grow with every network the devices met
  on, and each connect probed every stale address.
- Item 10 (in-place TXT update) is cosmetic; leave it unless the 15-minute gap is ever noticed.

## Pairing: QR ticket, TLS-exporter proofs, 6-digit code

**Right choice.** The scanner dials the key in the ticket, so TLS authenticates the shower; each
side MACs this connection's TLS exporter with the ticket secret, so proofs can't be replayed; the
6-digit code comes from the exporter too, so it only matters if the ticket leaked.

- **Code length:** 6 digits (~20 bits) is enough here. An attacker needs the ticket (single use,
  10 minutes) *and* must win a 1-in-a-million match that the user then confirms. Longer codes would
  cost usability for no real gain. Keep.
- **Ticket TTL:** 10 minutes is generous but harmless since the ticket is single use and replaced
  by the next one.

## Session and message framing

`[channel u8][version u8]` per stream, then varint-length protobuf messages (1 MiB cap). Unknown
channels are refused per stream, unknown event kinds are ignored, so newer peers keep working.
**Right choice**; protobuf with frozen field numbers is the standard way to evolve this.

- **Versioning (item 1):** `Hello.protocol_version` must match exactly, which makes any bump a
  hard break between app versions. Keep it for that purpose, and add `repeated string features = 5`
  for everything else. Old peers ignore the unknown field and send none, so "absent" already means
  "no optional features": nothing has to ship before the first feature that uses it.
- **Events (item 2):** one uni stream per event, acked by the peer reading it to the end. Simple and
  robust, but two events sent back to back can arrive out of order (a notification and its removal),
  and each event costs a stream open. With a `events-stream` feature, both sides would keep one
  long-lived ordered uni stream per direction and fall back to per-event streams for older peers.
  The ack would move to an application-level sequence number every N events.
- **Field numbers (item 6, done):** `ferry-proto` tests now pin every `Event` kind's number and the
  `Hello`/pairing/file fields that matter, a cheap stand-in for `buf breaking` that needs no extra
  tooling in CI. Add a line for each new field; never edit one.
- **Session races (items 7, 8, done):** both sides dialing each other at the same moment used to end
  with each keeping the connection the other had just closed, killing the send. Both sides now keep
  the connection dialed by the larger device id when two start within 3 s. Dialing is locked per
  peer, and sends queued behind a dial that failed share that failure instead of each waiting out
  another 10 s timeout. An event that isn't acked within 3 s no longer kills a session that is
  still receiving packets, which on a busy Bluetooth link used to take a running transfer with it.

## File transfer

Offer → per-item accept with resume offsets → one uni data stream per file (8 in parallel) →
whole-file BLAKE3 → atomic hard-link into place. **Right choice**: parallel streams hide per-file
latency for many small files, resume keys survive a new transfer id, and placement never
overwrites an existing file.

- Received files are now `fsync`ed before they're placed and reported saved, and a retried offer
  waits at most 5 s in total (not per item) for partials still held by a dying transfer.
- Item 9 (chunk-verified resume) only helps when bytes get corrupted mid-file, which QUIC's own
  integrity makes very unlikely on a LAN. Not worth it.

## Bluetooth bridge

The session runs over an L2CAP byte stream using the USB shim's framing (`u16` length + one QUIC
datagram). **Right choice** as a fallback: one code path, same encryption and channels.

- QUIC over a reliable stream means two layers of retransmission and, more importantly, queueing:
  datagrams wait in the 256 KiB app↔core duplex plus the shim's UDP receive buffer whenever the
  radio is slower than QUIC's congestion window. At Bluetooth speeds that can be seconds, which
  inflates RTT and delays small events behind a transfer (item 8 keeps such a session alive; item 4
  would keep it fast). Measure the queueing delay on a phone first (ping an event during a file
  send over Bluetooth only); if it's over ~1 s, shrink the duplex to 64 KiB and drop datagrams in the
  shim when the writer is behind, so QUIC sees loss and backs off instead of a growing queue.

## Screen (FSP1)

A bi stream on `CH_SCREEN`: `u8 type, u32 len` messages, H.264 Annex-B frames one way, input the
other. **Right choice** for video: frames must arrive in order anyway, and a stream per session keeps
the encoder simple.

- **Pointer moves (item 3):** CURSOR/TOUCH moves and SCROLL share one ordered stream, so a lost packet
  on Wi-Fi delays every later move by a round trip or more. They're idempotent (absolute positions),
  so they suit QUIC datagrams: newest wins, a lost one is simply skipped. Downs/ups, TEXT, KEY and NAV
  stay on the stream. Needs datagrams enabled, a `screen-datagrams` feature (item 1) and a
  `ScreenStream::send_datagram`/`read_datagram` pair in the FFI.
- Frames under congestion: the phone already repeats frames and sends keyframes every 10 s; if
  mirroring lags on weak Wi-Fi, have the phone skip non-key frames while the stream's send buffer is
  backed up. That's an app-side change, no wire change.
