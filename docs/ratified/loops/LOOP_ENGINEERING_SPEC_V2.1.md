# Loop Engineering Specification v2.1

> **License:** `LICENSE-PENDING` — see `docs/non_ratified/LICENSE_PENDING.md`.

**Status:** RATIFIED. **Supersedes** `docs/ratified/LOOP_ENGINEERING_SPEC.md` (v1.0) and the
proposed v2.0 draft — v1.0 was never emitted into this repository (WP-1 correctly skipped it per
the master build brief, and a repo-wide search confirms no such file exists anywhere under
`docs/ratified/`); this is the *only* Loop Engineering Specification this repository carries.
**Preserves** `FB-RAT-LOOP-001` through `FB-RAT-LOOP-006` (all six ACCEPTED, unchanged in
disposition) while adding dual-surface authoring, package lifecycle, activation, marketplace,
result-sharing, compatibility, and lineage concerns the base pack did not cover. Three places
where "preserves" is a superset rather than a strict extension are called out, not silently
folded in — see **Amendments to LOOP-001…006** below; that section is required reading before
citing this document's §4 or §9 as if they were the base pack's original scope. **Scope:** the
semantic and behavioral contract for a loop — the canonical object model, node and gateway
contracts, validation, testing, the run state machine, safety/authority boundaries, portability,
identity/versioning, budgets, the package build pipeline, transfer/activation, updates,
marketplace and shared-result boundaries, security invariants, migration, and the conformance
suite. This document is the citation target for `FB-RAT-LOOP-001` through `FB-RAT-LOOP-006`, and
— per `LOOP_FORK_LINEAGE_CONTRACT.md` §13 and `LOOP_COMPATIBILITY_CONTRACT.md` §1 — the sole
owner of `LoopDraft`/`LoopDefinition`/`LoopPackage`/`LoopRelease`/`LoopInstallation`/
`BindingProfile`/`LoopRun`/`SharedResult`/`ForkLineage` identity (§2), the actor-class vocabulary
a draft revision or lineage event records (§15), the release-identity tuple `{loopId,
semanticVersion, packageDigest, publisherKeyFingerprint}` (§14), and the run state machine (§9)
every other loop document treats as forward-defined context, not restated.

**This document is the semantic/behavioral authority; four frozen registries under
`schemas/loops/registries/` are the byte-level authority for the same underlying concepts, and
this document does not redefine any of them** (`docs/ratified/loops/LOOP_FROZEN_CONCEPTS_WP1L_G0.md`
indexes all six):

| Concept this spec uses normatively | Byte-level definition lives in | Not restated here |
|---|---|---|
| `.floop` container format (§2.3, §20, §21, §25) | `floop-container-format.v1.json` | archive layout, path rules, package-content-digest algorithm |
| Semantic digest (§3, §12, §14, §15) | `semantic-digest.v1.json` | the `definition.json` exclusion list and digest formula |
| Validation rule codes (§7, §19) | `loop-validation-rules.v1.json` | the 24 stable codes and their namespaces (§19 states the ten namespaces this spec's own findings MUST use; the registry states the concrete codes within them) |
| Capability IDs / `engineVersion` (§3, §4, §10) | `capability-ids.v1.json` | the reverse-DNS ID namespace, authority-ladder cross-reference, and `engineVersion` object |
| Canonicalization profile (feeds the two digests above) | `canonicalization.v1.json` | `fb-loop-canon-1` (JCS baseline, IEEE-754 doubles forbidden) |
| Model capability vocabulary (§4 "model inference" node category) | `model-capability-vocabulary.v1.json` | the 14 model-feature tags |

A conflict between this document's prose and one of these registries is this document's error;
the registry wins.

## Note on verification

This document's source draft (`inputs/dual_surface/specs/LOOP_ENGINEERING_SPEC_V2.1.md`, Appendix
E of the dual-surface report) was read in full and checked against `10_DUAL_VALIDATION_ADDENDUM.md`
§B2's claim that *"every one of the 12 [Appendix E] files is a first pass with a second,
overlapping pass appended."* **This file is the exception the addendum itself implies but does
not spell out**: its 29 sections use one single numbering sequence, no section number repeats, and
no topic is stated twice under two different numerals — verified by reading every section heading
and cross-checking topic coverage, not assumed from the absence of an obvious duplicate. Section
numbers below are therefore **unchanged from the draft** — every citation elsewhere in this
constellation of the form `LOOP_ENGINEERING_SPEC_V2.1 §<n>` (six sibling ratified documents and
four registries already cite this spec by number as of this document's authoring — see the
cross-reference footer) resolves correctly against this file with no re-anchoring needed.

Two defects were found and fixed, both cosmetic (the source file's own header note anticipated
this: *"formatting artifacts from docx→markdown conversion may remain"*), neither affecting
section numbering or normative content:

1. **Two ordered lists were mislabeled by a docx→markdown conversion artifact that let a single
   list-numbering counter run across two unrelated sections.** §4's eight node categories rendered
   as items 37–44 and §20's ten build-pipeline steps rendered as items 45–54 — a contiguous 37–54
   run spanning two different lists in two different sections, confirming `10_DUAL_VALIDATION
   _ADDENDUM.md`'s closing note (*"item order is intact — only labels are wrong"*) for exactly the
   two corrupted lists that belong to this file (`LOOP_P0_P1_RELEASE_GATES.md` §2 independently
   verified its own source file carries neither corrupted range and attributed the node-category
   fix to this document by name). Both are renumbered 1–8 and 1–10 below, order and content
   unchanged.
2. **§9's run state machine was a bare state list, not a transition table.** The source file's own
   header note and `10_DUAL_VALIDATION_ADDENDUM.md`'s closing line both require this: *"each state
   machine must be re-rendered as a from-state/event/to-state/receipt table."* §9 below replaces
   the list with that table; the state roster itself (all 17 names) is unchanged from the draft.

No other structural change was made. Section prose is preserved as written except for the two
fixes above, added inline decision-ID citations, and the new **Amendments** section this file's
build instruction requires.

---

## 1. Purpose

A loop is a typed, versioned, testable, replayable, inspectable, distributable operating
procedure. It is not a prompt list, opaque agent session, executable plugin, or hosted automation
dependency (`FB-RAT-LOOP-001`).

The loop system has two authoring surfaces (`FB-RAT-LBX-001` — one canonical loop language,
package model, validation model, and runtime contract across both):

- **Fonebrew app:** phone-primary, complete authoring, local activation, execution, intervention,
  evidence, repair, and export (`FB-RAT-LBX-002` — the app is the primary, complete, untethered
  surface; the browser is optional).

- **Fonebrew Web Loop Studio:** browser-optimized construction, testing, documentation, package
  creation, signing, publication, and discovery preparation (`FB-RAT-LBX-003` — the browser
  optimizes dense construction and publication; it is never the authoritative runtime).

Both surfaces manipulate one canonical semantic model and package format. They MAY use different
layouts and interaction grammars (`FB-RAT-LBX-004` — semantic equivalence, UX divergence).

## 2. Canonical object model

#### 2.1 LoopDraft

Mutable authoring state with stable draft identity, revision, graph, presentation layout,
validation state, test workspace, source surface, and local history. A draft is not trusted or
publishable until packaged.

#### 2.2 LoopDefinition

The semantic graph. It declares identity, semantic version, typed inputs and outputs, nodes,
edges, gateways, budgets, capability slots, target constraints, verification, terminal states,
engine compatibility, and provenance.

#### 2.3 LoopPackage

An immutable, content-addressed archive. It is the only portable interchange unit for
browser-to-phone, phone-to-phone, Git, QR/URL, and marketplace transfer (`FB-RAT-PKG-001` —
canonical package boundary). Its byte-level shape is `floop-container-format.v1.json`, cited, not
restated (see the registry table above).

#### 2.4 LoopRelease

An immutable published version of a package, addressed by loop ID, semantic version, package
digest, and publisher signature.

#### 2.5 LoopInstallation

The local record of a verified package on a phone, including source, signature state,
compatibility report, local bindings, authority grants, installation state, and receipts
(`FB-RAT-IMP-009` — every installation records source, digest, signature state, validation
report, bindings digest, authority grant IDs, and installation receipt).

#### 2.6 BindingProfile

Device- and user-specific resolution of abstract package slots to actual models, repositories,
execution targets, devices, secrets, policies, and budgets. Binding profiles are not publishable
package content (`FB-RAT-WEB-005` — packages declare typed binding slots; Fonebrew resolves them
locally).

#### 2.7 LoopRun

A concrete execution of one draft or installed release against one binding profile, input set,
authority set, engine version, and execution context. Its state machine is §9.

#### 2.8 SharedResult

An explicit redacted export of selected run facts and evidence. A run is never shared
automatically (`FB-RAT-RES-001` — explicit result sharing only; see §24).

#### 2.9 ForkLineage

A provenance record connecting a derivative draft or release to parent releases, applied upstream
changes, authorship, attribution, and licenses (`FB-RAT-LIN-001`…`FB-RAT-LIN-006`; detailed
normatively in `LOOP_FORK_LINEAGE_CONTRACT.md`, not restated here).

## 3. Definition model

A LoopDefinition MUST declare (`FB-RAT-LOOP-001`):

-   stable loopId and semantic version;

-   human-readable objective and documentation reference;

-   input and output JSON Schemas;

-   stable node IDs and edge IDs;

-   start and terminal nodes;

-   budgets: steps, wall clock, tokens, cost, tool calls, and optional resource ceilings;

-   typed binding slots and required capabilities;

-   execution-target and distribution constraints;

-   minimum and maximum tested engine ranges (`engineVersion`, `capability-ids.v1.json` — not
    redefined here, see the registry table above);

-   verification policy and terminal-state mapping;

-   provenance and license references.

A definition MUST be serializable independently of UI layout. Presentation metadata MAY be stored
separately and MUST NOT affect semantic digest unless explicitly declared semantic
(`FB-RAT-PKG-004`; the exclusion list itself is `semantic-digest.v1.json`, not restated here).
Target-constraint and engine-range evaluation is one of the ten compatibility axes
`FB-RAT-CMP-001` names; `LOOP_COMPATIBILITY_CONTRACT.md` is that axis list's normative home.

## 4. Node contracts

Every node MUST declare (`FB-RAT-LOOP-002`):

-   stable ID and category;

-   typed input and output ports;

-   implementation reference or binding slot;

-   requested capabilities and authority class;

-   preferred and permitted execution targets;

-   timeout and retry policy;

-   idempotency mode and key strategy;

-   compensation behavior or explicit non-compensability;

-   verification requirements;

-   side-effect classification;

-   failure-to-terminal-state mapping;

-   human-readable explanation.

**Node categories** (eight; see **Amendments to LOOP-001…006** §1 below for how this set grew
from the base pack's five):

1. deterministic transformation;
2. model inference;
3. typed tool;
4. human decision;
5. bounded subloop;
6. verifier;
7. artifact import/export;
8. wait/event gate, where supported by the runtime policy.

Marketplace packages MUST NOT introduce executable node implementations (`FB-RAT-PKG-002` —
declarative package policy; `FB-RAT-PKG-003` — REJECTED alternative: DEX/JAR/native/script
executable payloads). They reference capabilities already provided by trusted Fonebrew modules or
separately installed providers (`capability-ids.v1.json`).

## 5. Gateway contracts

A gateway MUST use typed conditions, stable edge labels, priority semantics where relevant, and
an explicit exhaustiveness policy (`FB-RAT-LOOP-003`). A non-exhaustive gateway MUST declare a
default or terminal failure path. Cycles require a statically visible bound through budgets,
counters, or runtime hard limits.

## 6. Authoring and semantic diff

All structural edits generate a draft revision. AI-assisted edits are proposals consisting of:

-   base revision;

-   semantic operations;

-   affected nodes, edges, schemas, capabilities, budgets, tests, and documentation;

-   behavior explanation;

-   validation result;

-   authority-diff summary.

The user approves or rejects the proposal. No AI tool silently rewrites a graph
(`FB-RAT-PHN-006` — Distiller/AI-assisted edits are proposed diffs, applied as one undoable
transaction only on explicit approval; `LOOP_PHONE_AUTHORING_SPEC.md` details the
`BASE_REVISION → PROPOSED → APPLIED/DISCARDED` transition table on the phone side, not restated
here). §15 records the actor class (`AI_PROPOSAL` among them) a resulting revision carries.

## 7. Static validation

Validation MUST detect at least:

-   unbound required parameters;

-   invalid or incompatible schemas;

-   duplicate identities;

-   missing start or terminal paths;

-   unreachable nodes;

-   gateway gaps and ambiguous edges;

-   illegal or unbounded cycles;

-   missing budgets;

-   unavailable or prohibited capabilities;

-   impossible target constraints;

-   non-compensated destructive paths lacking explicit acknowledgement;

-   unverified success paths where verified success is claimed;

-   secret-like package material;

-   forbidden executable payloads;

-   package/path canonicalization errors.

Each of these fifteen detection classes has a concrete, stable rule code in
`loop-validation-rules.v1.json` (§19 states the namespace architecture those codes live under;
the registry states the codes themselves — not re-derived here). Three additional P0 controls
(invisible-Unicode rejection, prompt static scanning, manifest egress allowlist) were added to
that registry by `10_DUAL_VALIDATION_ADDENDUM.md` §B3 and are not part of this fifteen-class list
inherited from the source draft; §25 states the security rationale.

## 8. Test modes

-   **Static validation:** structure, schema, authority, compatibility, package, and policy
    checks.

-   **Simulation:** mock tools, model fixtures, deterministic human decisions, and no external
    side effects.

-   **Replay:** recorded outputs and decisions, with drift reporting when engine semantics differ.

-   **Fault injection:** timeout, malformed output, denial, network loss, target loss, duplicate
    delivery, stale revision, and partial side effects.

-   **Benchmark:** correctness, verification, intervention, cost, tokens, duration, retries,
    local/cloud ratio, and regression.

-   **Cross-surface conformance:** canonical package and semantic digest equivalence between app
    and browser implementations.

(`FB-RAT-LOOP-005` — static validation, simulation, replay, fault injection, and benchmark
execution. `FB-RAT-WEB-006` extends the browser side of this into an advanced test laboratory:
matrix fixtures, mocked tools, deterministic replay, fault injection, graph diff, and package
reproducibility checks — `LOOP_WEB_STUDIO_SPEC.md`, not restated here.) Fixture identity and
staleness detection for the simulation/replay modes is `LOOP-TEST-002`
(`loop-validation-rules.v1.json`; keyed on `(nodeId, iterationIndex, callIndex)` with a
non-binding recorded prompt digest, per `10_DUAL_VALIDATION_ADDENDUM.md` §D — not re-derived
here).

## 9. Run state machine

**Required run states (17):** `CREATED`, `PREFLIGHT`, `WAITING_BINDING`, `WAITING_AUTHORITY`,
`READY`, `RUNNING`, `WAITING_USER`, `SUSPENDED`, `CANCELLING`, `SUCCEEDED_VERIFIED`,
`SUCCEEDED_UNVERIFIED`, `STOPPED_BUDGET`, `STOPPED_POLICY`, `FAILED_SAFE`,
`FAILED_SIDE_EFFECTS_POSSIBLE`, `TARGET_STATE_UNKNOWN`, `CANCELLED`.

**Terminal (8):** `SUCCEEDED_VERIFIED`, `SUCCEEDED_UNVERIFIED`, `STOPPED_BUDGET`,
`STOPPED_POLICY`, `FAILED_SAFE`, `FAILED_SIDE_EFFECTS_POSSIBLE`, `TARGET_STATE_UNKNOWN`,
`CANCELLED`. **Non-terminal (9):** `CREATED`, `PREFLIGHT`, `WAITING_BINDING`,
`WAITING_AUTHORITY`, `READY`, `RUNNING`, `WAITING_USER`, `SUSPENDED`, `CANCELLING`. **This is a
change from the base pack** — see **Amendments to LOOP-001…006** §2 below: `WAITING_USER` moves
from terminal to non-terminal and `CANCELLED` is new. `FB-RAT-LOOP-006` (base pack) is preserved
as the union this set is built from, not overridden.

Terminal states MUST include a terminal reason, side-effect state, verification summary,
execution receipt references, output artifact references, and recovery actions where applicable.
`FB-RAT-PHN-008` governs the phone's Run-view presentation of this machine (an operational
timeline, not an editable canvas; structural edits require pause/stop and a new draft revision —
`LOOP_PHONE_AUTHORING_SPEC.md`, not restated here).

The list above is the source draft's required state roster, unchanged. The table below is new —
the source draft stated no transitions; it is derived from this document's own surrounding
sections (cited per row) exactly as `10_DUAL_VALIDATION_ADDENDUM.md`'s closing note and this
file's own header comment require ("re-flow state machines into transition tables").

| From state | Event | To state | Receipt |
|---|---|---|---|
| *(none — initiation)* | User or automation starts a `LoopRun` against a draft or installed release, binding profile, input set, and engine version (§2.7). | `CREATED` | Lightweight run-created event, no receipt yet. |
| `CREATED` | Engine runs preflight: static validation (§7), budget declaration check (§18), capability-request enumeration (§10). | `PREFLIGHT` | None yet. |
| `PREFLIGHT` | Validation passes; one or more binding slots (§2.6) are unresolved. | `WAITING_BINDING` | None yet. |
| `PREFLIGHT` | Validation passes; all binding slots already resolved but capability grants (§10) are not yet confirmed. | `WAITING_AUTHORITY` | None yet. |
| `PREFLIGHT` | Static validation fails (any `LOOP-*` rule-code class in §7/`loop-validation-rules.v1.json`). | `FAILED_SAFE` | Terminal receipt — terminal reason: validation failure; side-effect state: none (nothing executed, §25). |
| `WAITING_BINDING` | Binding-profile resolution completes for every required slot. | `WAITING_AUTHORITY` | Binding-resolved event. |
| `WAITING_BINDING` | User cancels before binding completes. | `CANCELLING` | None yet. |
| `WAITING_AUTHORITY` | User approves the requested (possibly narrowed, §10, `FB-RAT-IMP-005`) capability grant. | `READY` | Authority-grant receipt (§10). |
| `WAITING_AUTHORITY` | User declines the grant. | `CANCELLING` | None yet. |
| `READY` | Engine dispatches the first step within the declared budget (§18). | `RUNNING` | None yet. |
| `RUNNING` | Execution reaches a human-decision or wait/event-gate node (§4 categories 4, 8). | `WAITING_USER` | None yet — a pause is not itself receipt-bearing. |
| `WAITING_USER` | User supplies the decision, or the awaited event arrives. | `RUNNING` | Node receipt for the human-decision/gate node. |
| `WAITING_USER` | User cancels while paused. | `CANCELLING` | None yet. |
| `RUNNING` | Operational pause requested (user- or policy-initiated suspend, distinct from a structural stop). | `SUSPENDED` | None yet. |
| `SUSPENDED` | User resumes. | `RUNNING` | None yet. |
| `SUSPENDED` | User cancels while suspended. | `CANCELLING` | None yet. |
| `RUNNING` | User or automation requests cancellation. | `CANCELLING` | None yet. |
| `CANCELLING` | In-flight step(s) reach a safe stopping point; no side effects pending. | `CANCELLED` | Terminal receipt — terminal reason: cancellation requested; side-effect state: none. |
| `CANCELLING` | In-flight step(s) cannot be confirmed safely stopped. | `TARGET_STATE_UNKNOWN` | Terminal receipt — terminal reason: cancellation requested, target state unconfirmed; recovery actions required. |
| `RUNNING` | A budget dimension is exhausted (checked before dispatch and after every step receipt, §18). | `STOPPED_BUDGET` | Terminal receipt — terminal reason: exact exhausted budget dimension (§18). |
| `RUNNING` | A capability/policy boundary is hit mid-run (e.g. a binding is revoked, or an authority check fails, §10). | `STOPPED_POLICY` | Terminal receipt — terminal reason: policy boundary. |
| `RUNNING` | Every reachable terminal node is reached; every verifier marked `requiredForSuccess` (§16) has a passing result tied to this run's inputs, digest, and artifacts. | `SUCCEEDED_VERIFIED` | Terminal receipt — verification summary: all required verifiers passed. |
| `RUNNING` | Every reachable terminal node is reached; one or more `requiredForSuccess` verifiers is absent, skipped, or non-passing. | `SUCCEEDED_UNVERIFIED` | Terminal receipt — verification summary: incomplete. |
| `RUNNING` | A node fails cleanly; its failure-to-terminal-state mapping (§4) resolves to no side effects. | `FAILED_SAFE` | Terminal receipt — side-effect state: none. |
| `RUNNING` | A node fails after a side-effecting operation was dispatched and its outcome cannot be confirmed reverted or compensated (§4 compensation behavior). | `FAILED_SIDE_EFFECTS_POSSIBLE` | Terminal receipt — side-effect state: possible, non-empty; recovery actions required. |
| `RUNNING` | A side-effecting operation's outcome cannot be confirmed at all (e.g. device/host connectivity lost mid-operation, §4/§18). | `TARGET_STATE_UNKNOWN` | Terminal receipt — recovery actions required. |

## 10. Safety and authority

Package capability declarations are requests, never grants. Fonebrew resolves local bindings and
grants capabilities under the Capability Authority Model (`CAPABILITY_AUTHORITY_MODEL.md`, not
redefined here). A binding MAY narrow requested authority and MUST NOT widen it silently
(`FB-RAT-IMP-005`). Updates that widen authority require fresh approval (§22).

## 11. Portability invariants

-   Phone and browser preserve semantic node/edge identity (`FB-RAT-LBX-004`).

-   Canonicalization produces the same package digest for semantically and byte-equivalent
    package content (`FB-RAT-PKG-004`; `canonicalization.v1.json`, not redefined here).

-   Imported packages remain editable on the phone through a forked draft.

-   Browser absence never blocks local execution (`FB-RAT-LBX-002`).

-   Marketplace absence never blocks local import, export, or execution
    (`LOOP_MARKETPLACE_CONTRACT.md` §15's outage-portability contract, not restated here).

-   Package signatures establish publisher/package integrity, not runtime authority
    (`FB-RAT-PKG-005`; §21 restates this at the activation boundary).

## 12. Conformance

A loop-engine release is non-conformant if either surface accepts a package the other rejects
without a documented platform constraint, produces different semantic digests, loses unknown safe
minor fields, or permits execution before local activation. `FB-RAT-WEB-001` scopes package build
and signing as browser-authoring responsibilities that MUST still satisfy this gate; how the two
surfaces stay in lockstep to satisfy it is `LOOP_WEB_STUDIO_SPEC.md` §9's concern, including the
**`FB-RAT-WEB-010` (PROPOSED, not yet ratified)** recommendation to compile one shared headless
implementation to both surfaces rather than maintain two hand-written ones forever — noted here as
context, not re-argued or self-ratified by this document.

## 13. Normative terminology and precedence

MUST, MUST NOT, SHOULD, SHOULD NOT, and MAY are normative (`FB-RAT-COM-001`,
`docs/ratified/COMMON_CONVENTIONS.md` §1, not restated here). Where this specification conflicts
with the Common Conventions, Capability Authority, Execution, Workspace, Device Safety, or
Distribution contracts, the stricter privacy, authority, safety, integrity, or recovery
requirement governs. A UI convenience cannot weaken a semantic or security invariant.

The semantic model is authoritative over visual layout. BPMN 2.0 remains a portable graph
representation, but the Fonebrew contract layer carries requirements not safely expressible as
ordinary BPMN alone: capabilities, authority classes, binding slots, budgets, verification,
package identity, test fixtures, compatibility, and provenance. A round trip through a BPMN tool
MUST preserve the Fonebrew extension namespace or report precisely which semantics would be lost.
(`definition.bpmn` is a derived, digest-excluded export per `floop-container-format.v1.json` —
see the registry table above; this section states the *semantic* round-trip obligation, the
registry states the *file* is not part of package identity.)

## 14. Identity and versioning

-   loopId identifies a loop family and is stable across releases owned by the same publisher.

-   draftId identifies mutable authoring state and is never reused after deletion.

-   nodeId, edgeId, slotId, testId, and verifier IDs remain stable across edits unless the
    semantic object is replaced rather than modified.

-   semanticVersion follows SemVer. A breaking input/output, authority, side-effect, verifier, or
    compatibility change requires a major version even when the visible graph changes only
    slightly.

-   A release is identified by {loopId, semanticVersion, packageDigest,
    publisherKeyFingerprint}. The digest is the final source of byte-level identity
    (`FB-RAT-PKG-004`, `FB-RAT-PKG-006` — immutable releases; `semantic-digest.v1.json`, not
    redefined here).

-   Presentation-only changes MAY preserve semantic version when the semantic digest is
    unchanged. Publication policy MAY issue a metadata revision without issuing a new package
    release (`FB-RAT-MKT-003` — listing versus release separation, `LOOP_MARKETPLACE_CONTRACT.md`,
    not restated here).

## 15. Draft revision and concurrency contract

Every draft mutation is applied against an expected revision. A stale mutation is rejected with a
semantic conflict report; it is not last-write-wins. Autosave stores complete recoverable
revisions or an append-only operation journal with deterministic reconstruction (`FB-RAT-COM-006`
— append-only facts, `docs/ratified/COMMON_CONVENTIONS.md` §6, not restated here). Phone and
browser drafts do not silently merge. An explicit import, fork, comparison, or selected-change
application creates the bridge.

A draft revision records actor class (`USER`, `DISTILLER`, `AI_PROPOSAL`, `IMPORT_MIGRATION`,
`UPSTREAM_APPLY`), timestamp, base semantic digest, resulting semantic digest, operation list,
validation state, and authority/verification delta. This is the same actor-class vocabulary
`LOOP_FORK_LINEAGE_CONTRACT.md` §3 reuses for lineage events rather than reinventing (a lineage
event and a draft revision are the same kind of fact), and the same conflict rule
`LOOP_FORK_LINEAGE_CONTRACT.md` §6 names as the fork-lineage instance of this section's general
draft-concurrency rule, not a second independent invariant.

## 16. Input, output, and form contracts

Input and output schemas MUST be JSON Schema draft 2020-12 or a later ratified version. The
package MAY include a presentation form schema, but the JSON Schema remains authoritative.
Required input values MUST be resolved before activation unless a node is explicitly declared as
the acquiring step. Secret values are never embedded in input fixtures intended for publication
(`FB-RAT-WEB-002` — no secrets in web drafts or packages; `FB-RAT-PKG-007` — package validation
MUST reject secret-like fields).

Outputs distinguish:

-   primary typed result;

-   artifacts with media type, digest, and provenance;

-   receipts and verifier evidence;

-   human-readable summary;

-   warnings and unresolved uncertainty.

A loop MUST NOT claim SUCCEEDED_VERIFIED unless every verifier marked requiredForSuccess has a
passing result tied to the actual run inputs, package digest, and relevant artifacts
(`LOOP-VERIFY-002`, `loop-validation-rules.v1.json`).

## 17. Subloop composition

A subloop is referenced by immutable package identity or embedded as a package-local definition.
Its input/output mapping, budget allocation, authority inheritance policy, and terminal-state
mapping are explicit. Authority is non-transitive: the parent cannot delegate capabilities it was
not granted, and the child cannot silently widen the parent request. A parent budget MUST reserve
a bounded child budget; recursive subloops require a statically visible depth limit
(`LOOP-BUDGET-002`, `loop-validation-rules.v1.json`).

## 18. Budget and stop behavior

Budgets are hard runtime boundaries, not estimates. The engine checks limits before starting a
step and after recording the step receipt. When a limit is reached, the run enters
STOPPED_BUDGET with the exact exhausted dimension (§9). A node MAY request a user-approved
extension; the extension creates a new authority/budget receipt and does not rewrite the original
limit.

Minimum budget dimensions are steps, wall clock, model tokens, monetary cost, and tool calls.
Providers MAY add memory, thermal, storage, network, or device-operation budgets. Unknown budget
dimensions are preserved on round trip and treated conservatively by runtimes that cannot enforce
them.

## 19. Validation rule architecture

Validation findings use stable codes grouped by namespace:

-   LOOP-ID-\* identity/versioning;

-   LOOP-GRAPH-\* topology, reachability, cycles, and gateways;

-   LOOP-SCHEMA-\* inputs, outputs, ports, and mappings;

-   LOOP-CAP-\* capability and authority requests;

-   LOOP-BUDGET-\* budget bounds and recursion;

-   LOOP-VERIFY-\* verification and success claims;

-   LOOP-PKG-\* package, path, digest, secret, and payload policy;

-   LOOP-COMPAT-\* engine, target, provider, platform, and migration;

-   LOOP-TEST-\* fixture and expectation validity;

-   LOOP-LINEAGE-\* attribution, licensing, and parent identity.

Rule codes are API. Their meaning cannot be silently repurposed. Severity may change only through
a documented ruleset version. A validation report includes ruleset version, semantic digest,
surface implementation, and findings sorted deterministically. **This is the namespace
architecture; the concrete codes within it are frozen in `loop-validation-rules.v1.json`
(`rulesetVersion: "fb-loop-validation-rules-1"`, 24 codes as of this freeze) — not re-derived
here** (see the registry table above and `LOOP_FROZEN_CONCEPTS_WP1L_G0.md`).

## 20. Package build pipeline

A package build executes:

1. freeze a specific draft revision;
2. validate the semantic graph and included schemas;
3. canonicalize all semantic JSON and normalized text;
4. scan for secrets, unsafe paths, and forbidden payloads;
5. run required fixture tests;
6. generate compatibility and provenance documents;
7. inventory every file with digest and semantic/nonsemantic classification;
8. compute the package content digest;
9. optionally sign a release identity;
10. emit a build receipt.

The build fails closed. It never publishes a partially built package. The app and browser MUST
produce the same content digest for equivalent source material under the same canonicalization
version (`FB-RAT-PKG-004`; `FB-RAT-WEB-001` scopes package build as a Web Studio responsibility
that MUST still satisfy this; `canonicalization.v1.json` and `floop-container-format.v1.json`
define the digest algorithms this pipeline invokes, not redefined here). Step 5's fixture
requirement is `FB-RAT-LOOP-004` (base pack); see **Amendments to LOOP-001…006** §3 below for how
this interacts with `FB-RAT-PKG-008`'s public-release completeness bar.

## 21. Transfer and activation boundary

Transfer moves inert bytes. It does not grant authority, resolve secrets, or start execution
(`FB-RAT-IMP-002` — no model, tool, shell, remote, device, or side-effecting node may execute
during parsing, validation, preview, or installation). Every transfer is represented by a
user-initiated envelope containing expected media type and digest. Fonebrew snapshots the bytes,
validates the container, and performs local compatibility evaluation before presenting authority
or binding controls. This section states the boundary invariant; the full ten-step import
grammar and its eleven-state machine are `LOOP_IMPORT_ACTIVATION_CONTRACT.md`'s (`FB-RAT-IMP-001`),
not restated here.

Activation is phone-authoritative and produces an immutable activation receipt. A package
imported from a trusted marketplace still undergoes local verification (`FB-RAT-IMP-003` — local
revalidation; server validation is advisory, never authoritative). A valid signature proves
publisher/package integrity; it does not prove safety, correctness, suitability, or permission
(`FB-RAT-MKT-005` — publisher identity does not confer authority on the phone).

## 22. Update contract

An available release update is evaluated as a semantic diff plus compatibility, authority,
verification, and lineage changes. Updates never auto-activate. The user may install side by
side, replace the active release, or fork. Any authority widening, verifier weakening, new
external target, new secret slot, increased budget, or destructive path requires fresh approval
even for a patch release (`FB-RAT-IMP-008` — authority-change update gate; `LOOP-COMPAT-002`,
`loop-validation-rules.v1.json`, is the machine-checkable form of this rule).

Existing run receipts retain their original package and binding identities. Updating a loop never
rewrites historical evidence (`FB-RAT-PKG-006` — immutable releases; `FB-RAT-IMP-006` — editing an
installed release creates a forked local draft rather than mutating it).

## 23. Marketplace constraints

Marketplace packages are declarative. They cannot deliver executable implementations, dynamically
fetched code, or hidden provider installers (`FB-RAT-MKT-004` — declarative marketplace only;
`FB-RAT-PKG-002`/`FB-RAT-PKG-003`). A package references capability IDs implemented by trusted
local modules or separately installed providers. Missing capabilities produce a compatibility
finding; they do not trigger automatic installation.

Publication is explicit and private by default (`FB-RAT-MKT-002`). The public service may store
deliberately published packages, listing metadata, reviews, publisher keys, moderation records,
and explicitly shared result receipts (`FB-RAT-MKT-001` — bounded marketplace exception to the
no-backend law, scoped to exactly this content). It MUST NOT receive private drafts, local
bindings, credentials, private repository content, run logs, or artifacts without a separate
user-directed export.

## 24. Shared-result semantics

A shared result is a new redacted artifact built from an allowlist, previewed before upload, and
tied to explicit consent (`FB-RAT-RES-001`, `FB-RAT-RES-002` — minimum-safe result profile).
Runtime signature establishes that a compatible Fonebrew runtime produced the receipt for the
stated package digest. It does not establish objective correctness (`FB-RAT-RES-004` — verified
receipt claim boundary). Correctness claims require loop-specific verifier evidence
(`FB-RAT-RES-005`, owned by `LOOP_RESULT_SHARING_CONTRACT.md`, not restated here).

The default share excludes prompts, responses, file paths, repository and host names, device
identifiers, raw logs, credentials, and generated artifacts (`FB-RAT-RES-003` — default
exclusions). Optional evidence is individually selected and scanned again before upload
(`FB-RAT-RES-006` — redaction pipeline).

## 25. Security invariants

-   Import never executes package content (`FB-RAT-IMP-002`; `LOOP-PKG-004`,
    `loop-validation-rules.v1.json`, is the machine-checkable form).

-   Package extraction rejects absolute paths, traversal, duplicate normalized paths, symlink
    escapes, decompression bombs, and inconsistent lengths/digests
    (`floop-container-format.v1.json`, not redefined here).

-   Package prompts and documentation are treated as untrusted content; they cannot override
    product law or authority policy. Model- and human-visible strings are additionally scanned
    for disallowed invisible/control Unicode (`LOOP-PKG-006`, non-dismissible) and heuristic
    prompt-injection indicators (`LOOP-PKG-008`) — both added to `loop-validation-rules.v1.json`
    by `10_DUAL_VALIDATION_ADDENDUM.md` §B3, not present in the source draft, cross-referenced
    here rather than re-derived. `FB-RAT-MKT-007` governs the marketplace-side moderation this
    scanning feeds.

-   Secret values are resolved by reference at runtime and are not exposed to model context
    unless a separately scoped capability explicitly permits it (`FB-RAT-PKG-007`).

-   Model steps cannot invoke undeclared tools.

-   Human-decision nodes cannot be simulated as approved in a real run without an explicit policy
    and receipt.

-   Destructive and publish operations require the applicable high-risk authority gate
    (`FB-RAT-IMP-005` — authority may narrow, never widen; `CAPABILITY_AUTHORITY_MODEL.md`, not
    redefined here).

## 26. Privacy and observability

Local observability consists of validation reports, run timelines, receipts, cost ledgers, and
user-visible performance measurements. No execution telemetry is uploaded by default
(`FB-RAT-MKT-008` — no phone telemetry). Marketplace page requests and downloads belong to the
bounded public service and MUST be documented separately from phone runtime behavior.

A local run can be inspected at four levels: outcome, stage, node, and raw receipt. The system
records enough to diagnose failure without requiring private content to leave the device.

## 27. Migration contract

Migrations are explicit, pure where possible, versioned, testable transformations. A migration
declares source and target contract versions, preserved fields, transformed fields, dropped
fields, semantic risks, and reverse availability. The phone keeps the original package snapshot
and migration receipt. A migrated package is not presented as the publisher's original digest.
Unknown major schemas are rejected; unknown minor fields are preserved where safe
(`FB-RAT-CMP-006` — forward compatibility; `LOOP_COMPATIBILITY_CONTRACT.md` §7, not restated
here).

## 28. Conformance suite

The shared conformance suite includes:

-   JSON Schema structural tests;

-   canonical digest golden vectors;

-   package traversal and decompression adversarial cases;

-   graph topology and gateway cases;

-   authority-widening diff cases;

-   fixture simulation and replay cases;

-   browser/phone round-trip cases;

-   unknown-minor-field preservation cases;

-   migration cases;

-   import cancellation and interrupted transfer cases;

-   accessibility and phone-completeness journeys.

A release cannot claim cross-surface compatibility until both implementations pass the same
golden corpus and produce matching rule IDs, semantic digests, package digests, and simulation
outcomes. Browser/phone round-trip and compatibility-outcome cases are `FB-RAT-IMP-007`'s
compatible/compatible-with-bindings/degraded/blocked/unsupported outcomes
(`LOOP_COMPATIBILITY_CONTRACT.md`, not restated here). `FB-RAT-WEB-006` names the browser-side
test-laboratory features (matrix fixtures, mocked tools, fault injection, graph diff, package
reproducibility) this suite exercises.

## 29. Implementation readiness checklist

A loop feature is implementation-ready only when its object, states, commands, authority,
invariants, errors, recovery, evidence, versioning, schema, interface, fixtures, and conformance
tests are identified. UI-only specifications are insufficient. Provider implementations may
remain separate, but the contract and failure boundaries must exist first.

---

## Amendments to LOOP-001…006

`FB-RAT-LOOP-001` through `FB-RAT-LOOP-006` are preserved by this spec (all six remain ACCEPTED,
unchanged in disposition — see the register). But "preserves" understates what changed: this v2.1
spec makes **three undeclared changes** relative to the base pack (`04_ARCHITECTURE_CONTRACTS.md`
§5, the FB-ENG-03 §5/§5.1/§5.3 source `FB-RAT-LOOP-001`…`006` cite) that the source draft states
as if they were the original scope, not as changes. None of the three is stated outright anywhere
in the source pack; all three were found by comparing this spec against the base pack's own
decision text and model description, per `10_DUAL_VALIDATION_ADDENDUM.md` §F's "v2.1 'preserves
LOOP-001..006'" row. Recording them here — rather than silently treating v2.1 as a strict
superset — is this document's compliance with that finding.

### 1. Node categories grow from five to eight

**Base pack** (`FB-RAT-LOOP-002`; `04_ARCHITECTURE_CONTRACTS.md` §5's `NodeDefinition` model)
named exactly five node categories: **deterministic**, **model-inference**, **typed-tool**,
**human-decision**, **subloop**.

**This spec's §4** names eight: deterministic transformation, model inference, typed tool, human
decision, bounded subloop — the same five, renamed/clarified but not changed in kind — **plus
three new categories the base pack never had**: **verifier**, **artifact import/export**, and
**wait/event gate**. `FB-RAT-LOOP-002`'s own decision text ("typed I/O, implementation kind,
timeout, retry, idempotency, compensation, verification") is worded generally enough to cover the
five original categories' obligations; it does not anticipate a node category whose entire
purpose *is* verification (category 6) as a first-class graph citizen distinct from the
verification *property* every node already carries, nor an explicit event-wait primitive
(category 8) as distinct from an ordinary typed-tool node with a long timeout.

**Resolution:** adopted as stated in §4. The three additions are compatible extensions — no base
node category was removed, redefined, or had its contract narrowed — but they are genuinely new,
not merely renamed, and `LOOP_COMPATIBILITY_CONTRACT.md`'s "Node type" axis (`FB-RAT-CMP-001`)
and `loop-validation-rules.v1.json`'s node-category-aware rules (e.g. `LOOP-TEST-001`) MUST treat
all eight as the current set, not the base pack's five, when evaluating installed-engine support.

### 2. The base pack's 8-value LoopResult becomes this spec's 17-state run machine, with two semantic changes hidden inside the expansion

**Base pack** (`FB-RAT-LOOP-006`) named exactly eight terminal values: `SUCCEEDED_VERIFIED`,
`SUCCEEDED_UNVERIFIED`, `STOPPED_BUDGET`, `STOPPED_POLICY`, **`WAITING_USER`**, `FAILED_SAFE`,
`FAILED_SIDE_EFFECTS_POSSIBLE`, `TARGET_STATE_UNKNOWN` — a flat, all-terminal enum (`04
_ARCHITECTURE_CONTRACTS.md` §5: *"Terminal states:"* followed by exactly these eight, `WAITING_USER`
included in that list with no separate non-terminal category anywhere in the base pack).

**This spec's §9** defines 17 states with an explicit terminal/non-terminal split. Two changes
are not a mechanical refinement of the base eight — they are semantic reversals or additions that
change what a consumer of the old enum must now handle differently:

- **`WAITING_USER` moves from terminal to non-terminal.** In the base pack, a run reaching
  `WAITING_USER` was *done* — the LoopResult was final and the caller's job was to present it and
  stop. In this spec, `WAITING_USER` is a mid-run pause (§9's table: `RUNNING → WAITING_USER` on
  reaching a human-decision or wait/event-gate node, then `WAITING_USER → RUNNING` once resolved)
  — the run continues. A consumer that still treats `WAITING_USER` as terminal (e.g. releasing
  resources, closing a receipt, or reporting the run "done, awaiting user") is now wrong under
  v2.1 and will silently drop the rest of the run.
- **`CANCELLED` is new.** The base pack had no cancellation-specific terminal value at all — a
  cancelled run had no distinct outcome from, presumably, `STOPPED_POLICY` or an unstated
  fallback. This spec adds `CANCELLED` as its own terminal state (§9: `CANCELLING → CANCELLED`
  when in-flight steps reach a safe stop with no side effects pending) plus the `CANCELLING`
  non-terminal state that reaches it.

**Resolution:** adopted as stated in §9. `FB-RAT-LOOP-006` is preserved as the *origin* of the
eight terminal-outcome concepts this machine still expresses (six of the base eight are
unchanged: `SUCCEEDED_VERIFIED`, `SUCCEEDED_UNVERIFIED`, `STOPPED_BUDGET`, `STOPPED_POLICY`,
`FAILED_SAFE`, `FAILED_SIDE_EFFECTS_POSSIBLE`, `TARGET_STATE_UNKNOWN` are terminal in both), not
as a still-accurate description of the full state machine. Any document or fixture written
against the base pack's flat eight-value `LoopResult` enum MUST be updated to the 17-state
machine in §9 before being treated as current.

**Related, not resolved by this document:** `10_DUAL_VALIDATION_ADDENDUM.md` §F also notes this
spec's run states have no `VERIFYING` state and no explicit mapping to
`EXECUTION_CONTRACT.md`'s `FB-RAT-EXE-002` lifecycle (`QUEUED → PREPARING → RUNNING → VERIFYING →
SUCCEEDED/SUCCEEDED_UNVERIFIED/FAILED_SAFE/FAILED_SIDE_EFFECTS_POSSIBLE`), which makes
`FB-RAT-EXE-009` ("success ≠ verification") unimplementable at the loop level without an explicit
`LoopRunState ↔ ExecutionState` mapping. That mapping is out of scope for this document (it
belongs where `EXECUTION_CONTRACT.md` and this spec's runtime layer meet, not to either file
alone) and is flagged here, not silently absorbed into §9's table above — §9's `RUNNING` state
should be read as encompassing `EXECUTION_CONTRACT.md`'s `PREPARING`/`RUNNING`/`VERIFYING` as a
single loop-level phase until that mapping is written.

### 3. LOOP-004 (fixtures required) vs. PKG-008 (bare test-coverage declaration accepted) — resolved in favor of LOOP-004 for P0

**`FB-RAT-LOOP-004`** (base pack): *"A distributable loop package contains definition, schemas,
tests, fixtures, documentation, license, signatures, and minimum engine version."* Fixtures are
stated as a flat requirement, no escape hatch.

**`FB-RAT-PKG-008`** (dual-surface pack, §10 of this spec / `LOOP_PACKAGE_SPEC.md` §10): *"A
public release includes manifest, definition, schemas, documentation, compatibility declaration,
license, provenance, **tests or an explicit test-coverage declaration**, and signature."* This
accepts a bare declaration — no fixture required — as long as it is explicit.

These two decisions genuinely conflict for a public release: LOOP-004 requires fixtures
unconditionally; PKG-008 accepts a package with no fixtures at all, provided it says so.

**Resolution (explicit, per this file's build instruction):** **LOOP-004 wins for P0.** A
LoopPackage intended for P0 use — including any package a user installs and runs, not only
published ones — MUST carry required fixtures per §20 step 5 and a machine-checkable minimum
engine version. **PKG-008's bare test-coverage declaration is accepted only for marketplace
listings**, and only as a visibly downgraded state: a listing accepting the bare declaration MUST
render a visible **no-tests badge** on that listing, so a browsing user can see, before install,
that the package's correctness claims rest on the publisher's word alone, not on a fixture the
engine can actually re-run. This exact resolution is already encoded as machine-checkable policy
— **not re-derived here, only cross-referenced**: rule code `LOOP-TEST-001` in
`loop-validation-rules.v1.json` states precisely this ("Node has no required fixture for its
declared node category, and no explicit test-coverage declaration... Attach a fixture, or
(marketplace listings only) an explicit bare test-coverage declaration rendered as a visible
no-tests badge"), with `sourceObligation` pointing at `10_DUAL_VALIDATION_ADDENDUM.md` §F's
"LOOP-004 vs PKG-008" finding — the same finding this amendment documents at the specification
level. `LOOP_PACKAGE_SPEC.md` §10 states the same resolution from the package-completeness side;
neither document should be read as having decided this independently of the other.

---

## Cross-references and open items

**Decision IDs cited in this document:** `FB-RAT-LOOP-001` (§1, §3), `FB-RAT-LOOP-002` (§4,
Amendment 1), `FB-RAT-LOOP-003` (§5), `FB-RAT-LOOP-004` (§20, Amendment 3), `FB-RAT-LOOP-005` (§8),
`FB-RAT-LOOP-006` (§9, Amendment 2) — all six ACCEPTED and preserved, per the header. `FB-RAT-LBX
-001`…`-004` (§1, §11), `FB-RAT-PKG-001`/`-002`/`-003`/`-004`/`-005`/`-006`/`-007` (§2.3, §3, §4,
§11, §12, §14, §16, §20, §21, §22, §23, §25), `FB-RAT-WEB-001`/`-002`/`-005`/`-006` (§2.6, §8, §12,
§16, §20, §28), `FB-RAT-PHN-006`/`-008` (§6, §9, §15), `FB-RAT-IMP-002`/`-003`/`-005`/`-006`/
`-008`/`-009` (§2.5, §9, §10, §21, §22, §25), `FB-RAT-MKT-001`/`-002`/`-003`/`-004`/`-005`/`-007`/
`-008` (§14, §21, §23, §25, §26), `FB-RAT-RES-001`/`-002`/`-003`/`-004`/`-005`/`-006` (§2.8, §24),
`FB-RAT-CMP-001`/`-006` (§3, §27), `FB-RAT-LIN-001`…`-006` (§2.9), `FB-RAT-COM-001`/`-006` (§13,
§15) are cited as already-ratified or sibling-owned context this document specializes or relies
on, not re-decided here. **`FB-RAT-WEB-010`** (§12) is cited at its true status —
**PROPOSED, not yet ratified** (`LOOP_WEB_STUDIO_SPEC.md` §9) — and is not self-ratified by this
document.

**Registries reconciled, not redefined:** `floop-container-format.v1.json` (§2.3, §13, §20, §21,
§25), `semantic-digest.v1.json` (§3, §11, §14), `canonicalization.v1.json` (§11, §20), `capability
-ids.v1.json` (§3, §4, §10, `engineVersion`), `loop-validation-rules.v1.json` (§7, §16, §17, §19,
§22, §25, Amendment 3's `LOOP-TEST-001`), `model-capability-vocabulary.v1.json` (§4 "model
inference" category) — the full cross-reference table is at the top of this document. A conflict
between this document's prose and a registry's data is this document's error, and the registry
wins, per `LOOP_FROZEN_CONCEPTS_WP1L_G0.md`.

**Sibling documents this document defers to and does not restate:** `LOOP_DUAL_SURFACE
_ARCHITECTURE.md` (dual-surface architecture detail — §1); `LOOP_PHONE_AUTHORING_SPEC.md` (the
phone `BASE_REVISION→PROPOSED→APPLIED` proposal table, Run-view presentation — §6, §9);
`LOOP_WEB_STUDIO_SPEC.md` (browser test laboratory, package build/sign scope, the `FB-RAT-WEB-010`
one-implementation proposal — §8, §12, §20, §28); `LOOP_PACKAGE_SPEC.md` (byte-level package
completeness, §10's own statement of the LOOP-004/PKG-008 resolution — §2.3, §20, Amendment 3);
`LOOP_IMPORT_ACTIVATION_CONTRACT.md` (the full ten-step import grammar and eleven-state machine
this document's §21 only states the boundary invariant for — §21); `LOOP_COMPATIBILITY_CONTRACT.md`
(the ten-axis compatibility evaluation, outcome levels, forward/backward compatibility — §3, §11,
§27); `LOOP_MARKETPLACE_CONTRACT.md` (marketplace scope, outage-portability contract — §11, §23);
`LOOP_RESULT_SHARING_CONTRACT.md` (shared-result redaction detail, correctness-evidence rule —
§24); `LOOP_FORK_LINEAGE_CONTRACT.md` (lineage detail built on this document's §2.9, §14–§15
identity/actor vocabulary — §2.9); `CAPABILITY_AUTHORITY_MODEL.md` (the authority ladder — §10,
§25); `COMMON_CONVENTIONS.md` (normative language, append-only facts — §13, §15);
`EXECUTION_CONTRACT.md` (the `ExecutionState` lifecycle this spec's `RUNNING` state currently
encompasses without an explicit mapping — Amendment 2's "related, not resolved" note).

**Not yet written, forward-pointed only:** `LoopDefinitionContracts.kt`, `LoopAuthoringContracts.kt`,
and `LoopRuntimeContracts.kt` (`10_DUAL_VALIDATION_ADDENDUM.md` §E names these as this document's
Kotlin-contract targets — `LoopRuntimeContracts.kt` specifically for the §8 test modes and §9 run
states, since neither belongs to packaging or activation) — schema and Kotlin-contract authoring
is WP-1L scope beyond this document, not performed here. The `LoopRunState ↔ ExecutionState`
mapping Amendment 2 flags is likewise not written here.

**No new decision ID is proposed by this document.** All three amendments resolve using
already-ratified decisions (`FB-RAT-LOOP-002`/`-006`, `FB-RAT-LOOP-004` vs `FB-RAT-PKG-008`) or an
already-frozen registry rule (`LOOP-TEST-001`); none required inventing a new `FB-RAT-LOOP-NEW-*`
slot. The one `PROPOSED` ID this document cites (`FB-RAT-WEB-010`) was proposed by
`10_DUAL_VALIDATION_ADDENDUM.md` and is registered as `PROPOSED` by `LOOP_WEB_STUDIO_SPEC.md`, not
by this document — it is noted here only because §12's conformance discussion touches it.
