# Ferry for Android (Phase 1: pairing + file sharing · Phase 2: everyday sync)

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

Re-run `build-android.sh` after any change in `core/` or `ffi/`. The `.so`, the generated
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
