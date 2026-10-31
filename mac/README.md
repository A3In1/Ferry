# Ferry for Mac (Phase 0 skeleton)

Menu-bar app (no Dock icon) over the shared Rust core, via UniFFI bindings from `ffi/`.

## Build

From the repo root:

```sh
scripts/build-apple.sh   # Rust → mac/Frameworks/FerryCoreFFI.xcframework + Sources/FerryCore/FerryCore.swift
scripts/bundle-mac.sh    # swift build -c release → mac/build/Ferry.app (ad-hoc signed)
open mac/build/Ferry.app
```

Re-run `build-apple.sh` after any change in `core/` or `ffi/`. For quick iteration,
`cd mac && swift run Ferry` also works, but without the app bundle macOS has no
Info.plist to read (no Local Network prompt text, Dock icon shows).

The xcframework and `FerryCore.swift` are generated and git-ignored.

## What it does

- Starts a `FerryNode` with data in `~/Library/Application Support/Ferry`, saving received files to `~/Downloads`.
- **Pair a device…** shows a QR + selectable `ferry://pair?...` ticket (paste it into the CLI to test).
  When the other side connects, an alert shows the 6-digit code: Match / Doesn't match.
- **Paired devices** → per device: Send files… (multi-select), Forget.
- The last five transfer events (progress %, saved names) are listed in the menu.

Arm64 only for now; see the comment in `scripts/build-apple.sh` for universal builds.
