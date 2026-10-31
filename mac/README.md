# Ferry for Mac (Phases 1–2)

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

### USB is switched off (FFI bug)

`FerryNode.usbWatch` is a sync FFI call, and it runs `tokio::spawn` outside the Tokio runtime. The panic
poisons the core's USB lock, and after that every incoming session is dropped and `usbLinks()` traps. One
constant at the top of `Sources/Ferry/AppModel.swift` turns USB back on once the FFI is fixed:

```swift
let usbEnabled = false   // → true: usbWatch(nil) at startup, usbLinks() poll every 2 s, "Pair phone over USB" enabled
```

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

**With the phone over USB** (after `usbEnabled = true` and the core and Android USB work are in):
1. Turn on USB debugging on the phone, plug it in and accept the "Allow USB debugging" prompt
   (`adb devices` shows it). Open Ferry on the phone.
2. Menu → **Pair phone over USB**. The phone shows a code and the Mac shows Match / Doesn't match.
3. The menu shows "📱 <phone> — USB". Drop a file on the icon to send it over the cable.

## Later

- Publishing the phone's now-playing to `MPNowPlayingInfoCenter` (media keys / Control Center). Skipped:
  it would take the media keys away from the Mac's own players. If added, it goes behind a toggle, off by default.

- **Share extension** (Share menu in Finder and other apps) needs an Xcode app-extension target, which
  this SwiftPM package can't build. It comes with the Xcode project.
- Universal build: arm64 only for now; see the comment in `scripts/build-apple.sh`.
