# Screen control protocol (FSP1): Share Cursor

Phase 4 contract shared by the core, the Android app and the Mac app. Ferry doesn't mirror the phone's
screen: there's no capture or video here, only input from the Mac.

- **Carrier:** a QUIC bi stream on channel `CH_SCREEN = 0x03` of the normal Ferry session (Wi-Fi/USB/Bluetooth),
  opened by the Mac. Only paired peers.
- **Control (phone):** the user turns on Ferry's Accessibility service in Settings. Ferry uses
  `dispatchGesture` and `ACTION_SET_TEXT` / `ACTION_SET_SELECTION` on the focused field. A cursor is
  drawn as an accessibility overlay. Control is off unless the user enables it, and the phone shows an
  ongoing notification with a Stop sharing button while a session is open.

## Framing
Both directions start with the 4-byte magic `FSP1`. Then messages: `u8 type`, `u32 len` (big-endian),
then `len` payload bytes. Integers are big-endian, `f32` is IEEE-754 big-endian. Coordinates are
**normalized** to `0.0..=1.0` of the phone screen in its current rotation. Unknown types are skipped.
`len` above 8 MiB is a protocol error (close).

### Mac → phone
| Type | Name | Payload |
|---|---|---|
| `0x20` | START | empty |
| `0x11` | SCROLL | `f32 x`, `f32 y`, `f32 dx`, `f32 dy` (wheel lines; +dy = scroll up), `u8 flags` (bit0 continuous: trackpad deltas incl. momentum, bit1 end: the scroll and its momentum are over; 0 = a wheel step) |
| `0x13` | TEXT | UTF-8 text typed into the focused field at its caret (replacing the selection) |
| `0x12` | KEY | `u32 code` (Android keycode), `u8 meta` (bit0 shift: extend the selection, bit1 option: by word). 67 delete back, 112 delete forward, 66 enter, 21/22 left/right, 19/20 up/down a line, 122/123 start/end of line; others ignored |
| `0x16` | CURSOR | `u8 action` (0 down, 1 up, 2 move, 3 hide), `f32 x`, `f32 y`: overlay cursor; down/up at the cursor = tap or drag |
| `0x18` | KEEPALIVE | empty, every 5 s so the link isn't timed out; ignored |

### Phone → Mac
| Type | Name | Payload |
|---|---|---|
| `0x01` | INFO | `u16 width`, `u16 height` (screen px, current rotation), `u8 caps` (bit1 control). Sent first and again on rotation |
| `0x03` | END | `u8 reason` (0 normal, 1 stopped on the phone, 2 phone locked, 3 not allowed, 4 error), UTF-8 detail; then close |

## Rules
- The phone sends nothing before START. START needs the Accessibility service; if it is off →
  `END 3 "turn on Ferry control"`. A locked phone → `END 2`.
- Input messages are ignored unless the Accessibility service is on (caps bit1 tells the Mac).
- Typing: TEXT/KEY arriving within 8 ms of each other are applied together to one snapshot of the
  focused field (text + selection), then written back with `ACTION_SET_TEXT` and `ACTION_SET_SELECTION`.
  A field that reports no selection is edited at its end. Up/down use the field's line movement.
- Scrolling: deltas are sent as macOS reports them (so the natural-scrolling setting carries over).
  The phone plays them as one continued finger drag starting at (x, y), one segment per frame, and
  lifts after holding still (no fling on top of the Mac's momentum): on `end` (after 40 ms, in case
  momentum follows the finger lift), after 120 ms without a wheel step or 500 ms without trackpad
  deltas, and to re-grip when the finger would leave the screen. Trackpad: 2 dp per point; wheel: 40 dp per line.
- Phone input pacing: drag moves are played at most every 8 ms (the newest point), so bursts from
  the network don't become 1 ms finger jumps; a press whose drag is still queued is not a tap. CURSOR moves
  are merged before the main thread (only the newest waits; a following non-move message brings it along
  first) and move the arrow by its translation in a full-screen untouchable overlay (no window relayout).
- Keep awake (phone setting, default on, needs Ferry control): while a session is open, a 1 px accessibility
  overlay with `FLAG_KEEP_SCREEN_ON`; removed when the session ends. The keyguard is never touched.
- Wi-Fi (phone): while any session is open, Wi-Fi locks `WIFI_MODE_FULL_LOW_LATENCY` (honored only with
  Ferry in the foreground and the screen on) plus `WIFI_MODE_FULL_HIGH_PERF` as the fallback: Wi-Fi power
  save is a classic +30–100 ms spike. Released when the session ends.
- Cursor crossing (Mac): the pointer crosses after pushing 8 pt into the edge (pauses over 0.4 s start
  over) and comes back after 4 pt past the phone's Mac-side edge, at the proportional height. Moves
  are merged and sent at most at 120 Hz, latest-wins on the wire (a newer CURSOR move replaces one still
  waiting for the writer; any other message takes a waiting one along first, so order holds).
  The phone moves its overlay arrow at once to the latest spot. The first push opens the
  stream (after the Mac password check below); if the pointer is still at the edge when INFO arrives it
  crosses then. The stream stays open with KEEPALIVE until 10 minutes without a crossing. Every cross,
  return (with why) and refused push (with why: no Accessibility trust, control off, a display past the
  edge, stream not ready) is logged (`cursor: …`); lasting problems show as "Share Cursor (not working)"
  in the menu with the reason as its tooltip.
- Mac password (setting "Require Mac Password for Share Cursor", default on): Touch ID or the Mac password
  (`LAPolicy.deviceOwnerAuthentication`) before a cursor control stream opens; remembered 5 minutes,
  forgotten on sleep, screen lock or user switch.
- Messages may grow trailing fields; a receiver ignores bytes past what it knows.
- Rotation: new INFO.
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
