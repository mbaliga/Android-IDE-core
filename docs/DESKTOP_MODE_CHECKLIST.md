# Desktop-mode checklist (Android freeform windows)

> **License:** `LICENSE-PENDING` — see `docs/non_ratified/LICENSE_PENDING.md`.

Owner-run. Nothing here has been executed: the build container has no device or emulator, so
every row is `NEEDS-DEVICE-VALIDATION`. Run it on hardware that offers windowed freeform mode
with a mouse and keyboard (Android desktop windowing; a phone docked to a display).

## What the manifest now declares (`core-engine/src/main/AndroidManifest.xml`)

- `<layout>` on `MainActivity`: default 960x720 dp, minimum 360x480 dp. Applies to freeform
  windows only; a fullscreen phone ignores it.
- `android.hardware.touchscreen` `required="false"`, so touchscreen-less hosts are not filtered out.

## Checks

| # | Check | Status |
|---|---|---|
| 1 | Launch in a freeform window; it opens near 960x720 dp. | NEEDS-DEVICE-VALIDATION |
| 2 | Resize to the minimum; the spatial room model (edge bands 72 dp and 56 dp in `SpatialRoot`) still lays out and every room stays reachable. | NEEDS-DEVICE-VALIDATION |
| 3 | Navigate rooms with mouse drag and the keyboard. | NEEDS-DEVICE-VALIDATION |
| 4 | Phone regression: on a fullscreen phone the app looks and behaves as before. | NEEDS-DEVICE-VALIDATION |

## Known limitations (no code change proposed)

The `full` flavour's heavy tiers have no desktop-window equivalent:

- The overlay bubble (`SYSTEM_ALERT_WINDOW`) is a phone-level floating surface.
- Screen capture and OCR use MediaProjection; `ScreenCaptureActivity` uses a translucent theme
  that may misbehave inside a freeform window.
- USB-host flashing depends on OTG hardware that a desktop host may not expose.

Treat these as unsupported in desktop mode until a device run says otherwise.
