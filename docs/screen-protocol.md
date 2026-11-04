# Screen protocol (FSP1): mirroring, control and cursor crossing

Phase 4 contract shared by the core, the Android app and the Mac viewer.

- **Carrier:** a QUIC bi stream on channel `CH_SCREEN = 0x03` of the normal Ferry session (Wi-Fi/USB),
  opened by the Mac. Only paired peers.
- **Capture (phone):** MediaProjection. The user taps "Start now" on the phone every session; Android
  shows its own capture indicator; capture ends when the phone locks.
- **Control (phone):** the user turns on Ferry's Accessibility service in Settings. Ferry uses
  `dispatchGesture`, global actions (Back/Home/Recents/Notifications) and `ACTION_SET_TEXT` /
  `ACTION_SET_SELECTION` on the focused field. A cursor is drawn as an accessibility overlay. Control
  is off unless the user enables it, and the phone shows an ongoing "Controlled by <Mac>" notification
  with a Stop button while a session is open.

## Framing
Both directions start with the 4-byte magic `FSP1`. Then messages: `u8 type`, `u32 len` (big-endian),
then `len` payload bytes. Integers are big-endian, `f32` is IEEE-754 big-endian. Coordinates are
**normalized** to `0.0..=1.0` of the phone screen in its current rotation. Unknown types are skipped.
`len` above 8 MiB is a protocol error (close).

### Mac → phone
| Type | Name | Payload |
|---|---|---|
| `0x20` | START | `u16 max_size` (long side px, 0 = native), `u32 bitrate` (bps), `u8 flags` (bit0 video; 0 = control only, used by cursor crossing) |
| `0x10` | TOUCH | `u8 action` (0 down, 1 up, 2 move), `f32 x`, `f32 y` |
| `0x11` | SCROLL | `f32 x`, `f32 y`, `f32 dx`, `f32 dy` (wheel lines; +dy = scroll up), optional `u8 flags` (bit0 continuous: trackpad deltas incl. momentum, bit1 end: the scroll and its momentum are over; absent = 0, a wheel step) |
| `0x13` | TEXT | UTF-8 text typed into the focused field at its caret (replacing the selection) |
| `0x12` | KEY | `u32 code` (Android keycode), optional `u8 meta` (bit0 shift: extend the selection, bit1 option: by word). 67 delete back, 112 delete forward, 66 enter, 21/22 left/right, 19/20 up/down a line, 122/123 start/end of line; others ignored |
| `0x14` | NAV | `u8` 1 back, 2 home, 3 recents, 4 notifications |
| `0x15` | KEYFRAME | empty: send an IDR frame now |
| `0x16` | CURSOR | `u8 action` (0 down, 1 up, 2 move, 3 hide), `f32 x`, `f32 y`: overlay cursor; down/up at the cursor = tap or drag |
| `0x17` | ACK | `u64 pts_us`: the newest FRAME received (sent once per read chunk that held frames). Everything up to it has arrived |

### Phone → Mac
| Type | Name | Payload |
|---|---|---|
| `0x01` | INFO | `u16 width`, `u16 height` (screen px, current rotation), `u8 caps` (bit0 video, bit1 control). Sent first and again on rotation |
| `0x02` | FRAME | `u64 pts_us`, `u8 flags` (bit0 keyframe, bit1 codec config), then H.264 Annex-B bytes as MediaCodec emits them. Config (SPS+PPS) precedes the first keyframe after each INFO |
| `0x03` | END | `u8 reason` (0 normal, 1 stopped on the phone, 2 phone locked, 3 not allowed, 4 error), UTF-8 detail; then close |
| `0x04` | QUALITY | `u32 bitrate` (bps): the encoder's new target after the phone adapted to the link (informational) |
| `0x05` | STATS | every ~2 s while video runs: `u16 window_ms`, `u16 encoded`, `u16 sent`, `u16 dropped` (frames in the window), `u32 target_bps`, `u32 out_bps` (FRAME bytes actually written), `u16 encode`, `u16 write`, `u16 rtt`, `u16 rtt_min` (averages in 0.1 ms; `0xFFFF` = no sample). encode = capture → encoder output; write = time a FRAME write took; rtt = FRAME write → its ACK (needs a Mac that sends ACK). Informational |

## Rules
- The phone sends nothing before START. START with video → MediaProjection consent on the phone
  (notification the user taps if the activity can't be shown directly); refused → `END 3`.
  START control-only needs only the Accessibility service; if it is off → `END 3 "turn on Ferry control"`.
- Input messages are ignored unless the Accessibility service is on (caps bit1 tells the Mac).
- Typing: TEXT/KEY arriving within 8 ms of each other are applied together to one snapshot of the
  focused field (text + selection), then written back with `ACTION_SET_TEXT` and `ACTION_SET_SELECTION`.
  A field that reports no selection is edited at its end. Up/down use the field's line movement.
- Scrolling: deltas are sent as macOS reports them (so the natural-scrolling setting carries over).
  The phone plays them as one continued finger drag starting at (x, y), one segment per frame, and
  lifts after holding still (no fling on top of the Mac's momentum): on `end` (after 40 ms, in case
  momentum follows the finger lift), after 120 ms without a wheel step or 500 ms without trackpad
  deltas, and to re-grip when the finger would leave the screen. Trackpad: 2 dp per point; wheel: 40 dp per line.
- Cursor crossing (Mac): the pointer crosses after pushing 8 pt into the edge (pauses over 0.4 s start
  over) and comes back after 4 pt past the phone's Mac-side edge, at the proportional height. Moves
  are merged and sent at most at 120 Hz; the phone moves its overlay once per frame to the latest spot.
- Messages may grow trailing fields; a receiver ignores bytes past what it knows and treats missing
  optional fields as 0.
- Video: H.264 High profile where offered (else the encoder's default), no B-frames, VBR, realtime
  priority + operating rate, `KEY_LOW_LATENCY` where supported, at most 60 fps fed to the encoder (90/120 Hz
  screens), SPS/PPS with every keyframe, repeat previous frame after 100 ms. Keyframes on demand (KEYFRAME,
  catching up) plus one every 30 s: QUIC loses nothing, so periodic ones only cost bitrate spikes. Size: long
  side ≤ max_size (0 = native), multiples of 16, stepping down 1920/1600/1280/1024/720 until the encoder
  takes it at 60 fps (else at all; else 30 fps). Any key the encoder refuses → configured again with only
  the basic keys. The encoder runs in async mode on its own thread and never waits for the network.
  Defaults: max_size 1920 (older Macs ask 1280), 8 Mbps.
- Pacing and adaptive bitrate (phone side): START's bitrate is the ceiling. With ACKs (newer Macs): per 1 s
  window, mean write → ACK round trip above the best of the last 30 windows is the queueing on the link;
  > 80 ms in two windows in a row (or > 160 ms in one) → bitrate × 0.75; < 25 ms for 3 windows and ≥ 5 s
  after the last cut → × 1.15 (up to the ceiling). A steady high round trip (distance, relay) is not
  congestion. The picture is *behind* when the oldest unacked FRAME is ≥ 300 ms old or ≥ 6 encoded frames
  wait for the writer: bitrate × 0.6 (once per window), frames are dropped until the oldest unacked one is
  < 60 ms old, then one keyframe is requested (again after 1 s if it doesn't come) and sending resumes from
  it. So the Mac's picture is never far behind and stale frames never queue behind each other. Without
  ACKs (older Macs): write timing (blocked > 50% of two windows in a row → × 0.75, < 10% for three → × 1.15;
  one write ≥ 400 ms → behind). Floor max(500 kbps, ceiling / 8). Applied with
  `PARAMETER_KEY_VIDEO_BITRATE`, kept across rotations, and reported as QUALITY.
- Measurements: FRAME `pts_us` is the capture time (surface input: `System.nanoTime` µs on the phone). The
  clocks differ, so the Mac reports one-way delay as (received − pts) above the lowest seen this session
  (= queueing the link added), plus the phone's write → ACK round trip from STATS. Logs: phone
  `adb logcat -s FerryScreen` (`stats: …` every 2 s), Mac os.Logger `dev.ferry.mac` (`screen: …` every 2 s).
- Rotation: new INFO, encoder restarted at the new size, config + keyframe.
- Bluetooth-only session: START with video → `END 4 "Bluetooth is too slow for video"`; control-only is allowed.
- Either side may close at any time; EOF = END 0.

## FFI surface (ferry-ffi)
```rust
#[derive(uniffi::Object)] pub struct ScreenStream;     // the FSP1 byte pipe (QUIC bi stream)
impl ScreenStream {
    pub async fn read(&self) -> Result<Vec<u8>>;       // next chunk; empty Vec = EOF
    pub async fn write(&self, data: Vec<u8>) -> Result<()>;
    pub async fn finish(&self);                         // not `close`: clashes with Kotlin AutoCloseable
}
// FerryNode
pub async fn open_screen(&self, peer_id: String) -> Result<Arc<ScreenStream>>;  // Mac
// FerryDelegate (new)
fn on_screen_stream(&self, peer_id: String, stream: Arc<ScreenStream>);         // phone
```
The idle-session reaper must not close a session while a screen stream is open.
