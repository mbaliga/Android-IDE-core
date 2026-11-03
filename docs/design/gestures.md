# Design: Message drag gestures

> Status: **built (WP4, `docs/THREAD_TOPOLOGY_PLAN.md`).** Pure classifier
> (`domain/gesture/MessageDragLogic.kt`) exhaustively JVM-tested; the Compose wrapper
> (`ui/MessageGestures.kt`) and every visual it drives (ribbon, radial fan, hints) are
> **owner-verified** — this container has no device (see CLAUDE.md "Environment honesty"),
> so gesture feel, timing precision, and haptic character are unconfirmed until the owner
> tries it on the phone.

## What this is

One touch detector on the message bubble, replacing the plain `combinedClickable`
(long-press + double-tap only) that was there before. It arbitrates five outcomes from a
single hold-then-drag gesture, per STUDIO_UX_SPEC.md §4.2 and this plan's Owner decision 1:

| Motion | Outcome | Tappable equivalent | TalkBack custom action |
|---|---|---|---|
| Hold 150ms, no drag, release | nothing (armed but idle) | — | — |
| Hold 150ms, no drag, hold to 500ms | opens TurnActionsSheet | long-press (unchanged) | "Open message actions" |
| Hold 150ms, pull up, release past ±24/±56dp | commits verdict +1/+2 | chevron row (▲/▽) | "Rate up" / "Rate down" |
| Hold 150ms, pull down, release past ±24/±56dp | commits verdict −1/−2 | chevron row | "Rate up" / "Rate down" |
| Hold 150ms, pull left, release | quotes into composer, framed as a reply | TurnActionsSheet → "Reply" | "Reply" |
| Hold 150ms, pull right, release before 400ms | quotes into composer | TurnActionsSheet → "Quote in composer" | "Quote in composer" |
| Hold 150ms, pull right, hold ≥400ms | opens the Branch/Fork/Spawn radial fan | TurnActionsSheet rows (unchanged) | "Branch/Fork/Spawn from here" |
| Two quick taps, neither one ever arming | toggles the message bookmark | 📍/📌 button (unchanged) | "Bookmark message" / "Remove bookmark" |

Every row above satisfies the parity gate (binding constraint 4): a gesture, a tappable
control, and a TalkBack custom action, all landing in the same PR — grep for the triple in
`ChatScreen.kt`'s `MessageBubble` (the `customActions` block) and `TurnActionsSheet`.

## Recorded spec divergence

STUDIO_UX_SPEC.md §4.2 specifies the **vertical** verdict-drag in detail (150ms arm,
±24/±56dp detents, judgment ribbon) but says nothing about a **horizontal** drag on a
message bubble — its own gesture table (§4, top) reserves horizontal motion for **edge**
drags (moving between spatial rooms). This WP adds a horizontal channel scoped strictly to
gestures that start on a message bubble body (never at a screen edge — verified against
`SpatialRoot.kt`'s `spatialEdgeDrag`, which claims only edge-band origins), per Owner
decision 1:

> "Pull right + release = quote into composer; pull right + hold = fan Branch/Fork/Spawn
> (`HyleRadialMenu`)."

Left was left undecided by that owner note; this WP fills it in as **reply** (the same
quote-block insertion, framed with a "Replying to" header) — a distinct, useful action that
doesn't collide with anything else in the arbitration table, and keeps the two horizontal
directions each doing exactly one thing. This is an addition to the spec, not a
contradiction of it, but it is new construction the spec text doesn't itself describe —
hence recorded here explicitly, as this plan's binding constraint 4 requires.

## Gesture arbitration (why this never fights the shell or the list)

Verified against `SpatialRoot.kt`, which this WP does not modify:

- `spatialEdgeDrag` runs on the `Initial` pointer pass but returns immediately unless the
  **down** event's position is within `edgePx` of a screen edge, and even then only claims
  (consumes) the gesture once a slop-break decides an axis matching that edge. A drag
  starting on a message bubble is essentially never at the screen edge, so this detector
  never engages for it.
- `spatialPinch` only ever engages for ≥2 simultaneous pointers.
- `Modifier.messageGestures` (this WP) never calls `PointerInputChange.consume()` until the
  150ms arm has fired — before that, a fast vertical fling is still free to be picked up by
  the enclosing `LazyColumn`'s own scroll detection, matching the plan's "LazyColumn scroll
  is beaten by the 150ms hold-arm" (not before it).

Once armed, `messageGestures` does consume every subsequent event for that pointer — this
is deliberate and matches real touch behavior everywhere else in the codebase (e.g.
`LoopCanvas`'s node drag): an armed, in-progress verdict/reply/quote/radial drag should not
also scroll the list underneath it.

## Architecture

- **`domain/gesture/MessageDragLogic.kt`** — pure, JVM-tested. A single function,
  `resolve(trace, previousTapUpAtMs)`, classifies a growing list of timestamped
  down/move/up samples (in dp, relative to the touch-down point) into one of nine `Intent`
  values. No Android/Compose types anywhere in this file. See its own KDoc for the full
  design rationale (why a stateless classifier over the whole trace, not a hand-rolled
  incremental state machine, was the better fit for exhaustive testing).
- **`domain/gesture/ComposerQuote.kt`** — pure text transform for the "insert quoted
  message into composer" behavior, shared by the drag callbacks and the tappable
  TurnActionsSheet rows so both produce byte-identical output.
- **`ui/MessageGestures.kt`** — the thin, `awaitEachGesture`-based Compose wrapper that
  feeds real pointer samples into `MessageDragLogic.resolve` and translates whatever comes
  back into haptics (`HyleHaptics`), ViewModel calls, and the two visual affordances
  (`VerdictDragRibbon`, `HorizontalDragHint`). This file is the one piece of this WP that
  cannot be exercised by a JVM test — it needs a real touchscreen, exactly like
  `SpatialRoot.kt`'s own gesture detectors before it.
- **`ChatScreen.kt`** — `MessageBubble` wires the wrapper in place of the old
  `combinedClickable`, holds the small amount of local drag-preview state (current grade,
  current horizontal direction, the radial menu's anchor), and renders `HyleRadialMenu`
  (from the `hyle-design-system` submodule) for the Branch/Fork/Spawn fan, reusing
  `ChatViewModel.branchFrom/forkFrom/spawnFrom` unchanged — the drag never has its own
  branch/fork/spawn logic, only its own way of invoking the existing one.

## Patent design-around (binding constraint 1)

The vertical channel's `Intent.CommitVerdict(grade: Int)` carries only a grade — never a
node id, a destination, or a collection. The caller (`ChatScreen.kt`) is the one that
associates a grade with `step.node.id` via the existing `ChatViewModel.setVerdict`, the same
call the chevron buttons already made before this WP. The message itself never moves in the
append-only tree; a verdict is a `CurationStore` row keyed by message id, nothing else. See
`domain/curation/Verdict.kt`'s own KDoc for the same rule from the data-model side (US
9,729,695: drag-distance selects a destination among collections; this is drag-distance
selects a judgment value, with no destination and no collection).

## Settings → Gestures

Three independent `SessionStore` booleans, each defaulting **on**:
`gestureVerdictDragEnabled`, `gestureQuoteReplyEnabled`, `gestureRadialFanEnabled`. Turning
one off collapses that channel's live half of `MessageGestureCallbacks` to a no-op inside
`Modifier.messageGestures` — the tappable equivalent stays reachable regardless, since it is
wired independently (not gated by these flags at all). Long-press and double-tap-to-bookmark
are not behind a toggle — they were already the un-gestured baseline before this WP and stay
that way.

## What is explicitly owner-verified, not machine-verified

- Whether 150ms/400ms/500ms *feel* right on a real screen (the constants are named in
  `MessageDragLogic` precisely so they're one place to retune after a device try).
- Whether the radial fan visually clips against the edge of a narrow user-message bubble —
  `HyleRadialMenu`'s own KDoc calls for enough surface to draw its 76dp arc + labels;
  `MessageBubble` gives it a `Box` sized to the Card, not the full screen, to keep its
  anchor coordinate space simple (no cross-composable conversion) — see `MessageGestures.kt`
  for the trade-off this makes.
- Haptic character: `HyleHaptics` (the `hyle-design-system` submodule) does not yet have
  dedicated arm-tick/verdict-detent/radial-open haptic constants (the plan's own inventory
  flags this: "`HyleHaptics` (needs arm-tick/detent additions)"). This WP reuses the
  existing `tap()`/`settle()` vocabulary rather than adding new ones to a **separate git
  submodule repository** out of this session's scope — a real forward-pointer, not a
  shortcut: whoever picks up the Hyle-side haptic vocabulary work should route
  `MessageGestures.kt`'s `haptics.tap()` calls to distinct arm/detent/radial constants once
  they exist there.
- Whether `withTimeoutOrNull` racing `awaitPointerEvent()` inside `awaitEachGesture`
  (needed so the 150/400/500ms thresholds fire even while the finger sits perfectly still)
  behaves smoothly under real frame timing — the technique mirrors Compose Foundation's own
  `detectTapGestures` long-press implementation, but this is this codebase's first use of it
  outside that library, and it deserves a real-device pass.
