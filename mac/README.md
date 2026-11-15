# Ferry for Mac (Phases 1–6)

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

## Updates

[Sparkle 2](https://sparkle-project.org) (2.10.0, pinned in `Package.swift`). The menu starts with
"Ferry <version>" and **Check for Updates…**; Ferry also checks once a day unless Settings → **Automatically
Check for Updates** is off. A background check that finds something shows "Update to Ferry x.y…" in the menu and
a notification instead of opening a window behind your apps.

- **Version**: `mac/VERSION` is the only place it lives; `bundle-mac.sh` puts it in both `CFBundleShortVersionString`
  and `CFBundleVersion` (what Sparkle compares).
- **Feed**: `FERRY_FEED_URL=https://…/appcast.xml scripts/bundle-mac.sh` bakes it in (must be https). Without it
  (the default) `SUFeedURL` is empty and Check for Updates… says "Updates aren't set up for this build yet".
- **Hosting (planned)**: zips as GitHub Release assets (`v<version>/Ferry-<version>.zip`), `appcast.xml` committed at
  the repo root and read via `raw.githubusercontent.com` (public repo), or GitHub Pages via `FERRY_FEED_URL`.

**Signing key.** Updates are EdDSA-signed; the app ships the public key (`PUBLIC_ED_KEY` in `bundle-mac.sh`).
The private key was made with Sparkle's `generate_keys --account ferry` and lives only in this Mac's **login
Keychain** ("Private key for signing Sparkle updates", account `ferry`). Never commit it. **Back it up**: if it's
lost, every installed copy rejects all future updates and users have to download a new build by hand.

```sh
cd mac && swift package resolve
.build/artifacts/sparkle/Sparkle/bin/generate_keys --account ferry -x ~/ferry-sparkle-key.txt  # export, then store
#   it in a password manager and delete the file
.build/artifacts/sparkle/Sparkle/bin/generate_keys --account ferry -f ferry-sparkle-key.txt    # restore on a new Mac
```

**Cutting a release** (builds and signs locally; uploads nothing):

```sh
FERRY_GITHUB_REPO=owner/Ferry scripts/release-mac.sh 0.2.0 notes.md   # notes: .md, .txt or .html
```

It writes `mac/VERSION`, runs `bundle-mac.sh` with the feed URL, zips the app (`ditto -c -k --keepParent`), signs
the zip with `sign_update` (macOS asks once to let it use the Keychain key: **Always Allow**), and adds the item,
newest first, to `mac/release/appcast.xml` (git-ignored, along with the zips). It refuses if the Keychain key
doesn't match the shipped public key. Then: create GitHub release `v0.2.0` with `mac/release/Ferry-0.2.0.zip`, copy
`mac/release/appcast.xml` to `appcast.xml` at the repo root and push, and commit `mac/VERSION`. Keep
`mac/release/appcast.xml` between releases (or copy the published one back first) so the feed keeps its history.
`FERRY_FEED_URL` / `FERRY_DOWNLOAD_URL` override the GitHub defaults; `FERRY_ED_KEY_FILE` signs from an exported key.

**Limits without a Developer ID.** The build is ad-hoc signed and not notarized, so the first copy someone
downloads in a browser is blocked by Gatekeeper ("Apple could not verify…"): they allow it once in System Settings →
Privacy & Security → **Open Anyway**. Updates Sparkle installs don't hit this (it handles quarantine), but each
update is a new binary, so macOS asks again for Downloads access, like any rebuild (see above). Ad-hoc signing
also means the hardened runtime is off (with it, the app can't load the ad-hoc Sparkle.framework). With a
Developer ID later: set `FERRY_SIGN_IDENTITY="Developer ID Application: …"` (which turns the hardened runtime on),
notarize and staple the zip before `sign_update`; the EdDSA key and feed stay the same.

**Testing an update end to end** (on a Mac or account not running your everyday Ferry, since two copies clash):
build 0.1.1 with `FERRY_FEED_URL=https://<you>.github.io/test/appcast.xml` (any https host you control) via
`release-mac.sh`, install that `Ferry.app` in /Applications and open it; release 0.1.2 the same way and publish its
zip and appcast; then Ferry menu → Check for Updates… should offer 0.1.2, install it and relaunch, and the menu
header reads "Ferry 0.1.2".

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
- **Menu layout**: status first (per phone: a section header with its name, "Connected over USB / Bluetooth /
  Wi-Fi", battery, a call with **Hang Up**, now playing with **Previous Track / Pause / Next Track**), then
  **Recent Transfers**. Then the phone's actions (**Messages** ⌘2, **Browse Files** ⌘3,
  **Send Files…** ⌘O, **Send Clipboard**, **Share Wi-Fi…**, **Share Cursor**, **Ring Phone / Stop Ringing**,
  **Do Not Disturb**); with several phones, one submenu per phone. Then **Pair Your Phone…** (⌘N; **Pair
  Another Phone…** once one is paired) and **Pair over USB…**, **Settings** (Sync Clipboard, Use Bluetooth,
  Cursor Crosses At, Require Mac Password for Share Cursor, Set Up Focus Sync…, Open at Login, Automatically Check for Updates, Forget <phone>…), **About Ferry** and **Quit Ferry**.
  "Wi-Fi" is inferred from the phone having sent something in the last 10 minutes; the core has no live
  connection state. Stopping Ferry on the phone tells the Mac, which shows "Not connected" at once. With no phone paired, the top of the menu says so and explains what pairing gives you.
- **Pair Your Phone…** shows a QR, the steps on the phone, and under "Can't scan the code?" a **Copy Pairing
  Link** button for the `ferry://pair?...` ticket. When the other side connects, an alert shows the 6-digit
  code: Match / Doesn't Match. Match closes the Pair window and posts "Paired with <phone>".
- **Pair over USB…** pushes a ticket to the plugged-in phone over adb. The phone shows the code and
  the same Match alert appears here. If adb isn't installed, the item is disabled and says why.
- **Forget <phone>…** (Settings) asks first, then closes that phone's windows and removes its notifications.
- **Open at Login** (`SMAppService.mainApp`). If macOS needs approval, System Settings → Login Items opens.

### Everyday sync (Phase 2)

Sent to every paired phone (usually one) in background tasks. Background sync failures are only logged;
things you click (ring, send clipboard/link, a notification reply) show an alert if no phone got them.

- **Phone notifications** show as Mac notifications ("App: Title", grouped by app). The same phone key
  replaces the banner, and removing it on the phone removes it here. Phone buttons become notification
  buttons, reply buttons get a text field, and dismissing it here dismisses it on the phone.
- **Clipboard**: text from the phone goes straight onto the Mac clipboard. Settings → **Sync Clipboard**
  (off by default) sends what you copy on the Mac (plain text up to 1 MB). Items that password managers mark
  as concealed or transient are never sent, and nothing the phone just sent is sent back.
  **Send Clipboard** sends once, even with sync off.
- **Now playing**: while the phone plays media, the menu shows "Title — Artist" (the app below it) with
  Previous Track / Pause (or Play) / Next Track.
- **Battery**: "Battery 42%" (", charging" while charging).
- **Ring Phone / Stop Ringing** (find my phone).
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
  `ble:` serial. The menu shows **"Connected over Bluetooth"** under the phone's name. If the bridge peer isn't a paired device,
  the channel is closed. The core replaces an older bridge for the same phone, and a USB bridge wins
  over Bluetooth.
- **Threading**: CoreBluetooth runs on its own serial queue. Each channel's input and output streams are
  scheduled on a dedicated thread with its own run loop (`StreamPump` in FerryLogic), and every stream
  call happens on that thread. The core's `ByteSink.write` comes from a Tokio blocking worker. It hands
  the bytes to the pump thread and blocks until they're written, which gives backpressure to the radio.
  Bytes read go through an `AsyncStream` to `bridge.push`. EOF or an error ends the bridge, and the core
  ending the bridge closes the streams.
- **When it runs**: only while at least one phone is paired and Settings → **Use Bluetooth** is on
  (default). The first start asks for Bluetooth permission (`NSBluetoothAlwaysUsageDescription`). No
  entitlement is needed outside the sandbox. If Bluetooth can't run, the menu says **Bluetooth off** or
  **Allow Bluetooth for Ferry in System Settings**. The permission is tied to the code signature, so an
  ad-hoc build may ask again after a rebuild, the same as Downloads access.

Check it from another device: in nRF Connect, scan for a service UUID starting `f377e501`. Connect, and
read `ABDD3056-…` under service `F377E501-0000-…`; it should give 2 bytes. A Mac usually can't see its
own advert, so this needs a second machine or the phone.

### Cursor crossing (Phase 4)

Contract: `docs/screen-protocol.md` (FSP1 over a QUIC stream from `FerryNode.openScreen`). Framing
and the cursor math are in FerryLogic (`Fsp.swift`, `Cursor.swift`, tested); the stream is
`Sources/Ferry/Screen.swift` and crossing is `Sources/Ferry/Crossing.swift`.

- **Share Cursor** (menu toggle, off by default) and Settings → **Cursor Crosses At** →
  Left / Right (default Right). Push the pointer past that edge of the screen it's on (and no other
  display is beyond it). Ferry then opens a control stream (`START`). It hides and freezes the Mac cursor and drives the phone's
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
  removed and re-added in that list.

### Messages and calls (Phase 5)

The phone is the source of truth: the Mac asks (`sendEvent`) and shows what comes back, and keeps nothing
on disk. The phone only answers when **Messages** / **Calls** are turned on in Ferry there. The window is
`Sources/Ferry/Messages.swift` (SwiftUI in an `NSWindow`). Code detection and number matching are in
FerryLogic (`Texts.swift`, tested).

- **Messages** (menu) opens a window and sends `SmsThreadsRequest` + `CallLogRequest`. **Refresh**
  (toolbar) asks again. The list shows name or number, snippet, date, and a dot for unread. If nothing comes back
  within 5 s, the sidebar says "Turn on Messages in Ferry on your phone" (or Calls, on the Calls tab).
- **Search** (the sidebar box, or **⌘F**) filters the list on the Mac: names, numbers and snippets, plus the texts of
  threads already opened, or names and numbers on the Calls tab. It only sees what the phone has sent (the latest 50
  conversations and calls). A typed number with no result offers **Call** and **Message**, and each call has both buttons.
- **The list stays current by itself.** The phone watches its SMS provider and sends the new list when a text comes or goes,
  including ones sent from its own messaging app. The Mac re-reads the open conversation then, and when the window comes back to the front.
- **Calling**: the phone button on a conversation, a call, or next to the number in a new message sends `CallDial`. The phone places the
  call through Android (needs the Calls switch).
- **Selecting a thread** sends `SmsMessagesRequest` and shows the chat: phone's messages on the left, yours on the
  right. Type and press **Send** or Return to send `SmsSend` to that thread's address. Nothing is sent any
  other way. Your text shows as "sending…" until the phone echoes it back as `SmsNew`. **New message**
  (toolbar) takes a number and text. If that number already has a thread, it goes there.
- **`SmsNew`** updates the open chat and moves the thread to the top of the list. An incoming text posts a
  notification (sender, body) with **Reply**, which sends `SmsSend`. Clicking it opens the thread. No
  notification is posted while that thread is open in the front window. If the text has a one-time code
  (`oneTimeCode(in:)`: 4–8 digits, or 123-456, near code/OTP/verification/password/PIN, skipping phone numbers,
  amounts, dates and masked card numbers), the title starts with the code and there's a **Copy code**
  button. A copied code is never synced back to the phone.
- **Calls**: on `Call(RINGING)` a time-sensitive notification "<name or number> is calling" with
  **Answer** / **Decline** (→ `CallCommand`). It's removed on IDLE or ACTIVE. While a call is active, the
  menu shows "📞 On a call with X" and **Hang up** (`CallCommand DECLINE`). **Audio stays on the phone**:
  Answer picks up there, and you talk on the phone or its headset. The Mac is only a remote control.
- **Recent calls**: the **Calls** tab in the Messages window (`CallLogRequest`, refreshed after each call ends
  while the window is open). Missed calls are red with a missed-call icon and "Missed".
- The window handles ⌘C/⌘V/⌘X/⌘A/⌘Z/⌘W itself, because a menu-bar app has no Edit menu.
- **Time-sensitive** needs the `com.apple.developer.usernotifications.time-sensitive` entitlement to break through
  Focus. The ad-hoc bundle doesn't have it, so macOS shows the call as a normal notification.

### Phone files, Wi-Fi sharing, Do Not Disturb (Phase 6)

The browse window is `Sources/Ferry/Files.swift` (SwiftUI in an `NSWindow`, like Messages). Wi-Fi and Do Not
Disturb are in `Sources/Ferry/WifiFocus.swift`. Path joining, breadcrumbs and sort order are in FerryLogic
(`PhonePath.swift`, tested). Phone paths are always relative to the phone's shared storage root (`""` = root).
A path or name with `.` / `..` or a `/` inside a name is refused, never sent.

- **Browse Files** (menu) opens a window and sends `FsListRequest("")`. Path bar: the phone, then one
  breadcrumb per folder (click one to jump there). The list shows name, size and date, folders first. Double-click
  a folder to open it (a new `FsListRequest`). Double-click a file to download it. **Back** / **Up** / **Refresh**
  are in the toolbar. An answer for a folder you've already left is ignored.
- **Download** (or **⌘D**) sends `FsGet` with the selected files and folders. The bottom line says
  "Requested …, arriving in Downloads…". The phone sends them over the normal file channel, so they land in
  `~/Downloads` with the usual progress, quarantine and notification.
- **Upload…**, or drop Mac files anywhere on the window (it gets a blue outline), sends them with the normal
  send-files path. The bottom line says they land in Download/Ferry on the phone.
- **Errors**: a non-empty `FsList.error` is shown in the list (e.g. "Allow file access in Ferry on the phone").
  If nothing comes back within 5 s: "Turn on Files in Ferry on your phone".
- **Share Wi-Fi…** (menu): reads the current network name with CoreWLAN. macOS only gives that to
  apps with **Location** access. If it's missing, an alert explains why and then asks
  (`NSLocationWhenInUseUsageDescription`), or opens System Settings → Location Services if it was denied. Then
  "Send Wi-Fi “SSID” and its password to <phone>?". On Send, `security find-generic-password -D "AirPort network
  password" -a <SSID> -w` reads the password, and macOS shows its own keychain/admin prompt. If that fails (cancelled,
  or the password isn't in the System keychain), Ferry offers to send it as an open network or cancel. Then
  `WifiNetwork` goes to the phone, which asks before adding it. The password is never logged.
- **Do Not Disturb**: when the phone reports `Dnd(on)`, Ferry runs your Shortcut **Ferry Focus On** or
  **Ferry Focus Off** (`/usr/bin/shortcuts run`), if `shortcuts list` has it. If not, a one-time alert explains how to make
  them (Shortcuts app → new shortcut → **Set Focus** action → Do Not Disturb On / Off). **Do Not Disturb**
  (menu toggle) sends `Dnd(on)` to the phone. Its check mark only follows what the phone last reported, so it moves
  once the phone confirms.

## Test it

**With the CLI** (`cargo build --release -p ferry-cli`, then `F=target/release/ferry`):
1. `open mac/build/Ferry.app` → menu → **Pair Your Phone…** → Can't scan the code? → **Copy Pairing Link**.
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
  Settings → **Sync Clipboard**, then copy some text. The listener prints `event from …: Clipboard`. A clip just
  received from the phone isn't echoed back.
- Services: select a URL or some text in any app → Services → **Send link to phone**. The listener prints
  `Link` or `Clipboard`. **Send Clipboard** and **Ring Phone / Stop Ringing** print `Clipboard` / `Ring`.
- The CLI can't yet *send* Notification, NowPlaying or Battery (its `event` command only does
  clip/link/ring/stop-ring/play-pause/next/prev). Test those with the real phone, or add them to the CLI.
  The ⏮/⏯/⏭ items only show while the phone reports something playing.
- The CLI's port changes on every run. If an `event` times out, the Mac hasn't found it yet (mDNS); retry.

**With the phone over USB**:
1. Turn on USB debugging on the phone, plug it in and accept the "Allow USB debugging" prompt
   (`adb devices` shows it). Open Ferry on the phone.
2. Menu → **Pair over USB…**. The phone shows a code and the Mac shows Match / Doesn't match.
3. The menu shows "Connected over USB" under the phone's name. Drop a file on the icon to send it over the cable.

**Phase 4 with the phone** (Wi-Fi, USB or Bluetooth; turn on Ferry's Accessibility service on the phone first):
1. Menu → **Share Cursor** (grant Accessibility the first time, then toggle it again). Push the
   pointer against the right edge twice: the Mac cursor disappears and the phone's cursor appears at the
   same height. Move, click and drag on the phone, scroll, and type into a field. Move back left past the phone's edge
   (or press Escape): the Mac cursor reappears at the right edge. Switch **Cursor Crosses At** to
   Left and repeat on that side. With control off on the phone, the toggle turns off with "Turn on Ferry control on the phone".

**Phase 5 with the phone** (any link; turn on **Messages** and **Calls** in Ferry on the phone first):
1. Menu → **Messages**. The thread list fills within a second or two (unread threads have a dot), and the
   **Calls** tab shows recent calls with missed ones in red. Turn the Messages grant off on the phone, then
   **Refresh**: after 5 s, "Turn on Messages in Ferry on your phone". Turn it back on and Refresh.
2. Click a thread: the chat loads, oldest at the top, yours on the right. Type a reply and press Return:
   it shows "sending…", then the phone sends it and the bubble turns normal. Check it on the phone.
3. **New message** → a number (e.g. your own) + text → Send. Once the phone echoes it, the new thread is selected.
4. Close the window (or switch to another app) and text the phone from another phone: a notification with
   the sender and text. **Reply** from the notification and check the reply arrives. Click a notification: it
   opens that thread.
5. Codes: get a real OTP (a bank or app login), or text the phone "Your verification code is 482913". The
   notification title starts with 482913, and **Copy code** puts it on the clipboard (`pbpaste`).
6. Call the phone: "<name> is calling" with Answer / Decline. **Decline** rejects it. Call again and press
   **Answer**: the phone picks up (talk on the phone), the notification goes away, and the menu shows
   "📞 On a call with <name>" with **Hang up**. Hang up from the menu. The line goes away and the
   Calls tab shows the call. Let a call ring out: it shows as Missed.
- Without a phone, the CLI's `listen` prints what the Mac sends (`SmsThreadsRequest`, `SmsSend`, `CallCommand`, …).
  The CLI can't send Phase 5 events yet, so it can only test the Mac → phone direction.

**Phase 6 with the phone** (turn on **Files** in Ferry on the phone first):
1. Menu → **Browse Files**. The root of the phone's storage appears, folders first. Double-click `DCIM`, then
   `Camera`: the path bar reads <phone> › DCIM › Camera. Click **Up** and **Back**, and click a breadcrumb.
2. Select a photo or two and press **⌘D**: "Requested …, arriving in Downloads…", then the usual incoming
   transfer in the menu and a "Received" notification. Check `~/Downloads`.
3. Drop a Mac file on the window (or **Upload…**). The menu shows "Sending…". Check Download/Ferry on the phone.
4. Turn file access off on the phone and **Refresh**: its error shows in the list. Turn **Files** off in Ferry
   on the phone and Refresh: after 5 s, "Turn on Files in Ferry on your phone".
5. Menu → **Share Wi-Fi…**. The first time: the Location alert, then the macOS prompt; Allow, then choose it
   again. Confirm "Send Wi-Fi “…” …?". macOS asks for your admin password or keychain access (Allow). The phone offers to
   add the network. Repeat and cancel the macOS prompt: Ferry offers "Send without password". Check Console
   (`log stream --predicate 'process == "Ferry"'`): the password never shows.
6. Turn Do Not Disturb on on the phone. With no Ferry Focus Shortcuts: the one-time hint. In Shortcuts, create
   **Ferry Focus On** (Set Focus → Do Not Disturb → On) and **Ferry Focus Off** (Set Focus → turn off). Toggle DND on the
   phone: the Mac's Focus follows, and **Do Not Disturb** in the menu shows its state. Toggle it from the
   menu: the phone's DND changes and the check mark follows.
- Without a phone, the CLI's `listen` prints what the Mac sends (`FsListRequest`, `FsGet`, `Dnd`, `WifiNetwork`).
  It prints `WifiNetwork` with the password, so only use a test network. The CLI can't send Phase 6 events yet, so
  the list stays empty and turns into "Turn on Files…" after 5 s.

## Trial: talk on the Mac

Experimental: the Mac as a Bluetooth **hands-free unit** (HFP, like a car kit or headset) for the phone, so
call audio uses the Mac's mic and speakers. It uses IOBluetooth's `IOBluetoothHandsFreeDevice` (macOS 10.7+,
still in the SDK, not deprecated). Whether it still works end to end on current macOS is **unknown**:
`hfp-probe` is there to find out. The code is `Sources/FerryHandsFree/HandsFree.swift` (shared by the app and the
probe); the probe is `Tools/hfp-probe/main.swift`. Ferry's own link (Wi-Fi/BLE) isn't involved: hands-free
needs the phone paired with the Mac over **Classic Bluetooth**.

**Pair first** (once): on the phone, open Settings → Connected devices → **Pair new device**. On the Mac, open
System Settings → **Bluetooth**, find the phone under Nearby Devices and click **Connect**. Confirm the same
code on both. On the phone, open the Mac's entry (⚙︎) and allow **Phone calls** if it's listed (it may only
show up after the first `connect`).

**Run the probe** from Terminal (not over ssh): the first Bluetooth use asks to allow **Terminal** in
Privacy & Security → Bluetooth, and the first call audio asks for the **Microphone**. The binary carries a
usage string in its `__info_plist` section, so macOS doesn't kill it for lacking one.

```sh
cd mac
swift run hfp-probe list                    # paired Classic devices (hfpGateway=true?) and Core Audio devices
swift run hfp-probe "Pixel 8" connect       # name (or part of it) or address aa-bb-cc-dd-ee-ff; then a prompt
```

At the prompt: `connect`, `disconnect`, `answer`, `hangup`, `audio-to-mac`, `audio-to-phone`, `sco` (open
audio without asking the phone to move the call), `bridge on|off`, `devices`, `status`, `quit`. Every delegate
callback is logged with a timestamp (`connected status=0`, `indicator call=1`, `RING #1`, `SCO audio opened
status=0`, signal/battery indicators, unhandled AT result codes).

What to try, and what each result means:

1. `connect` → `connected status=0` and a burst of indicators: the service-level connection works. A non-zero
   status (or nothing for ~20 s) means macOS or the phone refused. Check the phone's Bluetooth settings for
   the Mac and its Phone calls switch.
2. Call the phone. `RING #1` / `incoming call from …` / `indicator callsetup=1` should show. Type `answer`:
   the call picks up and `SCO audio opened status=0` follows. Then `devices`: is there a Bluetooth device
   named like the phone with `in=1 out=1`? The probe copies its audio to the Mac's speakers and the Mac's mic
   to it (`bridge: caller → …` in the log). Talk both ways. Use headphones: there's no echo cancellation.
   If the caller sounds doubled, macOS routes the audio itself: `bridge off`, then `audio-to-phone` and
   `audio-to-mac` to retry without Ferry's copy.
3. `audio-to-phone` → `SCO audio closed`, audio back on the phone; `audio-to-mac` → it comes back. `hangup` ends it.

If `SCO audio opened` comes but no Core Audio device appears, the probe prints the device list and "This macOS
no longer supports hands-free calls". The probe never records or saves audio.

**In the app**: Settings → **Talk on the Mac (trial)** (default off; `Sources/Ferry/TalkOnMac.swift`). While on,
Ferry looks for the Ferry phone among the Mac's paired Classic Bluetooth devices (by name, else the only
hands-free gateway) and keeps a hands-free connection, retrying once a minute. Problems show as a line in
the menu: "Pair your phone with this Mac in Bluetooth settings first.", "Couldn't connect to … for calls. …turn
on Phone calls.", or "This macOS no longer supports hands-free calls." (no `IOBluetoothHandsFreeDevice`, or call
audio arrived but macOS didn't offer it as a Core Audio device).

- With hands-free connected, the call notification says "Answer on Mac to talk here." and has **Answer on
  Mac** (`acceptCall`: the phone sends the audio here), **Answer on Phone** (`acceptCallOnPhone`) and
  **Decline** (Ferry's `CallCommand`). Without it, the notification is the Phase 5 one; **Answer on Mac** on a
  stale notification falls back to Ferry's Answer and says why.
- While a call is active, the menu's "On a call with X" adds **Move Audio to Mac** / **Move Audio to Phone**
  (`transferAudioToComputer` / `transferAudioToPhone`) next to **Hang Up**.
- When the call's audio (SCO) opens, Ferry finds the phone's Bluetooth Core Audio device and copies its audio
  to the Mac's default speakers and the default mic to it (two `AVAudioEngine`s, nothing written anywhere;
  the built-in mic/speakers if macOS made the phone the default). The first time, macOS asks for the
  **Microphone** (`NSMicrophoneUsageDescription` in `bundle-mac.sh`). With a real signing identity
  (hardened runtime) the bundle would also need the `com.apple.security.device.audio-input` entitlement,
  which `bundle-mac.sh` doesn't add yet.
- Side effect: like a car kit, while hands-free is connected Android may send **every** call's audio to the
  Mac, including calls answered on the phone. Use **Move Audio to Phone**, or turn the setting off.
- No echo cancellation: use headphones. No call recording.

**Test it in the app**: pair over Classic Bluetooth as above, then `scripts/bundle-mac.sh`, open Ferry, turn on
Calls in Ferry on the phone and Settings → Talk on the Mac (trial) on the Mac. The menu shouldn't show a
problem line after a few seconds (Console: `log stream --predicate 'process == "Ferry"' | grep hands-free`
shows each callback). Call the phone: **Answer on Mac**; talk both ways. Menu → **Move Audio to Phone**,
then **Move Audio to Mac**, then **Hang Up**. Unpair the phone in Bluetooth settings: the menu says to pair it.

## Later

- Phase 6: one folder listing at a time, no paging or thumbnails. Download skips folders. Two quick Do Not Disturb changes can
  run their Shortcuts out of order. Ferry doesn't watch the Mac's own Focus, so changing Focus on the Mac doesn't
  reach the phone.

- Phase 5: read state isn't sent back to the phone (opening a thread clears its dot on the Mac only), a send
  that never echoes stays "sending…", and threads/messages aren't paged (the phone's default limits).

- Crossing uses one phone
  at a time and doesn't follow rotation mid-crossing (the cursor speed keeps the old size).

- Publishing the phone's now-playing to `MPNowPlayingInfoCenter` (media keys / Control Center). Skipped:
  it would take the media keys away from the Mac's own players. If added, it goes behind a toggle, off by default.

- **Share extension** (Share menu in Finder and other apps) needs an Xcode app-extension target, which
  this SwiftPM package can't build. It comes with the Xcode project.
- Universal build: arm64 only for now; see the comment in `scripts/build-apple.sh`.

### Mac Focus → phone
Ferry handles `ferry://focus?on=1` / `ferry://focus?on=0`. In Shortcuts → Automation, add a Focus
automation for Do Not Disturb: when turning on, Open URL `ferry://focus?on=1`; when turning off,
`ferry://focus?on=0` (turn off "Ask Before Running"). The phone needs Do Not Disturb turned on in Ferry.
Menu → Settings → **Set Up Focus Sync…** shows these steps. A change that already matches a phone is not
sent to it, so the phone → Mac "Ferry Focus On/Off" Shortcuts can't loop.
