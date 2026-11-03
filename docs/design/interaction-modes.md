# Design: Interaction modes — Regular / asoc

> Status: **built (bifurcation wave 1, lane R).** The mode-resolution logic
> (`dev.aarso:interaction-mode`'s `ModeDefaults` + this repo's own
> `domain/mode/ModeLegacySignal.kt`, `domain/mode/GestureModeDefaults.kt`, and
> `ui/regular/RegularShellPresenter.kt`) is pure and JVM-tested. The two shells themselves
> (`ui/spatial/SpatialRoot.kt`, `ui/regular/RegularShell.kt`) and everything about how they
> render/feel are **owner-verified** — this container has no device (see CLAUDE.md
> "Environment honesty").

## The ruling (owner, 2026-09-15, quoted for context)

> The rooms model remains somewhat experimental while we perfect it; users choose between a
> traditional 'Regular' layout/interaction pattern and an 'asoc' mode with the enhanced style
> of interactions; this goes to the other apps as well.

Naming is exactly **"Regular"** and **"asoc"** (lowercase `asoc`) at the UI layer — never
"Spatial", "Classic", or any other synonym. This is a constellation-wide split, not a
Fonebrew-only one: the shared choice lives in `dev.aarso:interaction-mode`
(`mbaliga/Shared-Libraries-asoc`) precisely so every app in the constellation can adopt the
same enum, store, and picker rather than re-deriving its own.

## The two modes

**Regular** — a conventional layout: a bottom tab bar, visible buttons, everything reachable
by tap. This is `ui/regular/RegularShell.kt`: a `Scaffold`-shaped stance hosting the *same*
room composables `SpatialRoot` mounts spatially (`ChatScreen`, `ChatsRoom`, `ProductRoomFree`
via the same Studio seam, `DevelopRoom`, `SettingsRoom`), switched by
`HyleBottomTabBar` — the same tab-bar component every room's own internal tab row already
uses, not a new one invented for this shell.

**asoc** — the spatial-rooms layout and its gesture grammar: edge drags, pinch-to-zoom along
the z-axis, room "parking", the `SpatialMapOverlay` teaching. This is `ui/spatial/
SpatialRoot.kt`, **unchanged** by this lane.

## What's deliberately NOT in Regular mode

- **No edge-drag or pinch handlers.** `RegularShell` registers none of `SpatialRoot`'s
  `spatialEdgeDrag`/`spatialPinch` modifiers. Regular's whole premise is that nothing is
  gesture-only.
- **No spatial "parking."** A room fills the screen when its tab is selected; there is no
  lifted/parked home card, no grip pill, no `SpatialController` progress values.
- **No `SpatialMapOverlay`.** That teaching is specific to the spatial gesture grammar; it
  never shows in Regular mode (see "Mode resolution" below for how a returning asoc user
  still sees it once, the first time they're actually in the spatial shell).
- **Tree and Loops are not tab destinations.** Regular reaches them exactly the way lane Q
  made them reachable everywhere: Tree from Chat's header affordance (`onOpenTree`), Loops
  from Develop's entry (`onOpenLoops`) — each opens as a full-screen overlay above the tab
  content, not a sixth/seventh tab. `GraphRoom` stays reachable via the Tree room's own
  "Graph" tab in both shells, unchanged.
- **Message-bubble gestures default OFF, not just "still available."** See below — this is a
  *default*, not a removal; an explicit Settings choice always wins.

## Defaults policy

`dev.aarso.interactionmode.ModeDefaults.defaultFor(hasExplicitChoice, legacySignal)`:

- No explicit choice, no legacy signal → **REGULAR**. Every fresh install starts here.
- No explicit choice, legacy signal → **ASOC**. Continuity for an install that was already
  living in the (until now, only) spatial shell.
- Any explicit choice always wins, in either direction, forever (until changed again).

Fonebrew's own `legacySignal` answer (`domain/mode/ModeLegacySignal.kt`): **`onboardingDone
|| spatialMapSeen`**, both already persisted by `SessionStore` before this bifurcation
existed. Either fact alone means this install completed a run of the app back when the
spatial shell was the *only* shell, so it keeps landing there rather than being silently
dropped into an unfamiliar Regular layout. `SessionStore.legacySignal(context)` reads this
directly off `SessionStore`'s own prefs file — a static helper, not a full `SessionStore`
instance — because `AppContainer` needs it to construct `interactionModeStore` *before*
`SessionStore` itself exists (that constructor, in turn, needs the resolved mode back, for its
own gesture-toggle defaults below).

**Message-bubble gesture toggles** (verdict drag / quote-reply drag / radial fan,
`docs/design/gestures.md`) follow the same "explicit choice always wins" shape one level down:
`domain/mode/GestureModeDefaults.resolve(hasExplicitChoice, explicitValue, mode)` — a toggle
nobody has touched yet defaults **OFF** in REGULAR (the tappable parity surfaces — chevrons,
the long-press sheet — are primary there) and **ON** in ASOC (unchanged, the shipped
gesture-first default). Once a user flips a toggle explicitly, that choice holds regardless of
mode. **Named follow-up, not a silent gap:** this default is resolved once, at `SessionStore`
construction — if the interaction mode changes later in the same process while a gesture
toggle is *still* unset, that toggle's default does not recompute live (see that constructor's
own KDoc). A real consumer for that edge is rare — mode is normally chosen once, in
onboarding, before anyone visits Settings → Gestures.

**Onboarding** inserts a mode-choice page (`ui/OnboardingScreen.kt`, page 2 of 4, between the
two stance pages and model setup) using the shared `HyleModePicker` +
`ui/mode/InteractionModeOptions` copy. Choosing asoc there also explicitly clears
`spatialMapSeen`, priming the teaching to show the first time this install actually lands in
the spatial shell. Skipping the wizard entirely, or reaching model setup without ever picking
a mode on that page, records no explicit choice — the REGULAR default holds (a fresh install
can never carry a legacy signal; onboarding hasn't completed yet by definition).

**Settings → General → "Interaction style"** (`ui/rooms/SettingsRoom.kt`, near "How to move
around") opens the same picker in a bottom sheet, current mode shown, switching live — no
restart. This is possible because `AppRoot` branches on `AppContainer.interactionMode`, a
`StateFlow` wrapper (`data/InteractionModeBridge.kt`) over the shared module's plain-getter
`InteractionModeStore` — see that bridge's own KDoc for why the wrapper exists at the app
layer rather than as a change to the shared, dependency-free module.

## Constellation adoption note

`dev.aarso:interaction-mode` and Hyle's `HyleModePicker` are built generic on purpose — plain
`InteractionMode.REGULAR`/`.ASOC` and caller-supplied `HyleModeOption` copy, no Fonebrew-only
assumptions. This repo is the first adopter; wiring the other constellation apps onto the same
enum/store/picker (per the ruling's "this goes to the other apps as well") is a follow-up
wave, tracked outside this repo.
