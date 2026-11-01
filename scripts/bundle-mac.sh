#!/usr/bin/env bash
# Build the Ferry menu-bar app and assemble an ad-hoc-signed mac/build/Ferry.app.
# Run scripts/build-apple.sh first (or after any Rust change).
#
# FERRY_FEED_URL=https://…/appcast.xml bakes in where Sparkle looks for updates. Unset (the default) leaves
# SUFeedURL empty and the app says "Updates aren't set up for this build yet". scripts/release-mac.sh sets it.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/mac"

# The one place the version lives; scripts/release-mac.sh bumps it. Sparkle compares CFBundleVersion, so both
# keys get it (dotted versions compare fine).
VERSION="$(tr -d '[:space:]' < VERSION)"
FEED_URL="${FERRY_FEED_URL:-}"
if [[ -n "$FEED_URL" && "$FEED_URL" != https://* ]]; then
  echo "FERRY_FEED_URL must be https:// (Sparkle refuses plain http)" >&2
  exit 1
fi
# EdDSA public key for update signatures. The private half is in the login Keychain (Sparkle's generate_keys,
# account "ferry"); see mac/README.md → Updates. Never commit the private key.
# FERRY_PUBLIC_ED_KEY overrides it, only for testing the update flow with a throwaway key.
PUBLIC_ED_KEY="${FERRY_PUBLIC_ED_KEY:-YQLHEt/ZoTTsTuZZ5J6QUX88+6gqevE8zMmIsCsMjg8=}"

swift build -c release
BIN_DIR="$(swift build -c release --show-bin-path)"

APP="build/Ferry.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Frameworks"
cp "$BIN_DIR/Ferry" "$APP/Contents/MacOS/Ferry"
# ditto keeps the framework's symlinks and its helpers (Autoupdate, Updater.app, XPCServices) intact.
ditto "$BIN_DIR/Sparkle.framework" "$APP/Contents/Frameworks/Sparkle.framework"
# SwiftPM links with rpaths into .build; the bundle should only find Sparkle inside itself.
otool -l "$APP/Contents/MacOS/Ferry" | awk '/cmd LC_RPATH/ { getline; getline; print $2 }' | while read -r rpath; do
  if [[ "$rpath" == /* && "$rpath" != /usr/lib/* ]]; then install_name_tool -delete_rpath "$rpath" "$APP/Contents/MacOS/Ferry"; fi
done
install_name_tool -add_rpath "@executable_path/../Frameworks" "$APP/Contents/MacOS/Ferry"

cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleIdentifier</key><string>dev.ferry.mac</string>
  <key>CFBundleName</key><string>Ferry</string>
  <key>CFBundleExecutable</key><string>Ferry</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>$VERSION</string>
  <key>CFBundleVersion</key><string>$VERSION</string>
  <key>SUFeedURL</key><string>$FEED_URL</string>
  <key>SUPublicEDKey</key><string>$PUBLIC_ED_KEY</string>
  <key>SUEnableAutomaticChecks</key><true/>
  <key>SUScheduledCheckInterval</key><integer>86400</integer>
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
IDENTITY="${FERRY_SIGN_IDENTITY:--}"
# Hardened runtime only with a real identity: with it, an ad-hoc app refuses to load the ad-hoc Sparkle.framework
# (library validation wants matching Team IDs, and ad-hoc has none). Notarization needs both anyway.
sign() {
  if [[ "$IDENTITY" == "-" ]]; then codesign --force --sign - "$@"; else codesign --force --sign "$IDENTITY" --options runtime "$@"; fi
}
# Inside-out, no --deep (Sparkle's docs): its helpers, then the framework, then the app.
SPARKLE="$APP/Contents/Frameworks/Sparkle.framework"
sign "$SPARKLE/Versions/B/XPCServices/Installer.xpc"
sign --preserve-metadata=entitlements "$SPARKLE/Versions/B/XPCServices/Downloader.xpc"
sign "$SPARKLE/Versions/B/Autoupdate"
sign "$SPARKLE/Versions/B/Updater.app"
sign "$SPARKLE"
sign "$APP"
codesign --verify --deep --strict --verbose "$APP"
plutil -lint "$APP/Contents/Info.plist"
# Register the bundle so Finder's Services menu picks up "Send with Ferry" right away.
/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister -f "$APP"
echo "Built mac/$APP $VERSION${FEED_URL:+ (updates from $FEED_URL)} — launch with: open mac/$APP"
