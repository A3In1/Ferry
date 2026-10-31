# Ferry for Mac (Phases 1–4)

Menu-bar app (no Dock icon) over the shared Rust core, via UniFFI bindings from `ffi/`.
AppKit `NSStatusItem` + `NSMenu` (so the icon can take drops); the pairing window is SwiftUI.

## Build

From the repo root:

```sh
scripts/build-apple.sh   # Rust → mac/Frameworks/FerryCoreFFI.xcframework + Sources/FerryCore/FerryCore.swift
scripts/bundle-mac.sh    # swift build -c release → mac/build/Ferry.app (signed, plist linted, registered for Services)
open mac/build/Ferry.app
```

Re-run `build-apple.sh` after any change in `core/` or `ffi/`. `cd mac && swift run Ferry` also works
for quick iteration, but without the bundle there is no Info.plist: no Services entry, no notifications,
no Open at Login.

The bundle is ad-hoc signed by default, so macOS asks again for **Downloads folder access** after every
rebuild (the grant is tied to the binary hash). Until you click Allow, received files stay in
`~/Downloads/.ferry-partial` and the sender hangs at 100%. `FERRY_SIGN_IDENTITY="Apple Development: …"
scripts/bundle-mac.sh` signs with a stable identity so the grant sticks.

The xcframework and `FerryCore.swift` are generated and git-ignored.

## What it does

- Starts a `FerryNode` with data in `~/Library/Application Support/Ferry`, saving received files to `~/Downloads`.
- **Drop files on the menu-bar icon** to send them (the icon highlights). It goes to the only paired phone,
  or the one you sent to last. Otherwise a small menu asks which one.
- **Finder → right-click → Services → Send with Ferry** does the same (`NSServices` in the Info.plist,
  handled by the running app; macOS launches Ferry if it isn't running).
- **Received files** are marked quarantined (`kLSQuarantineTypeOtherDownload`, agent "Ferry"), so
  Gatekeeper checks them like a browser download. One notification per finished transfer: clicking it or
  **Show in Finder** reveals the files. Permission is requested at first launch.
- **Transfers in the menu**: incoming shows the current file, overall % and MB/s, live while the menu is
  open. Outgoing shows "Sending …" then the size and MB/s, or an error. The last five lines are kept.
- **Pair a device…** shows a QR + selectable `ferry://pair?...` ticket (Copy ticket button). When the
  other side connects, an alert shows the 6-digit code: Match / Doesn't match.
- **Pair phone over USB** pushes a ticket to the plugged-in phone over adb. The phone shows the code and
  the same Match alert appears here. **USB links** show as "📱 <name> — USB".
- **Paired devices** → per device: Send files… (multi-select), Forget. The list refreshes every 2 s and
  each time the menu opens.
- **Open at Login** (`SMAppService.mainApp`). If macOS needs approval, System Settings → Login Items opens.

### Everyday sync (Phase 2)

Sent to every paired phone (usually one) in background tasks. Background sync failures are only logged;
things you click (ring, send clipboard/link, a notification reply) show an alert if no phone got them.

- **Phone notifications** show as Mac notifications ("App: Title", grouped by app). The same phone key
  replaces the banner, and removing it on the phone removes it here. Phone buttons become notification
  buttons, reply buttons get a text field, and dismissing it here dismisses it on the phone.
- **Clipboard**: text from the phone goes straight onto the Mac clipboard. **Sync clipboard to phone**
  (off by default) sends what you copy on the Mac (plain text up to 1 MB). Items that password managers mark
  as concealed or transient are never sent, and nothing the phone just sent is sent back.
  **Send clipboard to phone** sends once, even with sync off.
- **Now playing**: while the phone plays media, the menu shows "♪ Title — Artist (App)" with
  Previous / Play/Pause / Next.
- **Battery**: "🔋 42%" (⚡ while charging).
- **Ring phone / Stop ringing** (find my phone).
- **Links**: an http(s) link from the phone opens in your browser, with a notification saying so. Other
  schemes (`file:`, `javascript:`, custom app schemes) are ignored. **Services → Send link to phone**
  (selected text or a URL in any app) sends one web URL as a link (the phone opens it). Any other text
  goes to the phone's clipboard.

**Clipboard privacy (macOS 15.4+).** The general pasteboard's `accessBehavior` defaults to *ask* for
programmatic reads. Reading `changeCount` and `types` never triggers the alert, so the 1 s poll is silent.
Ferry only reads the text after you copy something, with sync on. The first read may show the system
"Ferry would like to paste from …" alert, and the choice then lives in System Settings → Privacy & Security →
Paste from Other Apps. If you choose *Deny*, sync can't read anything; the menu item's tooltip says so.
Writing to the pasteboard (text from the phone) never prompts. On the dev machine (macOS 27) the poll's
reads came back with the text right away. Whether an alert appeared on screen wasn't checked.

### Bluetooth (Phase 3)

When you're not on the same Wi-Fi, the phone reaches the Mac over Bluetooth LE. The Mac is the peripheral
and the phone is the central. `Sources/Ferry/Bluetooth.swift` does this:

- **Advert**: one 128-bit service UUID, `FerryNode.presenceBleUuid()`, and **no local name**. The UUID fills
  the 28-byte budget, and a name would identify the Mac. Only paired phones can match the UUID. It rotates
  about every 15 min. Ferry re-reads it every minute and restarts the advert when it changes, and again on
  power-on.
- **GATT**: a fixed service `F377E501-0000-1000-8000-00805F9B34FB` with Apple's read-only L2CAP PSM
  characteristic (`CBUUIDL2CAPPSMCharacteristicString`, `ABDD3056-…`). Reading it returns the PSM as a
  little-endian UInt16. macOS assigns the PSM on each `publishL2CAPChannel(withEncryption: false)`.
  The channel is unencrypted because Android can't open a secure one without bonding; QUIC/TLS secures it.
- **L2CAP channel → core**: each channel the phone opens becomes `openStreamBridge(label: "ble:<id prefix>")`.
  The core runs the same bridge hello and framing as USB, so the bridge shows up in `usbLinks()` with a
  `ble:` serial. The menu shows **"📱 <phone> — Bluetooth"**. If the bridge peer isn't a paired device,
  the channel is closed. The core replaces an older bridge for the same phone, and a USB bridge wins
  over Bluetooth.
- **Threading**: CoreBluetooth runs on its own serial queue. Each channel's input and output streams are
  scheduled on a dedicated thread with its own run loop (`StreamPump` in FerryLogic), and every stream
  call happens on that thread. The core's `ByteSink.write` comes from a Tokio blocking worker. It hands
  the bytes to the pump thread and blocks until they're written, which gives backpressure to the radio.
  Bytes read go through an `AsyncStream` to `bridge.push`. EOF or an error ends the bridge, and the core
  ending the bridge closes the streams.
- **When it runs**: only while at least one phone is paired and the **Bluetooth** menu toggle is on
  (default). The first start asks for Bluetooth permission (`NSBluetoothAlwaysUsageDescription`). No
  entitlement is needed outside the sandbox. If Bluetooth can't run, the menu says **Bluetooth off** or
  **Allow Bluetooth for Ferry in System Settings**. The permission is tied to the code signature, so an
  ad-hoc build may ask again after a rebuild, the same as Downloads access.

Check it from another device: in nRF Connect, scan for a service UUID starting `f377e501`. Connect, and
read `ABDD3056-…` under service `F377E501-0000-…`; it should give 2 bytes. A Mac usually can't see its
own advert, so this needs a second machine or the phone.

### Phone screen and cursor crossing (Phase 4)

Contract: `docs/screen-protocol.md` (FSP1 over a QUIC stream from `FerryNode.openScreen`). Framing,
H.264 NAL splitting and the cursor math are in FerryLogic (`Fsp.swift`, `Cursor.swift`, tested); the
window is `Sources/Ferry/Screen.swift` and crossing is `Sources/Ferry/Crossing.swift`.

- **Mirror <phone>** (menu) opens a window and sends `START` (video, long side 1280, 8 Mbps). The phone asks
  for screen-sharing consent ("Start now") every time. The window shows that until the first frame. H.264
  Annex-B is split into NAL units, SPS/PPS become the `CMVideoFormatDescription`, and the slices go as
  AVCC sample buffers (display-immediately) into an `AVSampleBufferDisplayLayer`, which decodes them.
  The window keeps the aspect ratio from `INFO` and resizes on rotation. If decoding fails, or a frame
  arrives before a keyframe, the viewer drops frames and sends `KEYFRAME` (at most once a second).
- **Input from the window**: click/drag → `TOUCH` (normalized to the video rect, letterbox ignored),
  scroll → `SCROLL`, typing → `TEXT`, delete → `KEY 67`, return → `KEY 66`. Back / Home / Recents are
  title-bar buttons and **⌘[ / ⌘H / ⌘R**; **⌘W** closes. If `INFO` says control is off, the window
  subtitle reads "Turn on Ferry control on the phone to click and type".
- **END** shows as text with a **Reconnect** button: "Phone locked: unlock it and press Reconnect",
  "Allow screen sharing on the phone", "Stopped on the phone", and so on. Closing the window finishes the stream.
- **Share cursor with <phone>** (menu toggle, off by default) and **Cursor crosses at screen edge** →
  Left / Right (default Right). Push the pointer past that edge of the screen it's on (and no other
  display is beyond it). Ferry then uses the viewer's stream if a window is open, or opens a
  control-only one (`START` flags 0). It hides and freezes the Mac cursor and drives the phone's
  overlay cursor with the mouse deltas (`CURSOR` move/down/up). Wheel → `SCROLL` at the cursor, typing →
  `TEXT`/`KEY`. All of that input is swallowed on the Mac. Moving back past the phone's Mac-side edge,
  or pressing **Escape**, puts the pointer back at the Mac edge at the same height and sends `CURSOR` hide.
  Speed: the phone's long side spans the Mac screen's height. The first push only opens the stream; push
  again once it's up. While crossed, Ferry becomes the active app (it's the only way to hide the cursor
  with public API), and the previous app is reactivated when you come back.
- **Permissions**: crossing needs a `CGEventTap`, which needs **Accessibility**. If the tap can't be created,
  an alert says "Allow Ferry in System Settings → Privacy & Security → Accessibility" and the toggle turns
  off. There's no Info.plist usage string for Accessibility (macOS uses its own text), so the bundle gets
  no new keys. Like Downloads, the grant is tied to the code signature: an ad-hoc rebuild needs Ferry
  removed and re-added in that list. The Mac needs no Screen Recording permission (it only shows the phone's video).

## Test it

**With the CLI** (`cargo build --release -p ferry-cli`, then `F=target/release/ferry`):
1. `open mac/build/Ferry.app` → menu → **Pair a device…** → **Copy ticket**.
2. `$F --data-dir /tmp/ferryB --name PhoneB pair '<ticket>'`, and confirm the code in both places.
3. Receive: `$F --data-dir /tmp/ferryB send "<Mac name>" some-file`. Open the menu to watch % and MB/s.
   A notification appears when it's done (Allow Downloads access on the first run). Then check
   `xattr -p com.apple.quarantine ~/Downloads/some-file`.
4. Send: run `$F --data-dir /tmp/ferryB listen --dir /tmp/recvB`, then drop a file on the menu-bar icon,
   or right-click it in Finder → Services → **Send with Ferry**. If the Services entry is missing, run
   `/System/Library/CoreServices/pbs -update` or log out and back in.
5. Menu → **Open at Login**: the check mark follows System Settings → General → Login Items.

**Phase 2 with the CLI as a fake phone** (`E='$F --data-dir /tmp/ferryB --name PhoneB event "<Mac name>"'`):
- Clipboard in: `$E clip hello`, then `pbpaste` prints `hello`.
- Links in: `$E link https://example.com` opens the browser and posts a notification.
  `$E link file:///etc/passwd` does nothing.
- Clipboard out: run `$F --data-dir /tmp/ferryB --name PhoneB listen --dir /tmp/recvB`, turn on
  **Sync clipboard to phone**, then copy some text. The listener prints `event from …: Clipboard`. A clip just
  received from the phone isn't echoed back.
- Services: select a URL or some text in any app → Services → **Send link to phone**. The listener prints
  `Link` or `Clipboard`. **Send clipboard to phone** and **Ring phone / Stop ringing** print `Clipboard` / `Ring`.
- The CLI can't yet *send* Notification, NowPlaying or Battery (its `event` command only does
  clip/link/ring/stop-ring/play-pause/next/prev). Test those with the real phone, or add them to the CLI.
  The ⏮/⏯/⏭ items only show while the phone reports something playing.
- The CLI's port changes on every run. If an `event` times out, the Mac hasn't found it yet (mDNS); retry.

**With the phone over USB**:
1. Turn on USB debugging on the phone, plug it in and accept the "Allow USB debugging" prompt
   (`adb devices` shows it). Open Ferry on the phone.
2. Menu → **Pair phone over USB**. The phone shows a code and the Mac shows Match / Doesn't match.
3. The menu shows "📱 <phone> — USB". Drop a file on the icon to send it over the cable.

**Phase 4 with the phone** (Wi-Fi or USB; over Bluetooth the phone answers "Bluetooth is too slow for video"):
1. Menu → **Mirror <phone>**. Tap **Start now** on the phone. The phone's screen appears and the window takes its shape.
2. Turn on Ferry's Accessibility service on the phone, then **Reconnect** (the subtitle hint goes away).
   Click an app icon, drag to scroll a list, use the trackpad/wheel, and type into a text field (delete and return too).
   Try ⌘[ / ⌘H / ⌘R and the title-bar buttons. Rotate the phone: the window reshapes.
3. Lock the phone: the window says "Phone locked: unlock it and press Reconnect". Unlock it and press Reconnect.
   Decline the consent dialog: "Allow screen sharing on the phone".
4. Close the window: the phone's capture indicator and "Controlled by" notification go away.
5. Menu → **Share cursor with <phone>** (grant Accessibility the first time, then toggle it again). Push the
   pointer against the right edge twice: the Mac cursor disappears and the phone's cursor appears at the
   same height. Move, click and drag on the phone, scroll, and type into a field. Move back left past the phone's edge
   (or press Escape): the Mac cursor reappears at the right edge. Switch **Cursor crosses at screen edge** to
   Left and repeat on that side. With control off on the phone, the toggle turns off with "Turn on Ferry control on the phone".

## Later

- Mirroring isn't recorded or audio-forwarded, and only one window per phone is supported. Crossing uses one phone
  at a time and doesn't follow rotation mid-crossing (the cursor speed keeps the old size).

- Publishing the phone's now-playing to `MPNowPlayingInfoCenter` (media keys / Control Center). Skipped:
  it would take the media keys away from the Mac's own players. If added, it goes behind a toggle, off by default.

- **Share extension** (Share menu in Finder and other apps) needs an Xcode app-extension target, which
  this SwiftPM package can't build. It comes with the Xcode project.
- Universal build: arm64 only for now; see the comment in `scripts/build-apple.sh`.
