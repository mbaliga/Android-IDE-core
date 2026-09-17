# Amendments — the WP-1L loop / dual-surface corpus

> **License:** `LICENSE-PENDING` — see `docs/non_ratified/LICENSE_PENDING.md`.

**What this is.** The single required amendments ledger for WP-1L (per the master build brief).
It does three things, and only these three: (1) records supersessions/deltas the ten dual-surface
contract documents and the loop schema/Kotlin groups already applied as normative text but that a
future reader needs a merged, cross-document view of; (2) states two mapping rows — the
`Write`-bucket-to-ladder row and the `LoopRunState`↔`ExecutionState` row — that make an existing
invariant (`FB-RAT-AUTH-002`'s eight-rung ladder; `FB-RAT-EXE-009`'s success≠verification split)
actually checkable at the loop level, without inventing a new invariant; (3) collects every
`FB-RAT-*` ID a WP-1L document proposed but did not self-ratify, in one place, per this pack's
standing rule (`FB-RAT-COM-002`: stable IDs are never invented by the document that needs one —
see `docs/non_ratified/EXPERIMENTAL_DECISIONS.md`'s "Proposed new decisions" section for the
precedent this ledger follows, `FB-RAT-WS-NEW-1`).

This document does not re-decide anything. Every amendment below already exists as normative text
in the ratified document that owns it; this ledger's job is to make the merged picture visible in
one place and to give the six-plus-one required subsections below a single citable home, per this
work package's explicit instruction. Where this ledger quotes another document, the quote is
exact; where it summarizes, the summary is checked against the source section cited.

---

## 1. The `FB-RAT-INT-003` supersession line

**Verbatim, as required:**

> FB-RAT-MKT-001 partially supersedes FB-RAT-INT-003 as to deliberately published public
> artifacts only; INT-003 remains in force for shared databases, background sync, and any private
> workspace state.

This is not new text. It is already ratified, word-for-word (with backtick styling around the
two IDs), at `LOOP_MARKETPLACE_CONTRACT.md` §2.1 ("Supersession of `FB-RAT-INT-003` (verbatim)"),
directly beneath `FB-RAT-MKT-001`'s own statement (§2). This ledger does not restate the
reasoning §2.1 already gives — it exists there, in full, as binding normative text — this section
only fixes the forward pointer that named this document before it existed.

**The forward pointer this section resolves.** `docs/non_ratified/REJECTED_ALTERNATIVES.md`
(written in WP-1) carries, under its own `FB-RAT-INT-003` entry, a note titled "Forward pointer,
not resolved here":

> A later loop/dual-surface contract corpus introduces `FB-RAT-MKT-001`, a scoped amendment to
> this rule for deliberately-published marketplace artifacts only. See `AMENDMENTS.md` (a later
> work package) for the `MKT-001` supersession line, once that file exists. This register does
> not write `AMENDMENTS.md`, does not define `FB-RAT-MKT-001`'s scope beyond the one-line pointer
> above, and does not weaken `FB-RAT-INT-003` itself — the rejection stands as written until a
> ratified amendment says otherwise.

That pointer now resolves here. `FB-RAT-INT-003` remains **REJECTED** as a general rule — this
section does not touch its disposition — and the scope of the one permitted carve-out is exactly
what `LOOP_MARKETPLACE_CONTRACT.md` §2.1 states: deliberately published public artifacts
(packages, listings, reviews, publisher keys, moderation records, explicitly shared redacted
result receipts — `FB-RAT-MKT-001`, §2) only. Shared databases, account backends, background
services, broadcasts, automatic synchronization, and any private workspace state remain governed
by `FB-RAT-INT-003` exactly as `REJECTED_ALTERNATIVES.md` states it, undisturbed by the
marketplace amendment. A reader arriving at either document's `FB-RAT-INT-003`/`FB-RAT-MKT-001`
entry can now follow the pointer in either direction to the same resolution.

---

## 2. The three undeclared LOOP-001…006 deltas in v2.1

Copied, not re-derived, from `LOOP_ENGINEERING_SPEC_V2.1.md`'s own "Amendments to LOOP-001…006"
section (that document's closing sections, immediately before its "Cross-references and open
items"). That document's own framing, preserved here verbatim as the header for this section:

> `FB-RAT-LOOP-001` through `FB-RAT-LOOP-006` are preserved by this spec (all six remain ACCEPTED,
> unchanged in disposition — see the register). But "preserves" understates what changed: this v2.1
> spec makes **three undeclared changes** relative to the base pack (`04_ARCHITECTURE_CONTRACTS.md`
> §5, the FB-ENG-03 §5/§5.1/§5.3 source `FB-RAT-LOOP-001`…`006` cite) that the source draft states
> as if they were the original scope, not as changes. None of the three is stated outright anywhere
> in the source pack; all three were found by comparing this spec against the base pack's own
> decision text and model description, per `10_DUAL_VALIDATION_ADDENDUM.md` §F's "v2.1 'preserves
> LOOP-001..006'" row.

### 2.1. Node categories grow from five to eight

**Base pack** (`FB-RAT-LOOP-002`; `04_ARCHITECTURE_CONTRACTS.md` §5's `NodeDefinition` model)
named exactly five node categories: **deterministic**, **model-inference**, **typed-tool**,
**human-decision**, **subloop**.

**`LOOP_ENGINEERING_SPEC_V2.1.md` §4** names eight: deterministic transformation, model inference,
typed tool, human decision, bounded subloop — the same five, renamed/clarified but not changed in
kind — **plus three new categories the base pack never had**: **verifier**, **artifact
import/export**, and **wait/event gate**. `FB-RAT-LOOP-002`'s own decision text ("typed I/O,
implementation kind, timeout, retry, idempotency, compensation, verification") is worded generally
enough to cover the five original categories' obligations; it does not anticipate a node category
whose entire purpose *is* verification (category 6) as a first-class graph citizen distinct from
the verification *property* every node already carries, nor an explicit event-wait primitive
(category 8) as distinct from an ordinary typed-tool node with a long timeout.

**Resolution (already adopted, at `LOOP_ENGINEERING_SPEC_V2.1.md` §4):** the three additions are
compatible extensions — no base node category was removed, redefined, or had its contract
narrowed — but they are genuinely new, not merely renamed. `LOOP_COMPATIBILITY_CONTRACT.md`'s
"Node type" axis (`FB-RAT-CMP-001`) and `loop-validation-rules.v1.json`'s node-category-aware
rules (e.g. `LOOP-TEST-001`) MUST treat all eight as the current set, not the base pack's five,
when evaluating installed-engine support.

### 2.2. The base pack's 8-value `LoopResult` becomes the v2.1 spec's 17-state run machine, with two semantic changes hidden inside the expansion

**Base pack** (`FB-RAT-LOOP-006`) named exactly eight terminal values: `SUCCEEDED_VERIFIED`,
`SUCCEEDED_UNVERIFIED`, `STOPPED_BUDGET`, `STOPPED_POLICY`, **`WAITING_USER`**, `FAILED_SAFE`,
`FAILED_SIDE_EFFECTS_POSSIBLE`, `TARGET_STATE_UNKNOWN` — a flat, all-terminal enum
(`04_ARCHITECTURE_CONTRACTS.md` §5: *"Terminal states:"* followed by exactly these eight,
`WAITING_USER` included in that list with no separate non-terminal category anywhere in the base
pack).

**`LOOP_ENGINEERING_SPEC_V2.1.md` §9** defines 17 states with an explicit terminal/non-terminal
split. Two changes are not a mechanical refinement of the base eight — they are semantic reversals
or additions that change what a consumer of the old enum must now handle differently:

- **`WAITING_USER` moves from terminal to non-terminal.** In the base pack, a run reaching
  `WAITING_USER` was *done* — the `LoopResult` was final and the caller's job was to present it and
  stop. In v2.1, `WAITING_USER` is a mid-run pause (§9's table: `RUNNING → WAITING_USER` on
  reaching a human-decision or wait/event-gate node, then `WAITING_USER → RUNNING` once resolved)
  — the run continues. A consumer that still treats `WAITING_USER` as terminal (e.g. releasing
  resources, closing a receipt, or reporting the run "done, awaiting user") is now wrong under
  v2.1 and will silently drop the rest of the run.
- **`CANCELLED` is new.** The base pack had no cancellation-specific terminal value at all — a
  cancelled run had no distinct outcome from, presumably, `STOPPED_POLICY` or an unstated
  fallback. v2.1 adds `CANCELLED` as its own terminal state (§9: `CANCELLING → CANCELLED` when
  in-flight steps reach a safe stop with no side effects pending) plus the `CANCELLING`
  non-terminal state that reaches it.

**Resolution (already adopted, at `LOOP_ENGINEERING_SPEC_V2.1.md` §9):** `FB-RAT-LOOP-006` is
preserved as the *origin* of the eight terminal-outcome concepts this machine still expresses (six
of the base eight are unchanged: `SUCCEEDED_VERIFIED`, `SUCCEEDED_UNVERIFIED`, `STOPPED_BUDGET`,
`STOPPED_POLICY`, `FAILED_SAFE`, `FAILED_SIDE_EFFECTS_POSSIBLE`, `TARGET_STATE_UNKNOWN` are
terminal in both), not as a still-accurate description of the full state machine. Any document or
fixture written against the base pack's flat eight-value `LoopResult` enum MUST be updated to the
17-state machine before being treated as current. `schemas/loops/loop-run.schema.json`'s
`runState` enum is that 17-state machine, copied verbatim from §9.

The gap this amendment left open — no `VERIFYING` state and no explicit mapping to
`EXECUTION_CONTRACT.md`'s lifecycle — is not resolved by this section. It is resolved by §4 below.

### 2.3. `FB-RAT-LOOP-004` (fixtures required) vs. `FB-RAT-PKG-008` (bare test-coverage declaration accepted) — resolved in favor of `LOOP-004` for P0

**`FB-RAT-LOOP-004`** (base pack): *"A distributable loop package contains definition, schemas,
tests, fixtures, documentation, license, signatures, and minimum engine version."* Fixtures are
stated as a flat requirement, no escape hatch.

**`FB-RAT-PKG-008`** (dual-surface pack; `LOOP_PACKAGE_SPEC.md` §10): *"A public release includes
manifest, definition, schemas, documentation, compatibility declaration, license, provenance,
**tests or an explicit test-coverage declaration**, and signature."* This accepts a bare
declaration — no fixture required — as long as it is explicit.

These two decisions genuinely conflict for a public release: `LOOP-004` requires fixtures
unconditionally; `PKG-008` accepts a package with no fixtures at all, provided it says so.

**Resolution (already adopted, at `LOOP_ENGINEERING_SPEC_V2.1.md`'s Amendments section and
`LOOP_PACKAGE_SPEC.md` §10 — the two documents agree, neither decided this independently of the
other):** **`LOOP-004` wins for P0.** A LoopPackage intended for P0 use — including any package a
user installs and runs, not only published ones — MUST carry required fixtures per
`LOOP_ENGINEERING_SPEC_V2.1.md` §20 step 5 and a machine-checkable minimum engine version.
**`PKG-008`'s bare test-coverage declaration is accepted only for marketplace listings**, and only
as a visibly downgraded state: a listing accepting the bare declaration MUST render a visible
**no-tests badge** on that listing, so a browsing user can see, before install, that the package's
correctness claims rest on the publisher's word alone, not on a fixture the engine can actually
re-run. This exact resolution is already encoded as machine-checkable policy, cross-referenced
(not re-derived) by both source documents: rule code `LOOP-TEST-001` in
`loop-validation-rules.v1.json` states precisely this obligation, with `sourceObligation` pointing
at `10_DUAL_VALIDATION_ADDENDUM.md` §F's "`LOOP-004` vs `PKG-008`" finding.

---

## 3. The `Write = {MODIFY_DRAFT, EXECUTE_REVERSIBLE}` mapping row

**The row, already ratified as new normative text at `LOOP_IMPORT_ACTIVATION_CONTRACT.md` §6:**
the `WAITING_AUTHORITY` review groups capability requests into seven UI buckets for display —
Observe, Read, Propose, Write, External, Destructive, Publish:

| Review bucket | Authority ladder rung(s) (`FB-RAT-AUTH-002`) |
|---|---|
| Observe | `OBSERVE` |
| Read | `READ` |
| Propose | `PROPOSE` |
| **Write** | **`MODIFY_DRAFT`, `EXECUTE_REVERSIBLE`** |
| External | `EXECUTE_EXTERNAL` |
| Destructive | `EXECUTE_DESTRUCTIVE` |
| Publish | `PUBLISH_OR_RELEASE` |

**Stated explicitly, as this ledger's own required clarification:** this table is a **UI
grouping**, never a real ladder rung. The canonical eight-rung ladder ratified at
`CAPABILITY_AUTHORITY_MODEL.md` §2 (`FB-RAT-AUTH-002`) — `OBSERVE`, `READ`, `PROPOSE`,
`MODIFY_DRAFT`, `EXECUTE_REVERSIBLE`, `EXECUTE_EXTERNAL`, `EXECUTE_DESTRUCTIVE`,
`PUBLISH_OR_RELEASE` — remains the **only real enum**. Nothing in this corpus, including this
ledger, introduces a competing seven- or eight-value "bucket" enum as an alternative
representation of authority. `Grant.authorityRung` (`schemas/authority/grant.schema.json`),
`AuthorityDecisionRecord.requiredRung`, and every Kotlin `AuthorityRung` reference in
`contracts/kotlin/` all resolve to one of the eight ladder rungs — never to one of the seven
review-bucket labels.

**Why this row exists at all, if the bucket is not a rung:** so an authority-diff engine has a
deterministic answer for what "Write" means when it needs to detect a **widening within the
bucket**. Two capability requests that both display under the "Write" bucket label can still
differ by a full ladder rung — one at `MODIFY_DRAFT` (a reversible edit to an uncommitted draft),
the other at `EXECUTE_REVERSIBLE` (a persisted side effect, e.g. `fb.repo.commit`). An
authority-diff engine that only compared bucket labels would see two "Write" requests and report
no change — silently hiding a real widening the eight-rung ladder would catch immediately by
`.ordinal` comparison. `10_DUAL_VALIDATION_ADDENDUM.md` §F names exactly this failure mode as the
reason the mapping needed to be written down at all (`LOOP_IMPORT_ACTIVATION_CONTRACT.md` §6). The
row above is the fix: any code that groups by bucket for display purposes MUST still diff by rung
underneath, and this row is what makes that translation unambiguous.

This mapping row lives normatively at `LOOP_IMPORT_ACTIVATION_CONTRACT.md` §6, where the review
UI it describes actually exists (the `WAITING_AUTHORITY` state, §3). This ledger restates it
because it is the concrete, worked example of "UI grouping vs. real ladder rung" for future
sessions building an authority-diff engine across this corpus — including
`schemas/loops/authority-diff.schema.json` (the standalone `AuthorityDiff` object, §4 below),
which operates on rungs, never on buckets.

---

## 4. The `LoopRunState` ↔ `ExecutionState` mapping

**The gap.** `loop-run.schema.json`'s `runState` (the 17-state machine from
`LOOP_ENGINEERING_SPEC_V2.1.md` §9, §2.2 above) has no `VERIFYING` state and no `QUEUED` state.
`EXECUTION_CONTRACT.md` §2's 12-state `ExecutionState` lifecycle (`QUEUED → PREPARING → RUNNING →
{WAITING_USER ⇄ RUNNING} → {SUSPENDED ⇄ RUNNING} → VERIFYING → SUCCEEDED /
SUCCEEDED_UNVERIFIED / FAILED_SAFE / FAILED_SIDE_EFFECTS_POSSIBLE / TARGET_STATE_UNKNOWN /
CANCELLED`) has both. Without an explicit mapping between the two machines, `FB-RAT-EXE-009`
("success is not the same as verification") is **unimplementable at the loop level** — a loop
engine has nowhere to record that its `SUCCEEDED_VERIFIED` terminal state actually rests on a
`verification.state: VERIFIED` receipt, as opposed to just reaching the last node.

`LOOP_ENGINEERING_SPEC_V2.1.md`'s own Amendments section flags this gap explicitly and declines to
resolve it: *"That mapping is out of scope for this document (it belongs where
`EXECUTION_CONTRACT.md` and this spec's runtime layer meet, not to either file alone) and is
flagged here, not silently absorbed into §9's table... §9's `RUNNING` state should be read as
encompassing `EXECUTION_CONTRACT.md`'s `PREPARING`/`RUNNING`/`VERIFYING` as a single loop-level
phase until that mapping is written."* This section is that mapping.

**Why the two machines are different shapes, not just differently named.** `ExecutionState`
describes **one** `ExecutionRequest`/`ExecutionHandle`'s lifecycle — one dispatched operation.
`LoopRunState` describes the **whole run**, which dispatches a *sequence* of node executions, each
of which may itself go through a full `ExecutionState` lifecycle. The two machines are not two
views of the same state; `LoopRunState` is the outer orchestration layer, `ExecutionState` is the
inner per-operation layer. A mapping between them is therefore a **phase correspondence**, not a
1:1 value rename — some `LoopRunState` values (loop-orchestration gates that happen before any
node has dispatched an `ExecutionRequest` at all) have no `ExecutionState` counterpart, and that is
the correct, honest answer for those rows, not a gap to paper over.

**Normative mapping table:**

| `LoopRunState` (loop-run.v1, §9) | Nearest `ExecutionState` phase (`EXECUTION_CONTRACT.md` §2) | Resolution |
|---|---|---|
| `CREATED` | `QUEUED` | **Resolved as stated in this ledger's brief:** `CREATED` and `QUEUED` are the run-level and execution-level names for the identical phase — accepted, not yet begun. A `LoopRun` entering `CREATED` has not yet dispatched any `ExecutionRequest`; once it does (at `READY → RUNNING`, below), that request's own handle begins its life at `QUEUED`. |
| `PREFLIGHT` | `PREPARING` | **Resolved as stated in this ledger's brief:** loop-level preflight (static validation, budget declaration check, capability enumeration — §9's own table) is the run-orchestration analog of an execution's environment-preparation phase. No `ExecutionRequest` exists yet at this point either — this is a phase correspondence, not a claim that an `ExecutionHandle` is already `PREPARING`. |
| `WAITING_BINDING` | *(no counterpart)* | Loop-orchestration-only gate. Binding-slot resolution (`FB-RAT-IMP-001`/`PKG-009`) happens before any node dispatches an operation; nothing in the `ExecutionState` machine describes "waiting for a binding slot," because that concept does not exist at the single-operation layer. |
| `WAITING_AUTHORITY` | *(no counterpart, but see note)* | Loop-orchestration-only gate — same reasoning as `WAITING_BINDING`. Note the *effect* this state exists to produce is realized, per-request, inside `ExecutionProvider.prepare()`: `EXECUTION_CONTRACT.md` §5 requires the `FB-RAT-COM-012` authority-grant check to run in `prepare()`, before any operation starts. `WAITING_AUTHORITY` is the loop-level, once-per-run act of obtaining the grant `prepare()` later checks per-request — it is upstream of, not identical to, that check. |
| `READY` | *(transition point)* | The run is about to dispatch its first node's `ExecutionRequest`. Not itself an `ExecutionState` value — it is the `LoopRunState` immediately before one begins at `QUEUED`. |
| `RUNNING` | `PREPARING`, `RUNNING`, `VERIFYING` (per node) | **This is the correspondence `LOOP_ENGINEERING_SPEC_V2.1.md`'s Amendments section already named and deferred — resolved here, as that section asked.** A `LoopRun` in `RUNNING` dispatches a sequence of node executions; each one's own `ExecutionHandle` cycles through `PREPARING → RUNNING → VERIFYING` before reaching a terminal `ExecutionState`. The loop machine has **no separate `VERIFYING` state of its own** — it does not need one, because `VERIFYING` is fully absorbed as a sub-phase of the loop-level `RUNNING` state. What the loop machine records instead is the *aggregate* outcome: §9's own transition rule for `RUNNING → SUCCEEDED_VERIFIED` fires only when *"every reachable terminal node is reached; every verifier marked `requiredForSuccess` (§16) has a passing result tied to this run's inputs, digest, and artifacts"* — i.e., the loop-level `SUCCEEDED_VERIFIED` receipt is gated on the same per-node `verification.state: VERIFIED` evidence `FB-RAT-EXE-009` requires at the execution layer, rolled up across every required verifier in the run. The execution-level `VERIFYING` phase's receipt is therefore the evidence that **precedes** — and is a precondition for — the loop-level `SUCCEEDED_VERIFIED` terminal state, exactly as this ledger's brief states. A loop engine implementing `RUNNING → SUCCEEDED_VERIFIED` MUST check the underlying per-node `ExecutionReceipt.verification.state` values, not merely that every node returned some terminal `ExecutionState`. |
| `WAITING_USER` | `WAITING_USER` | Direct, by-name correspondence — both non-terminal, both mean "paused pending a user decision," at their respective layers (a human-decision/wait-event-gate node at the loop layer; an unfamiliar host key or destructive-op confirm at the execution layer). |
| `SUSPENDED` | `SUSPENDED` | Direct, by-name correspondence. |
| `CANCELLING` | *(no distinct counterpart)* | Loop-orchestration-only. `ExecutionState` has no "cancelling" value of its own — cancellation is a verb (`ExecutionProvider.cancel()`, `ExecutionHandle.cancellationMode`) applied against whatever `ExecutionState` an in-flight node execution currently holds, not a state that machine transitions into. `CANCELLING` is the loop-level record that this verb has been invoked and the run is waiting for in-flight node executions to reach a safe stop. |
| `SUCCEEDED_VERIFIED` | `SUCCEEDED` (with `verification.state: VERIFIED`) | Direct correspondence, strengthened: `EXECUTION_CONTRACT.md` §2's own transition table already requires `exitState: SUCCEEDED` to carry `verification.state: VERIFIED` (`FB-RAT-EXE-009`) — plain execution-level `SUCCEEDED` already means "verified" at that layer. `SUCCEEDED_VERIFIED` is the run-level rollup of that same guarantee holding across every `requiredForSuccess` verifier in the run (see the `RUNNING` row above). |
| `SUCCEEDED_UNVERIFIED` | `SUCCEEDED_UNVERIFIED` | Direct, by-name and by-meaning correspondence at both layers. |
| `STOPPED_BUDGET` | *(no counterpart)* | Loop-orchestration-only terminal. Budget exhaustion is checked "before dispatch and after every step receipt" (§9, §18) at the run-orchestration layer — it is not a state any single `ExecutionRequest`'s own lifecycle reaches. A node execution in flight when the run's budget is exhausted keeps whatever `ExecutionState` it already held; the loop's `STOPPED_BUDGET` does not retroactively rewrite it. |
| `STOPPED_POLICY` | *(no counterpart)* | Loop-orchestration-only terminal, same reasoning as `STOPPED_BUDGET` — a capability/policy boundary hit mid-run (a binding revoked, an authority check failing) is evaluated at the run/authority layer, not inside a single execution's state machine. |
| `FAILED_SAFE` | `FAILED_SAFE` | Direct, by-name and by-meaning correspondence — both mean "failed cleanly, no side effects." |
| `FAILED_SIDE_EFFECTS_POSSIBLE` | `FAILED_SIDE_EFFECTS_POSSIBLE` | Direct correspondence. |
| `TARGET_STATE_UNKNOWN` | `TARGET_STATE_UNKNOWN` | Direct correspondence — both driven by the same `FB-RAT-EXE-004` reconnect-uncertainty rule ("if reconnect cannot establish state, report UNKNOWN rather than termination or success"). |
| `CANCELLED` | `CANCELLED` | Direct correspondence — both terminal, both new relative to their respective base enums' predecessors (§2.2 above notes `CANCELLED` is new to the loop machine; it was already present in `ExecutionState`). |

**The three resolutions this ledger's brief asked for, stated explicitly in one place:**

1. `QUEUED` and `CREATED` both map to the run's pre-start phase (row 1).
2. `PREPARING` maps to `PREFLIGHT` (row 2).
3. `VERIFYING` is a `RUNNING` sub-phase in the loop machine, whose per-node receipt precedes — and
   gates — the loop-level `SUCCEEDED_VERIFIED` terminal state, since the loop machine has no
   separate `VERIFYING` state of its own (row 6, `RUNNING`).

A future `loop-run.schema.json` revision MAY choose to surface a denormalized `currentNodeExecutionState`
field carrying the live per-node `ExecutionState` for UI purposes; this ledger does not require or
design that field — it is a possible consumer of the mapping above, not part of it.

---

## 5. The `derivesFrom` column — restated/duplicate decisions across the two packs

`10_DUAL_VALIDATION_ADDENDUM.md` identified a set of decisions restated, narrowed, or closely
paralleled across the base pack and the dual-surface pack under two different IDs. Per this
ledger's instruction, these are **not** deleted, merged, or renumbered — `FB-RAT-COM-002`'s
stable-ID rule (independent of display topic) forbids that in both registers. Instead, each row
below records the relationship and names the **normative parent** — the document a future
implementer should treat as authoritative if the child and parent documents ever drift.

| Restated ID | Parent ID | Relationship |
|---|---|---|
| `FB-RAT-CMP-006` (`LOOP_COMPATIBILITY_CONTRACT.md` §7) | `FB-RAT-COM-003` (`COMMON_CONVENTIONS.md` §3) | Restates. `CMP-006`'s "unknown major schemas rejected, unknown minor fields preserved" is `COM-003`'s general schema-compatibility rule, specialized to the loop-compatibility axis. `LOOP_COMPATIBILITY_CONTRACT.md` §7 already says as much inline ("This specializes `FB-RAT-COM-003`"). |
| `FB-RAT-PHN-005` (`LOOP_PHONE_AUTHORING_SPEC.md`) | `FB-RAT-COM-009` (`COMMON_CONVENTIONS.md` §9) | Restates. `PHN-005`'s "every graph action has visible controls, TalkBack actions, keyboard equivalents, no color-only state" is `COM-009`'s general accessibility-semantics rule, applied to the phone Stage View specifically. |
| `FB-RAT-PKG-009` (`LOOP_PACKAGE_SPEC.md` §5 / §8) | `FB-RAT-COM-002` (`COMMON_CONVENTIONS.md` §2) | Restates. `PKG-009`'s "node IDs and edge labels are stable across surfaces, canonicalization, import, export, and layout changes" is `COM-002`'s general stable-identity rule, applied to loop package node/edge identity specifically. |
| `FB-RAT-PKG-004` (`LOOP_PACKAGE_SPEC.md` §6) | `FB-RAT-COM-005` (`COMMON_CONVENTIONS.md` §5) | Partially restates. `PKG-004`'s canonical-digest rule (deterministic path ordering, UTF-8 normalization, line endings, JSON canonicalization, digest computation) shares `COM-005`'s "SHA-256-or-stronger plus byte length" integrity baseline but adds package-specific canonicalization steps `COM-005` does not itself specify — hence "partially," not a full restatement. |
| `FB-RAT-IMP-005` (`LOOP_IMPORT_ACTIVATION_CONTRACT.md` §6) | `FB-RAT-AUTH-004` + `FB-RAT-AUTH-007` (`CAPABILITY_AUTHORITY_MODEL.md` §6, §9) | Restates both. `IMP-005`'s "a local binding profile MAY grant less authority than requested but MUST NOT silently grant more" is the loop-import-time instance of `AUTH-004`'s no-implicit-privilege-expansion rule and the positive-form restatement of `AUTH-007`'s rejection of automatic/transitive delegation. `LOOP_IMPORT_ACTIVATION_CONTRACT.md` §6 already cites `AUTH-004` inline as the source of the narrowing invariant. |
| `FB-RAT-LBX-007` (`DUAL_RATIFICATION_REGISTER.md`, DUAL §7) | `FB-RAT-INT-001` + `FB-RAT-INT-003` + `FB-RAT-INT-012` (`MANUAL_INTEGRATION_GRAMMAR.md` §1, §8; `REJECTED_ALTERNATIVES.md`) | Narrower case. `LBX-007`'s rejection of automatic/silent browser-to-phone loop sync is the dual-surface-specific instance of `INT-001`'s general manual-trust-boundary rule (every cross-app transfer begins with an explicit user action), `INT-003`'s general rejection of background sync, and `INT-012`'s "remembered shortcut is not itself a read" carve-out — all three already govern this exact shape for the CSApp/Assay companion-import lanes; `LBX-007` applies the same discipline to the phone↔browser transfer lane specifically. |
| `FB-RAT-IMP-009` (`LOOP_IMPORT_ACTIVATION_CONTRACT.md` §9) | `FB-RAT-COM-008` (`COMMON_CONVENTIONS.md` §8) + `FB-RAT-INT-024` (`IMPORT_RECEIPT_V1.md` §1) | Restates both. `IMP-009`'s "every installation records source, package digest, signature state, validation report, bindings digest, authority grant IDs, and installation receipt" is `COM-008`'s general provenance-minimum rule (source location, revision, initiating principal, evidence links) applied to loop installation, combined with `INT-024`'s already-ratified receipt shape. |
| `FB-RAT-MKT-004` (`LOOP_MARKETPLACE_CONTRACT.md` §5) | `FB-RAT-PORT-010` (`REJECTED_ALTERNATIVES.md`) | Narrower case. `MKT-004`'s "the marketplace distributes LoopPackages, not runtime plugins or executable extensions" is the marketplace-specific instance of `PORT-010`'s general rejection of an unrestricted package universe embedded inside Fonebrew — `LOOP_MARKETPLACE_CONTRACT.md` §5 itself notes this is the rule that keeps `MKT-004` from drifting into `FB-RAT-PORT-009` (DEFERRED, general extension ecosystem) territory. |
| `FB-RAT-PHN-006` (`LOOP_PHONE_AUTHORING_SPEC.md` §7) | `FB-RAT-WS-005` (`WORKSPACE_KERNEL_SPEC.md`) | Narrower case. `PHN-006`'s "AI/Distiller-assisted graph changes MUST produce an inspectable semantic diff and require approval before changing the draft" is the loop-authoring-specific instance of `WS-005`'s general "human and agent edits share one transaction/journal/review/undo/conflict model" rule. |
| `FB-RAT-WEB-002` (`LOOP_WEB_STUDIO_SPEC.md` §5) | `FB-RAT-EXE-007` (`EXECUTION_CONTRACT.md` §4.2.1) | Narrower case. `WEB-002`'s "browser drafts and packages MUST use abstract binding slots and MUST NOT contain API keys, Keystore aliases, private host credentials, or raw secrets" is the loop-package-content-specific instance of `EXE-007`'s general "requests reference purpose-bound secret handles; logs/receipts never contain secret values" rule. |
| `FB-RAT-RES-004` (`LOOP_RESULT_SHARING_CONTRACT.md` §6) | `FB-RAT-EXE-009` (`EXECUTION_CONTRACT.md` §4.5) | Closely related, not a restatement. `RES-004`'s corrected claim boundary (a verified shared receipt proves *the same key signed N receipts* — `SELF_SIGNED_RECEIPT`, per that document's own §6 correction — not that a compatible runtime produced them) and `EXE-009`'s "operation success and result verification are separate states" are both instances of the same underlying discipline (a claim of success/verification must be exactly as strong as its actual evidence, no stronger), applied at two different layers — one execution-receipt signing, one result-sharing — without one being a narrower case of the other's specific mechanism. |

**The rule going forward:** the **parent ID is the normative home**. A document implementing or
citing a restated ID SHOULD cite both IDs (the restated one, for the specific surface it governs,
and the parent, for the general rule it derives from) but MUST treat the parent document as
authoritative if the two ever drift — e.g. if `COMMON_CONVENTIONS.md` §5's integrity baseline
changes shape, `LOOP_PACKAGE_SPEC.md` §6's `PKG-004` canonicalization rule inherits that change
without needing its own re-ratification, not the reverse. This rule does not retroactively change
any ID's ACCEPTED/REJECTED/EXPERIMENTAL/DEFERRED/PROPOSED status — every ID in the table above
keeps the disposition its own home document already gives it.

---

## 6. Glossary fix — `StudioApp` versus `WebLoopStudio`

Two different objects in this constellation share the bare English word "Studio," and this corpus
already contains at least one bare, ambiguous usage that this section exists to prevent from
recurring:

- **`StudioApp`** — the paid PM/authoring application, built out of the `Android-IDE-Studio`
  repository, whose product boundary `FB-RAT-PORT-011` (`PRODUCT_DIRECTION_AND_BENCHMARK_
  BASELINE.md`) states: *"Studio may consume development evidence but MUST NOT own or block the
  core development substrate."* This is the app `MANUAL_INTEGRATION_GRAMMAR.md`'s companion-import
  lanes (CSApp/Assay/Studio, per `REJECTED_ALTERNATIVES.md`'s `FB-RAT-INT-004` binding-scope note)
  and `FB-RAT-INT-012`'s "Studio remembered shortcuts" (§8 of that document) both refer to. It is
  **not** a loop/browser surface at all — it is the companion PM/authoring app importing
  Fonebrew-produced evidence, one of the constellation's own apps, distinct from anything this
  WP-1L corpus builds.
- **`WebLoopStudio`** — the browser-based loop authoring, testing, and packaging surface this
  WP-1L corpus specifies at `LOOP_WEB_STUDIO_SPEC.md` (titled, in that document itself, "Browser
  Web Studio Specification" — this ledger adopts the single compound name `WebLoopStudio` for
  unambiguous cross-referencing, since "Web Studio" alone still contains the bare, collidable
  word). This is the `FB-RAT-LBX-002`-adjacent, `FB-RAT-LBX-001`/`FB-RAT-LBX-004`-grounded surface
  covering canvas authoring, the test laboratory, binding-slot/secret handling, the package build
  pipeline, and publisher signing (`LOOP_WEB_STUDIO_SPEC.md` §1–§15).

**Stated plainly, as this section's own required rule:** these are two different things that share
the word "Studio." `StudioApp` is a companion PM/authoring app; `WebLoopStudio` is this corpus's
browser loop-authoring surface. **Every document in this corpus MUST use one of these two full
names, never a bare "Studio," in any context where the loop/browser surface could be confused with
the companion app** (a document discussing, e.g., publisher key storage, binding slots, or
canvas/graph editing is unambiguously about `WebLoopStudio`; a document discussing remembered
import shortcuts, evidence consumption, or the PM/task/waterfall surface is unambiguously about
`StudioApp` — but the *word itself*, unqualified, is not a safe cross-reference target and MUST NOT
be used where a reader could reasonably resolve it either way).

**Concrete instance this rule catches.** `MANUAL_INTEGRATION_GRAMMAR.md` §8, "Studio remembered
shortcuts (`INT-012`)," uses the bare word four times in two sentences ("Studio may remember...";
"populating a picker..."). Read in context (source URIs, repo/branch shortcuts, a companion-import
grammar step) this is unambiguously `StudioApp`, not `WebLoopStudio` — `WebLoopStudio` has no
"remembered source URI" concept of its own in this corpus; it authors and packages loops directly,
it does not import from a companion app's manifest/index files the way the CSApp/Assay/Studio lanes
do. This ledger does not edit `MANUAL_INTEGRATION_GRAMMAR.md` (out of WP-1L's scope — that document
belongs to WP-1) but records the disambiguated reading here so a future WP-1L-adjacent reader does
not misassign that section to `WebLoopStudio`.

---

## 7. New decisions proposed by WP-1L

Per this pack's rule (do not invent decision IDs; a session may propose and build *toward* a
decision while flagging it for owner ratification) and its precedent — `docs/non_ratified/
EXPERIMENTAL_DECISIONS.md`'s "Proposed new decisions" section already records exactly this pattern
for `FB-RAT-WS-NEW-1` (the JGit-for-real-git-operations proposal, filed by the WP-1
workspace-kernel domain agent and restated by the WP-1 non-ratified-registers agent) — this
section collects every `FB-RAT-*` ID a WP-1L document proposed, used normatively as if accepted in
the document that proposed it, but explicitly did **not** self-ratify. `FB-RAT-WS-NEW-1` differs
from the four below in one respect worth naming: it needed a `-NEW-<n>` suffix because no existing
`WS-*` sequence slot fit it. The four below did not need that — each continues its own domain's
existing `FB-RAT-<domain>-NNN` sequence directly (e.g. `PHN-011` follows the existing `PHN-001…010`
run) — but they are exactly as unratified as `WS-NEW-1` was, and this section is their citable
collection point, per this ledger's own stated purpose.

### `FB-RAT-PHN-011` — Bounded semantic undo/redo

**Proposed at:** `LOOP_PHONE_AUTHORING_SPEC.md` §14, "Undo and redo." **Status in that document:**
*"`FB-RAT-PHN-011` — PROPOSED, not yet ratified. This decision does not exist in the source pack —
the word 'undo' occurs zero times across the entire dual-surface draft... This is a genuine gap,
not a style preference, so it is recorded here as a proposal for the Amendments-phase agent to
accept, reject, or fold into an existing ID — this document does not self-ratify it."* **Proposed
shape:** undo/redo operates over semantic Stage View operations (add, insert, duplicate, reorder,
connect, branch, wrap-as-subloop, disable, delete) — never raw UI events; a visible control MUST
exist (never gesture-only); undo MUST be exposed as a TalkBack custom action; an AI-proposal-apply
is one undo transaction; undo is prohibited across activation/export boundaries. **Used normatively
as if accepted at:** `LOOP_PHONE_AUTHORING_SPEC.md` §3.2 (destructive structural verbs list, which
cross-references §14 as their mitigation) and §14's own state table.

### `FB-RAT-WEB-010` — One implementation compiled to both surfaces

**Proposed at:** `LOOP_WEB_STUDIO_SPEC.md` §9, "Package build pipeline." **Status in that
document:** *"PROPOSED, not self-ratified... This document does not have the authority to amend
`LOOP_DUAL_SURFACE_ARCHITECTURE.md` §10; it proposes the following as `FB-RAT-WEB-010`, for the
Amendments-phase agent to accept, reject, or fold into an existing ID."* **Proposed shape:** ship
one implementation of canonicalization, validation, and packaging (headless `:loop-canonicalization`,
`:loop-validation`, `:loop-packaging` Kotlin modules compiled to an ES-module npm artifact,
consumed from a TypeScript/React `WebLoopStudio` UI), replacing today's "two hand-written
implementations reconciled by golden vectors" obligation — which, per that section, makes the
browser a hard release blocker for the phone even though `LOOP_DUAL_SURFACE_ARCHITECTURE.md` §8
elsewhere requires "browser absence never blocks local execution." **Used normatively as if
accepted at:** `LOOP_WEB_STUDIO_SPEC.md` §9 (golden-vector requirement explicitly written against
"today's two-implementation reality," with a note that the fixtures become a regression suite if
`WEB-010` is ratified) and `LOOP_ENGINEERING_SPEC_V2.1.md` §12 (cited at its true PROPOSED status,
per that document's own report). **Explicit dependency this ledger surfaces:** accepting
`WEB-010` would require amending `LOOP_DUAL_SURFACE_ARCHITECTURE.md` §10's "need not share code"
language — that document is not amended here; this section only collects the proposal.

### `FB-RAT-MKT-012` — Registry replaceability guarantee

**Proposed at:** `LOOP_MARKETPLACE_CONTRACT.md` §12, "Registry replaceability." **Status in that
document:** *"NEW — PROPOSED, not self-ratified... This is a PROPOSED decision — it is stated here
as the file-specific instruction requires, but this document does not self-ratify a new stable ID;
formal registration in `docs/non_ratified/EXPERIMENTAL_DECISIONS.md`'s 'Proposed new decisions'
section, alongside `FB-RAT-WS-NEW-1`, is Amendments-phase / registry-hygiene work."* **Proposed
shape:** the registry base URL MUST be user-configurable; the registry client MUST be fully
disablable without losing local-authoring/direct-file/Git-transfer functionality; the index format
MUST be published so a third party MAY host a conformant registry of their own. **Used normatively
as if accepted at:** `LOOP_MARKETPLACE_CONTRACT.md` §12's own text and its "Correction" note (§2,
correction 4 in that document's dedupe report) explaining why it was added. This ledger is the
formal registration `LOOP_MARKETPLACE_CONTRACT.md` §12 itself asked for; a future pass MAY also
cross-list it in `EXPERIMENTAL_DECISIONS.md`'s global proposal queue for corpus-wide visibility —
this ledger does not edit that file, staying within WP-1L's own assigned output (`AMENDMENTS.md`).

### `FB-RAT-RES-008` — Revocation/deletion service obligation

**Not named in this ledger's original instruction set, but found by this ledger's own review of
the DedupeSpecs phase reports and included per the same "collect every PROPOSED decision" rule.**
**Proposed at:** `LOOP_RESULT_SHARING_CONTRACT.md` §9. **Status in that document:**
*"`FB-RAT-RES-008` — PROPOSED, not yet ratified. This obligation is stated above as normative text
because the source draft states it in both passes and it is a real, load-bearing requirement for
any hosting destination — but no decision ID assigned to this document (`FB-RAT-RES-001` through
`FB-RAT-RES-007`) actually covers it... This gap is recorded here as a proposal for the
Amendments-phase agent to accept, reject, or fold into an existing ID; this document does not
self-ratify it."* **Proposed shape:** promote the deletion/delisting support obligation, the
undiscoverable-third-party-copy disclosure, and the "never uploaded, nothing left to purge"
distinction to a numbered `RES-*` decision — distinct from `FB-RAT-MKT-007` (marketplace *listing*
moderation/takedown), since a shared *result* can be hosted and need removing independently of any
package listing it might be attached to. **Used normatively as if accepted at:**
`LOOP_RESULT_SHARING_CONTRACT.md` §9's merged "Revocation and deletion" / "Removal and retention"
section.

---

## 8. Other build-time items flagged for this ledger, not decision proposals

Beyond the seven items above, several WP-1L phase reports explicitly flagged findings "for the
Amendments-phase agent" that are not `FB-RAT-*` proposals — filename/path discrepancies and one
pre-existing package-level compile blocker. Recorded here so they are not lost between phase
reports; none of them is resolved by this ledger, since resolving them means editing files outside
this ledger's own scope (`AMENDMENTS.md` only, per this work package's instruction).

- **`loop-semantic-diff.schema.json` / `authority-diff.schema.json` filename-versioning
  discrepancy.** The WP-1L task brief's "SCHEMAS TO WRITE" file-list enumeration names both files
  with no version segment; the brief's per-schema instructions prose calls them
  `loop-semantic-diff.v1.schema.json` / `authority-diff.v1.schema.json`. Both schema-authoring
  groups treated the file-list enumeration as authoritative (matching every other file under
  `schemas/loops/`, none of which carries a `.v1.` segment) and flagged the discrepancy inline via
  `$comment`, not silently reconciled. No action needed unless a future pass wants filenames to
  carry an explicit version segment corpus-wide — a naming-convention decision, not a semantic one.
- **`dev.fonebrew.contracts.loops.ValidationFinding` declared twice.** `LoopAuthoringContracts.kt`
  (`severity: FindingSeverity`) and `LoopPackageContracts.kt` (`severity: LoopValidationSeverity`)
  each declare their own, different, top-level `data class ValidationFinding` in the same package —
  a genuine redeclaration the package will not compile with as-is. Both the activation-compatibility
  and marketplace Kotlin groups found this, avoided adding a third conflicting name (`activation`'s
  own finding type is `ValidationReportFinding`), and left the pre-existing collision for whichever
  session next runs a real compiler across the full `dev.fonebrew.contracts.loops` package to resolve
  — not fixed here, since it requires editing two files this ledger does not own.
- **Two stale forward pointers to `docs/release-gates/LOOP_P0_P1_RELEASE_GATES.md`.**
  `LOOP_DUAL_SURFACE_ARCHITECTURE.md` §12 and `LOOP_PHONE_AUTHORING_SPEC.md` §15 (via its
  `FB-RAT-PHN-007` citation) both point at that path — matching the dual-surface register's own
  citation path — but this work package's explicit build instruction places the actual file at
  `docs/ratified/loops/LOOP_P0_P1_RELEASE_GATES.md`, alongside the other nine dual-surface
  contracts. `LOOP_P0_P1_RELEASE_GATES.md` itself names this ("Correction 4") and declines to edit
  the two sibling files, since doing so is outside that document's own assignment. Fixing the two
  stale pointers is two small edits to files this ledger does not own; flagged here so it is not
  forgotten.
- **`authority-diff.schema.json` is a forward pointer into `loop-installation.schema.json`, not
  yet wired in.** The task brief names `AuthorityDiff` as "a reusable shape consumed by both
  `loop-installation` (update gate) and `loop-fork-lineage` (lineage comparison)." Only the
  `loop-fork-lineage` half is actually wired (`ForkLineage.authorityDiffSinceFork`) —
  `loop-installation.schema.json`, frozen by an earlier WP-1L group under this same work package,
  does not yet carry an update-gate `AuthorityDiff` field of its own. A future revision of that
  schema, or of `LOOP_IMPORT_ACTIVATION_CONTRACT.md`'s update flow (`FB-RAT-IMP-008`), is where
  that wiring belongs.
- **`ForkLineage` has no field recording that an authority-widening finding was acknowledged
  before publication.** `authorityDiffSinceFork.requiresFreshApproval` (§4 above's `AuthorityDiff`
  object) can be `true` on a fork a publisher then tries to publish, and `loop-fork-lineage.
  schema.json` has no field capturing that this specific finding was reviewed and acknowledged
  before that publication went out — documented as a gap in that schema group's adversarial
  fixture coverage rather than silently left unstated, but not closed by a schema field.

---

## Decision IDs cited in this document

`FB-RAT-INT-003` (§1), `FB-RAT-MKT-001` (§1), `FB-RAT-LOOP-001`…`006` (§2), `FB-RAT-PKG-008` (§2.3),
`FB-RAT-AUTH-002` (§3), `FB-RAT-IMP-005` (§3), `FB-RAT-EXE-009` (§4), `FB-RAT-EXE-004` (§4),
`FB-RAT-COM-002` (§5 header, table), `FB-RAT-COM-003`/`FB-RAT-COM-005`/`FB-RAT-COM-008`/
`FB-RAT-COM-009` (§5 table), `FB-RAT-CMP-006`, `FB-RAT-PHN-005`, `FB-RAT-PKG-009`, `FB-RAT-PKG-004`,
`FB-RAT-AUTH-004`, `FB-RAT-AUTH-007`, `FB-RAT-LBX-007`, `FB-RAT-INT-001`, `FB-RAT-INT-012`,
`FB-RAT-IMP-009`, `FB-RAT-INT-024`, `FB-RAT-MKT-004`, `FB-RAT-PORT-010`, `FB-RAT-PHN-006`,
`FB-RAT-WS-005`, `FB-RAT-WEB-002`, `FB-RAT-EXE-007`, `FB-RAT-RES-004` (all §5 table), `FB-RAT-PORT-011`,
`FB-RAT-INT-004` (§6), `FB-RAT-LBX-001`/`FB-RAT-LBX-002`/`FB-RAT-LBX-004` (§6), `FB-RAT-WS-NEW-1` (§7
header, precedent only), `FB-RAT-PHN-011`, `FB-RAT-WEB-010`, `FB-RAT-MKT-012`, `FB-RAT-RES-008`
(§7, PROPOSED — this document does not self-ratify any of the four), `FB-RAT-IMP-008` (§8).

No new `FB-RAT-*` ID is self-ratified by this document. `FB-RAT-PHN-011`, `FB-RAT-WEB-010`,
`FB-RAT-MKT-012`, and `FB-RAT-RES-008` remain PROPOSED after this ledger, exactly as their
respective source documents left them — this ledger's job was to collect them, not to rule on
them.

## Forward pointers (owned elsewhere, not restated here)

- **Owner ratification of the four PROPOSED IDs in §7** — not this session's, or any build-out
  session's, authority.
- **The two stale `docs/release-gates/` pointers and the `ValidationFinding` redeclaration (§8)** —
  small, mechanical fixes belonging to whichever future pass next touches
  `LOOP_DUAL_SURFACE_ARCHITECTURE.md`/`LOOP_PHONE_AUTHORING_SPEC.md`, and
  `LoopAuthoringContracts.kt`/`LoopPackageContracts.kt`, respectively.
- **`loop-installation.schema.json`'s update-gate `AuthorityDiff` wiring (§8)** — a future revision
  of that schema or of `LOOP_IMPORT_ACTIVATION_CONTRACT.md`'s update flow.
- **Cross-listing §7's four proposals into `docs/non_ratified/EXPERIMENTAL_DECISIONS.md`'s global
  "Proposed new decisions" queue** — optional follow-up hygiene work, not required for this ledger
  to satisfy its own stated purpose (§7 already is the citable collection point WP-1L's own
  documents pointed at).
