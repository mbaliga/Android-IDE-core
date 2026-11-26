# Docked / external-display layout brain (wave 2, lane D1)

Status: design note, written before code. Scope is the **layout brain only**: pure logic plus thin
Android adapters. There is **no pane UI** here - the desktop-class pane screens are designed
separately and need new Hyle components first (Hyle is single-sourced; nothing is copied into
core). Nothing below is claimed to work on a device: this container has no device, emulator,
external display or input hardware.

## Why
The owner docks a RedMagic phone to a 32:9 Samsung Odyssey G9 (5120x1440) and wants a desktop-IDE
layout, not a stretched phone screen. The phone is not a Samsung, so Samsung DeX APIs do not
apply; vendor desktop modes are treated as unknown. Everything uses generic Android:
`DisplayManager`, `WindowMetrics`, `Display` flags, `InputManager`, `ActivityOptions`.

## What exists today (audit)
- No window-size, display, foldable or multi-window handling anywhere in `core-engine`.
- `MainActivity`: `singleTask`, `adjustResize`, no `resizeableActivity`, no `configChanges`, so
  rotation, a dock/undock or a hardware-keyboard attach **recreates the activity**.
- `ui/AppRoot` picks `RegularShell` / `SpatialRoot` only from the user's `InteractionMode`.

## What this lane builds
1. `domain/layout/ScreenClassResolver` (pure): PHONE / TABLET / DESKTOP / ULTRAWIDE from window
   dp size, aspect ratio, external-display flag, hardware keyboard + pointer. Thresholds are
   named constants labelled **PROPOSED**; a table test pins the G9 at plausible densities.
   Pixel size alone is never an input (density converts it to dp first).
2. `domain/layout/PaneLayout` (pure): panes as a row/column split tree with fractions and
   per-pane minimum dp sizes; a constraint solver; operations split / close / move / resize /
   maximise / restore / focus next-previous-direction; undo history; per-ScreenClass presets
   (all marked PLACEHOLDER); versioned JSON with migration hook and fallback to a preset; layouts
   keyed by a **display fingerprint** (display id, size class, density bucket) so dock and undock
   do not clobber each other.
3. `domain/layout/Shortcuts` (pure): command-id to chord map, chord parser/printer, conflict
   detection, persisted user overrides, and the terminal-focus policy (below).
4. `domain/layout/DisplayEnvironment` + `ui/layout/*` Android adapters: a `StateFlow` of window
   metrics, density, display id / default flag, presentation flag, rotation, connected displays,
   keyboard and pointer presence, behind three source interfaces (JVM-tested with fakes).
   MIRRORED vs EXTENDED is distinguished; a launch-on-display plan is pure logic, the actual
   `ActivityOptions.setLaunchDisplayId` start is a thin adapter that is owner-verified only.
5. Manifest: `resizeableActivity=true` and `configChanges` for the listed bits on `MainActivity`,
   `adjustResize` kept. See "Restart audit".
6. Wire only the resolver output into `AppRoot` as `LocalScreenClass` / `LocalDisplayState`.
   Which shell is shown does **not** change.

## What this lane does not build
- Any composable that draws panes, dividers, a rail or a command palette.
- The desktop shell, shell selection by ScreenClass, or any change to `RegularShell`/`SpatialRoot`.
- A SharedPreferences adapter for layout/shortcut persistence (the pure `LayoutStore` seam and
  codecs exist and are tested; the adapter lands with the pane UI that needs it).
- Real launch-on-display behaviour (owner-verified follow-up) and any Samsung-specific API.
- Pane content, tab models, or a terminal-key capture path (terminal lane owns that).

## Seams
`ScreenClassResolver` consumes plain numbers. `PaneLayout` is an immutable value; operations take
the container size in dp so feasibility is checked, never assumed. `DisplayEnvironment` reads
three interfaces (`WindowSource`, `DisplayListSource`, `InputPresenceSource`); `ui/layout` supplies
Android implementations. `KeyEventLike` keeps `android.view.KeyEvent` out of the pure code;
`AndroidKeyMapping` maps `(keyCode, metaState)` ints so it is JVM-testable without Robolectric.

## Terminal-focus shortcut policy
While a terminal pane has focus, plain Ctrl chords (Ctrl+C, Ctrl+D, Ctrl+R ...) belong to the
shell. In that context app shortcuts resolve **only** for Ctrl+Shift or Alt chords; any other
chord is `PassThrough`. Overrides that would put a non-terminal-safe chord in the terminal map
are rejected. Alt chords do cost the shell its Alt+letter readline motions, so defaults use Alt
only in combination with Ctrl (focus-direction) or non-letter keys (Alt+PageDown).

## Failure modes
- Container too small for the layout: `arrange` reports `fits=false` and still returns
  best-effort rects; operations that would create sub-minimum panes are **refused**, never clamped
  into negative or sub-minimum sizes. The caller should fall back to a smaller preset.
- Corrupt/foreign/future persisted JSON, duplicate panes, unknown pane kind, absurd depth,
  non-finite fractions: decode returns a fallback preset, never throws.
- Display listener fires from the main thread only; state is a replaceable snapshot.
- `adjustResize` + `configChanges`: soft-keyboard open/close is an inset change, not a config
  change, so it is unaffected.

## Restart audit (what relied on recreate-on-rotation)
Recorded in the final section of this note once the audit is done (see "Audit result").

## Owner-verified only (nothing here is device-verified)
Whether the RedMagic mirrors or extends; the density it reports on the G9; whether
`setLaunchDisplayId` works on its software; input-device detection for keyboard/mouse; refresh
behaviour; that docking no longer restarts the activity. Exact checklist at the end of this note.
