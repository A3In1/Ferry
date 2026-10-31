#!/usr/bin/env bash
# Build ferry-ffi for macOS and package it for the Swift package in mac/:
#   mac/Frameworks/FerryCoreFFI.xcframework  (static lib + C header + module.modulemap)
#   mac/Sources/FerryCore/FerryCore.swift    (generated UniFFI bindings)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# shortcut: arm64 only (x86_64-apple-darwin isn't installed). For a universal build,
# `rustup target add x86_64-apple-darwin`, build it too and `lipo -create` the two .a files.
TARGET=aarch64-apple-darwin
# Match mac/Package.swift (macOS 15) so cc-built C objects (ring, blake3) link without warnings.
export MACOSX_DEPLOYMENT_TARGET=15.0
OUT="target/$TARGET/release"
XCFW="mac/Frameworks/FerryCoreFFI.xcframework"
SWIFT_DIR="mac/Sources/FerryCore"
GEN="$(mktemp -d)"
trap 'rm -rf "$GEN"' EXIT

cargo build -p ferry-ffi --release --target "$TARGET"

cargo run -q -p ferry-ffi --features bindgen --bin uniffi-bindgen -- \
  generate "$OUT/libferry_ffi.dylib" --language swift --out-dir "$GEN/swift"

mkdir -p "$GEN/headers"
cp "$GEN/swift/FerryCoreFFI.h" "$GEN/headers/"
# SwiftPM/Xcode only pick up a modulemap named module.modulemap inside an xcframework.
cp "$GEN/swift/FerryCoreFFI.modulemap" "$GEN/headers/module.modulemap"

rm -rf "$XCFW"
mkdir -p "$(dirname "$XCFW")" "$SWIFT_DIR"
xcodebuild -create-xcframework \
  -library "$OUT/libferry_ffi.a" -headers "$GEN/headers" \
  -output "$XCFW" >/dev/null

cp "$GEN/swift/FerryCore.swift" "$SWIFT_DIR/FerryCore.swift"

echo "Built $XCFW and $SWIFT_DIR/FerryCore.swift"
ls -lh "$OUT/libferry_ffi.a" "$OUT/libferry_ffi.dylib"
