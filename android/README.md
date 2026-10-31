# Ferry for Android (Phase 1: pairing + file sharing · Phase 2: everyday sync · Phase 3: Bluetooth · Phase 4: screen)

Compose app over the shared Rust core (`ffi/`, UniFFI → Kotlin package `dev.ferry.core`, via JNA).
Application id `dev.ferry.android` in both flavors (`play`, `full`; identical for now).

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

