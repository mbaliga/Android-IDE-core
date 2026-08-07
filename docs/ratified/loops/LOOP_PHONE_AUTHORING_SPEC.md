# Phone-First Loop Authoring Specification

> **License:** `LICENSE-PENDING` — see `docs/non_ratified/LICENSE_PENDING.md`.

**Status:** RATIFIED (this document's own sections), with two named exceptions carried at their
true status: `FB-RAT-PHN-010` is **EXPERIMENTAL** and `FB-RAT-PHN-011` is **PROPOSED, not yet
ratified**. Both are called out where they appear; see §10 and §14. **Scope:** the phone-native
loop authoring and run-intervention surface — the five views, the touch and pointer interaction
grammars, AI-assisted editing, safety gating, accessibility, persistence, undo, and the
phone-completeness acceptance gate. This document is the citation target for `FB-RAT-PHN-001`
through `FB-RAT-PHN-011` and, in the one section where pointer input is defined as *not* a second
information architecture, `FB-RAT-LBX-006`. **Correction (WP-1L gate, `docs/WP1L_GATE_REPORT.md`
§5 Defect 1):** this document is ALSO the canonical home of `FB-RAT-LBX-002` — the dual-surface
ratification register (`inputs/dual_surface/DUAL_RATIFICATION_REGISTER.md`) names
`docs/ratified/LOOP_PHONE_AUTHORING_SPEC.md` as `FB-RAT-LBX-002`'s repository effect, not
`LOOP_DUAL_SURFACE_ARCHITECTURE.md` — the paragraph below previously mis-cited the canonical home
as that document, creating a circular pointer where neither document actually declared ownership.
Fixed here. It does not restate the rest of the dual-surface architecture that grounds this
surface's existence (`FB-RAT-LBX-001`, `FB-RAT-LBX-004`, `docs/ratified/
LOOP_DUAL_SURFACE_ARCHITECTURE.md` — a separate WP-1L output), the loop language's frozen
container/digest/validation-code/capability-ID concepts (`docs/ratified/loops/
LOOP_FROZEN_CONCEPTS_WP1L_G0.md`), or the common envelope vocabulary (`docs/ratified/
COMMON_CONVENTIONS.md`, `contracts/kotlin/CommonContracts.kt`) — those are cross-referenced only.

**Why this surface is load-bearing.** `FB-RAT-LBX-002` — ACCEPTED, **canonical home: this
document** — makes the phone app "the primary, complete, untethered authoring and execution
surface; the browser is optional." Everything below is what makes that claim true rather than
aspirational: a phone editing model that stays legible at real graph sizes, a touch connection
grammar that does not depend on precision pointer input, and an explicit, testable completeness
gate.

## Note on this document's structure

The extracted first-pass/second-pass draft this document replaces used one 1–21 section sequence
but stated the same topic twice under two different numbers in two places (draft §9 vs §19,
"pointer mode"; draft §11 vs §20, "accessibility") and split one topic in half in two more (draft
§4 vs §15, "stage card fields"; draft §13 vs §17, "phone completeness / one-handed creation";
draft §12 vs §18, "persistence / interruption recovery"). Every one of the 21 draft sections is
folded into exactly one section below; nothing from the draft is dropped. A `LOOP-VALIDATE-
NO-CONCURRENCY` rule, an undo/redo requirement (`FB-RAT-PHN-011`), and several phone-completeness
amendments are new normative text that did not exist in the draft at all — each is flagged inline
as new where it appears.

Any other document in this handoff pack that cites this spec by a draft section number (e.g. the
dual-surface register's `PHONE §9`, `PHONE §13`) should re-anchor against this table, not the
draft:

| Draft § | Draft topic | This document's § |
|---|---|---|
| 1 | Product requirement | §1 |
| 2 | Core authoring principles | §2 |
| 3 | Five-view information architecture | §3 |
| 4 | Stage grammar (card fields, first pass) | §4 |
| 5 | New Loop entry points | §6 |
| 6 | Touch connection grammar | §7 |
| 7 | AI-assisted structural edits | §8 |
| 8 | Run interaction | §9 |
| 9 | Pointer and external-display mode (first pass) | §10 |
| 10 | Safety and preflight | §11 |
| 11 | Accessibility (first pass) | §12 |
| 12 | Persistence and recovery (first pass) | §13 |
| 13 | Phone-completeness acceptance scenario (first pass) | §15 |
| 14 | Phone information-density law | §16 |
| 15 | Stage card anatomy (second pass) | §4 |
| 16 | Branch editing | §5 |
| 17 | One-handed creation target | §15 |
| 18 | Interruption and recovery (second pass) | §13 |
| 19 | Pointer mode details (second pass) | §10 |
| 20 | Accessibility acceptance (second pass) | §12 |
| 21 | Phone performance budgets | §17 |

Section numbers within list items in the draft (e.g. items numbered 62–74 across draft §5–§6) were
an artifact of the source document's document-wide list numbering, not a meaningful sequence —
every list below is renumbered locally, from 1, within its own section.

---

## 1. Purpose and scope

The in-app builder MUST be a complete authoring environment for the phone in hand. External
display, keyboard, and mouse are accelerators, never requirements: a user MUST NOT be forced to
return to a browser to understand, repair, or complete an imported or newly authored loop
(`FB-RAT-LBX-002`; the rejected alternative this guards against, "browser as real editor, phone as
player," is `FB-RAT-LBX-005` — REJECTED, `docs/non_ratified/REJECTED_ALTERNATIVES.md`). This
document specifies that environment: its five views (§3), its card and connection grammars (§4,
§5, §7), its AI-editing and run-intervention contracts (§8, §9), its pointer/external-display
accelerator mode (§10), its safety gate (§11), its accessibility and persistence obligations (§12,
§13), a new undo/redo requirement (§14), and the acceptance scenario that operationalizes
completeness (§15).

## 2. Core authoring principles

These are design principles, not individually testable obligations (`FB-RAT-COM-001` reserves
MUST/SHOULD/MAY for testable claims); each is made testable by a later section.

- Vertical comprehension before spatial precision — realized as Stage View being the primary
  editing view (§3.2, `FB-RAT-PHN-002`).
- Progressive disclosure: objective → stages → topology → node mechanics → raw contract —
  realized as the phone information-density law (§16).
- Visible actions before gestures — realized throughout §4, §7, §12.
- Interruption-safe drafts and operations — realized as §13.
- Semantic selection rather than pixel-precise manipulation — realized as §7's tap connection
  grammar and the `FB-RAT-PHN-003` rejection of drag-as-primary.
- Every side effect and remote boundary legible — realized as the stage card's authority summary
  (§4) and the preflight gate (§11).
- Run intervention separate from structural editing — realized as §9 (`FB-RAT-PHN-008`).

## 3. Five-view information architecture

**`FB-RAT-PHN-001` — ACCEPTED.** Intent, Stage, Graph, Node Sheet, and Run are adopted as the
complete phone authoring and execution model; no sixth view is required for completeness (§15).

### 3.1 Intent View

Shows objective, inputs, outputs, main stages, expected side effects, authority envelope, typical
targets, cost/time envelope, tests, and the current validation summary. It supports
natural-language loop creation through Distiller and direct edits to objective or constraints.
Distiller output is a draft plus explanation; it MUST NOT publish or activate a loop automatically
(carried forward into the New Loop flow, §6).

### 3.2 Stage View — the primary editing view

**`FB-RAT-PHN-002` — ACCEPTED.** Stage View is the primary phone editing view for sequence and
branching; Graph View (§3.3) remains available for topology. It renders a vertical narrative of
stages: branches appear as nested outcome cards and rejoin markers, repetition appears as bounded
cycle cards, and subloops collapse into named stages. Stage View MUST support: add; insert
before/after; duplicate; reorder where semantics allow; connect; branch; wrap as subloop; disable
optional node; and delete with impact preview.

**Stage View MUST be total by construction — a graph that cannot be linearized is not an escape
hatch out of the phone surface.** Kiepuszewski, Küster & Ouyang ("Fundamentals of Control Flow in
Workflows," CAiSE 2000) established that arbitrary control-flow graphs are not all expressible as
properly nested sequence/choice/repetition — there is a genuine expressive gap between arbitrary
and *structured* workflow graphs. Polyvyanyy, García-Bañuelos & Dumas ("Structuring Acyclic
Process Models," BPM 2010) give a necessary-and-sufficient condition for when a graph region *is*
structurable. Some legally-constructed loop graphs are therefore inherently non-structurable, and
naive linearization is not always possible. Stage View resolves this without falling back to "open
Graph View to edit this" — which would violate `FB-RAT-LBX-005`'s rejection and make `FB-RAT-PHN-
007` (§15) untestable for any loop containing such a region — as follows:

1. The validator MUST compute structurability per region as part of validation (extends the
   `LOOP-GRAPH` namespace of `schemas/loops/registries/loop-validation-rules.v1.json`).
2. A structurable region MUST render as nested outcome cards per the rules above.
3. A non-structurable region MUST render as exactly one explicitly labelled **"unstructured
   region" card**, containing a flat node list for that region plus named inbound and outbound
   jump markers identifying where control enters and leaves it. This card is fully editable in
   place (node sheet, connect, delete) — it is not a read-only placeholder and it is not a
   redirect to Graph View.
4. Rendering MUST NOT silently flatten a non-structurable region into a false linear sequence —
   doing so would misrepresent the loop's actual control flow to the person editing it.
5. The conformance test suite for this rule SHOULD include a golden corpus of irreducible graphs
   (graphs containing no structurable decomposition) so the unstructured-region card path has
   dedicated coverage, not just incidental coverage from structurable fixtures. This corpus does
   not yet exist in this repository; it is scoped to WP-1L's fixture work, not this document.

**Rule `LOOP-VALIDATE-NO-CONCURRENCY` (new, ratified here — not yet a code in the frozen
`loop-validation-rules.v1.json`, since that registry's v1 predates this rule's identification;
carrying it forward into a future `loop-validation-rules.v2` is a follow-up for whoever owns that
registry, not a change made in this document).** The loop language MUST NOT support parallel
execution (no AND-split, no AND-join) in this version: at any point during a run, at most one
token is active per loop instance. A subloop invocation transfers the active token into the
subloop and back; it never forks it. This is a structural language rule, not merely a Stage View
rendering convenience — it is exactly what keeps Stage View's totality-by-construction guarantee
above tractable (a single-token graph's non-structurable regions are still boundedly enumerable;
concurrent tokens would not be), and it was never previously stated as a rule anywhere in the
source pack. None of the loop language's eight node categories (deterministic transformation,
model inference, typed tool, human decision, bounded subloop, verifier, artifact I/O, wait/event
gate — `schemas/loops/registries/...` node-category enumeration referenced from `docs/ratified/
loops/LOOP_FROZEN_CONCEPTS_WP1L_G0.md`) is a fork/join primitive, so this rule requires no
language change today — it only needs to be written down so nothing downstream (this Stage View
guarantee among them) is quietly invalidated by a future fork/join addition without a design for a
parallel-region card.

### 3.3 Graph View

A complete topology overview with semantic zoom, used for locating branch structure, cycles,
disconnected components, and failed nodes. Precision editing here is optional, never required —
Stage View (§3.2) and the unstructured-region card cover every editable case a phone-only user
needs.

### 3.4 Node Sheet

A bottom sheet / full-screen inspector containing: purpose and explanation; node category; typed
input/output ports; model/tool/binding slot; requested capabilities; permitted execution targets;
side-effect class; timeout, retry, idempotency, compensation; verification; failure paths; test
fixtures; and a raw contract view.

**Schema and long-text authoring (new normative text — amends the phone-completeness gap in
§15).** The Node Sheet's raw-contract view MUST NOT be the only phone-side way to author a typed
schema: it MUST offer a structured JSON Schema editor (form-driven property/type/enum/constraint
pickers) with a raw-JSON escape hatch that live-validates against JSON Schema draft 2020-12 (or
later ratified version, matching `LOOP-SCHEMA-002`). Any Node Sheet text field whose content
commonly exceeds roughly 200 characters (prompts, instructions, documentation fields) MUST offer a
full-screen, distraction-free editing mode with per-field autosave. Every long-text field MUST
offer first-class dictation, routed through ASOM so voice input stays on-device/sovereign rather
than adding a second cloud dependency alongside the model-provider one. Long-text fields MUST also
offer import-text-from-file and import-from-share-sheet, so a user is never limited to on-glass
typing for content authored elsewhere. Distiller (§3.1, §6) is the intended amortiser for
documentation fields specifically — a user MAY ask Distiller to draft documentation from the
node's other fields rather than typing it — but this does not relax the completeness gate in §15,
which requires demonstrating manual authoring too.

### 3.5 Run View

A vertical operational timeline with current node, completed nodes, retries, waits, remote
boundaries, authority requests, outputs, receipts, and intervention actions (detailed in §9).
Editing the graph from Run View creates a new draft branch and MUST NOT mutate an active
execution — the mechanism behind `FB-RAT-PHN-008` (§9). The canonical run-state machine itself
(the full set of states a running loop instance passes through) is owned by the loop
execution/run contract, not this document; nothing here about the Run View invents or restates
that machine.

## 4. Stage card anatomy and grammar

*(Merges draft §4 "Stage grammar" and draft §15 "Stage card anatomy," which described the same
card at two levels of detail.)*

Each stage card, collapsed, MUST show: title (action, in plain language); node category; executor
glyph and label; local/watched/remote target-class label; model/tool binding state; expected
output; authority badge (the authority summary); validation state; and a branch/loop summary where
applicable. An overflow menu carries secondary actions.

Expanding a card MUST reveal: inputs and outputs; retry policy; timeout; budget contribution;
verifier; failure path; and linked tests. **Destructive or publish-authority stages MUST NOT be
able to collapse away their risk indicator** — the authority/risk badge stays visible in both
collapsed and expanded state, independent of whichever other fields collapse hide.

Branches render as outcome lanes under their gateway. A user MAY enter a branch, edit it as a
vertical sequence (§3.2's structurable-region rendering), and return to the gateway. Rejoins MUST
explicitly name their destination stage — a rejoin arrow with no stated destination is not a valid
render of this grammar, consistent with `LOOP-VALIDATE-NO-CONCURRENCY` (§3.2): a rejoin is where
the single active token re-enters the main sequence, and that re-entry point MUST be nameable.

## 5. Branch editing

A branch is edited as a labeled decision group, not as raw graph coordinates: the user selects the
gateway, views its outcomes in order, edits typed conditions through a form or an expression
editor, adds a default/failure route, and previews reachable stages before committing. The
condition/expression editor MUST offer a keyboard accessory row of condition/expression tokens
(comparison operators, boolean connectives, common field references) so condition authoring does
not require a desktop keyboard — the same phone-completeness concern that drives the Node Sheet
requirements in §3.4.

The UI MUST warn when branches overlap, omit cases, or would create an unbounded cycle — this is
the phone-editing-time surface of validation rules already frozen in `schemas/loops/registries/
loop-validation-rules.v1.json`: `LOOP-GRAPH-003` (gateway not exhaustive, no default/terminal-
failure path) and `LOOP-GRAPH-004` (cycle with no statically visible bound). Graph coordinates
MUST NOT be required to understand or edit branch semantics — everything above is expressible and
editable from the labeled decision group alone.

## 6. New Loop entry points

A new loop MAY be started by any of:

1. Describing what is wanted, in natural language (Distiller).
2. Starting from a pattern.
3. Building step by step, stage by stage.
4. Importing a package, URL, QR code, or Git reference.
5. Remixing an installed loop.
6. Converting a successful run into a draft pattern, where provenance permits.

Distiller outputs a draft and an explanation; it MUST NOT publish or activate a loop automatically
(restated from §3.1 because this is the entry point where that guarantee matters most — a user's
very first interaction with a new loop must not be an accidental live activation).

## 7. Touch connection grammar

**`FB-RAT-PHN-003` — REJECTED.** Drag-a-wire (precision edge dragging) as the only or primary way
to connect nodes on a phone is rejected (`docs/non_ratified/REJECTED_ALTERNATIVES.md`). Drag-a-
wire MAY exist as an *additive* gesture for users who want it, but it is never the only path to a
connection, and TalkBack exposes the same connection as source/destination/label actions
regardless of whether drag-a-wire is present (§12).

**`FB-RAT-PHN-004` — ACCEPTED.** The tap connection grammar below is the normative touch
connection flow. It is a linear procedure over an editing session's connection-draft state,
rendered here as an explicit state table rather than a step list with implied arrows:

| From state | Event | To state | Notes |
|---|---|---|---|
| `IDLE` | User selects a source stage/node | `SOURCE_SELECTED` | No draft mutation yet. |
| `SOURCE_SELECTED` | User taps "Connect from here" | `AWAITING_DESTINATION` | |
| `AWAITING_DESTINATION` | User selects an existing destination, or creates a new node | `DESTINATION_CHOSEN` | Node creation reuses the Node Sheet (§3.4). |
| `DESTINATION_CHOSEN` | User chooses the output port and edge label | `LABELED` | |
| `LABELED` | User defines a condition, where the gateway requires one (§5) | `CONDITIONED` | Skipped (falls through directly to `PREVIEWING`) when no condition is required. |
| `LABELED` or `CONDITIONED` | User requests preview | `PREVIEWING` | Affected paths are shown before commit. |
| `PREVIEWING` | User commits | `COMMITTED` | Creates a new draft revision (§13); this is the only state in the table that mutates the draft. |
| `SOURCE_SELECTED` / `AWAITING_DESTINATION` / `DESTINATION_CHOSEN` / `LABELED` / `CONDITIONED` / `PREVIEWING` | User cancels | `IDLE` | No draft mutation occurs before `COMMITTED`, so cancellation from any intermediate state is a pure discard. |

## 8. AI-assisted structural edits

**`FB-RAT-PHN-006` — ACCEPTED.** Distiller or any AI-assisted graph change MUST produce an
inspectable semantic diff and require explicit approval before it changes the draft. The review
MUST show: nodes added, removed, replaced, or reordered; edges and conditions changed; schemas
changed; new capabilities or side effects; budget changes; test changes; expected-behavior change;
and validation results before and after. Authority-widening changes MUST be visually separated
from ordinary graph edits, not interleaved with them in one undifferentiated list.

| From state | Event | To state | Notes |
|---|---|---|---|
| `BASE_REVISION` | Distiller/AI proposes an operation list over the base revision | `PROPOSED` | Nothing in the draft has changed yet — a proposal is inert until applied. |
| `PROPOSED` | User edits the proposal (e.g. removes one operation from the list) | `PROPOSED` | Self-loop; re-diffed before the next transition. |
| `PROPOSED` | User approves | `APPLIED` | Becomes a new draft revision; per §14, this MUST be recorded as exactly **one** undoable transaction, however many individual operations the proposal contained. |
| `PROPOSED` | User rejects | `DISCARDED` | Base revision is unchanged; equivalent to `BASE_REVISION`. |

## 9. Run interaction

**`FB-RAT-PHN-008` — ACCEPTED.** During execution the phone MUST present a run timeline and node
state (§3.5) rather than an editable canvas. Editing the graph while a run is active MUST fork
into a new draft revision without mutating the active execution; where the underlying runtime
cannot safely branch without first halting the active instance, the surface MUST pause or stop
before branching rather than attempt a live mutation. Structural graph edits never alter an active
run by any other path.

Allowed active-run actions, and the simplified interaction-state classes they are legal from (this
table describes the *phone Run View's* action legality only — it is not the canonical loop
run-state machine, which is owned by the loop execution/run contract, not yet written in this
repository):

| From state class | Event | To state class | Notes |
|---|---|---|---|
| `AWAITING_AUTHORITY` | User approves requested authority | `RUNNING` | |
| `AWAITING_AUTHORITY` | User denies requested authority | `TERMINAL(recovery path or CANCELLED)` | Denial is a legitimate outcome, not an error state. |
| `AWAITING_DECISION` | User provides the human decision input | `RUNNING` | |
| `RUNNING` | User inspects current inputs and permitted context | `RUNNING` | Read-only; no state change. |
| `RUNNING` | User requests pause, where the runtime supports safe suspension | `PAUSED` | Not every runtime/target supports safe suspension — this is a conditional transition, not a guarantee. |
| `PAUSED` | User resumes | `RUNNING` | |
| `RUNNING` / `PAUSED` / `AWAITING_AUTHORITY` / `AWAITING_DECISION` | User cancels | `TERMINAL(CANCELLED)` | |
| `TERMINAL(failed, idempotent node)` | User retries the failed node | `RUNNING` | Retry is only offered where the failed node is declared idempotent. |
| `TERMINAL(failed)` | User chooses a declared recovery path | `RUNNING` (recovery branch) | Only declared recovery paths are offered; the surface never improvises one. |
| any state, including terminal | User forks from the current receipt into a new draft | *(unchanged)* — new state in Stage View: `BASE_REVISION` (§8) | This is the one action that reaches into editing; it never mutates the run it forked from. |

## 10. Pointer and external-display mode

*(Merges draft §9 "Pointer and external-display mode" and draft §19 "Pointer mode details," which
described the same accelerator mode at two levels of detail.)*

**`FB-RAT-LBX-006` — ACCEPTED.** Pointer/keyboard/external-display mode accelerates in-app
authoring; it does not define a second, competing information architecture. Every command
available under pointer input is an accelerator over a command already reachable by touch (§4,
§5, §7) — pointer mode adds speed, not capability.

Pointer detection MAY enable: hover help; right-click menus; direct/edge-drag wiring; marquee/
lasso selection; resize handles; multi-select; copy/paste; alignment guides; keyboard shortcuts;
and split inspectors. An external display MAY concurrently show graph, inspector, tests, and
documentation; the phone MAY concurrently act as approval deck, run controller, model selector, or
touchpad.

| State | Event | State | Notes |
|---|---|---|---|
| `PHONE_LAYOUT` | Pointer/external display connects | `POINTER_LAYOUT` | Selection, draft revision, viewport focus, and unsaved editor state carry over unchanged. |
| `POINTER_LAYOUT` | Pointer/external display disconnects | `PHONE_LAYOUT` | MUST restore an understandable phone layout with no data loss — carrying over the same state listed above. |

**Pointer-only state is prohibited**: there is no reachable app state that exists only under
`POINTER_LAYOUT` and has no phone-reachable equivalent — every capability pointer mode adds is an
accelerator (above), never a gate.

**`FB-RAT-PHN-010` — EXPERIMENTAL** (`docs/non_ratified/EXPERIMENTAL_DECISIONS.md`; not yet
owner-verified). Detachable inspectors, lasso selection, and simultaneous graph/test panes are
treated as pointer-mode experiments, not committed shape, until owner verification on real
external-display hardware. Nothing in `POINTER_LAYOUT`'s dense-layout affordances may be assumed
stable until that verification lands.

## 11. Safety and preflight

**`FB-RAT-PHN-009` — ACCEPTED** (canonical ratification home: `docs/ratified/
LOOP_IMPORT_ACTIVATION_CONTRACT.md`, a separate WP-1L output not yet written in this repository;
cited here because this is where the phone-side preflight surface is specified). Before the first
activation of any loop — imported or newly authored — that requests write, external, destructive,
credential, device, release, or publish authority, the app MUST show: the requested capabilities;
the selected bindings; potential side effects; simulation availability; unverifiable paths;
compensation limits; and budget boundaries. This gate fires once per loop, at first activation,
regardless of whether the loop arrived by import or by on-phone authoring — authorship does not
exempt a loop from preflight.

## 12. Accessibility

*(Merges draft §11 "Accessibility" and draft §20 "Accessibility acceptance," which described the
same requirement at two levels of detail, and applies the correction below.)*

### 12.1 General principles

Every gesture MUST have a visible control and a TalkBack alternative. Reading order MUST follow
semantic execution order, not canvas coordinates. Branch and rejoin relationships MUST be
announced textually (not merely implied by visual nesting). Node state MUST NOT be color-only.
Keyboard navigation MUST support stage, branch, node sheet, and run timeline. A reduced-motion mode
MUST remove animated canvas transitions without reducing the information those transitions
conveyed.

### 12.2 TalkBack acceptance criteria

TalkBack MUST expose: stage order; branches; connection destinations; node state; authority;
validation findings; and run progress. A TalkBack-only user MUST be able to reorder stages, create
connections, open node details, approve semantic diffs (§8), and intervene in runs (§9) without any
spatial gesture. Dynamic graph updates MUST announce a concise semantic change (e.g. "stage 3 now
connects to stage 5 on failure") rather than raw coordinate movement.

### 12.3 `FB-RAT-PHN-005` — scope and sizing correction

**`FB-RAT-PHN-005` — ACCEPTED.** "Every graph action has visible controls, TalkBack actions,
keyboard equivalents where applicable, and no color-only state." As stated in the draft this was
one bullet for what is, on the evidence of comparable shipped work, a multi-year problem: Blockly
— Google's own block editor, with a dedicated accessibility fund behind it — shipped keyboard
navigation only as an *experimental* plugin in May 2025, with screen-reader ARIA work still
forward-looking at that point. Treating full parity as a single P0 line item alongside Stage View
misrepresents its size. This document splits `FB-RAT-PHN-005` into five named deliverables, sized
comparably to Stage View (§3.2) itself, and states an explicit priority split rather than leaving
"full parity" as an undated aspiration:

1. **Semantics contract** for stage, branch, and rejoin — the textual-announcement shape §12.1/
   §12.2 require, specified precisely enough to be fixture-tested (not just prose).
2. **Custom-action inventory per structural verb** — every Stage View verb in §3.2/§4 (add,
   insert, duplicate, reorder, connect, branch, wrap as subloop, disable, delete) has a named,
   documented TalkBack custom action, not an ad hoc gesture mapping.
3. **Screen-reader outline view** — a linear, navigable outline of the whole loop, independent of
   Stage View's card rendering, for orientation before diving into card-by-card navigation.
4. **A TalkBack + Switch Access + 200%-font matrix** — the three assistive configurations tested
   together, not just TalkBack alone, since font scaling and Switch Access both change what "one
   action" means for a structural verb.
5. **A recorded TalkBack-only run of the §15 acceptance scenario** — the same phone-completeness
   scenario `FB-RAT-PHN-007` requires sighted-and-touch, performed and recorded TalkBack-only, as
   the closing acceptance artifact for this decision.

**Priority split (explicit, so this does not silently become an indefinitely deferred P1 the way
the draft's single bullet risked becoming):** deliverables 1 and 2 above — the semantics contract
and the custom-action inventory for the core structural verbs (add, connect, branch, delete) — are
**P0**: a loop MUST be TalkBack-navigable and editable for these core verbs before this surface
ships. Deliverables 3, 4, and 5 are **P1**: they harden and prove the P0 claim but do not block
initial ship. This is the honest downgrade the correction offered as an alternative to the full
five-deliverable P0 slate; this document takes the "downgrade with an explicit P1 list" path
rather than leaving the scope unstated.

**Mechanism note (Jetpack Compose).** Compose's TalkBack traversal order is strictly linear —
`traversalIndex` sorts within a semantics group — so a rejoin (§4) cannot be represented by
traversal order alone; a join has more than one predecessor, and linear order can only place one
of them immediately before it. Implementations on this codebase's Compose UI layer (`ui/loops/`
per `CLAUDE.md`'s repo map) MUST attach `Modifier.semantics { customActions = ... }` per structural
verb (deliverable 2) plus explicit "go to predecessor N" / "go to successor N" actions on every
node with more than one neighbor, and MUST announce each node's inbound edges individually rather
than relying on visual arrangement to imply them.

## 13. Persistence, interruption, and recovery

*(Merges draft §12 "Persistence and recovery" and draft §18 "Interruption and recovery," which
described the same draft-journaling and process-death behavior at two levels of detail.)*

| From state | Event | To state | Notes |
|---|---|---|---|
| `DRAFT_CLEAN` | User edits a Node Sheet or Stage View field | `DIRTY_JOURNALED` | The field-level edit is journaled immediately — not deferred to an eventual commit. A journal entry SHOULD carry an idempotency key (`FB-RAT-COM-006`, `docs/ratified/COMMON_CONVENTIONS.md` §6) so a retried journal write is not double-applied. |
| `DIRTY_JOURNALED` | Edit is accepted (navigation away, explicit save, or an equivalent commit event) | `DRAFT_CLEAN` | Produces a new draft revision. |
| `DIRTY_JOURNALED` | Process death | `DIRTY_JOURNALED` (persisted) | On relaunch, the same draft and the same view focus MUST be restored from the journal — zero lost edits across a forced process death. |
| `VALIDATING` / `BUILDING_PACKAGE` / `SIMULATING` / `RUNNING` | Process death | `INTERRUPTED(<operation>)` | The UI MUST show which specific operation was interrupted on relaunch. It MUST NOT imply an agent or target is still running when its actual state is unknown — an unknown state is reported as unknown, never guessed as still-running. |
| `INTERRUPTED(BUILDING_PACKAGE)` | User resumes or retries | `PACKAGE_EXPORT_COMPLETE` or `NO_PUBLISHED_PACKAGE` | Package export MUST be atomic: it either completes fully or leaves no published package — never a partially-written one. |

Long operations SHOULD use notifications and resumable receipts where the underlying provider
supports it, so an interrupted validation/build/simulation/run is discoverable without the user
having to keep the app foregrounded.

## 14. Undo and redo

**`FB-RAT-PHN-011` — PROPOSED, not yet ratified.** This decision does not exist in the source
pack — the word "undo" occurs zero times across the entire dual-surface draft. Drafts are
append-only revisions with a stated durability gate ("zero lost edits across forced process
deaths," §13) but no *reversibility* gate, on the input modality with the highest accidental-
actuation rate, with destructive structural verbs (§3.2's delete, §4's disable) within one tap's
reach. This is a genuine gap, not a style preference, so it is recorded here as a proposal for the
Amendments-phase agent to accept, reject, or fold into an existing ID — this document does not
self-ratify it.

**Proposed shape**, sized to be actually implementable rather than aspirational:

- Undo/redo operates over **semantic Stage View operations** (§3.2's verb list: add, insert,
  duplicate, reorder, connect, branch, wrap-as-subloop, disable, delete), never over raw UI
  events (individual taps, scroll positions, focus changes).
- A **visible control** MUST exist for undo/redo — it is never gesture-only (consistent with §12's
  "every gesture has a visible alternative" principle).
- Undo MUST be exposed as a **TalkBack custom action** (folds into §12.3 deliverable 2's
  custom-action inventory once that work exists).
- Applying an AI proposal (§8) is **exactly one** undoable transaction, however many individual
  operations the proposal's operation list contained — undoing an applied AI edit reverts the
  whole proposal atomically, not operation-by-operation.
- Undo is **prohibited across activation and export boundaries**: once a loop has been activated
  (§11) or a package has been exported (§13), no undo action reaches back across that boundary to
  un-happen it. Undo **never rewrites an installed release** — an installed release is immutable
  per `LOOP-ID-002` (`schemas/loops/registries/loop-validation-rules.v1.json`), and undo respects
  that invariant rather than creating a side channel around it.

**P0 acceptance gate proposed for this decision, once ratified:** every Stage View structural verb
is reversible in one action.

| From state | Event | To state | Notes |
|---|---|---|---|
| `DRAFT_CLEAN` (undo stack `U`, redo stack `R`) | User performs a reversible Stage View verb, or applies an AI proposal (§8) | `DRAFT_CLEAN` (`U' = U + [op]`, `R' = []`) | A new action clears the redo stack — standard linear undo history, not a tree. |
| `DRAFT_CLEAN` (`U` non-empty) | User invokes Undo (visible control or TalkBack custom action) | `DRAFT_CLEAN` (`U' = U - [top]`, `R' = R + [top]`) | Restores the prior semantic state; does not cross an activation/export boundary (above). |
| `DRAFT_CLEAN` (`R` non-empty) | User invokes Redo | `DRAFT_CLEAN` (`U' = U + [top]`, `R' = R - [top]`) | |
| any state | Loop is activated (§11) or a package is exported (§13) | *(unchanged)*, `U` and `R` cleared for the activated/exported boundary | Undo cannot reach back across this event afterward. |

## 15. Phone-completeness acceptance scenario

*(Merges draft §13 "Phone-completeness acceptance scenario" and draft §17 "One-handed creation
target," which were two overlapping acceptance statements for the same underlying claim, and
applies the amendment below.)*

**`FB-RAT-PHN-007` — ACCEPTED** (canonical ratification home: `docs/release-gates/
LOOP_P0_P1_RELEASE_GATES.md`, not yet written in this repository; the acceptance scenario that
operationalizes the gate is specified here). "A user MUST be able to create, validate, simulate,
bind, run, repair, fork, and export a branching loop without browser, monitor, mouse, or
keyboard."

### 15.1 Base scenario

On a phone with no external accessories, a user MUST be able to, in one session:

1. Create a loop with at least seven nodes, one gateway, one bounded retry cycle, one human
   decision, one model slot, and one typed tool.
2. Validate it.
3. Fix a compatibility issue (repair).
4. Run its fixtures.
5. Bind a local model and a CI target.
6. Approve scoped authority (§11).
7. Execute it.
8. Inspect receipts.
9. Fork it.
10. Export the package.

### 15.2 Amendment — schema, long-prompt, and documentation authoring (new normative text)

**As stated, this scenario was untested where it is hardest.** The only schema-authoring UI
anywhere in the source pack was browser-side; the phone Node Sheet only *listed* schemas as
read-only content, and this scenario never required authoring a schema, a long prompt, or
documentation on the phone — so `FB-RAT-PHN-007` was not actually being tested by its own
acceptance scenario. This matters concretely: mobile text entry averages 36.2 WPM with a 2.3%
uncorrected error rate (MobileHCI 2019, n=37,370), and 74% of people type with two thumbs — which
is in tension with a purely one-handed target (§15.3) unless dictation and structured pickers do
real work, not just exist as an option nobody is required to exercise.

Step 1 of §15.1 is amended: the created loop's typed tool node (or an added node) MUST include
authoring, on the phone, of a two-field JSON Schema with at least one `enum` constraint and one
other constraint (e.g. a `minLength`/`pattern`/numeric bound), using the structured schema editor
required by §3.4. The scenario MUST also include authoring a prompt field longer than 500
characters using the full-screen distraction-free editor (§3.4), and authoring the loop's
documentation fields — phone-only, with Distiller-assisted drafting permitted but not substituted
for the requirement that the phone-side authoring path exists and was exercised.

### 15.3 One-handed constraint

The base scenario (§15.1, as amended by §15.2) MUST be completable using taps, sheets, text/voice
input, and visible controls only. No precision gesture, hover state, chorded keyboard shortcut, or
external keyboard may be required. The composer and bottom sheets MUST keep primary actions within
one-handed reachable zones, while destructive actions remain deliberately placed outside that easy
reach — the same separation §4's non-collapsible risk indicator protects.

## 16. Phone information-density law

The phone does not show the entire semantic model at once. It reveals one decision scale at a
time while preserving a stable route to raw detail: Intent View (§3.1) answers "what is this
for"; Stage View (§3.2) answers "what happens and in what order"; Graph View (§3.3) answers "how
does control flow"; Node Sheet (§3.4) answers "what exactly does this step require"; and Run View
(§3.5) answers "what is happening now." This law is what makes §3's five-view answer to
`FB-RAT-PHN-001` a coherent ladder rather than five unrelated screens.

## 17. Phone performance budgets

The following are target budgets for owner verification on the reference phone, **not shipped
claims** — this build container has no device or emulator (`CLAUDE.md` "Environment honesty";
binding rule 6), so none of the figures below have been measured yet:

- Gesture acknowledgement: under 16 ms.
- Stage View first meaningful content: under 500 ms, for 100 nodes.
- Graph View: 60 fps for 250 visible nodes, with usable degraded rendering up to 500.
- Node Sheet open: under 250 ms.
- Autosave acknowledgement: under 150 ms.
- Validation first blocker surfaced: under 750 ms.
- Package import preview: under 2 seconds for a typical package, excluding network download.

**This table is the authoritative phone performance budget for this document.** A differently-
worded figure ("Stage View 60 fps for 200 visible stages") appears elsewhere in the handoff pack's
non-normative material and conflicts with the Graph View figure above (250 nodes at 60 fps,
degrading to 500) rather than restating it. That other figure is not ratified by this document and
MUST NOT be quoted as an alternative to the table above until the two are reconciled into one set
— reconciliation is out of scope for this document and is flagged here, not resolved here.

## 18. Cross-references and open items

**Decision IDs cited in this document:** `FB-RAT-LBX-002` (header, §1 — canonical home, ACCEPTED;
see the header correction note), `FB-RAT-LBX-006` (§10, pointer mode is an input register, not a
second IA), `FB-RAT-PHN-001` (§3), `FB-RAT-PHN-002` (§3.2), `FB-RAT-PHN-003` (§7, REJECTED —
cross-reference `docs/non_ratified/REJECTED_ALTERNATIVES.md`), `FB-RAT-PHN-004` (§7),
`FB-RAT-PHN-005` (§12), `FB-RAT-PHN-006` (§8), `FB-RAT-PHN-007` (§15), `FB-RAT-PHN-008` (§9),
`FB-RAT-PHN-009` (§11), `FB-RAT-PHN-010` (§10, EXPERIMENTAL — cross-reference
`docs/non_ratified/EXPERIMENTAL_DECISIONS.md`), `FB-RAT-PHN-011` (§14, PROPOSED, not yet ratified
— cross-reference `docs/ratified/loops/AMENDMENTS.md` §7). No other `FB-RAT-*` ID is ratified by
this document.

**Registries reconciled, not redefined:** `loop-validation-rules.v1.json` (structurability and
undo-related findings referenced in §3.2 and §14 are illustrative of the intent, not a restatement
of the registry — the registry is the byte-level authority), `capability-ids.v1.json`
(authority-class references in §11 and §12.3).

**Sibling documents this one depends on but does not restate:** `LOOP_DUAL_SURFACE_ARCHITECTURE.md`
(`FB-RAT-LBX-001`/`FB-RAT-LBX-004`, the two-surface architecture this document's phone half
implements), `LOOP_ENGINEERING_SPEC_V2.1.md` (the node/gateway/run-state vocabulary this document's
UI wraps), `AMENDMENTS.md` (the `FB-RAT-PHN-011` proposal record).
