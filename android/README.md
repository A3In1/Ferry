# Ferry for Android (Phase 1: pairing + file sharing)

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
