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
  <key>CFBundleURLTypes</key>
  <array><dict>
    <key>CFBundleURLName</key><string>dev.ferry.mac</string>
    <key>CFBundleURLSchemes</key><array><string>ferry</string></array>
  </dict></array>
  <key>NSLocalNetworkUsageDescription</key>
  <string>Ferry finds and connects to your paired phone on this network.</string>
  <key>NSBluetoothAlwaysUsageDescription</key>
  <string>Ferry uses Bluetooth to reach your paired phone when you're not on the same Wi-Fi.</string>
  <key>NSLocationWhenInUseUsageDescription</key>
  <string>macOS only shows apps your Wi-Fi network's name with Location access. Ferry uses it to share the network with your phone, never your location.</string>
  <key>NSLocationUsageDescription</key>
  <string>macOS only shows apps your Wi-Fi network's name with Location access. Ferry uses it to share the network with your phone, never your location.</string>
  <key>NSBonjourServices</key>
  <array><string>_ferry._udp</string></array>
  <key>NSServices</key>
  <array>
    <dict>
      <key>NSMenuItem</key><dict><key>default</key><string>Send with Ferry</string></dict>
      <key>NSMessage</key><string>sendFiles</string>
      <key>NSPortName</key><string>Ferry</string>
      <key>NSSendTypes</key>
      <array><string>public.file-url</string><string>NSFilenamesPboardType</string></array>
      <key>NSSendFileTypes</key>
      <array><string>public.data</string></array>
      <key>NSRequiredContext</key><dict/>
    </dict>
    <dict>
      <key>NSMenuItem</key><dict><key>default</key><string>Send link to phone</string></dict>
      <key>NSMessage</key><string>sendLink</string>
      <key>NSPortName</key><string>Ferry</string>
      <key>NSSendTypes</key>
      <array><string>public.url</string><string>public.utf8-plain-text</string></array>
      <key>NSRequiredContext</key><dict/>
    </dict>
  </array>
</dict>
</plist>
PLIST

# Ad-hoc by default: runs locally only, and macOS re-asks for Downloads access after each rebuild
# (the privacy grant is tied to the binary hash). FERRY_SIGN_IDENTITY="Apple Development: …" keeps it.
# Release builds need a Developer ID + notarization.
codesign --force --sign "${FERRY_SIGN_IDENTITY:--}" --options runtime "$APP"
codesign --verify --verbose "$APP"
plutil -lint "$APP/Contents/Info.plist"
# Register the bundle so Finder's Services menu picks up "Send with Ferry" right away.
/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister -f "$APP"
echo "Built mac/$APP — launch with: open mac/$APP"
