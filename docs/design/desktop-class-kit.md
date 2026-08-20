# Desktop-class kit — Hyle fields, trees, context menus, clickability (owner mockups 2026-08-20)

Owner directive (verbatim intent): **maintain clickability; bring in desktop-class actions;
the Hyle-style input fields/buttons are barely used in the app.** Four mockups were supplied;
their contents are transcribed below precisely so this doc is buildable without the images.

## 0. Binding UX rule: clickability is never sacrificed

Every gesture affordance (drag, hold, radial fan, scrub) has a **visible, ordinary tappable
path to the same outcome** — this restates THREAD_TOPOLOGY_PLAN binding constraint 4 and is
re-affirmed by the owner as a first-class requirement. New rule the mockups add: every
interactive row/control must **look** interactive (Hyle finish, state color, pressed state)
— no bare text that secretly responds to taps, no dead-looking rows that need a long-press to
discover. Desktop-class = discoverable actions: a context menu you can *see your way into*
(long-press on touch; right-click and keyboard where a pointer/keyboard exists — the app
targets desktop-class use on a sovereign phone, incl. external displays/keyboards).

## 1. Mockup transcription (source of truth)

**A. HyleField — the input-field state system** (light ground shown; slanted leading tick is
the signature mark — a parallelogram "/" hugging the field's left edge, its color = state):

| State | Tick | Border | Extras |
|---|---|---|---|
| Not selected | grey tick | none (soft card edge + subtle drop shadow) | — |
| Selected (focused) | violet/indigo tick | thin violet border, full perimeter | — |
| Selected & mandatory | violet tick | thin violet border | trailing `*` asterisk, violet |
| Error / invalid, not selected | red tick (with `!` notch) | none | — |
| Error / invalid, selected | red tick (`!` notch) | thin red border | — |
| Error & mandatory | red tick | thin red border | trailing `*`, red |
| Disabled | ghosted grey tick | none | text ghosted (placeholder grey) |

Geometry: generously rounded rect (~14–16 dp radius), roomy vertical padding, text size
~body-large; the tick sits flush left, slightly taller than the text line, slanted (top
leaning right ~12–15°); the error tick carries a small exclamation notch. Shadow is soft,
low-elevation. Text left-aligned; single-line default with ellipsis.

**B. HyleToggle**: rounded-square track (not a pill), thumb is a **slanted-edged square**
(same parallelogram language as the tick), shown off-state: grey track, light thumb right…
states: off (grey track/white thumb), on (violet track/white thumb), disabled (ghosted).

**C. HyleKeycap**: keyboard-key chips for shortcut hints, e.g. `#` and `*` pairs: a rounded
light tray containing a dark keycap with a subtle top-right fold/highlight; two variants
(emphasis on first vs second key). Used to surface desktop-class shortcuts inline.

**D. HyleTree** (light + dark mockups identical in structure): hierarchical file/folder tree —
per row: chevron (`›` collapsed / `⌄` expanded; files have none), outline folder/file icon,
label; **indentation guides**: one thin vertical hairline per ancestor depth, running the
full height of the expanded span; row height comfortable (~44 dp); dark theme = same
structure on near-black ground with light strokes. Selected/pressed row = full-width subtle
highlight (see mockup C's "Project B" row). Rows are fully clickable: chevron toggles
expansion; row body opens/selects; long-press opens the context menu.

**E. HyleContextMenu** (mockup shows it invoked on a tree row): floating rounded card
(elevated, opaque), icon + label rows, generous row height, a hairline separator before the
destructive group, destructive action (**Delete**) in red with red icon; shown anchored near
the pressed row, overlapping content. Menu rows from the mockup: `+ Create new folder`,
`✎ Rename`, `⤢ Expand`, `⤡ Collapse`, `🗑 Delete`(red). Keyboard/pointer parity: right-click
opens it where hardware exists; Escape/back dismisses.

## 2. Where components live

The **Hyle repo** (`hyle-design-system` submodule → `mbaliga/Hyle-Design-System`) is the
single source of the design system — these are general components and belong there
(`dev.aarso.hyle.component` / `cells` conventions of that repo), with previews in
`hyle-probe`. Core adopts them; core-local `ui/hyle/` wrappers stay thin. Landing a Hyle
change means: commit+push on the Hyle repo's `claude/fonebrew-development-clzu43` branch,
then bump the submodule pin in core (`git add hyle-design-system`) in the same core commit
that adopts it.

New components: `HyleField` (single-line + multiline; state enum NOT_SELECTED/SELECTED/
ERROR/DISABLED × mandatory flag; label+supporting-text slots), `HyleToggle`, `HyleKeycap`,
`HyleTree` (generic: caller supplies nodes + lazy children + icons; exposes expand-state,
selection, context-menu hook), `HyleContextMenu` (items: icon, label, destructive flag,
enabled flag; anchored popup). All theme-aware via the Hyle token system (light + dark per
the mockups; app default remains AMOLED-dark).

## 3. Adoption map in core (replace plain Material equivalents)

- **Settings**: every text input (API keys, base URLs, model names, search) → `HyleField`
  (mandatory/error states real: empty required key = mandatory, failed validation = error);
  every `Switch` → `HyleToggle` (gestures toggles, observer toggle, on-device⇄cloud stays
  the chip pair).
- **Search overlay** input → `HyleField`.
- **Council participant editor**, project/chapter rename dialogs → `HyleField`.
- **Agentic IDE / GitBrowse repo browser** → `HyleTree` (repo directories/files, lazy-loaded
  children) + `HyleContextMenu` with desktop-class actions appropriate per node (Open,
  Expand/Collapse, Copy path, and for the working-tree editor surfaces: New file/folder,
  Rename, Delete-red — destructive actions confirm).
- **Conversations lists (ChatsRoom cards, TreeRoom chain rows)** → long-press
  `HyleContextMenu` (Open, Star, Assign to project, Rename, Export, Delete-red) — parity
  with existing per-card buttons, which stay (clickability rule).
- **Loop editor node long-press** → keeps `HyleRadialMenu` (gesture-native) AND gains a
  context-menu parity path from the node's tap-selected state.
- **Terminal facet / keyboard-visible surfaces**: shortcut hints via `HyleKeycap`.
- **3D viewer + graph rooms**: their overlay buttons use HyleButton/HyleChip (no bare
  Material buttons on any new surface).

## 4. Verification

JVM: component state-machine/presenter tests in the Hyle repo (`:hyle:test` runs in Hyle CI;
core gate stays the arbiter for adoption compiles); grep-parity check: every
`HyleContextMenu` destructive item has a confirm; every gesture-only path named in
`docs/design/gestures.md` lists its tappable twin. Render/feel: owner-verified (probe app +
device), as always.
