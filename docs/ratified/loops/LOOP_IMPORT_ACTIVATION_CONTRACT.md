# Loop Import and Activation Contract

> **License:** `LICENSE-PENDING` — see `docs/non_ratified/LICENSE_PENDING.md`.

**Status:** RATIFIED (this document's own sections), with one named exception carried at its true
status: `FB-RAT-IMP-010` is **DEFERRED** (`docs/non_ratified/DEFERRED_DECISIONS.md`), called out
inline at §6. **Scope:** the local-only process that turns a verified `.floop` `LoopPackage` (or a
freshly authored draft) into a running, authority-bound installation on one phone — acquisition,
container verification, parsing/validation, compatibility evaluation, preview, binding, authority
review, simulation, installation, and the receipt each step leaves behind. This document is the
citation target for `FB-RAT-IMP-001` through `FB-RAT-IMP-010` and, because it owns the state
machine importing a package runs through, `FB-RAT-PHN-009` (dangerous first-run simulation, §7).

This document does **not** redefine the `.floop` container's bytes, digests, declarative-content
policy, package-safety adversarial classes, or binding-placeholder *shape* — those are
`LOOP_PACKAGE_SPEC.md` (`FB-RAT-PKG-*`, `FB-RAT-WEB-005`), frozen and cross-referenced throughout,
never re-derived. It does not redefine the two-surface architecture that makes activation a
phone-only operation (`LOOP_DUAL_SURFACE_ARCHITECTURE.md`, `FB-RAT-LBX-001`/`FB-RAT-LBX-002`) or
the general authority ladder and grant model (`CAPABILITY_AUTHORITY_MODEL.md`, `FB-RAT-AUTH-*`).
It does not redefine multi-axis compatibility outcome levels — that is the sibling
`LOOP_COMPATIBILITY_CONTRACT.md` (`FB-RAT-CMP-*`), not yet written under `docs/ratified/loops/` as
of this document; this document only names the point in its own state machine (§3) where that
sibling document's outcome levels apply. A conflict between this document's prose and a frozen
registry's data, or an already-ratified sibling document's decision, is this document's error, and
the other side wins.

## Note on this document's structure

The extracted first-pass/second-pass draft this document replaces uses an 18-heading sequence
(`### 1.` through `### 19.`, with `### 11.` never used — a numbering gap, not a duplicate, left
as-is below rather than silently closed) that states the same import/activation flow **twice**:
`10_DUAL_VALIDATION_ADDENDUM.md` §B2 names this file specifically — *"two different import state
machines (§2 has no compatibility state, required by IMP-007; §12 has no preview state, required
by IMP-001)"* — and that same first-pass/second-pass split repeats through most of the sections
that follow §2 and §12: binding (draft §5, then again at §14), authority (draft §6, then again at
§15), the installation/activation distinction (draft §7, then again at §17), and update behavior
(draft §8, then again at §18). Draft §3's ten numbered "normative flow" steps are a third
restatement of the same sequence draft §2 and §12 each state as a bare state list — folded into
this document's merged transition table (§3) rather than kept as a separate prose list, so the
flow is stated once. Draft §4 (import isolation, the whole pipeline) and draft §13 (preview
specifically) are related but not duplicate — the pipeline-wide "nothing executes" guarantee and
the preview step's own narrower display rules are two different claims about two different scopes
— and are kept as one section (§4) with that distinction stated explicitly rather than merged into
one undifferentiated claim.

Every one of the 19 draft sections is folded into exactly one section below; nothing is dropped.
§2 ("Relationship to the manual-integration grammar") is new framing text that did not exist in
the draft at all — it states normatively what `09_DUAL_SURFACE_LOOP_BUILDER.md` §3 says
descriptively (the six-step companion-data grammar and this file's package grammar are one learned
workflow, two profiles, not two import UIs) — flagged inline where it appears.

| Draft § | Draft topic | This document's § |
|---|---|---|
| 1 | Principle | §1 |
| 2 | States (first pass — no compatibility state) | §3 |
| 3 | Normative flow (ten numbered steps) | §3 |
| 4 | Import isolation | §4 |
| 5 | Binding (first pass) | §5 |
| 6 | Authority (first pass) | §6 |
| 7 | Installation and editing (first pass) | §8 |
| 8 | Updates (first pass) | §9 |
| 9 | Receipt | §10 |
| 10 | Error examples | §11 |
| 11 | *(gap in the draft — no §11 exists; not closed here, only noted)* | — |
| 12 | Import state machine (second pass — no preview state) | §3 |
| 13 | Preview before trust | §4 |
| 14 | Binding resolution order (second pass) | §5 |
| 15 | Authority review (second pass) | §6 |
| 16 | Simulation policy | §7 |
| 17 | Installation and activation distinction (second pass) | §8 |
| 18 | Update behavior (second pass) | §9 |
| 19 | Error recovery | §12 |
| — | *(not in draft)* | §2 (shared-grammar relationship and `LOOP-PKG-005` cross-reference), §6's authority-bucket/ladder mapping |

**Corrections applied, beyond de-duplication (detailed where they appear):**

1. **Two state machines resolved to one eleven-state machine, exactly as specified.** Draft §2's
   ten states have no `COMPATIBILITY_EVALUATED` state, which `FB-RAT-IMP-007` requires; draft
   §12's ten states have no `PREVIEWED` state, which `FB-RAT-IMP-001` requires. §3 below merges
   them into the single eleven-state ordered sequence — (1) `ACQUIRING`, (2) `SNAPSHOTTED`,
   (3) `CONTAINER_VERIFIED`, (4) `PARSED_VALIDATED`, (5) `COMPATIBILITY_EVALUATED`,
   (6) `PREVIEWED`, (7) `WAITING_BINDINGS`, (8) `WAITING_AUTHORITY`, (9) `READY_TO_SIMULATE`,
   (10) `INSTALLABLE`, (11) `INSTALLED` — per `10_DUAL_VALIDATION_ADDENDUM.md` §B2's own
   resolution, rendered as a from-state/event/to-state/receipt table rather than an arrow chain.
2. **Terminal states reconciled, not merely unioned.** Draft §2 names `REJECTED_INTEGRITY`,
   `REJECTED_POLICY`, `INCOMPATIBLE`, `CANCELLED`, `FAILED_SAFE`; draft §12 names `CANCELLED`,
   `REJECTED_UNSAFE`, `BLOCKED_INCOMPATIBLE`, `FAILED_SAFE` — four terminals, dropping
   `REJECTED_POLICY` without explanation. §3 keeps all five distinct terminal conditions, using
   each pass's more precise name where the two disagree (`REJECTED_UNSAFE` over
   `REJECTED_INTEGRITY`, `BLOCKED_INCOMPATIBLE` over `INCOMPATIBLE`), because a policy validation
   failure (schema/graph/engine-policy rejection) and a safety validation failure (digest/
   signature/forbidden-payload rejection) are observably different conditions that route to
   different remediation, and collapsing them loses that distinction.
3. **The six-step manual-integration grammar is named explicitly as this file's shared spine.**
   Neither draft pass states this — it is asserted only descriptively in
   `09_DUAL_SURFACE_LOOP_BUILDER.md` §3. §2 below makes it normative: one `ImportFlow` state
   family, two profiles (`DATA_IMPORT`, `PACKAGE_IMPORT`), never two unrelated import UIs.
4. **`PARTIAL_IMPORT` is declared unsupported for `PACKAGE_IMPORT`, normatively, not just noted.**
   Neither draft pass states this either — INT-007 (`docs/ratified/MANUAL_INTEGRATION_GRAMMAR.md`
   §5) requires explicit partial-import support for the `DATA_IMPORT` profile; §2 below states the
   opposite rule for `PACKAGE_IMPORT` and cross-references the already-frozen `LOOP-PKG-005` rule
   code (`loop-validation-rules.v1.json`) that enforces it.
5. **§6's authority-bucket-to-ladder mapping is stated explicitly, as new normative text.** Draft
   §6/§15 group capability requests into Observe/Read/Propose/Write/External/Destructive/Publish
   without ever relating that grouping to the eight-rung ladder `CAPABILITY_AUTHORITY_MODEL.md`
   §2 (`FB-RAT-AUTH-002`) already ratifies. §6 states the mapping row
   `10_DUAL_VALIDATION_ADDENDUM.md` §F identifies as missing (`Write = {MODIFY_DRAFT,
   EXECUTE_REVERSIBLE}`) so an authority-diff engine has a deterministic answer for what "Write"
   means when comparing two releases.

---

## 1. Principle

Importing bytes is not granting authority. Installation is not execution. Activation MUST be the
local, phone-only process that binds an inspected, verified, declarative `LoopPackage`
(`LOOP_PACKAGE_SPEC.md`) to this phone's own capabilities — models, repositories, execution
targets, devices, secrets — under explicit, scoped, revocable user control. Nothing downstream in
this document weakens that sentence; every section is what makes it testable.

## 2. Relationship to the manual-integration grammar — one spine, two profiles

`docs/ratified/MANUAL_INTEGRATION_GRAMMAR.md` §2 ratifies `FB-RAT-INT-011`: a six-step grammar —
(1) Choose source, (2) Snapshot, (3) Validate, (4) Preview, (5) Confirm, (6) Receipt — for every
companion-app data import (CSApp issues, Assay findings). **This document's eleven-state machine
(§3) is that same spine, plus package-specific extension steps, not a second import UI.** One
learned workflow, one code path, two profiles:

| Profile | Governs | Extension steps beyond the shared spine |
|---|---|---|
| `DATA_IMPORT` | CSApp/Assay records — no executable intent, no authority request | None. INT-011's six steps run unmodified (`docs/ratified/MANUAL_INTEGRATION_GRAMMAR.md` §2). |
| `PACKAGE_IMPORT` | A `LoopPackage` — carries declared capability requests and, once activated, produces executable behavior | Verify container identity/integrity; evaluate compatibility; resolve binding slots; review and grant authority; offer simulation; install as a distinct step from confirming (§3). |

`FB-RAT-IMP-001` — **ACCEPTED.** *"Use (1) Choose source, (2) Snapshot, (3) Verify
identity/integrity, (4) Validate, (5) Preview, (6) Bind, (7) Approve, (8) Simulate, (9) Install,
(10) Receipt."* This is the `PACKAGE_IMPORT` profile's step sequence; §3 gives it as an
eleven-state machine because two of the ten steps above (`Validate` and `Preview`) each need a
state either side of a decision point (`COMPATIBILITY_EVALUATED` sits between them) that the flat
step list does not distinguish.

A reader who only needs the `DATA_IMPORT` profile should stop at
`docs/ratified/MANUAL_INTEGRATION_GRAMMAR.md` — this document exists only for the steps
`PACKAGE_IMPORT` adds.

**`PARTIAL_IMPORT` is unsupported for `PACKAGE_IMPORT` — a `LoopPackage` import is all-or-nothing.**
This is a deliberate divergence from the `DATA_IMPORT` profile, where `FB-RAT-INT-007` (`docs/
ratified/MANUAL_INTEGRATION_GRAMMAR.md` §5) requires explicit support for a user-confirmed partial
import of individual records. A package's nodes, schemas, fixtures, and manifest form one
digest-addressed, signature-covered unit (`LOOP_PACKAGE_SPEC.md` §4, §11); there is no sub-package
granularity a partial import could select without invalidating the digest and the signature both
cover. This is enforced as rule code `LOOP-PKG-005` in `loop-validation-rules.v1.json`
(*"Package import for '{loopId}' was PARTIAL; partial import is unsupported for PACKAGE_IMPORT
profile... contrast with INT-007's companion-data partial-import support"*) — a reader implementing
either profile's Confirm/Install step MUST reject any code path that would let a `PACKAGE_IMPORT`
user select a subset of a package's declared content the way a `DATA_IMPORT` user selects a subset
of records.

## 3. The eleven-state import/activation machine

*(Merges draft §2's state list, draft §3's ten numbered steps, and draft §12's state list —
the pack's central duplicate-numbering defect for this file per `10_DUAL_VALIDATION_ADDENDUM.md`
§B2, resolved to the single sequence that document specifies.)*

**`FB-RAT-IMP-001`** (§2) governs the step sequence below; **`FB-RAT-IMP-002` — ACCEPTED** (*"No
model, tool, shell, remote, device, or side-effecting node may execute during package parsing,
validation, preview, or installation"*) governs every state through `INSTALLABLE` (detailed at
§4); **`FB-RAT-IMP-007` — ACCEPTED** (*"Import distinguishes compatible, compatible-with-bindings,
degraded, blocked, and unsupported states with actionable reasons"*) governs the
`COMPATIBILITY_EVALUATED` transition, whose full outcome-level vocabulary is owned by the sibling
`LOOP_COMPATIBILITY_CONTRACT.md` and not restated here.

| From state | Event | To state | Receipt |
|---|---|---|---|
| *(none — user action)* | User chooses a source: `.floop` file, share-sheet payload, marketplace/unlisted release URL, QR-carried URL+digest, or a user-owned Git repository (`LOOP_PACKAGE_SPEC.md` §2). Nothing is read yet. | `ACQUIRING` | none yet — choosing is not importing |
| `ACQUIRING` | Core reads the exact bytes at this instant and computes `packageContentDigest` (`LOOP_PACKAGE_SPEC.md` §4). This is the byte-for-byte content the rest of the flow reasons about even if the source changes a moment later. | `SNAPSHOTTED` | lightweight append-only event (`FB-RAT-COM-006`) |
| `SNAPSHOTTED` | Verify container manifest, archive safety (adversarial classes per `floop-container-format.v1.json`), digest, and signature (`LOOP_PACKAGE_SPEC.md` §11). | `CONTAINER_VERIFIED` | lightweight event |
| `CONTAINER_VERIFIED` | Validate schemas, graph structure, forbidden content, and engine policy against `loop-validation-rules.v1.json`. Nothing is created or changed yet. | `PARSED_VALIDATED` | validation report (rule codes + severities) |
| `PARSED_VALIDATED` | Evaluate the compatibility axes (`FB-RAT-IMP-007`, above) against this phone's engine version, capability set, and distribution flavor. | `COMPATIBILITY_EVALUATED` | compatibility report (owned by `LOOP_COMPATIBILITY_CONTRACT.md`) |
| `COMPATIBILITY_EVALUATED` | Compute and show the safe preview: identity, objective, stages, side effects, compatibility result, publisher, provenance, tests (§4). | `PREVIEWED` | none — preview is a read, not a mutation |
| `PREVIEWED` | User confirms proceeding; resolve binding slots (§5) against local resources. | `WAITING_BINDINGS` | none until bindings resolve |
| `WAITING_BINDINGS` | All required binding slots resolved (or, per §5, blocked). | `WAITING_AUTHORITY` | binding profile digest |
| `WAITING_AUTHORITY` | User reviews and grants (or narrows/denies) requested capabilities (§6). | `READY_TO_SIMULATE` | authority grant record(s), one per `Grant` (`CAPABILITY_AUTHORITY_MODEL.md` §3.2) |
| `READY_TO_SIMULATE` | Simulation/preflight runs where required or available (§7), or the UI states the gap and requires stronger acknowledgement. | `INSTALLABLE` | simulation result |
| `INSTALLABLE` | User confirms install; Core installs the immutable release plus the local binding profile (§8). | `INSTALLED` | full activation receipt (§10, `FB-RAT-IMP-009`) |

**Terminal states — reachable from any non-terminal state above (`ACQUIRING` through
`INSTALLABLE`), never only from one:**

| From state | Event | To state | Receipt |
|---|---|---|---|
| any non-terminal state | User explicitly cancels. | `CANCELLED` | cancellation event, no partial installation left active |
| `ACQUIRING`…`CONTAINER_VERIFIED` | Container-level safety check fails: digest mismatch, invalid signature, archive-safety violation, forbidden payload, or a `LOOP-PKG-006`/`LOOP-PKG-001` secret/Unicode finding (`LOOP_PACKAGE_SPEC.md` §6–§7). | `REJECTED_UNSAFE` | rejection record naming the failed check; source untouched |
| `CONTAINER_VERIFIED`…`WAITING_AUTHORITY` | Schema/graph/engine-policy validation fails, or a required capability is explicitly denied (§6), or a binding attempt would widen authority (`LOOP-CAP-002`). | `REJECTED_POLICY` | rejection record naming the failed rule code(s) |
| `PARSED_VALIDATED`…`COMPATIBILITY_EVALUATED` | Compatibility evaluation returns `BLOCKED` or `UNSUPPORTED` (`LOOP_COMPATIBILITY_CONTRACT.md`). | `BLOCKED_INCOMPATIBLE` | compatibility report with actionable reasons |
| any non-terminal state | An unexpected error occurs, or the target/authority state becomes unknown mid-flow (§12). | `FAILED_SAFE` | error record; installation disabled until re-evaluated |

A state transition is receipt-backed at every step (`FB-RAT-COM-006`, append-only) — the "Receipt"
column above names what that specific transition's record carries; §10 gives the shape of the
one, full `ActivationReceipt` written on entry to `INSTALLED`. **The package is never exposed as
runnable before `INSTALLED`** — this is the same "no execution during import" guarantee §4 states
for the pipeline, restated here as the state machine's own invariant: no state before `INSTALLED`
has an outgoing edge that runs package-declared behavior.

## 4. Import isolation and preview boundaries

*(Draft §4, "Import isolation," governs the whole pipeline through `INSTALLABLE`; draft §13,
"Preview before trust," narrows that same no-execution guarantee to exactly what the
`PREVIEWED` state may display. Two different claims about two different scopes, kept as one
section because they share one guarantee and diverge only in what each scope is allowed to touch.)*

**`FB-RAT-IMP-002` — ACCEPTED.** *"No model, tool, shell, remote, device, or side-effecting node
may execute during package parsing, validation, preview, or installation."* Concretely: import
parsing and validation (`SNAPSHOTTED` through `PARSED_VALIDATED`) run without model calls, tool
calls, shell execution, remote access, device access, or any package-defined network call. Images
and documentation are decoded with resource limits (`LOOP_PACKAGE_SPEC.md` §13). No package
content executes at any state before `INSTALLED`.

**`FB-RAT-IMP-003` — ACCEPTED.** *"Fonebrew revalidates every package, including
marketplace-signed packages; server validation is advisory, never authoritative."* A package that
arrived through a signed marketplace listing (`LOOP_MARKETPLACE_CONTRACT.md`, not yet written as
of this document) is revalidated on-device through the same `CONTAINER_VERIFIED`,
`PARSED_VALIDATED`, `COMPATIBILITY_EVALUATED` sequence (§3) as a package acquired by any other
channel — a remote review or signature check is evidence this phone MAY show the user, never a
substitute for its own validation pass. No publisher trust level skips this pipeline, and (§6)
none skips authority review either.

**Preview specifically (`PREVIEWED` state).** Safe preview MAY display package identity,
documentation text after sanitization, static graph structure, publisher information, requested
capabilities, and validation findings. It **MUST NOT** resolve remote assets, execute embedded
content, or invoke model/tool nodes — the preview step is read-only evidence for the user's own
decision, not a partial activation.

## 5. Binding

*(Merges draft §5, "Binding," with draft §14, "Binding resolution order" — the shape a binding
record carries, then the deterministic order in which a resolver picks a value for it.)*

**`FB-RAT-IMP-004` — ACCEPTED.** *"Capabilities, models, repositories, execution targets, devices,
budgets, and secret references are resolved locally before activation."* A binding resolves one of
a package's declared abstract placeholders (`LOOP_PACKAGE_SPEC.md` §9 — `model.writer`,
`repository.primary`, `ci.default`, `device.target`, `secret.release_signing`, and so on; that
document owns the placeholder's shape and is not re-derived here) to an actual local resource.
Each binding record MUST carry:

- slot ID and requirement;
- selected provider/resource ID;
- provider version and target type;
- substitution policy and reason;
- local/cloud/remote provenance;
- constraints and budgets;
- secret reference ID **without** the secret value (`LOOP_PACKAGE_SPEC.md` §9's `fb.secret.use`
  purpose-bound-handle mechanism — a binding never carries a raw secret).

A binding resolver MUST attempt, in this order, stopping at the first that succeeds:

| Order | Rule | Outcome |
|---|---|---|
| 1 | An exact, previously approved local binding exists for the same package digest. | Reuse it — no new prompt. |
| 2 | An exact capability/provider match the user selects fresh. | Bind to it; record as a new approval. |
| 3 | A declared equivalent under the package's own substitution policy. | Bind to the equivalent; record the substitution reason. |
| 4 | An explicit user-approved substitute outside the declared policy. | Bind to it only with the user's explicit override; record it as an override, not a policy match. |
| 5 | None of the above resolves. | Unresolved blocker — the slot stays in `WAITING_BINDINGS`. |

Missing required bindings block activation — the state machine (§3) cannot leave
`WAITING_BINDINGS` while any required slot is unresolved. Optional bindings MAY produce a
`DEGRADED` compatibility outcome instead of a block, but only where the graph itself declares a
valid fallback for that slot — an optional binding left unresolved without a declared fallback is
still a blocker, not a silent no-op. Bindings are scoped to a specific package digest or to a
user-approved compatible version range; a binding never survives a change of `loopId` or an
incompatible `semanticVersion` bump without fresh review.

## 6. Authority

*(Merges draft §6, "Authority," with draft §15, "Authority review" — the same review requirement
stated first as a rule, then again with the grouping and defaults it uses in practice.)*

**`FB-RAT-IMP-005` — ACCEPTED.** *"A local binding profile MAY grant less authority than requested
but MUST NOT silently grant more."* This is the loop-import-time instance of the narrowing
invariant `CAPABILITY_AUTHORITY_MODEL.md` §6 (`FB-RAT-AUTH-004`, no implicit privilege expansion)
already states generally, and of `LOOP-CAP-002` (`loop-validation-rules.v1.json`), which
`LOOP_PACKAGE_SPEC.md` §9 already cites as the same invariant's binding-time enforcement — neither
is re-decided here, only applied at the point authority review actually happens.

The authority review (`WAITING_AUTHORITY` state, §3) groups capability requests by seven buckets —
Observe, Read, Propose, Write, External, Destructive, Publish — for display. **This grouping is a
UI convenience over the eight-rung ladder `CAPABILITY_AUTHORITY_MODEL.md` §2 (`FB-RAT-AUTH-002`)
already ratifies, never a second, competing enum**, per the mapping below (new normative text —
neither draft pass states it, and `10_DUAL_VALIDATION_ADDENDUM.md` §F identifies its absence as
the reason an authority-diff engine cannot otherwise tell a `MODIFY_DRAFT`-to-`EXECUTE_REVERSIBLE`
widening from a same-bucket no-op):

| Review bucket | Authority ladder rung(s) (`FB-RAT-AUTH-002`) |
|---|---|
| Observe | `OBSERVE` |
| Read | `READ` |
| Propose | `PROPOSE` |
| Write | `MODIFY_DRAFT`, `EXECUTE_REVERSIBLE` |
| External | `EXECUTE_EXTERNAL` |
| Destructive | `EXECUTE_DESTRUCTIVE` |
| Publish | `PUBLISH_OR_RELEASE` |

The review names affected project/host/device scopes, network destinations where knowable
(scoped further at import time by `LOOP-PKG-007`'s manifest egress allowlist,
`LOOP_PACKAGE_SPEC.md` §7), maximum budget, and confirmation policy. The user MAY deny optional
capabilities or narrow scope; a **required** denial blocks activation with an explanation — the
state machine cannot leave `WAITING_AUTHORITY` while a required capability is denied without also
routing to `REJECTED_POLICY` (§3). The default grant is the narrowest set that permits the
selected bindings (§5); **"Allow all" is not a primary action.** No publisher trust level — signed,
marketplace-reviewed, or otherwise — skips this review (§4 restates the same rule for the
validation pipeline generally).

**`FB-RAT-IMP-010` — DEFERRED** (`docs/non_ratified/DEFERRED_DECISIONS.md`). *"Defer any
reduced-confirmation install flow until publisher trust, authority diff, and abuse gates are
proven."* A one-tap install from a trusted creator is not a live proposal in this document — the
full authority review above applies uniformly regardless of publisher reputation until that
deferral is lifted by a future ratification.

## 7. Simulation policy — dangerous first-run simulation

**`FB-RAT-PHN-009` — ACCEPTED.** *"Imported or newly authored loops requesting write, external,
destructive, or publish authority MUST offer simulation/preflight before first activation."*
Concretely: simulation is required before first activation for any loop — imported or freshly
authored on-phone — carrying a `Write`, `External`, `Destructive`, or `Publish` bucket request
(§6's mapping applies), **unless no safe simulator exists for that node category**, in which case
the UI MUST state the gap explicitly and require a stronger, distinct acknowledgement in place of
a simulation result. This is the `READY_TO_SIMULATE`-to-`INSTALLABLE` transition in §3's state
machine, and it is the on-device instance of `AuthorityDecision.AllowWithRedactionOrSandbox`'s
`SANDBOX` mode that `CAPABILITY_AUTHORITY_MODEL.md` §4 already names for exactly this case — *"an
imported companion artifact's first activation"* under the `IMPORTED_COMPANION_ARTIFACT` principal
kind (`CAPABILITY_AUTHORITY_MODEL.md` §3.1) — not a second, competing mechanism.

Simulation uses fixtures and mocked side effects (`LOOP_PACKAGE_SPEC.md` §3's `fixtures/`
directory, `LOOP-TEST-001`/`LOOP-TEST-002`). It **cannot** be represented as evidence that the real
target will succeed — a passing simulation is evidence the declared behavior is well-formed against
its own fixtures, never a claim about the live target's actual response.

## 8. Installation and activation are distinct

*(Merges draft §7, "Installation and editing," with draft §17, "Installation and activation
distinction" — what installation stores, then the separate concept of an activation profile
layered on top of it.)*

**`FB-RAT-IMP-006` — ACCEPTED.** *"An installed release remains byte-identical to its package;
editing creates a forked local draft."* Installation (the `INSTALLABLE`-to-`INSTALLED` transition,
§3) stores: package bytes or canonical extracted content, `packageContentDigest`, release identity
(`loopId` + `semanticVersion`), the compatibility report, signature state, the binding profile
(§5), authority grants (§6), and receipts (§10). **The installed artifact is immutable** — this is
`LOOP-ID-002` (`loop-validation-rules.v1.json`) at its install-time enforcement point, already
cited by `LOOP_PACKAGE_SPEC.md` §12 and not re-decided here. Editing an installed loop creates a
local forked draft and a lineage record (owned in detail by the forthcoming
`LOOP_FORK_LINEAGE_CONTRACT.md`, `FB-RAT-LIN-*`) — it never mutates the installed bytes in place.

**Installation and activation are two different acts, and an installed loop MAY have zero, one, or
several activation profiles.** Installation preserves the verified immutable package locally.
Activation attaches a *selected* binding profile and authority grant set to that installed
package — a user MAY install once and activate the same package under two different binding
profiles (say, two different target repositories) without a second install. Revoking a grant
disables the affected activation profile(s) without deleting the underlying package — the package
remains installed, inspectable, and re-activatable under a fresh grant.

## 9. Updates

*(Merges draft §8, "Updates," with draft §18, "Update behavior" — the fields an update preview
must separate, then the operational rules governing how an update actually proceeds.)*

**`FB-RAT-IMP-008` — ACCEPTED.** *"An update adding or widening capabilities, network access,
targets, side effects, or secrets requires a fresh approval."* This is the import-time citation
of `LOOP-COMPAT-002` (`loop-validation-rules.v1.json`: *"Update... widens authority, weakens a
verifier, adds an external target, adds a secret slot, increases budget, or adds a destructive path
without fresh approval"*), which `LOOP_PACKAGE_SPEC.md` §12 already establishes at the
versioning layer — this section states its consequence for the running import/activation flow, not
a second rule.

An update preview MUST separate:

- semantic graph changes;
- input/output changes;
- authority and side-effect changes;
- binding changes;
- compatibility changes;
- test and verification changes;
- publisher/signing changes.

Authority widening or a signing-key change requires a fresh `WAITING_AUTHORITY` pass (§6) even for
an otherwise same-major-version patch — an update MUST NOT reuse a prior grant across any of those
lines. Existing bindings (§5) are reused only if still compatible and not broadened by the update.
Operationally: a new release imports **beside** the old release until the user chooses migration —
it does not silently replace an active installation. Rollback switches the active
installation/profile pointer; historical runs remain attached to the original version they
actually ran against, never retroactively repointed at the newer release.

## 10. Receipt

**`FB-RAT-IMP-009` — ACCEPTED.** *"Every installation records source, package digest, signature
state, validation report, bindings digest, authority grant IDs, and installation receipt."* The
activation receipt written on entry to `INSTALLED` (§3) MUST include: package/release identity,
source, the validation `rulesetVersion` applied (`loop-validation-rules.v1.json`) and its findings,
signature result, compatibility result, binding profile digest, authority grant IDs, simulation
result (§7), installation timestamp (`FB-RAT-COM-004`), app/engine version (`engineVersion`,
`capability-ids.v1.json`), and any warnings the user acknowledged.

This specializes the same provenance minimum `docs/ratified/COMMON_CONVENTIONS.md` §8
(`FB-RAT-COM-008`) states generally for every execution/model/import result — source location,
project revision, initiating principal, evidence links — for the activation case specifically, the
same way `docs/ratified/IMPORT_RECEIPT_V1.md` specializes it for the `DATA_IMPORT` profile's
`ImportReceipt`. Receipts here are append-only (`FB-RAT-COM-006`) exactly as that document's are —
a correction is a new receipt, never an in-place edit. The concrete `loop-activation-receipt.v1`
schema and its Kotlin shape in `LoopActivationContracts.kt` are not yet written as of this
document (schema and Kotlin-contract authoring is WP-1L scope beyond this file); a future
consolidation of every receipt kind in this constellation behind one `Receipt` envelope with a
`receiptType` enum in `contracts/kotlin/CommonContracts.kt` (`10_DUAL_VALIDATION_ADDENDUM.md`
§E) is noted as a forward pointer, not designed here.

## 11. Error taxonomy

Error codes surfaced by the states in §3, stable across releases (`FB-RAT-COM-007`: code,
severity, retryability, side-effect state, technical detail, recovery action — every code below
carries all six as an `ErrorEnvelope`, `schemas/common/error.schema.json`, not restated per row):

| Code | Meaning | Routes to (§3) |
|---|---|---|
| `LOOP_IMPORT_ARCHIVE_UNSAFE` | Container fails an archive-safety adversarial check (traversal, decompression bomb, duplicate normalized paths — `floop-container-format.v1.json`). | `REJECTED_UNSAFE` |
| `LOOP_IMPORT_DIGEST_MISMATCH` | Declared digest does not equal computed `packageContentDigest`. | `REJECTED_UNSAFE` |
| `LOOP_IMPORT_SIGNATURE_INVALID` | Signature fails cryptographic verification (`LOOP_PACKAGE_SPEC.md` §11 — never treated as equivalent to unsigned). | `REJECTED_UNSAFE` |
| `LOOP_IMPORT_SCHEMA_MAJOR_UNSUPPORTED` | Package/definition schema major version is unknown to this engine. | `REJECTED_POLICY` |
| `LOOP_IMPORT_FORBIDDEN_PAYLOAD` | A file matches `LOOP-PKG-002`'s forbidden-executable-payload class. | `REJECTED_UNSAFE` |
| `LOOP_IMPORT_SECRET_SUSPECTED` | A file matches `LOOP-PKG-001`'s secret-like pattern class. | `REJECTED_UNSAFE` |
| `LOOP_BINDING_REQUIRED_MISSING` | A required binding slot (§5) has no resolvable candidate at any resolution-order tier. | stays in `WAITING_BINDINGS`, or `REJECTED_POLICY` if the user cancels rather than resolves |
| `LOOP_CAPABILITY_REQUIRED_DENIED` | A required capability (§6) was explicitly denied. | `REJECTED_POLICY` |
| `LOOP_UPDATE_AUTHORITY_WIDENED` | An update (§9) widens authority without a fresh `WAITING_AUTHORITY` pass — `LOOP-COMPAT-002`. | blocks the update; the prior installation is unaffected |
| `LOOP_IMPORT_PARTIAL_UNSUPPORTED` | A `PACKAGE_IMPORT` attempted a partial-selection import (§2) — `LOOP-PKG-005`. | `REJECTED_POLICY` |
| `LOOP_IMPORT_INCOMPATIBLE` | Compatibility evaluation (`FB-RAT-IMP-007`) returns `BLOCKED` or `UNSUPPORTED`. | `BLOCKED_INCOMPATIBLE` |

## 12. Error recovery

Interrupted download resumes only when the server and digest protocol support safe byte ranges;
otherwise it restarts from `ACQUIRING`. Interrupted extraction discards the temporary tree — a
partially extracted container is never left as a candidate for `CONTAINER_VERIFIED`. Interrupted
activation (a process death between `INSTALLABLE` and `INSTALLED`) leaves the immutable
installation from a prior successful install, if any, untouched, but produces no active profile
for the interrupted attempt. **If target or authority state becomes unknown mid-flow, installation
remains disabled until re-evaluated** — this is the concrete trigger for the `FAILED_SAFE` terminal
(§3): uncertainty about what a package can currently do is never resolved by optimistic default,
only by re-running the evaluation that produced the uncertainty.

## 13. Cross-references and open items

**Decision IDs cited in this document:** `FB-RAT-IMP-001` (§2, §3), `FB-RAT-IMP-002` (§3, §4),
`FB-RAT-IMP-003` (§4), `FB-RAT-IMP-004` (§5), `FB-RAT-IMP-005` (§6), `FB-RAT-IMP-006` (§8),
`FB-RAT-IMP-007` (§3), `FB-RAT-IMP-008` (§9), `FB-RAT-IMP-009` (§10), `FB-RAT-IMP-010` (§6,
DEFERRED), `FB-RAT-PHN-009` (§7). `FB-RAT-LBX-001`/`FB-RAT-LBX-002` (header), `FB-RAT-AUTH-002`/
`FB-RAT-AUTH-004` (§6), `FB-RAT-COM-004`/`FB-RAT-COM-006`/`FB-RAT-COM-007`/`FB-RAT-COM-008` (§10,
§11), and `FB-RAT-INT-007`/`FB-RAT-INT-011` (§2) are cited as already-ratified context this
document specializes or contrasts against, not re-decided here.

**Registries reconciled, not redefined:** `loop-validation-rules.v1.json` (rule codes
`LOOP-ID-002`, `LOOP-CAP-002`, `LOOP-PKG-001`/`002`/`005`/`006`/`007`, `LOOP-COMPAT-002`,
`LOOP-TEST-001`/`002` — §3, §4, §6, §9, §11); `capability-ids.v1.json` (`engineVersion`,
`fb.secret.use` — §5, §10); `floop-container-format.v1.json` (§3, §11).

**Sibling documents this document defers to and does not restate:** `LOOP_PACKAGE_SPEC.md`
(§5, §6, §8 of this document — package identity, digests, declarative-content policy, binding
placeholder shape, versioning); `LOOP_DUAL_SURFACE_ARCHITECTURE.md` (header — why activation is
phone-only); `CAPABILITY_AUTHORITY_MODEL.md` (§6, §7 — the general ladder and grant model this
document's binding/authority sections specialize); `docs/ratified/MANUAL_INTEGRATION_GRAMMAR.md`
and `docs/ratified/IMPORT_RECEIPT_V1.md` (§2, §10 — the shared spine and the receipt pattern this
document specializes for the `PACKAGE_IMPORT` profile). `LOOP_COMPATIBILITY_CONTRACT.md`,
`LOOP_MARKETPLACE_CONTRACT.md`, and `LOOP_FORK_LINEAGE_CONTRACT.md` are cited by name (§3, §4,
§8) but not yet written under `docs/ratified/loops/` as of this document — a reader who needs
their content should look for them as separate WP-1L outputs, not expect this document to have
absorbed them.

**Not yet written, forward-pointed only:** `loop-activation-receipt.v1` and `loop-installation`
JSON Schemas and `LoopActivationContracts.kt` (`inputs/dual_surface/DUAL_TRACEABILITY_MATRIX.md`
names all three as this decision family's implementation artifacts) — schema and Kotlin-contract
authoring is WP-1L scope beyond this document, not performed here. The consolidated `Receipt`
envelope with a `receiptType` enum in `contracts/kotlin/CommonContracts.kt`
(`10_DUAL_VALIDATION_ADDENDUM.md` §E) is WP-2 scope.

**No new decision ID is proposed by this document.** Every gap this file needed to close — the two
state machines, the terminal-state reconciliation, the shared-spine relationship, the
`PARTIAL_IMPORT` prohibition, the authority-bucket-to-ladder mapping — was closable by
cross-referencing an already-frozen registry or an already-ratified decision; none required
inventing a `FB-RAT-IMP-NEW-*` slot.
