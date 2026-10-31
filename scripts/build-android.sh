#!/usr/bin/env bash
# Build ferry-ffi for Android and generate the Kotlin bindings for android/app:
#   android/app/src/main/jniLibs/<abi>/libferry_ffi.so
#   android/app/src/main/java/dev/ferry/core/ferry_ffi.kt   (generated UniFFI bindings)
# Usage: scripts/build-android.sh [--x86_64]   (x86_64 = emulator ABI, off by default)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

SDK="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export ANDROID_HOME="$SDK"
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  ANDROID_NDK_HOME="$(ls -d "$SDK"/ndk/* | sort -V | tail -1)"
  export ANDROID_NDK_HOME
fi
if [[ -z "${JAVA_HOME:-}" ]]; then
  JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
  export JAVA_HOME
fi

ABIS=(arm64-v8a)
[[ "${1:-}" == "--x86_64" ]] && ABIS+=(x86_64)

JNI="android/app/src/main/jniLibs"
# strip=debuginfo (not the workspace's strip=true): uniffi-bindgen reads its metadata from
# the ELF symbol table. AGP strips the .so again when it packages the APK.
export CARGO_PROFILE_RELEASE_STRIP=debuginfo
# No mdns feature: Android discovers peers with NsdManager (see NsdPresence.kt).
cargo ndk $(printf -- '-t %s ' "${ABIS[@]}") --platform 29 \
  build --release -p ferry-ffi --no-default-features

# Copy only our library (cargo-ndk's -o also copies unrelated dylibs from deps).
rm -rf "$JNI"
for abi in "${ABIS[@]}"; do
  case "$abi" in
    arm64-v8a) triple=aarch64-linux-android ;;
    x86_64) triple=x86_64-linux-android ;;
  esac
  mkdir -p "$JNI/$abi"
  cp "target/$triple/release/libferry_ffi.so" "$JNI/$abi/"
done

# Library mode reads the UniFFI metadata from the built .so (any ABI works).
rm -rf android/app/src/main/java/dev/ferry/core
cargo run -q -p ferry-ffi --features bindgen --bin uniffi-bindgen -- \
  generate "target/aarch64-linux-android/release/libferry_ffi.so" \
  --language kotlin --no-format --out-dir android/app/src/main/java


echo "Built:"
ls -lh "$JNI"/*/libferry_ffi.so
find android/app/src/main/java/dev/ferry/core -name '*.kt'
