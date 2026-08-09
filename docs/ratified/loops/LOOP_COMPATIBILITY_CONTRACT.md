# Loop Compatibility Contract

> **License:** `LICENSE-PENDING` — see `docs/non_ratified/LICENSE_PENDING.md`.

**Status:** RATIFIED (this document's own sections), with `FB-RAT-CMP-007` carried at its true
status: **EXPERIMENTAL** (`docs/non_ratified/EXPERIMENTAL_DECISIONS.md`), cited here only as
already-scoped context, not re-ratified — §8. **Scope:** whether a given `.floop` LoopPackage can
be understood, installed, bound, and run on a specific Fonebrew installation, across every axis
that can make that untrue, without hiding degradation or silently substituting an unsafe resource.
This document is the citation target for `FB-RAT-CMP-001` through `FB-RAT-CMP-007`, and the
canonical source of the `compatibility.json` content `LOOP_PACKAGE_SPEC.md` §10 (`FB-RAT-PKG-008`)
requires every public release to carry — that document names `compatibility.json` as "the
compatibility declaration `LOOP_COMPATIBILITY_CONTRACT.md` governs in detail" and does not restate
it; this document is where that detail lives.

This document does **not** redefine capability IDs (`schemas/loops/registries/capability-ids.v1
.json`), model capability tags (`schemas/loops/registries/model-capability-vocabulary.v1.json`),
the `engineVersion` contract (that same registry's `engineVersion` object), the `.floop` container
format, the semantic digest, or the canonicalization profile (all frozen at `docs/ratified/loops/
LOOP_FROZEN_CONCEPTS_WP1L_G0.md`) — every one of those is cross-referenced below and never
re-derived; a conflict between this document's prose and a frozen registry's data is this
document's error, and the registry wins. Nor does it define the import/activation state machine a
compatibility evaluation is one step of, or the activation-receipt shape that step writes into —
both belong to `LOOP_IMPORT_ACTIVATION_CONTRACT.md`, a sibling WP-1L output not yet written as of
this document; every reference to "the activation receipt" below is a forward pointer to that
document's eventual shape, stating only what this document's evaluation must contribute to it.

## Note on this document's structure

The extracted first-pass/second-pass draft this document replaces used a 1–14 section sequence in
which four topics are stated twice — once as an initial pass, once as an appended second pass
under a later, unrelated-looking numeral (`10_DUAL_VALIDATION_ADDENDUM.md` §B2: *"every one of the
12 [Appendix E] files is a first pass with a second, overlapping pass appended"*): the compatibility
axis list (draft §2) and the "compatibility profile vocabulary" (draft §9) describe the same ten
axes from two angles; the outcome-level enum (draft §3) and "degraded compatibility" (draft §10)
both define what `DEGRADED` means; model requirements (draft §4) and "model compatibility" (draft
§11) both describe what a model-inference node declares; and distribution-flavor compatibility
(draft §12) restates and elaborates one bullet of the draft §2 axis list under its own numeral.
Every one of the 14 draft sections is folded into exactly one section below; nothing is dropped —
each second-pass section consistently added a sentence or two of genuine new normative content
(an activation-receipt obligation, an authority-widening approval gate, a vendor-pinning visibility
rule), which is preserved and flagged inline where it appears, not discarded along with the
duplicate framing around it.

A fifth defect, not literal duplication: **the draft's own axis list (§2, fourteen bullets) does
not match the ratified decision text it is filed under.** `FB-RAT-CMP-001`'s decision (§2 below)
names exactly ten axes — *schema, engine, node type, capability, model class, execution target,
platform/distribution, device, resource, and policy*. The draft's fourteen bullets are a
finer-grained, differently-cut list ("verifier availability" and "input/output migration
availability" are not axes in the ratified text at all). §2 adopts the ratified ten-axis list as
canonical and explicitly folds each of the draft's extra bullets into the axis that owns it,
rather than carrying two disagreeing axis counts forward into the repository copy.

Any other document in this handoff pack that cites this spec by a draft section number should
re-anchor against this table:

| Draft § | Draft topic | This document's § |
|---|---|---|
| 1 | Purpose | §1 |
| 2 | Compatibility axes | §2 |
| 3 | Outcome levels | §3 |
| 4 | Model requirements | §4 |
| 5 | Substitution | §5 |
| 6 | Phone-authoritative evaluation | §6 |
| 7 | Forward and backward compatibility | §7 |
| 8 | Evidence | §8 |
| 9 | Compatibility profile vocabulary (second pass of §2) | §2 |
| 10 | Degraded compatibility (second pass of §3) | §3 |
| 11 | Model compatibility (second pass of §4) | §4 |
| 12 | Distribution-flavor compatibility | §9 |
| 13 | Compatibility caching | §10 |
| 14 | Remediation actions | §11 |
| — | (not in draft — closes out the citation/registry list) | §12 |

`DUAL_RATIFICATION_REGISTER.md` cites this spec as `COMPAT §2` (`FB-RAT-CMP-001`), `COMPAT §3`
(`FB-RAT-CMP-004`), `COMPAT §4` (`FB-RAT-CMP-002`), `COMPAT §5` (`FB-RAT-CMP-003`), `COMPAT §6`
(`FB-RAT-CMP-005`), `COMPAT §7` (`FB-RAT-CMP-006`), and `COMPAT §8` (`FB-RAT-CMP-007`) — every one
of those numerals is unchanged by this cleanup, since draft §2–§8 map 1:1 onto this document's
§2–§8, so **no citation elsewhere in the pack needs re-anchoring**. Only a reader tracing draft
§9–§14 specifically needs the table above; `FB-RAT-IMP-007` (owned by `LOOP_IMPORT_ACTIVATION
_CONTRACT.md`, cited here only in §1 and §3 as consuming context) is the one external decision
whose source passage falls outside this file entirely and is not re-anchored here.

**Corrections applied, beyond de-duplication:**

1. **Ten-axis canonical list, per the file-specific build note.** §2 states the axes as exactly the
   ten `FB-RAT-CMP-001` names, cross-referenced to `capability-ids.v1.json` (capability axis) and
   `model-capability-vocabulary.v1.json` (model-class axis) rather than redefining either — neither
   registry's contents are restated here, only cited.
2. **`engineVersion` is cross-referenced, not re-derived.** Every place the draft used "engine
   version range" as an undefined term (§2, §9) now points at `capability-ids.v1.json`'s
   `engineVersion` object (`definition` / `v1Value` / `bumpRule` / `declaredAsRange`), frozen at
   WP-1L-G0 — this document does not restate what an engine version means, only how a range is
   evaluated against it.
3. **`BLOCKED` vs `UNSUPPORTED` is disambiguated.** `FB-RAT-CMP-004`'s decision text names "hard
   blockers" as a single category; the draft's outcome enum (§3) splits hard failure into two
   values without ever stating the split rule. §3 states it: `BLOCKED` is a missing-but-satisfiable
   requirement (bind it, install it, approve it, migrate it); `UNSUPPORTED` is a requirement no
   local action can satisfy (the schema, engine, or policy genuinely cannot interpret the package).
4. **Two prose-only decision procedures are re-flowed into explicit tables**, per the source
   pack's own docx-conversion note ("re-flow state machines into transition tables"): the
   outcome-level precedence rule (§3) and the compatibility-cache invalidation rule (§10 — a
   genuine small from-state/event/to-state machine the draft's §13 stated only as a run-on
   sentence).
5. **The remediation-action list (draft §14) is normalized into a closed enum** (§11) instead of
   an open prose list, so a finding's `remediationType` is machine-checkable rather than free text.

---

## 1. Purpose

A LoopPackage MUST be evaluated, before install and again before any execution whose preconditions
have changed since the last evaluation (§10), for whether the installing phone can understand,
install, bind, and run it without hiding degradation or silently substituting an unsafe resource.
This document defines: the ten axes an evaluation MUST cover, and the target-profile vocabulary
that expresses them as package content (§2); the five-value outcome enum and the finding shape
every evaluation produces (§3); what a model-inference node MAY require of its eventual model (§4);
how a binding slot declares what MAY satisfy it (§5); why evaluation is always local to the
installing phone, never a remote or marketplace judgment (§6); what MUST happen when a package is
newer or older than the evaluating phone understands (§7); what a compatibility or community claim
is worth without corroborating evidence (§8); how the Play/full split specifically affects an
outcome (§9); when a cached report MAY be reused and when it MUST NOT be (§10); and the closed set
of actions a finding MAY recommend (§11).

This document does not define the **import/activation state machine** a compatibility evaluation
is one step of — `LOOP_IMPORT_ACTIVATION_CONTRACT.md` owns that, and `10_DUAL_VALIDATION_ADDENDUM
.md` §B2 already fixes its shape as an eleven-state sequence including a `COMPATIBILITY_EVALUATED`
step. This document defines what a compatibility evaluation MUST produce (§3's outcome enum and
finding shape) so that step has somewhere to write its result; it does not re-derive the states
around it. `FB-RAT-IMP-007` — *"import distinguishes compatible, compatible-with-bindings,
degraded, blocked, and unsupported states with actionable reasons"* — is the import-side decision
that consumes this document's §3 outcome enum directly; it is ratified in, and cited by,
`LOOP_IMPORT_ACTIVATION_CONTRACT.md`, not here. This document only notes the dependency so a
reader does not go looking for a sixth outcome value that does not exist.

## 2. Compatibility axes and the target-profile vocabulary

**`FB-RAT-CMP-001` — ACCEPTED.** *"Compatibility covers schema, engine, node type, capability,
model class, execution target, platform/distribution, device, resource, and policy axes."*

A compatibility evaluation MUST assess exactly these ten axes — no fewer, and no draft-only axis
not folded into one of them:

| # | Axis | What it evaluates | Normative source (cross-referenced, not redefined) | Draft §2 bullets folded in |
|---|---|---|---|---|
| 1 | **Schema** | Package-container schema major/minor AND loop-definition schema major/minor | `floop-container-format.v1.json`; `semantic-digest.v1.json`'s `definition.json` identity (`LOOP_ENGINEERING_SPEC_V2.1`) | "package schema major/minor"; "loop definition schema"; "input/output migration availability" (migration *content* requirements are stated in §7, not here — this axis only evaluates whether a migration path exists at all) |
| 2 | **Engine** | Installed `engineVersion` vs. the package's declared engine version range | `capability-ids.v1.json`'s `engineVersion` object — `definition`, `v1Value`, `bumpRule`, `declaredAsRange` | "engine version range" |
| 3 | **Node type** | Supported node categories and their implementation references | `LOOP_ENGINEERING_SPEC_V2.1` node categories (eight, per `10_DUAL_VALIDATION_ADDENDUM.md` §F) | "supported node categories and implementation references"; "verifier availability" (a verifier is a node-adjacent capability dependency, evaluated the same way as any other node implementation reference) |
| 4 | **Capability** | Capability IDs and versions the package's `manifest.json` requests | `capability-ids.v1.json` — `capabilities[]`, `authorityLadder` | "capability IDs and versions" |
| 5 | **Model class** | Model capability tags a model-inference node requires (detailed in §4) | `model-capability-vocabulary.v1.json` — `tags[]`, `compatibilityRule` | "model capability requirements" |
| 6 | **Execution target** | Execution target classes AND their current connectivity/offline state | `schemas/execution/target.schema.json` — `ExecutionTargetType`, `connectivity` | "execution target classes"; "network/offline constraints" (modeled as a target's own connectivity state, not a separate axis) |
| 7 | **Platform/distribution** | Android distribution flavor and the mechanisms/permissions it carries | `docs/ratified/DISTRIBUTION_CAPABILITY_SPLIT.md` (`FB-RAT-DIST-001`); elaborated in §9 | "Android distribution flavor and permissions" |
| 8 | **Device** | Device/board/protocol requirements | `docs/ratified/DEVICE_STATE_AND_SAFETY_SPEC.md` §2.1 `DeviceIdentity` (`family`, `board`, `mcu`, `usbDescriptor`, `bootloaderInfo`) | "device/board/protocol requirements" |
| 9 | **Resource** | Memory/storage expectations and package-level size/count ceilings | `floop-container-format.v1.json` limits; `LOOP_PACKAGE_SPEC.md` §13 | "resource requirements" |
| 10 | **Policy** | Policy and authority constraints the installing principal currently holds | `docs/ratified/CAPABILITY_AUTHORITY_MODEL.md` (`Principal`, `Grant`, default deny `FB-RAT-AUTH-003`) | "policy and authority constraints" |

A **declared target profile** — the `compatibility.json` content `LOOP_PACKAGE_SPEC.md` §10
(`FB-RAT-PKG-008`) requires every public release to carry — MUST express these same ten axes as
data, one block per axis: engine version range (axis 2); node implementation set (axis 3);
capability versions (axis 4); model capability envelope (axis 5); execution target classes and
network constraints (axis 6); Android/API level and distribution flavor (axis 7); hardware/
protocol requirements (axis 8); memory/storage expectations (axis 9); and verifier set (folded
into axis 3, per the table above). A profile is **descriptive only** — §6 states plainly that the
phone evaluates its own actual installed state and MUST NOT treat a package's self-declared profile
as evaluation input, only as the claim being checked against that state.

## 3. Outcome levels and findings

**`FB-RAT-CMP-004` — ACCEPTED.** *"Compatibility distinguishes hard blockers, required bindings,
degraded operation, warnings, and optimizations."*

A compatibility evaluation MUST resolve to exactly one of five outcome levels:

| Outcome | Meaning | Locally recoverable? | `FB-RAT-CMP-004` category |
|---|---|---|---|
| `COMPATIBLE` | All required conditions across all ten axes (§2) are resolved; only `WARNING`/`INFO`-severity findings, if any, remain | — | "warnings", "optimizations" |
| `COMPATIBLE_WITH_BINDINGS` | All required conditions resolved except one or more resolvable local binding slots (§5) remain unbound | Yes — bind the slot(s) and re-evaluate | "required bindings" |
| `DEGRADED` | A valid, explicit fallback disables optional behavior; the loop's declared output, authority, side-effect, and verification semantics remain satisfied (see below) | Yes, by construction — the fallback *is* the recovery | "degraded operation" |
| `BLOCKED` | A required dependency, capability, authority, or migration is missing, but a local action (install, grant, approve, migrate) could satisfy it | Yes — apply the missing action and re-evaluate | "hard blockers" (recoverable half) |
| `UNSUPPORTED` | The schema, engine, or policy cannot safely interpret the package at all — no local action changes that without a new engine version or a rebuilt package | No | "hard blockers" (unrecoverable half) |

Evaluation MUST apply the five outcomes in strict precedence order — the first row below whose
condition is true wins, regardless of how many lower-precedence conditions also hold:

| Precedence | Condition | Resulting outcome |
|---|---|---|
| 1 (highest) | Any axis finding is `UNSUPPORTED`-triggering | `UNSUPPORTED` |
| 2 | Any axis finding is `BLOCKED`-triggering | `BLOCKED` |
| 3 | Any optional feature engaged a `DEGRADED` fallback | `DEGRADED` |
| 4 | Any binding slot remains unbound | `COMPATIBLE_WITH_BINDINGS` |
| 5 (lowest) | None of the above | `COMPATIBLE` |

Every outcome MUST carry zero or more findings; each finding MUST carry a stable rule code,
severity, affected object, requirement, local observation, and remediation type (§11). Findings
reuse the `loop-validation-rules.v1.json` code pattern — `LOOP-COMPAT-001` ("impossible target
constraint") and `LOOP-COMPAT-002` ("update widens authority, weakens a verifier, adds an external
target, adds a secret slot, increases budget, or adds a destructive path without fresh approval")
already exist in that registry's `LOOP-COMPAT` namespace — rather than inventing a parallel code
scheme; severities MUST be drawn from that same registry's three-value `severityLevels`
(`ERROR`/`WARNING`/`INFO`), not redefined here. This evaluation surface currently has two backing
`LOOP-COMPAT-*` codes; additional per-axis codes will be needed as concrete evaluators for axes 3,
8, 9, and 10 (§2) are implemented. Per `capability-ids.v1.json`'s own versioning convention ("new
capability IDs are additive... MUST NOT repurpose"), a new `LOOP-COMPAT-*` code is an additive
minor-version bump to `loop-validation-rules.v1.json`, not something this document mints inline —
recorded here as an implementation gap, not a new decision, and not a `PROPOSED` ID (no new
`FB-RAT-*` decision is implied — the registry's own additive-minor-version rule already covers it).

**`DEGRADED`, in full** (draft §10's genuine new content, preserved as normative text, not merely
mentioned):

- `DEGRADED` MUST NOT be used unless (a) the optional feature has an explicit, package-declared
  fallback, and (b) the loop's declared output contract, authority requirements, side-effect class,
  and verification semantics all remain satisfied under that fallback.
- The disabled node(s) and the selected fallback MUST be recorded in the activation receipt
  (`LOOP_IMPORT_ACTIVATION_CONTRACT.md`, not yet written — this document states only what the
  outcome/finding layer must hand that document, not the receipt's full shape).
- A fallback that would weaken verification or widen authority MUST NOT be labeled `DEGRADED`. It
  requires explicit user approval through the same authority-widening path `LOOP-COMPAT-002`
  already enforces at update time (§7) and `FB-RAT-AUTH-004` (no implicit privilege expansion,
  `CAPABILITY_AUTHORITY_MODEL.md` §6) governs generally; this section does not re-decide either,
  only applies them to the `DEGRADED` case specifically — such a change MUST instead resolve to
  `BLOCKED` pending that approval.

## 4. Model compatibility requirements

**`FB-RAT-CMP-002` — ACCEPTED.** *"Packages declare model capabilities and constraints, not
mandatory vendor-specific model IDs unless the loop is explicitly vendor-bound."*

A model-inference node MUST declare its requirements as capability tags drawn from
`model-capability-vocabulary.v1.json` (not redefined here — see that registry's fourteen-tag
`tags[]`, e.g. `text-generation`, `vision-input`, `tool-calling`, `json-mode`, `long-context` with
its `contextTokens` parameter, `logprobs`, `streaming`, `on-device-residency`, `cloud-residency`,
`asom-shared-residency`), plus: a context-length minimum, a structured-output reliability need, a
tool-use need, a local/cloud residency policy (the `on-device-residency` / `cloud-residency` /
`asom-shared-residency` tags — sovereignty default per `CLAUDE.md` binding rule 2, "on-device is
always the default"), a latency ceiling, a cost ceiling, and a verifier requirement.

A node MUST NOT pin a vendor/model ID as a hard requirement unless the loop's behavior genuinely
depends on that specific model's behavior — and any such pin MUST be visibly surfaced to the user
at bind time, never silently defaulted. (This sentence is the genuine new content the draft's
second pass, §11, added over its own §4; it is preserved here as normative MUST/MUST NOT text, not
merely mentioned as prose.)

At bind time, a model-inference node's required tag set MUST be a subset of the resolved
`ModelDescriptor.capabilityTags`. `model-capability-vocabulary.v1.json`'s own `compatibilityRule`
already states the failure mode this document adopts without re-deriving it: an impossible match
fails as `LOOP-COMPAT-001` ("impossible target constraint"), never a silent drop of the unmet
requirement in the hope the model complies anyway.

## 5. Substitution

**`FB-RAT-CMP-003` — ACCEPTED.** *"A binding slot declares exact, equivalent, or user-approved
substitution policy; substitutions are recorded in the activation receipt."*

A binding slot MUST declare exactly one of four substitution policies:

| Substitution policy | What may satisfy the slot | Who chooses the actual value |
|---|---|---|
| `EXACT_ONLY` | Only the exact declared value | Fixed — no substitution possible |
| `DECLARED_EQUIVALENTS` | Any value on a package-declared equivalents list | The phone auto-resolves among the declared list |
| `USER_APPROVED` | Any value the phone proposes | The user, with explicit confirmation at bind time |
| `ANY_COMPATIBLE` | Any locally available value satisfying the slot's declared capability requirements (§4 for model slots) | The phone auto-resolves |

A substitution MUST NOT change the side-effect class or authority rung the original binding slot
declared. This specializes `FB-RAT-AUTH-004` (no implicit privilege expansion,
`CAPABILITY_AUTHORITY_MODEL.md` §6) and `LOOP-CAP-002` (`loop-validation-rules.v1.json`) for the
substitution case specifically, in the same way `LOOP_PACKAGE_SPEC.md` §9 already specializes them
for binding placeholders generally — this document does not re-decide either, only names them at
the point substitution actually resolves a slot.

The activation receipt MUST record the selected provider and the substitution basis: which policy
applied, and — for `DECLARED_EQUIVALENTS` or `ANY_COMPATIBLE` — which declared or discovered
alternative was actually chosen and why. As with §3's `DEGRADED` recording obligation, this
document specifies only what compatibility evaluation must contribute to the receipt; the receipt's
full shape belongs to `LOOP_IMPORT_ACTIVATION_CONTRACT.md`.

## 6. Phone-authoritative evaluation

**`FB-RAT-CMP-005` — ACCEPTED.** *"The phone produces the authoritative compatibility report
against installed capabilities, distribution flavor, models, targets, and devices."*

The installing phone MUST be the sole authoritative evaluator. It evaluates against its own:
installed engine capabilities and `engineVersion` (§2 axis 2); app distribution flavor
(`DISTRIBUTION_CAPABILITY_SPLIT.md`, `FB-RAT-DIST-001`; §9); device permissions; available models
(`ModelDescriptor`, `model-capability-vocabulary.v1.json`; §4); execution targets
(`ExecutionTarget`, `schemas/execution/target.schema.json`; §2 axis 6); tools; devices
(`DeviceIdentity`, `DEVICE_STATE_AND_SAFETY_SPEC.md` §2.1; §2 axis 8); and policies (`Grant`/
`Principal`, `CAPABILITY_AUTHORITY_MODEL.md`; §2 axis 10).

A marketplace or browser-produced compatibility report MUST be labeled a **preview against a
declared profile**, never presented as an authoritative result, and MUST NOT be cached or reused
as if it were a phone-authoritative report (§10). `FB-RAT-IMP-007`'s `COMPATIBILITY_EVALUATED`
import state (§1) consumes this section's phone-authoritative report as its input, never a preview.

## 7. Forward and backward compatibility

**`FB-RAT-CMP-006` — ACCEPTED.** *"Unknown major schemas are rejected; unknown minor fields are
preserved where safe; unsupported optional nodes may be disabled only when graph semantics remain
valid."*

- An unknown MAJOR version of the package-container schema, the loop-definition schema, or
  `engineVersion` (`capability-ids.v1.json`'s `bumpRule`: *"a change that makes a previously-valid
  `definition.json` invalid, or changes `packageContentDigest`/`semanticDigest` for byte-identical
  input"*) MUST be rejected, resolving to `UNSUPPORTED` (§3). This specializes `FB-RAT-COM-003`
  (`COMMON_CONVENTIONS.md` §3, "readers reject unknown MAJOR") for these three version families
  specifically, the same pattern `LOOP_PACKAGE_SPEC.md` §12 already used when citing
  `LOOP-COMPAT-002` — not re-decided here.
- An unknown-but-safe MINOR field MUST be preserved on round trip (the `ContractEnvelope`
  `unknownFields` mechanism, `FB-RAT-COM-003`), never silently dropped.
- An optional unsupported node MAY be disabled only if the definition contains a validated fallback
  and the output contract remains satisfiable — this is exactly §3's `DEGRADED` gate; it is not
  restated in full here, only cross-referenced.
- A migration MUST be an explicit, versioned, tested transformation. This is where the draft's
  §2 axis bullet "input/output migration availability" resolves: the Schema axis (§2, axis 1)
  evaluates whether a migration path *exists* for a given schema-version gap; this section states
  what that migration path must actually be once one is required.

## 8. Evidence and community compatibility claims

**`FB-RAT-CMP-007` — EXPERIMENTAL**, not yet ratified as a fixed policy (`docs/non_ratified/
EXPERIMENTAL_DECISIONS.md`). *"Treat creator-declared device/language compatibility as unverified
metadata until backed by fixtures or explicit shared receipts."* The decision's own text is its
calibration condition — it graduates once a real fixture or shared-receipt corpus exists to check
declared claims against; until then, every rule below applies as stated.

- A compatibility claim MAY reference fixtures and explicit shared results as supporting evidence.
- A creator claim without such evidence MUST be labeled **declared**, never **verified** — the
  verified/declared distinction itself is bounded by whatever trust-level vocabulary
  `LOOP_RESULT_SHARING_CONTRACT.md` (a sibling WP-1L output, not yet written) defines; this
  document does not define trust levels, only requires that an unevidenced claim never be
  presented as if it had passed one.
- A device-specific claim MUST include device class, protocol, engine, and package digest, and MAY
  cite a result receipt — and MUST NOT include personal device identifiers. "Device class" here
  MUST be the `DeviceIdentity.family`/`board` classification (`DEVICE_STATE_AND_SAFETY_SPEC.md`
  §2.1), never `usbDescriptor` vendor/product-plus-serial or any other value capable of
  re-identifying one specific physical unit.

## 9. Distribution-flavor compatibility

This section elaborates `FB-RAT-CMP-001`'s platform/distribution axis (§2, axis 7) together with
`FB-RAT-DIST-001` (`DISTRIBUTION_CAPABILITY_SPLIT.md` §1, "explicit Play and full/sideload
capability manifests; never a single universal manifest") — it is not a separate decision, and no
new `FB-RAT-CMP-*` ID is proposed for it.

A compatibility report MUST distinguish Play, full/sideload, and any other ratified distribution
by name, per `FB-RAT-DIST-001`'s per-mechanism table. A loop requiring local executable tooling,
unrestricted package installation, or device access unavailable on the installed flavor MUST
resolve to `BLOCKED` with a remediation pointing at an explicit remote execution target (`fb.exec
.ssh_remote` or `fb.ci.dispatch`, `capability-ids.v1.json`) carrying visible authority evidence
(`FB-RAT-AUTH-006`) — or, if the package declares a validated fallback meeting §3's `DEGRADED`
bar, to `DEGRADED` with that fallback named. It MUST NOT be silently weakened into a lesser
feature set that produces neither a `BLOCKED` nor a labeled `DEGRADED` outcome.

## 10. Compatibility caching

This section elaborates `FB-RAT-CMP-005` (phone-authoritative evaluation, §6) — a cache MUST NOT
be allowed to undermine the freshness that decision requires. No new `FB-RAT-CMP-*` ID is proposed
for it.

A compatibility report MUST be cached under a key formed from exactly this tuple: package digest,
engine version, capability inventory digest, policy digest, device/OS profile, and distribution
flavor. Cache validity is a small state machine, stated explicitly rather than as prose (per the
source pack's own docx-conversion note to re-flow state machines into tables):

| From state | Event | To state |
|---|---|---|
| `CACHED_VALID` | Any one of {package digest, engine version, capability inventory digest, policy digest, device/OS profile, distribution flavor} changes | `CACHED_STALE` |
| `CACHED_STALE` | A fresh report is requested | Evaluation MUST re-run; the result becomes the new `CACHED_VALID` entry |
| `CACHED_VALID` | Execution about to start at or above the `EXECUTE_DESTRUCTIVE` rung, or a `PUBLISH_OR_RELEASE`-class action, is requested (`CAPABILITY_AUTHORITY_MODEL.md` authority ladder) | `MUST_REVALIDATE` — a fresh preflight MUST run regardless of cache validity |
| `CACHED_VALID` | Execution below `EXECUTE_DESTRUCTIVE` is requested | Remains `CACHED_VALID` — the cached report MAY be reused without re-evaluation |

Cached success MUST NOT substitute for a fresh preflight before destructive or publish execution,
even when every tracked dimension in the cache key is unchanged — the `MUST_REVALIDATE` row above
is unconditional on those two authority classes, not merely on cache-key drift.

## 11. Remediation actions

This section normalizes the draft's remediation list (§14) into a closed, machine-checkable enum.
It is new normative structure over `FB-RAT-CMP-004`'s finding concept (§3), not a separate
decision — flagged here, as `LOOP_PACKAGE_SPEC.md` §7 flagged its own new content, rather than
silently presented as if the draft already enumerated it this way.

A finding's `remediationType` MUST be one of:

| `remediationType` | Meaning |
|---|---|
| `BIND_RESOURCE` | Resolve a `COMPATIBLE_WITH_BINDINGS` slot (§5) to a locally available value |
| `INSTALL_TRUSTED_CAPABILITY` | Install or enable a missing capability the phone does not currently provide |
| `CHOOSE_ALTERNATE_RELEASE` | Select a different version/release of the same package that does not trigger this finding |
| `ENABLE_PERMISSION` | Grant an Android system permission the installed flavor supports but has not yet been granted |
| `SELECT_REMOTE_TARGET` | Route execution to an explicit remote `ExecutionTarget` (§9) instead of a local one the flavor blocks |
| `LOWER_AUTHORITY` | Reduce the requested authority rung so it fits within a grant the principal already holds |
| `UPDATE_ENGINE` | Update the installed `engineVersion` to fall within the package's declared range |
| `RUN_MIGRATION` | Apply an explicit, versioned migration (§7) to bridge a schema-version gap |
| `CONTACT_PUBLISHER` | No local action resolves the finding; the publisher must revise the package |
| `ABANDON_IMPORT` | No remediation exists; the import MUST be cancelled |

The UI MUST NOT send users to arbitrary package-supplied URLs for remediation. A `CONTACT_PUBLISHER`
finding MAY surface a publisher-declared contact reference as inert text, but MUST NOT auto-navigate
to it as if it were a trusted in-app action — package content is untrusted input
(`LOOP_PACKAGE_SPEC.md` §7, "the declarative boundary is an arbitrary-code-execution boundary, not
a safety boundary"), and a remediation flow is exactly the kind of implicit trust that boundary
statement exists to prevent.

## 12. Cross-references and open items

**Decision IDs cited in this document:** `FB-RAT-CMP-001` (§2), `FB-RAT-CMP-002` (§4),
`FB-RAT-CMP-003` (§5), `FB-RAT-CMP-004` (§3), `FB-RAT-CMP-005` (§6), `FB-RAT-CMP-006` (§7),
`FB-RAT-CMP-007` (§8, EXPERIMENTAL). `FB-RAT-PKG-008` (header, §2), `FB-RAT-DIST-001` (§2, §6, §9),
`FB-RAT-AUTH-003` (§2), `FB-RAT-AUTH-004` (§3, §5), `FB-RAT-AUTH-006` (§9), `FB-RAT-COM-003` (§7),
and `FB-RAT-IMP-007` (§1, §3, §6) are cited as already-ratified or sibling-owned context, not
re-decided here.

**Registries reconciled, not redefined:** `capability-ids.v1.json` (`capabilities[]`,
`authorityLadder`, `engineVersion` — §2, §4, §7); `model-capability-vocabulary.v1.json` (`tags[]`,
`compatibilityRule` — §2, §4); `loop-validation-rules.v1.json` (`LOOP-COMPAT-001`,
`LOOP-COMPAT-002`, `severityLevels` — §3, §5, §7); `floop-container-format.v1.json` and
`semantic-digest.v1.json` (§2). This document does not duplicate their contents; a conflict between
this document's prose and a registry's data is this document's error, and the registry wins, per
`LOOP_FROZEN_CONCEPTS_WP1L_G0.md`.

**Sibling documents this document defers to and does not restate:** `LOOP_PACKAGE_SPEC.md` (§9's
binding-placeholder narrowing rule, §10's `compatibility.json` completeness requirement, §13's
resource-limit values — §2, §5, §9 of this document); `DISTRIBUTION_CAPABILITY_SPLIT.md` (§6, §9);
`DEVICE_STATE_AND_SAFETY_SPEC.md` (§2, §8); `CAPABILITY_AUTHORITY_MODEL.md` (§3, §5, §6, §10);
`schemas/execution/target.schema.json` (§2, §6); `COMMON_CONVENTIONS.md` (§7). `LOOP_IMPORT
_ACTIVATION_CONTRACT.md`, `LOOP_RESULT_SHARING_CONTRACT.md`, and `LOOP_MARKETPLACE_CONTRACT.md`
did not exist under `docs/ratified/loops/` as of this document; a reader who needs their content
should look for them as separate WP-1L outputs, not expect this document to have absorbed them.

**Not yet written, forward-pointed only:** the `loop-compatibility-report` JSON Schema and
`LoopCompatibilityContracts.kt` (`inputs/dual_surface/DUAL_TRACEABILITY_MATRIX.md` names both as
this decision family's implementation artifacts) — schema and Kotlin-contract authoring is WP-1L
scope beyond this document, not performed here. Both should carry `compatibility.json` (§2) as a
`ContractEnvelope<T>` payload per `FB-RAT-COM-011`, consistent with every other frozen or ratified
loop object.

**No new decision ID is proposed by this document.** Every gap this file needed to close — the
axis-count mismatch between the draft and its own ratified decision text, the `BLOCKED`/
`UNSUPPORTED` split, the undefined `engineVersion` and capability/model-tag references, the
prose-only cache and remediation logic — was closable by cross-referencing an already-frozen
registry or an already-ratified decision; none required inventing an `FB-RAT-CMP-NEW-*` slot.
