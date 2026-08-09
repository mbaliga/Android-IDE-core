# WP-8b Gate Report — phone authoring surfaces

**Scope:** `06_WORK_PACKAGES.md`'s WP-8b entry: "Intent / Stage / Graph / Node Sheet / Run views per
09 §5; tap connection grammar (PHN-004) with drag as optional sugar; crash-safe journaled drafts;
AI-proposed semantic diffs with selective approval (PHN-006); local validation + simulation
surfaces; Run View as non-editable operational timeline with intervention actions (PHN-008);
accessibility parity." **This pass builds the JVM-testable domain-layer substrate those five
Compose UI views sit on top of — the same domain/no-UI split WP-3 (Workspace Kernel), WP-4
(Authority engine), and WP-8 (`LoopRunDriver`) already established for their own surfaces — not
the Compose screens themselves.** §4 explains why, in full, rather than leaving it implicit.

**Run date:** 2026-08-07. **Method:** the same real-compile-and-test standard every gate since
WP-2 — `./gradlew :core-engine:testFullDebugUnitTest`.

## Gate verdict: **GREEN** — compiled and passed on the first real build attempt

---

## 0. What was built

Five new files under `domain/loop/authoring/`, one per normative state table or algorithm
`LOOP_PHONE_AUTHORING_SPEC.md` specifies precisely enough to implement as real code, plus 45 new
tests across 7 test classes:

1. **`TouchConnectionGrammar.kt`** — `FB-RAT-PHN-004` (§7, ACCEPTED), the tap connection grammar
   that is the normative alternative to drag-a-wire (`FB-RAT-PHN-003`, REJECTED as primary). The
   spec's own 8-row state table (`IDLE → SOURCE_SELECTED → AWAITING_DESTINATION →
   DESTINATION_CHOSEN → LABELED → [CONDITIONED] → PREVIEWING → COMMITTED`, cancel-from-anywhere)
   made real, same fail-closed posture as `DocumentBufferMachine`/`WorkspaceProviderMachine`
   (WP-3): no draft mutation before `COMMITTED`, proven directly (a cancelled draft's accumulated
   fields are gone, not just its state label).
2. **`SemanticDiffProposal.kt`** — `FB-RAT-PHN-006` (§8, ACCEPTED): the review data model (ten
   `DiffOperationKind`s the spec's bullet list names, an `isAuthorityWidening` flag enforcing "MUST
   be visually separated from ordinary graph edits") plus the four-state
   `BASE_REVISION → PROPOSED → APPLIED/DISCARDED` machine, including `PROPOSED`'s self-loop
   ("user edits the proposal... re-diffed before the next transition").
3. **`RunViewActionGuard.kt`** — `FB-RAT-PHN-008` (§9, ACCEPTED): the phone Run View's
   intervention-action legality table. The spec is explicit this is **not** the canonical loop
   run-state machine (`RunState`, WP-8's `LoopRunDriver` territory) — it is a deliberately coarser
   set of interaction classes for one question only: which buttons the Run View may show right
   now. The core PHN-008 guarantee ("structural editing during a run is prohibited") is enforced
   by construction: there is no action in the vocabulary for arbitrary graph mutation, so every
   attempt is `Rejected` by the same fail-closed default as everywhere else.
4. **`DraftPersistenceLifecycle.kt`** — §13's persistence/interruption/recovery table
   (`DRAFT_CLEAN ⇄ DIRTY_JOURNALED`, `{VALIDATING,BUILDING_PACKAGE,SIMULATING,RUNNING} →
   INTERRUPTED(op)` on process death, atomic package-build resume), plus a real
   `DraftEditJournal` proving the section's `FB-RAT-COM-006` idempotency-key requirement ("a
   retried journal write is not double-applied") as tested code, not just a state label.
5. **`LoopDraftUndoStack.kt`** — `FB-RAT-PHN-011`, marked **`PROPOSED, not yet ratified`** in the
   source document itself ("this document does not self-ratify it"). Implemented anyway as real,
   tested code because the gap it names is genuine and the proposed shape is concrete — see §5 for
   why this isn't overstepping.
6. **`StageLinearizer.kt`** — §3.2's Stage View totality-by-construction requirement, the
   document's single most emphasized caveat (structurability per Kiepuszewski/Küster/Ouyang, CAiSE
   2000, and Polyvyanyy/García-Bañuelos/Dumas, BPM 2010). Computes, per gateway: back-edge/cycle
   classification (bounded repeat groups) via DFS coloring, and single-entry-single-exit
   structurability via nearest-common-rejoin + interior-overlap detection. See §3 for the one real
   bug this caught during authoring, and §6 for the honest scope boundary against the cited papers'
   full generality.

## 1. `core-engine` JVM gate — PASS, first real attempt

```
./gradlew --no-daemon :core-engine:testFullDebugUnitTest
BUILD SUCCESSFUL in 1m 57s
```

**1455 tests, 0 failures, 0 errors, 1 skipped** (the same pre-existing skip every gate since WP-1L
has reported), up from WP-8a's 1410-test baseline — exactly the 45 new tests below, zero
regressions elsewhere:

| Test class | Tests |
|---|---|
| `TouchConnectionGrammarTest` | 7 |
| `SemanticDiffProposalTest` | 7 |
| `RunViewActionGuardTest` | 8 |
| `DraftPersistenceLifecycleTest` | 7 |
| `DraftEditJournalTest` | 4 |
| `LoopDraftUndoStackTest` | 6 |
| `StageLinearizerTest` | 6 |

## 2. Fail-closed proven per-branch, not just by final-state assertion

- **`TouchConnectionGrammarTest`**: `` `cancel from every intermediate state is a pure discard back
  to IDLE, with no draft mutation before COMMITTED` `` asserts the restarted draft's
  `sourceNodeId` is `null`, not merely that `state == IDLE` — proving the discard is real, not
  cosmetic. A separate test proves `COMMITTED` itself refuses `Cancel` (nothing left to discard)
  and every other event.
- **`RunViewActionGuardTest`**: `` `structural editing has no legal action anywhere...proving
  PHN-008 by construction` `` deliberately fires actions with no structural meaning at all against
  `RUNNING` and `PAUSED` and checks they're rejected — the point being there is no verb to even
  attempt a mutation with, not that a mutation verb was checked and blocked.
- **`DraftEditJournalTest`**: `` `a retried write with an already-seen idempotency key is a no-op,
  never double-applied` `` asserts the journal size stays at 1, not 2, after the retry — the exact
  `FB-RAT-COM-006` property §13 names.
- **`LoopDraftUndoStackTest`**: `` `activation or export boundary clears both stacks and
  permanently retires this instance` `` proves a `record()` attempted *after* the boundary
  returns `false` and leaves the stacks empty — undo genuinely cannot reach back across the
  boundary, not merely discouraged from doing so.

## 3. One real bug caught while authoring `StageLinearizer`, before ever running Gradle

The first draft of the rejoin-selection logic picked the nearest node common to every branch's
forward-reachable set, without excluding the branch targets themselves from that candidate set.
Hand-deriving the intended non-structurable test fixture (a gateway with an edge jumping directly
from one branch's interior into a sibling branch) against that first draft showed it would have
picked the *sibling branch's own target node* as the "rejoin" — collapsing the crossing into the
rejoin definition and reporting the region as **structurable**, exactly backwards. Fixed by
excluding branch targets from the rejoin-candidate set (`commonToAll - branchTargetSet`) before
picking the nearest one; `` `a branch that jumps directly into a sibling branch's interior is
flagged non-structurable` `` is the regression test, and all five other `StageLinearizerTest`
fixtures (diamond, independent branches, bounded-cycle-inside-a-branch, single-branch-target) were
hand-traced against the DFS/BFS algorithm by hand before running Gradle to confirm the fix didn't
regress the happy paths — Gradle then confirmed all six on the first real attempt.

## 4. Why this pass is domain-only, not Compose screens

`LOOP_PHONE_AUTHORING_SPEC.md`'s own release gates for this surface are explicitly device-verified:
"the phone-completeness journey passes end-to-end with no browser/monitor/mouse/keyboard,"
"accessibility parity suite green," a "recorded TalkBack-only run" (§12.3 deliverable 5). This
build container has no device or emulator (`CLAUDE.md`'s "Environment honesty" rule, binding rule
6) and this codebase has no Robolectric/Compose-UI-test harness set up for `core-engine` (every
prior gate in this session has been a real JVM unit-test run, never an instrumented one) — so a
hand-written Compose screen here would compile at best and be otherwise **unverified**, the exact
class of claim this session has consistently refused to make (see WP-5's SSH honesty note, WP-8a's
§4, and every "device behaviour is owner-verified only" line in `HANDOFF_STATE.md`). The domain
layer this report documents is precisely the part of WP-8b that *is* real-JVM-testable: state
tables with fail-closed transitions, an idempotency-preserving journal, an undo stack's boundary
invariant, and a graph algorithm — the same "adapter/domain-first, UI later and owner-verified"
split this entire build-out has followed since WP-3. `ui/loops/LoopRoom.kt` (the pre-existing
1000-line hand-written Compose loop editor for the *old* BPMN/`Loop` model, not this WP's
Fonebrew-native `LoopDefinition` model) shows Compose UI without JVM tests is not unprecedented in
this codebase either — but writing more of it without any way to verify it is not this pass's
contribution to make.

## 5. Honesty note on `FB-RAT-PHN-011` (undo/redo)

The source document is explicit: `FB-RAT-PHN-011` is `PROPOSED, not yet ratified` — "the word
'undo' occurs zero times across the entire dual-surface draft... recorded here as a proposal for
the Amendments-phase agent to accept, reject, or fold into an existing ID." `LoopDraftUndoStack.kt`
implements the document's own "proposed shape" faithfully (semantic-verb-only history, one
undoable transaction per applied AI proposal, hard boundary at activation/export) because it is
fully specified and the gap is real, not because this session is ratifying it. This is the same
posture WP-3 took recording `FB-RAT-WS-NEW-1` as a proposal in
`docs/non_ratified/EXPERIMENTAL_DECISIONS.md` rather than a ruling — flagged here again rather than
silently treated as settled. **No file in `docs/ratified/` was edited to mark `FB-RAT-PHN-011`
ACCEPTED; that remains an owner/Amendments-phase decision.**

## 6. What's still genuinely unverified / honestly out of scope

- **The five Compose screens themselves** (Intent, Stage, Graph, Node Sheet, Run views) — not
  built this pass, per §4 above. A future pass with either a real device/emulator or a
  Robolectric/Compose-UI-test harness added to `core-engine` (neither exists today) is the natural
  next step; wiring the state machines above into that UI is straightforward once one exists to
  verify against.
- **`StageLinearizer` is a bounded SESE/nearest-rejoin approximation, not a full RPST/SPQR-tree
  implementation** of the cited papers — it catches the textbook non-structurable shape (a branch
  jumping into a sibling's interior) but does not claim the papers' full generality over arbitrary
  irreducible topologies. The spec's own §3.2 point 5 (a golden corpus of irreducible graphs) is
  "scoped to WP-1L's fixture work, not this document" and does not exist in this repository yet —
  this pass's six hand-traced fixtures are a start, not that corpus.
- **No AI/Distiller integration** — `SemanticDiffProposal`'s data model and state machine exist;
  nothing generates a real proposal from a model call. Same "seam, not implementation" shape as
  WP-8/WP-8a's `resolveBinding`/`resolveAuthority`.
- **`DraftEditJournal` is in-memory, not Room-backed** — see the file's own KDoc: WP-3's
  `RoomWorkspaceJournal` already proved the durable-append-across-forced-kill pattern generically
  for a different payload shape (buffer content); re-running that exact 100-iteration proof here
  would be repetition of a pattern, not new verification of a new one. This test suite instead
  proves the property specific to *this* payload shape (idempotency-key dedup) that WP-3's suite
  didn't need to. Wiring to Room is a flagged follow-up.
- **`LOOP-VALIDATE-NO-CONCURRENCY`** (§3.2, ratified in `LOOP_PHONE_AUTHORING_SPEC.md` itself, not
  yet in `loop-validation-rules.v1.json`) is relied on implicitly by `StageLinearizer`'s
  single-active-token assumption but not itself re-verified here — that registry update is
  explicitly flagged in the spec as "a follow-up for whoever owns that registry, not a change made
  in this document," and this report inherits the same scoping.
- **No wiring into `AppContainer`** — none of these six pieces has a live consumer yet, matching
  the "no consumer yet" pattern every WP since WP-2 has left for a comparable new piece.
