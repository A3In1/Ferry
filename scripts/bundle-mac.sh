#!/usr/bin/env bash
# Build the Ferry menu-bar app and assemble an ad-hoc-signed mac/build/Ferry.app.
# Run scripts/build-apple.sh first (or after any Rust change).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/mac"

swift build -c release
BIN="$(swift build -c release --show-bin-path)/Ferry"

APP="build/Ferry.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS"
cp "$BIN" "$APP/Contents/MacOS/Ferry"

cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleIdentifier</key><string>dev.ferry.mac</string>
  <key>CFBundleName</key><string>Ferry</string>
  <key>CFBundleExecutable</key><string>Ferry</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>0.1.0</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>LSMinimumSystemVersion</key><string>15.0</string>
  <key>LSUIElement</key><true/>
  <key>NSLocalNetworkUsageDescription</key>
  <string>Ferry finds and connects to your paired phone on this network.</string>
  <key>NSBonjourServices</key>
  <array><string>_ferry._udp</string></array>
</dict>
</plist>
PLIST

# Ad-hoc signature: runs locally only. Release builds need a Developer ID + notarization.
codesign --force --sign - --options runtime "$APP"
codesign --verify --verbose "$APP"
echo "Built mac/$APP — launch with: open mac/$APP"
