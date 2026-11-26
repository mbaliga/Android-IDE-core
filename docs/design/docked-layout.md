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
Adding `configChanges` stops the activity restarting on rotation, dock/undock, dark-mode flip and
keyboard attach. What was checked (grep over `core-engine/src/{main,full,play}`):
- `savedInstanceState` / `onSaveInstanceState`: only `MainActivity.onCreate` uses it, to run the
  widget-tap handler once. Fewer recreations only makes that guard rarer; no behaviour lost.
- `rememberSaveable`: zero uses, so no state was being restored across recreation; all UI state
  was plain `remember` (lost on every rotation). **Behaviour change, deliberate and visible:** the
  splash (`showSplash`), the spatial room position and similar `remember` state now **survive**
  rotation instead of resetting. The brief's "byte-identical" holds for every screen's code; this
  one runtime consequence of not restarting is called out here and for the owner.
- Theme: `ThemeMode.SYSTEM` uses `isSystemInDarkTheme()` (reads `LocalConfiguration`), which
  Compose updates without a restart. `enableEdgeToEdge()` resolves bar-icon style once, so
  `MainActivity.onConfigurationChanged` re-calls it (the restart used to do that implicitly).
- `LocalConfiguration.screenHeightDp` readers (`RemoteScreen`, `TerminalFacet`) recompose on change.
- `resources.displayMetrics` readers are in `full` services (`ScreenCaptureService`,
  `OverlayService`), not the activity; unaffected.
- `TerminalPlatform` uses `isChangingConfigurations` in its lifecycle callbacks; fewer stop/start
  pairs on rotation is the benign direction.
- No `screenOrientation` or `resizeableActivity` anywhere else in any manifest.
- `adjustResize` kept; soft-keyboard show/hide is an inset change and never a config change.
Not audited (no device): that every Hyle/Material component tolerates a live density change.

## Owner-verified only (nothing here is device-verified)
Whether the RedMagic mirrors or extends; the density it reports on the G9; whether
`setLaunchDisplayId` works on its software; input-device detection for keyboard/mouse; refresh
behaviour; that docking no longer restarts the activity. Exact checklist at the end of this note.

## G9 dp table (PROPOSED thresholds; pinned by `ScreenClassTest`)
5120x1440 external display, resolved with `ScreenClassResolver`:

| density | dp size | class |
|---|---|---|
| 0.7 | 7314 x 2057 | ULTRAWIDE |
| 1.0 | 5120 x 1440 | ULTRAWIDE |
| 1.5 | 3413 x 960 | ULTRAWIDE |
| 2.0 | 2560 x 720 | ULTRAWIDE |
| 2.5 | 2048 x 576 | ULTRAWIDE (height < 600 but external and wide, so not PHONE) |
| 3.0 | 1707 x 480 | ULTRAWIDE |
| 3.5 | 1463 x 411 | DESKTOP (under the 1600 dp ultrawide width floor) |

1920x1080 monitor: densities 1.0, 1.25, 1.5, 1.75 give DESKTOP; 2.0 (960 x 540) gives TABLET.
Thresholds (600 / 840 / 1000 / 1600 dp, aspect 2.4) are **proposals**, not facts.

## Owner checklist when docked (nothing below has been observed)
1. `adb shell dumpsys display` (or Settings > About): does the G9 appear as a second display
   (EXTENDED) or does the phone mirror at the phone's own size (no second display)?
2. What density does the phone report for the G9 (`wm density`, or the debug state)? The
   table above only says what each density would resolve to.
3. Does docking/undocking keep the app alive (no restart, chat not reset, splash not replayed)?
4. Rotation on the handset: splash no longer replays; room position retained; bars readable in
   both system themes (toggle system dark mode while the app is open).
5. Plug in a USB/Bluetooth keyboard and mouse: are they detected (keyboard + pointer)? A media-key
   dongle must not count as a keyboard.
6. Soft keyboard still resizes the chat composer (`adjustResize`) in portrait and landscape.
7. `LaunchOnDisplay` (not called by anything yet): whether `setLaunchDisplayId` works on this
   phone's software, and what `singleTask` does when the app is already open on the other display.
8. Refresh rate / frame pacing on the 32:9 panel once a real pane UI exists.

## Follow-ups (named, not done)
- SharedPreferences `LayoutStore` and shortcut-override store, with the pane UI.
- Hardware-key capture wired to `ShortcutMap.resolve` and the terminal's key encoder.
- Selecting the desktop shell by `LocalScreenClass`; adjusting the placeholder presets after the
  owner approves this design.
- Pane UI and any divider thickness (min sizes here exclude dividers).
