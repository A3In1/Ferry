# Ferry for Mac (Phase 1)

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

### USB is switched off until the core lands

`core/src/usb.rs` is still `todo!()`, and `usbLinks()` would trap. One constant at the top of
`Sources/Ferry/AppModel.swift` turns USB on:

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

**With the phone over USB** (after `usbEnabled = true` and the core and Android USB work are in):
1. Turn on USB debugging on the phone, plug it in and accept the "Allow USB debugging" prompt
   (`adb devices` shows it). Open Ferry on the phone.
2. Menu → **Pair phone over USB**. The phone shows a code and the Mac shows Match / Doesn't match.
3. The menu shows "📱 <phone> — USB". Drop a file on the icon to send it over the cable.

## Later

- **Share extension** (Share menu in Finder and other apps) needs an Xcode app-extension target, which
  this SwiftPM package can't build. It comes with the Xcode project.
- Universal build: arm64 only for now; see the comment in `scripts/build-apple.sh`.
