# Ferry for Android (Phase 1: pairing + file sharing · Phase 2: everyday sync · Phase 3: Bluetooth · Phase 4: screen · Phase 5: messages + calls · Phase 6: files, Wi-Fi, Do Not Disturb)

Compose app over the shared Rust core (`ffi/`, UniFFI → Kotlin package `dev.ferry.core`, via JNA).
Application id `dev.ferry.android` in both flavors (`play`, `full`). They differ only in Phase 6 file browsing:
`full` declares All files access (`src/full/AndroidManifest.xml`, `Flavor.ALL_FILES`), `play` doesn't.

## Build

Needs the Android SDK (`/opt/homebrew/share/android-commandlinetools`, platform 37, build-tools 37.0.0,
NDK 30.0.16248370), JDK 21, `cargo-ndk`, and `rustup target add aarch64-linux-android`.

```sh
scripts/build-android.sh            # Rust → app/src/main/jniLibs/arm64-v8a/libferry_ffi.so + Kotlin bindings
scripts/build-android.sh --x86_64   # also the emulator ABI
cd android
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
./gradlew :app:assembleFullDebug :app:assemblePlayDebug
./gradlew :app:testFullDebugUnitTest :app:lintFullDebug
```

Re-run `build-android.sh` after any change in `core/` or `ffi/`. It passes `uniffi-kotlin.toml` to
the bindings generator: that renames the Rust `close()` on `StreamBridge`/`ByteSink` to `finish()` in
Kotlin, because it clashes with the `AutoCloseable.close()` UniFFI adds to every object. The `.so`, the generated
`app/src/main/java/dev/ferry/core/` and `local.properties` are git-ignored
(`local.properties` holds `sdk.dir=...`; or set `ANDROID_HOME`).

## Install over USB

Enable Developer options → USB debugging on the phone, plug it in, accept the prompt, then:

```sh
adb install -r app/build/outputs/apk/full/debug/app-full-debug.apk
adb uninstall dev.ferry.android          # removes the app and its identity/trust data
adb logcat -s Ferry                      # app logs (tag "Ferry")
```

On first launch allow notifications (Android 13+): the ongoing "Ready to receive" / "Ferry is
connected" notification, received-file notifications and pairing prompts need it. If you denied it:
Settings → Apps → Ferry → Notifications. The camera is asked for only when you tap **Scan QR**.

## Test with the Mac

Phone and Mac on the same Wi-Fi (NsdManager finds the Mac's `_ferry._udp` advert).

1. Mac: Ferry.app → **Pair a device…** (or `target/release/ferry pair-show`).
2. Phone: **Scan QR**, or **Paste ticket** with the `ferry://pair?...` text, or from the Mac:
   `adb shell am start -n dev.ferry.android/.MainActivity -a android.intent.action.VIEW -d '<ticket>'`
   (Ferry.app / `ferry usb` do this for you over USB).
3. Both screens show a 6-digit code: tap **Match** on both. The Mac appears under Paired Macs.
4. Phone → Mac: **Send files**, or Share from any app → **Send to Mac**. Lands in the Mac's `~/Downloads`.
5. Mac → Phone: Paired devices → phone → **Send files…**. Lands in **Downloads/Ferry** on the phone.

USB: the app starts the core's USB bridge listener (127.0.0.1:47100); the Mac side
(`ferry usb` / Ferry.app with adb) forwards to it. Status shows "USB bridge listening" when it started.

**Stop** in the notification shuts the node down; opening the app starts it again.

## Phase 2: everyday sync

Everything below is **off by default**. Ferry → **Share with your Mac** has one switch per feature
(Notifications to Mac, Media controls, Clipboard sync, Battery status, Allow Mac to ring phone, Links)
and **Turn all off**. Nothing is sent, and incoming events are ignored, unless that switch is on. The
ongoing notification always lists what is shared ("Sharing: …"). Switches are global for now, not per Mac.

| Feature | Enable | Notes |
|---|---|---|
| Notifications to Mac | switch + **Allow notification access** (opens the system list; turn Ferry on) | Skips Ferry's own, ongoing/foreground-service, group summaries, local-only and empty notifications. Mac can dismiss, press buttons, and reply (RemoteInput). Android 15 hides OTP contents from listeners like ours. |
| Media controls | switch + notification access (same permission) | Sends the playing app's title/artist/state (≤ 1 update/s); play/pause, next, previous from the Mac. |
| Clipboard sync | switch | Mac → phone is automatic. Phone → Mac needs a tap (Android blocks background clipboard reads): the **Send clipboard** Quick Settings tile (edit the QS panel to add it), the **Send clipboard** button on the ongoing notification, or Share text → **Send to Mac**. |
| Battery status | switch | Sent on level/charging change, when a Mac shows up, and every 5 min. |
| Allow Mac to ring phone | switch | Default alarm sound at max alarm volume (rings through silent mode), vibration, a Stop notification; stops after 60 s. The alarm volume is restored after. |
| Links | switch | A link from the Mac (http/https only) shows a "Link from <Mac>" notification; tap to open (Android blocks opening it from the background). Share a lone URL → **Send to Mac** opens it on the Mac. |

"Restricted settings": Android 13+ greys out notification access for apps sideloaded from a browser
or file manager. Apps installed with `adb install` (or from a store) are not affected. If it is
greyed out: Settings → Apps → Ferry → ⋮ → Allow restricted settings.

### Test with the Mac CLI

Pair the phone with a CLI peer once (`ferry --data-dir /tmp/ferryMac usb --dir /tmp/r` over USB),
turn on the switches, then keep that command running: it prints every event the phone sends
(notifications, removals, now-playing, battery, clipboard, links). From a second terminal:

```sh
F="target/release/ferry --data-dir /tmp/ferryMac"
$F event V2222 ring              # phone rings (also on silent); `stop-ring` stops it
$F event V2222 stop-ring
$F event V2222 clip 'hello from mac'   # phone clipboard = that text (paste anywhere)
$F event V2222 link https://example.com   # "Link from …" notification; tap opens it
$F event V2222 link javascript:alert(1)   # ignored
$F event V2222 play-pause        # with music playing on the phone; also `next`, `prev`
```

- Notifications: send yourself a message (or any app notification) → the `usb` terminal prints it;
  swipe it away → prints the removal. Dismiss/reply from the Mac side needs the Mac app.
- Media: start playback in any player → a now-playing event within ~1 s; pause → another.
- Battery: plug/unplug the charger → a battery event.
- Clipboard phone → Mac: copy text, pull down Quick Settings → **Send clipboard** → printed.
- Links phone → Mac: share a URL from the browser → **Send to Mac** → printed as a link.
- Turn a switch off and repeat: nothing is sent, and `ring`/`clip`/`link` from the Mac do nothing.

## Phase 3: Bluetooth (Android 12+ / API 31+; hidden on older phones)

The Mac is a BLE peripheral. It advertises one rotating 128-bit service UUID (`presenceBleUuid()`:
`f377e501` + nonce + tag; only paired phones can match it) and serves GATT service
`f377e501-0000-1000-8000-00805f9b34fb`, which holds Apple's L2CAP PSM characteristic
`ABDD3056-28FA-441D-A470-55A75A52553A` (UInt16, little-endian). The phone is the central (`Ble.kt`).

- **Presence scan:** `startScan(filters, settings, PendingIntent)` with a service-UUID filter on the
  first 4 bytes (mask `ffffffff-0000-…`), `SCAN_MODE_LOW_POWER`, delivered to `BleScanReceiver`, so it
  works with the process dead. It runs while Bluetooth is allowed and a Mac is paired, and is re-registered
  when Bluetooth turns back on. **Stop** in the notification also stops it until the app is opened again.
  Not restarted after a reboot (open the app once).
- **On a hit:** match the UUID with the core. If it's a paired Mac, first `checkIn` (Wi-Fi/USB). Only if
  that fails, open the BLE bridge. Per Mac: no new attempt for 60 s after a bridge attempt, and none for
  5 min after a successful check-in. Nothing is attempted while a bridge is up.
- **Bridge:** `connectGatt(TRANSPORT_LE)` → MTU 517 → discover → read PSM → connection priority HIGH →
  `createInsecureL2capChannel(psm).connect()` → `openStreamBridge("ble:<addr>")`. The core's session runs
  over it unchanged (QUIC/TLS supplies the security, so the L2CAP channel is unencrypted and no bonding is needed).
  The bridged peer id must match the Mac we scanned, otherwise it is closed. GATT stays connected while the
  bridge is up (it's idle, and keeps the LE link up on stacks that tie the link to a GATT client).
  Status shows **Connected over Bluetooth** in the app and in the ongoing notification.
- **Slow:** expect 10–40 KB/s. Sending files while Bluetooth is the only link shows a toast suggesting Wi-Fi.

Permissions: `BLUETOOTH_SCAN` (`neverForLocation`, so no location permission) and `BLUETOOTH_CONNECT`,
asked by **Allow Bluetooth** in the Bluetooth section (shown once a Mac is paired).

**Allow Ferry in the background** (shown when a Mac is paired and there's no companion association yet)
runs the Companion Device Manager chooser, filtered to Ferry Macs. Pick your Mac. The association only lets
Ferry start its foreground service from a background scan hit (Android 12+ blocks that otherwise). It does
**not** give the Android 15 OTP-notification exemption, and Ferry doesn't use CDM presence (it breaks when
the Mac's Bluetooth address rotates). The chooser needs Bluetooth **and** Location switched on, and the Mac
advertising nearby. Undo it in Settings → Connected devices / Apps → Special access → Companion devices
(wording varies by OEM).

### Test (needs the Mac app's Phase 3 Bluetooth side)

1. Pair as usual. Ferry → Bluetooth → **Allow Bluetooth**, then **Allow Ferry in the background** → pick the Mac.
2. Turn Wi-Fi **off** on the phone and unplug USB. Leave the Mac within a few metres.
3. Within a scan cycle (seconds, up to a few minutes with the screen off on some OEMs), the app/notification
   shows **Connected over Bluetooth**. Logs: `adb logcat -s FerryBle Ferry` (once you plug back in, or over adb Wi-Fi).
4. Ring the phone from the Mac: it rings over Bluetooth. Clipboard and links also work; a small file works (slowly).
5. Background start: swipe Ferry away (or tap **Stop** and reopen once, then swipe away), wait, come into range:
   the service should start by itself. Without the association the log says `can't start Ferry from the background`.
   A force-stop (Settings, or an OEM battery manager) cancels the scan until the app is opened again.
6. Wi-Fi back on: a Bluetooth bridge stays up until the link drops (NSD discovery keeps running alongside);
   after that, scan hits check in over Wi-Fi again.

## Phase 4: screen mirroring, control, cursor crossing

Protocol: [`docs/screen-protocol.md`](../docs/screen-protocol.md) (FSP1 over the core's `CH_SCREEN` stream).
Code: `app/src/main/java/dev/ferry/android/screen/` (`Fsp.kt` codec, `Screen.kt` session + consent,
`Capture.kt` encoder, `Control.kt` Accessibility service). Logs: `adb logcat -s FerryScreen` (all at warn; vivo hides info).

- **Mirroring** needs no setup. When the Mac asks (START with video), Android's "Start now" dialog appears; if
  Android won't show it from the background, a **Tap to share your screen with <Mac>** notification opens it.
  Only *entire screen* is offered (Android 14+). Capture stops when the screen turns off (END 2), when you stop it
  from Android's capture indicator, or with **Stop** on the "<Mac> is connected to your screen" notification (END 1).
  While capturing, the service also runs with the `mediaProjection` foreground type. Video is refused over a
  Bluetooth-only link; control-only still works there. A locked phone refuses START (END 2).
- **Control** (and cursor crossing) needs **Ferry control**: Ferry → Screen & control → **Turn on Ferry control**
  opens Accessibility settings → Installed apps / Downloaded apps → Ferry control → on. Off by default. While off,
  INFO has no control bit, input is ignored, and a control-only START is answered with END 3 "turn on Ferry control".
  Sideloaded from a browser/file manager, Android 13+ greys it out: Settings → Apps → Ferry → ⋮ → Allow restricted settings.
- Mapping: TOUCH → gestures (a press that never moves past touch slop is a tap on up, held time kept, so long press
  works; drags are continued strokes as they arrive); SCROLL → a 120 ms swipe at the point (1 wheel unit ≈ 20 dp);
  NAV → Back/Home/Recents/Notifications; TEXT → appended to the focused field (`ACTION_SET_TEXT`); KEY 67 deletes the
  last character, KEY 66 is the IME enter action (Android 11+; older: newline in multi-line fields); CURSOR → an
  arrow overlay, down/up at the cursor are a tap or drag. One session at a time: a new one replaces the old.

### Test on the phone (needs the Mac viewer)

1. Install, open Ferry, pair (as above). Wi-Fi or USB.
2. Mirroring: start mirroring on the Mac → tap **Start now** (or the notification first). The Mac shows the screen;
   the phone shows Android's capture indicator and "<Mac> is connected to your screen". Rotate the phone: the Mac
   view re-sizes within a second. Press the power button: mirroring ends ("phone locked").
3. Control: turn on Ferry control, start mirroring again. Click = tap, click-hold = long press, drag = swipe/drag,
   wheel = scroll, type into a focused text field, Backspace/Return, the Mac's Back/Home/Recents buttons.
4. Cursor crossing: move the Mac pointer off its screen edge toward the phone → an arrow appears on the phone and
   clicks tap under it. Turn Ferry control off → the Mac is told "turn on Ferry control" and nothing happens on the phone.
5. **Stop** in the "connected to your screen" notification ends the session from the phone (END 1).
6. Logs: `adb logcat -s FerryScreen` shows `START video=… control=…`, `encoding WxH for screen WxH`, and `end <reason> …`.


## Phase 5: messages and calls

The phone is the source of truth: the Mac asks, the phone answers from its own providers. Two more switches in
**Share with your Mac**, both **off by default**. Turning one on asks for its permissions first; the switch stays
off unless all are granted, and turns itself off if you later revoke one in Settings. Off = Mac requests get no reply.
Code: `Sms.kt` (provider reads, `SmsReceiver`, sending), `Calls.kt` (call state, commands, call log), `Names.kt`
(contact names via `PhoneLookup`, cached). Logs: `adb logcat -s FerryPhone` (warn level; message bodies and numbers aren't logged).

| Switch | Permissions | What it does |
|---|---|---|
| Messages | READ_SMS, RECEIVE_SMS, SEND_SMS, READ_CONTACTS | `SmsThreadsRequest` → threads newest first (default 50, snippets cut to 200 chars, oldest dropped to stay under 1 MiB), `SmsMessagesRequest` → a thread's last N texts (default 100, oldest first). Incoming texts (`SMS_RECEIVED`; Ferry is not the default SMS app) → `SmsNew` to every paired Mac. `SmsSend` → `SmsManager` (multipart for long text) to that address only, then `SmsNew` (outgoing) to every Mac. Android itself writes it to the sent box for a non-default SMS app, so Ferry doesn't. |
| Calls | READ_PHONE_STATE, READ_CALL_LOG, ANSWER_PHONE_CALLS, READ_CONTACTS | `TelephonyCallback` (API 31+, registered by the service) + the PHONE_STATE broadcast (ringing number) → `Call{state, number, name}` on each change. `CallCommand` Answer → `TelecomManager.acceptRingingCall`, Decline → `endCall` (also hangs up). `CallLogRequest` → the last 50 calls. |

Limits: SMS only (no MMS/group chats, no pictures). Default SIM only. A text counts as sent once handed to
`SmsManager` (no delivery report). Incoming texts reach the Mac only while Ferry's service is running. `SmsNew` for
an incoming or just-sent text has `id = 0` (not in the provider yet; the next `SmsMessagesRequest` returns the real
id). The number of an outgoing call isn't known (shown with an empty number). **Call audio stays on the phone**:
answering from the Mac picks up on the phone (use its speaker or headset); Ferry doesn't route call audio to the Mac.

**Play flavor:** Google Play only allows SMS and Call Log permissions for apps approved through the
*Permissions Declaration Form* (SMS/Call Log policy, "companion device" / "connected device" use). The `play` build
requests them like `full`; the declaration is needed before uploading it to Play.

Sideloaded from a browser or file manager, Android 13+ may grey out the SMS/call-log permissions ("restricted
settings"): Settings → Apps → Ferry → ⋮ → Allow restricted settings. `adb install` isn't affected.

### Test on the phone (needs the Mac app's Phase 5 side)

1. Install, open Ferry, pair. Share with your Mac → **Messages** on → allow SMS and contacts. **Calls** on → allow
   phone, call logs and contacts. The ongoing notification lists "Messages, Calls".
2. Threads: open Messages on the Mac → the newest ~50 conversations, names for saved contacts, unread counts match.
3. Thread: open one → its last 100 texts in order, yours marked outgoing.
4. Incoming: text the phone from another phone → it shows on the Mac within a second or two (log
   `sms received, 1 sender(s), 1 Mac(s)`). A long (multi-part) text arrives as one message.
5. Send: reply from the Mac → the other phone receives it (a long reply arrives whole), the Mac shows it as sent, and
   it appears in the phone's own Messages app (written by Android). Log: `sms sent, N part(s)`.
6. Calls: call the phone → the Mac shows Ringing with the number/name (log `call RINGING`). Answer from the Mac →
   the phone picks up (`call ACTIVE`; talk on the phone). Decline / hang up from the Mac → `call IDLE`.
   Recent calls on the Mac → the last 50, incoming/outgoing/missed.
7. Off: turn **Messages** off → threads/messages requests go unanswered, incoming texts aren't sent, Send does
   nothing. Same for **Calls**. Revoke SMS in Settings → Apps → Ferry → Permissions, reopen Ferry → the switch is off.

## Phase 6: phone files, Wi-Fi from the Mac, Do Not Disturb

Two more switches in **Share with your Mac**, both **off by default**. Code: `Files.kt` (path checks, listing, sending),
`Wifi.kt` (`WifiActivity`), `Dnd.kt`, `Flavor.kt` per flavor. Logs: `adb logcat -s FerryFiles` (warn level; Wi-Fi
passwords are never logged).

| Feature | Enable | What it does |
|---|---|---|
| Files ("Let your Mac browse and copy files on this phone") | switch → Android's **All files access** screen → allow Ferry (full build, Android 11+) | `FsListRequest{path}` → `FsList` of that folder under shared storage (`/storage/emulated/0`): hidden dot-files skipped, folders first, then by name, at most 2000 entries. `FsGet{paths}` → those files sent to the Mac that asked, with their names (shows as a normal send). Paths must stay inside the root: absolute paths, `..` parts and symlinks that lead outside are refused; `FsGet` takes regular files only, refused ones are reported in a toast. Off, or access revoked → `FsList` carries an error ("Turn on Files in Ferry on the phone") and `FsGet` is ignored. Revoking All files access turns the switch off next time Ferry opens. |
| Do Not Disturb sync | switch; for Mac → phone also **Do Not Disturb access** (Settings → Notifications → Do Not Disturb access → Ferry, or tap the "Allow Ferry to change Do Not Disturb" notification) | Phone → Mac: any change of the interruption filter (while the service runs) → `Dnd{on}` to every paired Mac (`on` = anything but "all"). Mac → phone: `Dnd{on}` sets Priority only (on) or All (off); a change Ferry just made isn't sent back for 2 s. Without the access, the notification asks for it. |

**Wi-Fi from the Mac** needs no switch: Android's own **Save this network?** dialog is the consent, and Ferry never
connects by itself. With Ferry open, the dialog shows at once; otherwise a notification "<Mac> shared Wi-Fi “SSID”:
tap to add" opens it. WPA2 with a password, open without. Needs Android 11+.

**Uploads from the Mac** are unchanged: Mac → phone files still land in **Downloads/Ferry** (not in the folder being browsed).

**Play flavor:** no All files access (Play's policy doesn't allow it for this use). Turning on Files shows "Browsing phone
files needs the full build of Ferry", and every `FsListRequest` is answered with that error.

Limits: listing is capped at 2000 entries (no paging); folders can't be fetched whole (pick files); Ferry tells the Mac
about a Do Not Disturb change only when it happens (not the current state when a Mac connects); Wi-Fi offers WPA2 or
open only (no WPA3/enterprise/hidden networks).

### Test on the phone (needs the Mac app's Phase 6 side)

1. Install the **full** build, open Ferry, pair. Share with your Mac → **Files** on → All files access opens → allow Ferry →
   back in Ferry the switch is on and the ongoing notification lists "Files".
2. Browse: on the Mac open the phone's files → the root shows Android, DCIM, Download, Music… (folders first, no dot-files).
   Open DCIM → Camera → photos with sizes and dates (log `FerryFiles`: nothing for a good listing, `list refused: …` otherwise).
3. Copy: pick two photos on the Mac → they arrive in the Mac's Downloads with the same names; the phone shows the usual
   "Sent 2 file(s) to <Mac>" notification (log `get 2 path(s): sending 2, refused 0`).
4. Escape attempts (Mac CLI or a patched Mac app): `FsListRequest` for `../`, `/data`, `DCIM/../../..` → `FsList` with an
   error; `FsGet` for `/data/system/x` or a folder → a toast "Couldn't send 1 file(s) to <Mac>: …", nothing sent.
5. Off: turn **Files** off → browsing shows "Turn on Files in Ferry on the phone". Turn it on, then revoke All files access in
   Settings → Apps → Ferry → reopen Ferry → the switch is off. Play build: the switch shows the "needs the full build" toast
   and the Mac sees the same error.
6. Upload: send a file Mac → phone → still lands in Downloads/Ferry.
7. Wi-Fi with Ferry open: share a network from the Mac → **Save this network?** appears; Save → it's under Saved networks.
   With Ferry in the background: the "shared Wi-Fi" notification appears; tap → the same dialog. Cancel → nothing saved.
   Share an open network (no password) → saved as open.
8. Do Not Disturb: **Do Not Disturb sync** on. Toggle DND in Quick Settings → the Mac shows it within a second (log `dnd on=true → Macs`).
   From the Mac turn DND on before granting access → "Allow Ferry to change Do Not Disturb" notification → tap → turn Ferry on →
   ask again from the Mac → DND turns on (Priority only, log `dnd on=true from Mac`) and the Mac gets no echo; off → back to All.
   Switch off → DND changes aren't sent and Mac requests are ignored.
