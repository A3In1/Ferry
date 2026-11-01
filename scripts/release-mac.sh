#!/usr/bin/env bash
# Cut a Mac release: set mac/VERSION, build Ferry.app with the update feed baked in, zip it, sign the zip with
# Sparkle's EdDSA key (from the login Keychain) and add it to mac/release/appcast.xml. Publishes nothing.
#
#   FERRY_GITHUB_REPO=owner/Ferry scripts/release-mac.sh 0.2.0 notes.md
#
# Defaults assume GitHub: the zip is uploaded to the v<version> release and appcast.xml is committed at the repo
# root (public repo). Override with FERRY_FEED_URL (e.g. GitHub Pages) and FERRY_DOWNLOAD_URL (the zip's URL).
# Notes: .md (markdown), .txt (plain text) or .html. See mac/README.md → Updates.
#
# Signs with the private key in the login Keychain (account "ferry"); macOS asks once whether sign_update may
# use it (choose Always Allow). FERRY_ED_KEY_FILE=<generate_keys -x export> signs from a file instead (e.g. CI).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/mac"

VERSION="${1:-}" NOTES="${2:-}"
if [[ ! "$VERSION" =~ ^[0-9]+(\.[0-9]+){1,2}$ || ! -f "$NOTES" ]]; then
  echo "usage: FERRY_GITHUB_REPO=owner/repo $0 <version, e.g. 0.2.0> <release-notes.md|.txt|.html>" >&2
  exit 1
fi
NOTES="$(cd "$(dirname "$NOTES")" && pwd)/$(basename "$NOTES")"
REPO="${FERRY_GITHUB_REPO:-}"
if [[ -z "$REPO" && ( -z "${FERRY_FEED_URL:-}" || -z "${FERRY_DOWNLOAD_URL:-}" ) ]]; then
  echo "Set FERRY_GITHUB_REPO=owner/repo, or both FERRY_FEED_URL and FERRY_DOWNLOAD_URL" >&2
  exit 1
fi
ZIP_NAME="Ferry-$VERSION.zip"
export FERRY_FEED_URL="${FERRY_FEED_URL:-https://raw.githubusercontent.com/$REPO/main/appcast.xml}"
DOWNLOAD_URL="${FERRY_DOWNLOAD_URL:-https://github.com/$REPO/releases/download/v$VERSION/$ZIP_NAME}"
[[ "$DOWNLOAD_URL" == https://* ]] || { echo "FERRY_DOWNLOAD_URL must be https://" >&2; exit 1; }

OUT="release" # gitignored
APPCAST="$OUT/appcast.xml"
mkdir -p "$OUT"
if [[ -f "$APPCAST" ]] && grep -q "<sparkle:version>$VERSION</sparkle:version>" "$APPCAST"; then
  echo "$VERSION is already in $APPCAST; pick a new version (Sparkle only offers higher ones)" >&2
  exit 1
fi

# The signing key must match the public key the app ships with, or installs reject the update.
swift package resolve
BIN=".build/artifacts/sparkle/Sparkle/bin"
ACCOUNT="ferry"
SHIPPED_KEY="$(sed -n 's/^PUBLIC_ED_KEY="${FERRY_PUBLIC_ED_KEY:-\(.*\)}"$/\1/p' "$ROOT/scripts/bundle-mac.sh")"
SHIPPED_KEY="${FERRY_PUBLIC_ED_KEY:-$SHIPPED_KEY}"
if [[ -n "${FERRY_ED_KEY_FILE:-}" ]]; then
  KEY_ARGS=(--ed-key-file "$FERRY_ED_KEY_FILE")
  # The export is the base64 Ed25519 seed; CryptoKit gives its public half.
  SIGNING_KEY="$(swift -e 'import CryptoKit; import Foundation
    let seed = Data(base64Encoded: readLine()!.trimmingCharacters(in: .whitespaces))!
    print((try? Curve25519.Signing.PrivateKey(rawRepresentation: seed))?.publicKey.rawRepresentation.base64EncodedString() ?? "")' \
    < "$FERRY_ED_KEY_FILE")"
else
  KEY_ARGS=(--account "$ACCOUNT")
  SIGNING_KEY="$("$BIN/generate_keys" --account "$ACCOUNT" -p 2>/dev/null || true)"
fi
if [[ -z "$SHIPPED_KEY" || "$SIGNING_KEY" != "$SHIPPED_KEY" ]]; then
  echo "The signing key doesn't match the app's SUPublicEDKey ($SHIPPED_KEY)." >&2
  echo "Import your backup into the Keychain with: mac/$BIN/generate_keys --account $ACCOUNT -f <backup-file>" >&2
  exit 1
fi

printf '%s\n' "$VERSION" > VERSION
"$ROOT/scripts/bundle-mac.sh"

ZIP="$OUT/$ZIP_NAME"
rm -f "$ZIP"
ditto -c -k --sequesterRsrc --keepParent build/Ferry.app "$ZIP"
# Prints: sparkle:edSignature="…" length="…"
ATTRS="$("$BIN/sign_update" "${KEY_ARGS[@]}" "$ZIP")"
SIGNATURE="$(sed -n 's/.*sparkle:edSignature="\([^"]*\)".*/\1/p' <<<"$ATTRS")"
LENGTH="$(sed -n 's/.*length="\([0-9]*\)".*/\1/p' <<<"$ATTRS")"
[[ -n "$SIGNATURE" && -n "$LENGTH" ]] || { echo "sign_update gave no signature: $ATTRS" >&2; exit 1; }
"$BIN/sign_update" "${KEY_ARGS[@]}" --verify "$ZIP" "$SIGNATURE"

# Newest item first; earlier items stay so the feed keeps its history.
python3 -I - "$APPCAST" "$VERSION" "$NOTES" "$DOWNLOAD_URL" "$LENGTH" "$SIGNATURE" <<'PY'
import email.utils, os, sys, xml.etree.ElementTree as ET
appcast, version, notes, url, length, signature = sys.argv[1:]
SP = "http://www.andymatuschak.org/xml-namespaces/sparkle"
ET.register_namespace("sparkle", SP)
ET.register_namespace("dc", "http://purl.org/dc/elements/1.1/")
if os.path.exists(appcast):
    rss = ET.parse(appcast).getroot()
else:
    rss = ET.Element("rss", {"version": "2.0"})
    channel = ET.SubElement(rss, "channel")
    ET.SubElement(channel, "title").text = "Ferry"
channel = rss.find("channel")
item = ET.Element("item")
ET.SubElement(item, "title").text = f"Ferry {version}"
ET.SubElement(item, "pubDate").text = email.utils.formatdate(usegmt=True)
ET.SubElement(item, f"{{{SP}}}version").text = version
ET.SubElement(item, f"{{{SP}}}shortVersionString").text = version
ET.SubElement(item, f"{{{SP}}}minimumSystemVersion").text = "15.0"
fmt = {".md": "markdown", ".txt": "plain-text"}.get(os.path.splitext(notes)[1].lower(), "html")
description = ET.SubElement(item, "description", {f"{{{SP}}}format": fmt})
description.text = open(notes, encoding="utf-8").read().strip()
ET.SubElement(item, "enclosure", {
    "url": url, "length": length, "type": "application/octet-stream", f"{{{SP}}}edSignature": signature,
})
first = next((i for i, child in enumerate(channel) if child.tag == "item"), len(channel))
channel.insert(first, item)
ET.indent(rss)
ET.ElementTree(rss).write(appcast, encoding="utf-8", xml_declaration=True)
PY
xmllint --noout "$APPCAST"

echo
echo "Release $VERSION is ready in mac/$OUT (nothing was uploaded):"
echo "  1. Upload mac/$ZIP so it's served at $DOWNLOAD_URL"
echo "  2. Publish mac/$APPCAST at $FERRY_FEED_URL"
echo "  3. Commit mac/VERSION"
