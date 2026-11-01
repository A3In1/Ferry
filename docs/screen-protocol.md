# Screen protocol (FSP1): mirroring, control and cursor crossing

Phase 4 contract shared by the core, the Android app and the Mac viewer.

- **Carrier:** a QUIC bi stream on channel `CH_SCREEN = 0x03` of the normal Ferry session (Wi-Fi/USB),
  opened by the Mac. Only paired peers.
- **Capture (phone):** MediaProjection. The user taps "Start now" on the phone every session; Android
  shows its own capture indicator; capture ends when the phone locks.
- **Control (phone):** the user turns on Ferry's Accessibility service in Settings. Ferry uses
  `dispatchGesture`, global actions (Back/Home/Recents/Notifications) and `ACTION_SET_TEXT` on the
  focused field. A cursor is drawn as an accessibility overlay. Control is off unless the user
  enables it, and the phone shows an ongoing "Controlled by <Mac>" notification with a Stop button
  while a session is open.

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
| `0x13` | TEXT | UTF-8 text to insert into the focused field |
| `0x12` | KEY | `u32 code`: 67 = delete one char, 66 = enter (other Android keycodes ignored for now) |
| `0x14` | NAV | `u8` 1 back, 2 home, 3 recents, 4 notifications |
| `0x15` | KEYFRAME | empty: send an IDR frame now |
| `0x16` | CURSOR | `u8 action` (0 down, 1 up, 2 move, 3 hide), `f32 x`, `f32 y`: overlay cursor; down/up at the cursor = tap or drag |

### Phone → Mac
| Type | Name | Payload |
|---|---|---|
| `0x01` | INFO | `u16 width`, `u16 height` (screen px, current rotation), `u8 caps` (bit0 video, bit1 control). Sent first and again on rotation |
| `0x02` | FRAME | `u64 pts_us`, `u8 flags` (bit0 keyframe, bit1 codec config), then H.264 Annex-B bytes as MediaCodec emits them. Config (SPS+PPS) precedes the first keyframe after each INFO |
| `0x03` | END | `u8 reason` (0 normal, 1 stopped on the phone, 2 phone locked, 3 not allowed, 4 error), UTF-8 detail; then close |

## Rules
- The phone sends nothing before START. START with video → MediaProjection consent on the phone
  (notification the user taps if the activity can't be shown directly); refused → `END 3`.
  START control-only needs only the Accessibility service; if it is off → `END 3 "turn on Ferry control"`.
- Input messages are ignored unless the Accessibility service is on (caps bit1 tells the Mac).
- Scrolling: deltas are sent as macOS reports them (so the natural-scrolling setting carries over).
  The phone plays them as one continued finger drag starting at (x, y), one segment per frame, and
  lifts after holding still (no fling on top of the Mac's momentum): on `end` (after 40 ms, in case
  momentum follows the finger lift), after 120 ms without a wheel step or 500 ms without trackpad
  deltas, and to re-grip when the finger would leave the screen. Trackpad: 2 dp per point; wheel: 40 dp per line.
- Messages may grow trailing fields; a receiver ignores bytes past what it knows and treats missing
  optional fields as 0.
- Video: H.264, no B-frames, realtime priority/low latency where supported, repeat previous frame
  after 100 ms, keyframe every 10 s and on KEYFRAME. Defaults: max_size 1280, 8 Mbps.
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
