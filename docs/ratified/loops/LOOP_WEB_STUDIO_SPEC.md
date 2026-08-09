# Browser Web Studio Specification

> **License:** `LICENSE-PENDING` — see `docs/non_ratified/LICENSE_PENDING.md`.

**Status:** RATIFIED (this document's own sections), with named exceptions carried at their true
status: `FB-RAT-WEB-010` is **PROPOSED, not yet ratified** (§9); `FB-RAT-LBX-009` and
`FB-RAT-WEB-008` are **DEFERRED** (§1, §7); `FB-RAT-WEB-009` is **EXPERIMENTAL** (§8);
`FB-RAT-WEB-004` is **REJECTED** (§5) and is cited here only as already-decided context, not
reopened. **Scope:** the browser authoring, testing, packaging, and publication-preparation
surface for the Fonebrew loop system — Web Studio's role and scope, workspace layout, canvas and
authoring capabilities, binding-slot/secret handling, the test laboratory, the execution boundary,
the account/storage/persistence model, the package build pipeline, publication preparation,
cross-surface conformance, phone-preview projection, web security and publisher signing, and
accessibility. This document is the canonical ratification home for `FB-RAT-LBX-003` and the
citation target for `FB-RAT-WEB-001` through `FB-RAT-WEB-009`, and it proposes `FB-RAT-WEB-010`.
It does not restate the dual-surface architecture that grounds this surface's existence
(`FB-RAT-LBX-001`, `FB-RAT-LBX-004` — `docs/ratified/loops/LOOP_DUAL_SURFACE_ARCHITECTURE.md`), the
phone-native authoring surface (`FB-RAT-LBX-002` — `docs/ratified/loops/
LOOP_PHONE_AUTHORING_SPEC.md`), the loop language's frozen container/digest/validation-code/
capability-ID concepts (`docs/ratified/loops/LOOP_FROZEN_CONCEPTS_WP1L_G0.md` and the six
registries it indexes), or the common envelope vocabulary (`docs/ratified/COMMON_CONVENTIONS.md`,
`contracts/kotlin/CommonContracts.kt`) — those are cross-referenced only.

**Why this surface exists, and why it is bounded.** `FB-RAT-LBX-002` makes the phone app the
primary, complete, untethered authoring and execution surface. Web Studio is its optional
counterpart: a high-density surface for authoring, testing, documentation, packaging, and
publication work that benefits from a large screen and a keyboard, never a hidden operational
dependency the phone needs in order to function. Every correction applied below narrows the
browser's trust surface further than the source draft did — none of them widens it.

## Note on this document's structure

The extracted first-pass/second-pass draft this document replaces stated one topic twice under two
different section numbers in one place (draft §5 vs draft §13, both titled "Test laboratory"
verbatim — the first pass lists fixture/mock/fault/replay/coverage/reproducibility capabilities,
the second pass restates most of the same list and adds target-profile, engine-version, and
receipt-linkage detail the first pass omitted) and split one topic in half in two more (draft §8
"Accounts and storage" vs draft §11 "Browser workspace persistence," which describe the same
local-vs-account storage model at two levels of detail — §8 states the account requirement, §11
states the storage mechanics that implement it). Every one of the draft's 17 sections is folded
into exactly one section below; nothing from the draft is dropped. Five corrections from the
independent validation addendum (`10_DUAL_VALIDATION_ADDENDUM.md` §B3, §B4, §D) are applied below
as new normative text that did not exist in the draft at all — each is flagged inline as a
correction where it appears, and none of them re-litigates an already-ratified `FB-RAT-*` ID.

Any other document in this handoff pack that cites this spec by a draft section number (e.g. the
dual-surface register's `WEB §3`, `WEB §4`, `WEB §5`, `WEB §6`, `WEB §7`, `WEB §8`) should re-anchor
against this table, not the draft:

| Draft § | Draft topic | This document's § |
|---|---|---|
| 1 | Role | §1 |
| 2 | Deployment and naming boundary | §1 |
| 3 | Workspace layout | §2 |
| 4 | Authoring capabilities | §4 |
| 5 | Test laboratory (first pass) | §6 |
| 6 | Binding slots and secrets | §5 |
| 7 | Execution boundary | §7 |
| 8 | Accounts and storage (first pass) | §8 |
| 9 | Package build pipeline | §9 |
| 10 | Cross-surface conformance | §11 |
| 11 | Browser workspace persistence (second pass) | §8 |
| 12 | Canvas behavior | §3 |
| 13 | Test laboratory (second pass) | §6 |
| 14 | Publication preparation | §10 |
| 15 | Browser accessibility | §14 |
| 16 | Web security | §13 |
| 17 | Phone-preview conformance | §12 |

---

## 1. Purpose, scope, and browser role

**`FB-RAT-LBX-003` — ACCEPTED — Browser role.** *"The browser optimizes dense graph construction,
testing, documentation, package creation, and publication; it is not the authoritative runtime."*
This document is `FB-RAT-LBX-003`'s canonical ratification home
(`inputs/dual_surface/DUAL_RATIFICATION_REGISTER.md`). `LOOP_DUAL_SURFACE_ARCHITECTURE.md` §4
("Browser responsibilities") already states the outer boundary this decision requires — the
browser MUST be authoritative only for browser draft revisions, graph layout and documentation
workspace, fixtures and simulation outputs, client-side package build results (§9), publication
listing drafts, and account content when accounts are enabled, and MUST NOT be authoritative for
phone installation, bindings, secrets, authority, or run state. This document does not restate
that boundary; it specifies the surface that lives inside it.

Web Studio MUST be an optional high-density authoring, testing, documentation, packaging, and
publication environment. It MUST produce portable `.floop` packages
(`floop-container-format.v1.json`) for Fonebrew and MUST NOT become a hidden operational
dependency: every capability the phone needs to author, validate, run, or repair an
already-installed loop MUST remain available with the browser unreachable
(`LOOP_DUAL_SURFACE_ARCHITECTURE.md` §8, "Browser unavailable").

**`FB-RAT-LBX-009` — DEFERRED — Final browser product naming** (`docs/non_ratified/
DEFERRED_DECISIONS.md`). The final domain and product name (candidates noted in the source pack
include a dedicated `.com`, a `.dev`, or a subdomain) are deferred until the marketplace and
account boundaries (§8, §10) are ratified. Independent of how `FB-RAT-LBX-009` eventually
resolves, the architecture MUST support a standalone web deployment and MUST NOT couple loop
semantics to any particular hostname, service, or account tier.

## 2. Workspace layout

**`FB-RAT-WEB-001` — ACCEPTED — Browser authoring scope.** *"Web Studio owns graph authoring,
schemas, fixtures, simulation, documentation, package build, signing, and publication
preparation."* The workspace below is what makes that ownership concrete; every region MUST be
present, and Web Studio MUST NOT claim `FB-RAT-WEB-001`'s scope without them:

- project/release header;
- node and pattern palette;
- graph canvas (§3);
- inspector;
- schema/forms editor;
- tests and fixtures (§6);
- simulation trace;
- graph/package diff;
- compatibility declaration;
- documentation;
- package and publish panel (§9, §10);
- phone preview (§12).

**Correction — the word "signing" in `FB-RAT-WEB-001`'s decision text (`10_DUAL_VALIDATION_
ADDENDUM.md` §D).** `FB-RAT-WEB-001` names "signing" as part of the browser's scope. That
scope is the package-and-publish panel's *signature-status display*, not a browser-side signing
operation — Web Studio itself MUST NOT sign a package for publication. §13 states why and where
signing actually happens.

Responsive layout MAY collapse secondary panels but MUST preserve full authoring on conventional
laptop/tablet browser sizes.

## 3. Canvas behavior

The graph canvas MUST support semantic zoom, a minimap, search, selection sets, alignment,
grouping, subloop collapse, edge routing, keyboard navigation, and deterministic auto-layout.
Layout metadata (node position, canvas viewport, presentation hints) is nonsemantic — it is exactly
the `presentation`/`layout`/`x`/`y` exclusion-list content `semantic-digest.v1.json` strips before
computing `semanticDigest` — unless a package explicitly marks a view as authored documentation, in
which case it MUST be carried as documentation content rather than as digest-affecting definition
content. Copy/paste MUST remap object IDs and MUST report any broken external reference the remap
produces, rather than silently dropping it.

No graph-rendering library is named by this document; §9 leaves the choice to the implementing
session, subject to one binding constraint carried from `LOOP_DUAL_SURFACE_ARCHITECTURE.md` §10's
canonical-compiler-boundary rule: whichever library is chosen, the canvas MUST be a *view* over the
Fonebrew canonical model, never the semantic source of truth.

## 4. Authoring capabilities

Web Studio MUST support: adding, removing, duplicating, grouping, and connecting nodes; bulk
selection and movement; alignment and distribution; subloop extraction and inline expansion;
gateway and cycle visualization; typed port compatibility guidance; schema generation and editing;
binding-slot declaration (§5); authority and side-effect review; semantic diff between revisions
(the diff operation model itself is `LOOP_FORK_LINEAGE_CONTRACT.md`'s scope, `FB-RAT-LIN-004` — not
restated here); and a phone Stage/Node/Run projection (`FB-RAT-WEB-007`, defined at §12, not here —
this bullet is a forward pointer only).

## 5. Binding slots and secrets

**`FB-RAT-WEB-005` — ACCEPTED — Binding placeholders** (canonical ratification home:
`docs/ratified/loops/LOOP_PACKAGE_SPEC.md`; cited here because this is where the browser-side
declaration surface is specified). Web Studio MUST store binding requirements as abstract, typed
slots, never as resolved values — for example `model.writer`, `model.critic`,
`repository.primary`, `ci.default`, `device.target`, `secret.release_signing`. Fonebrew resolves
every slot locally, on the phone, at bind time (`LOOP_PACKAGE_SPEC.md` owns the resolution
mechanism; this document only states that Web Studio's output is a slot declaration, never a
resolved binding).

**`FB-RAT-WEB-002` — ACCEPTED — No secrets in web drafts or packages.** Browser drafts and
packages MUST use abstract binding slots and MUST NOT contain API keys, Android Keystore aliases,
private host credentials, or any other raw secret. A binding slot *name* is not a secret and MAY
appear in a package; the value behind it MUST NOT.

**`FB-RAT-WEB-004` — REJECTED — Browser-held operational credentials** (`docs/non_ratified/
REJECTED_ALTERNATIVES.md`). Storing Fonebrew execution credentials in a browser-side marketplace
service is rejected outright, independent of §7's P1-viable execution-boundary relaxation: even a
browser permitted to *view* a locally authorised runtime (§7) MUST NOT hold, cache, or proxy that
runtime's credentials. If a future browser account feature allows private drafts (§8,
`FB-RAT-WEB-009`), server-side draft privacy does not relax this rule — a private draft is still
forbidden package content if it contains a raw secret, exactly as a public one would be.

## 6. Test laboratory

*(Merges draft §5 "Test laboratory" and draft §13 "Test laboratory," which described the same
capability at two levels of detail under two different section numbers.)*

**`FB-RAT-WEB-006` — ACCEPTED — Advanced test laboratory.** Web Studio MUST provide:

- fixture sets and input matrices;
- mock capability providers;
- deterministic model responses;
- human-decision fixtures;
- fault schedules;
- replay trace import;
- expected terminal states and outputs;
- budget simulation;
- branch/path coverage (graph diff itself is specified in §4, not restated here);
- package reproducibility and canonical digest tests;
- a matrix presentation across input sets, model/tool mocks, fault injections, target profiles,
  and engine versions (draft §13's addition to the first-pass list above).

Tests MUST be runnable individually or as a suite. Results MUST link directly to the graph path and
the receipt they produced. Cloud-backed test execution is out of scope unless separately ratified;
v1 simulation MUST use inert fixtures and safe deterministic evaluators only — a mock is never a
live call.

Simulation MUST NOT be represented, in any UI copy or documentation, as proof that real providers,
phones, devices, or repositories will behave identically. A simulated PASS is evidence about the
fixture, not a claim about production behavior.

**Correction — fixture keying (`10_DUAL_VALIDATION_ADDENDUM.md` §D, "Fixture keying is
unspecified").** The draft never specified how a fixture is keyed to the node and call it replays.
Keying by node ID alone breaks on retries (which call?); keying by ordinal position alone breaks on
branch changes (which ordinal?); keying by a prompt hash alone breaks on every prompt edit (the
fixture silently goes missing, or silently matches the wrong thing). Web Studio MUST key every
fixture on the tuple **(`nodeId`, `iterationIndex`, `callIndex`)**, with a recorded but
**non-binding** prompt digest carried alongside the key, not as part of it. This is the same
fixture-keying shape `floop-container-format.v1.json` already assumes for its `fixtures/` layout
entry, and it is exactly what the frozen rule code below implements:

| Rule code | Namespace | Severity | Object type | Trigger |
|---|---|---|---|---|
| `LOOP-TEST-002` | `LOOP-TEST` | `WARNING` | `fixture` | The fixture's recorded prompt digest no longer matches the node's current prompt. |

A prompt edit therefore marks the affected fixture(s) **STALE** — a visible, rule-coded finding —
rather than the fixture silently going missing (if keyed by prompt hash) or silently matching the
wrong call (if keyed too loosely). `LOOP-TEST-002` is frozen in `schemas/loops/registries/
loop-validation-rules.v1.json` and MUST be emitted identically by both surfaces (§11).

## 7. Execution boundary

**`FB-RAT-WEB-003` — ACCEPTED — No private resource execution by default, reframed as a
credential-and-origination rule.** The draft stated this as an absolute prohibition ("the browser
does not run against private operational resources"). The independent validation pass
(`10_DUAL_VALIDATION_ADDENDUM.md` §D, "WEB-003 is stricter than the security goal needs") found the
prohibition stricter than the security goal actually requires, using LangGraph Studio's shape as
precedent: a browser UI over a *locally running* agent server (`langgraph dev` on
`127.0.0.1:2024`) with credentials held in a local `.env` — structurally identical to the ASOM
daemon this constellation already runs on `127.0.0.1:11435`
(`fb.model.local_inference`, `capability-ids.v1.json`, which already documents that ASOM residency
explicitly). This document adopts the reframing as the corrected rule:

Web Studio **MUST NOT** hold credentials and **MUST NOT** originate execution. Web Studio **MAY**
act as a view/controller over a runtime the user already runs and has authorized locally — where
credentials, bindings, grants, and receipts all remain local to that runtime, and every run it
triggers still produces a local receipt exactly as if the phone had triggered it directly.

**This relaxation is P1-viable, not a v1 change.** Nothing above authorizes shipping
browser-controlled local-runtime execution in v1; it authorizes *designing toward* the
credential-and-origination framing rather than the absolute-prohibition framing, so a future P1
feature built against this rule does not need to re-litigate `FB-RAT-WEB-003` itself. The v1
execution boundary remains: P0 browser operations are validation, local simulation with
mocks/fixtures (§6), package construction (§9), and publication (§10). The browser does not run
against private operational resources in v1. Any future real execution — including the P1-viable
local-runtime-view shape above — requires a separate ratification covering credentials, isolation,
billing, provenance, retention, and user authority; this document does not perform that
ratification.

**`FB-RAT-WEB-008` — DEFERRED — Browser hardware execution** (`docs/non_ratified/
DEFERRED_DECISIONS.md`). WebUSB/WebSerial/WebBluetooth execution and device flashing from the
browser are deferred until the phone runtime and marketplace trust models are stable. This is a
narrower, harder case than the local-runtime-view relaxation above — direct browser-to-hardware
execution, not a view over a runtime the phone already governs — and stays deferred independent of
this section's reframing of `FB-RAT-WEB-003`.

## 8. Accounts, storage, and persistence

*(Merges draft §8 "Accounts and storage" and draft §11 "Browser workspace persistence," which
described the same local-vs-account storage model at two levels of detail — draft §8 states the
account requirement, draft §11 states the storage mechanics that implement it.)*

The editor SHOULD work without an account for local or ephemeral drafts wherever technically
practical. The storage and persistence state model:

| From state | Event | To state | Notes |
|---|---|---|---|
| `NO_DRAFT` | Author starts editing without signing in | `ANONYMOUS_LOCAL` | Uses IndexedDB/local storage where technically practical; works with no account (`FB-RAT-WEB-001` scope). |
| `ANONYMOUS_LOCAL` | Autosave fires | `ANONYMOUS_LOCAL` (revision recorded) | Autosave history records semantic revisions; it MUST NOT make a draft public — a locally recorded revision is never itself a publish action. |
| `ANONYMOUS_LOCAL` | Browser storage is cleared (by the user or the browser) | `LOST` | The editor MUST warn the author, before this can happen, that clearing browser storage removes an anonymous local draft, and MUST always offer an export action as the durable escape hatch. |
| `ANONYMOUS_LOCAL` | Author exports | `EXPORTED_PACKAGE` | Produces a portable `.floop`/source bundle independent of browser storage. |
| `ANONYMOUS_LOCAL` | Author signs in and explicitly saves | `ACCOUNT_SAVED_DRAFT` | Account save is an explicit action, never an automatic promotion of an anonymous draft. |
| `ACCOUNT_SAVED_DRAFT` | Autosave fires | `ACCOUNT_SAVED_DRAFT` (revision recorded) | Same non-public guarantee as above. |
| `ACCOUNT_SAVED_DRAFT` | Author publishes or lists | `PUBLISHED` / `LISTED` | Publisher identity, reviews, and listings MAY require accounts. |
| `ANONYMOUS_LOCAL` or `ACCOUNT_SAVED_DRAFT` | Author saves anonymously, unlisted, with no persistent account tie (`FB-RAT-WEB-009`) | `UNLISTED_ANONYMOUS_DRAFT` | Only behind the abuse, retention, and privacy experiments below. MAY combine with the soft-key signing tier (§13). |

**`FB-RAT-WEB-009` — EXPERIMENTAL — Anonymous unlisted drafts** (`docs/non_ratified/
EXPERIMENTAL_DECISIONS.md`). Anonymous or account-free unlisted package generation is permitted
only behind the abuse, retention, and privacy experiments this decision's EXPERIMENTAL status
implies — it is not a committed shape until those experiments run.

**Correction — server-side private-draft scope (`10_DUAL_VALIDATION_ADDENDUM.md` §B4, "Scope
violation inside the pack itself").** The draft's account/storage language ("saved private drafts
... MAY require accounts") did not distinguish *server-side* private-draft storage from the
account-gated *publication* surfaces (publisher identity, reviews, listings) that are the actual
scope of the marketplace's no-backend-law amendment. `FB-RAT-INT-003` (`docs/non_ratified/
REJECTED_ALTERNATIVES.md`) rejects shared backends and databases for v1; its forthcoming scoped
amendment `FB-RAT-MKT-001` (`LOOP_MARKETPLACE_CONTRACT.md`, not yet written in this repository) is
understood to cover only deliberately-published, public marketplace artifacts — not private draft
storage. This document therefore states the corrected rule normatively: **server-side storage of a
private, unpublished draft MUST NOT exist in v1.** Accounts MAY gate publisher identity, signature
*provenance records* (the signature itself is produced on the phone — §13), reviews, and listings,
because those are deliberately-published surfaces within `FB-RAT-MKT-001`'s intended scope.
Private-draft persistence in v1 is limited to the `ANONYMOUS_LOCAL` and `EXPORTED_PACKAGE` states in
the table above. A future server-side private-draft feature requires its own ratification pass, not
an extension of this document's account scope. This correction narrows, and does not conflict with,
`LOOP_DUAL_SURFACE_ARCHITECTURE.md` §12's parallel correction striking the browser-side package
*build* service for the same underlying reason (the no-backend law).

Retention, deletion, encryption, and abuse rules for any account-gated state above require a
marketplace privacy specification before launch; this document does not write that specification.

## 9. Package build pipeline

The build pipeline is a linear sequence of gates:

| From state | Event | To state | Notes |
|---|---|---|---|
| `DRAFT` | Author requests a build | `STATICALLY_VALIDATED` | Runs the frozen `loop-validation-rules.v1` codes (`schemas/loops/registries/loop-validation-rules.v1.json`). |
| `STATICALLY_VALIDATED` | No blocking `ERROR`-severity finding | `CANONICALIZED` | Canonicalization runs the frozen `fb-loop-canon-1` profile — see the correction below; this document does not re-derive number policy. |
| `STATICALLY_VALIDATED` | A blocking `ERROR`-severity finding exists | `BUILD_BLOCKED` | Build MUST NOT proceed past a blocking finding. |
| `CANONICALIZED` | Fixtures run | `FIXTURES_TESTED` | Fixture keying and staleness follow §6's `LOOP-TEST-002` correction. |
| `FIXTURES_TESTED` | Documentation completeness is checked | `DOCUMENTATION_CHECKED` | Feeds §10's publication gate. |
| `DOCUMENTATION_CHECKED` | Forbidden-content scan runs (secrets, executable payloads, disallowed Unicode, prompt-risk heuristics) | `CONTENT_SCANNED` | `LOOP-PKG-001`/`002`/`006`/`008` per `loop-validation-rules.v1.json`. |
| `CONTENT_SCANNED` | No forbidding finding | `ASSEMBLED` | Package file inventory assembled per `floop-container-format.v1.json`'s `requiredTopLevelLayout` and `packageInventory`. |
| `CONTENT_SCANNED` | A forbidding finding exists | `BUILD_BLOCKED` | e.g. a raw secret or an executable payload — never silently stripped. |
| `ASSEMBLED` | `packageContentDigest` computed | `BUILD_COMPLETE` | Computed over the canonical file list, not archive bytes (`floop-container-format.v1.json`). |
| `BUILD_COMPLETE` | Author downloads | `DOWNLOADED_UNSIGNED` | Local download; no publication implied. |
| `BUILD_COMPLETE` | Author proceeds to publish | *(continues at §13's signing table, from `BUILT_UNSIGNED`)* | Publication requires the phone-signing flow in §13 — Web Studio does not sign for publication itself. |

Every build's output MUST include all validation findings, reported by stable rule code (not
message text alone), and a reproducibility receipt.

**Correction — canonical JSON numbers are already resolved at the registry level
(`10_DUAL_VALIDATION_ADDENDUM.md` §D, "Canonical JSON numbers are a trap").** This document does
not re-derive number canonicalization policy. `canonicalization.v1.json` (frozen by WP-1L-G0)
already forbids IEEE-754 double-precision floats in the canonical profile entirely — every numeric
value is either a plain JSON integer or a constrained decimal string — specifically because RFC
8785's ECMA-262 number-serialization requirement is unreachable from the JVM (`Double.toString`)
and kotlinx-serialization has no canonical mode. A browser-side canonicalizer MUST implement the
same `numberPolicy` the registry defines; it MUST NOT invent a JavaScript-native alternative (e.g.
relying on V8's own number-to-string behavior on the theory that "the browser already does RFC 8785
correctly") — the registry's ban on bare fractional numbers applies uniformly to both surfaces,
precisely so neither surface's number-serialization behavior is ever a digest-parity variable.

**Correction — one shared canonicalization/validation/packaging implementation, `FB-RAT-WEB-010`
(NEW — PROPOSED, not self-ratified; `10_DUAL_VALIDATION_ADDENDUM.md` §D).** `LOOP_DUAL_SURFACE_
ARCHITECTURE.md` §10 currently states that "the two surfaces' compiler implementations need not
share code, but they MUST share the golden vectors." Read together with this pipeline's
digest-and-validation-parity gate (§11), that is a **permanent two-implementation lockstep
obligation** — canonicalization, the full validation rule set, semantic diff, fixture semantics,
and the package builder, kept bit-for-bit identical, forever, by hand, for one maintainer — and it
makes the browser a hard release blocker for the phone even though
`LOOP_DUAL_SURFACE_ARCHITECTURE.md` §8 elsewhere requires "browser absence never blocks local
execution." This document does not have the authority to amend
`LOOP_DUAL_SURFACE_ARCHITECTURE.md` §10; it proposes the following as `FB-RAT-WEB-010`, for the
Amendments-phase agent to accept, reject, or fold into an existing ID:

> Ship **one** implementation of canonicalization, validation, and packaging, compiled to both
> surfaces, rather than two hand-written implementations reconciled by golden vectors. Kotlin/JS is
> **Stable**; Kotlin/Wasm and Compose-for-Web are **Beta** (with a Safari 18.2 floor) and do not
> belong on the critical path of a release-blocking gate. Compile the headless
> `:loop-canonicalization`, `:loop-validation`, and `:loop-packaging` modules to an ES-module npm
> artifact and consume it from a TypeScript/React Web Studio UI — no DOM, no Compose, so the usual
> KMP-for-web objections about UI-framework mismatch do not apply, and Web Studio keeps access to
> mature JavaScript graph libraries (§3) for everything above the compiler boundary. Under this
> proposal, the golden corpus (§11) becomes a **regression suite** proving the one shared core has
> not silently changed release to release — not, as it is today, the mechanism that keeps two
> independently maintained implementations honest with each other. A 100%-digest-parity P0 gate
> checked against two independent implementations is a permanent lockstep obligation this pack
> never actually asks for honestly, and this proposal removes it. If two implementations are kept
> anyway, the P0 digest-parity gate should be renegotiated so a browser conformance failure cannot
> block a phone release — consistent with the degraded-operation guarantee this proposal exists to
> make actually honest rather than aspirational.

Until `FB-RAT-WEB-010` is ratified (or rejected, or folded into another ID), the binding rule
remains `LOOP_DUAL_SURFACE_ARCHITECTURE.md` §9's and §10's as written: two implementations, full
digest-and-validation parity, checked against shared golden vectors.

## 10. Publication preparation

Before publication, Web Studio MUST require: complete documentation; a license declaration; a
provenance record; a compatibility declaration; an authority summary; fixture status (§6); the
package digest (§9); a publisher signature (§13 — produced on the phone, not in the browser); and
a rendered phone preview (§12). The publish review MUST highlight every requested capability and
side effect prominently — never merely the graph's title and description — so a reviewer cannot
approve a publish action without seeing what authority it grants.

## 11. Cross-surface conformance

The browser implementation MUST use the same shared golden fixtures as the phone implementation
(`LOOP_DUAL_SURFACE_ARCHITECTURE.md` §9's seven-row conformance table; not restated here). Two
rules this document states explicitly because they anchor the marketplace's semantic-equivalence
claim:

- Layout-only edits MUST NOT alter `semanticDigest` (`semantic-digest.v1.json`'s exclusion list is
  the concrete mechanism; §3 above states the canvas-layer consequence).
- Export/import round trips MUST preserve unknown safe minor fields and stable IDs — an
  unrecognized-but-declared field surviving a round trip is required behavior, not merely tolerated
  behavior, because it is what lets a newer package schema pass safely through an older
  implementation of either surface.

Per §9's `FB-RAT-WEB-010` proposal, this section's golden-vector requirement is written against
today's two-implementation reality; if `FB-RAT-WEB-010` is ratified, this section's fixtures become
a regression suite over the one shared core rather than a reconciliation mechanism between two
independent ones — the fixtures themselves do not change, only what they are understood to prove.

## 12. Phone-preview conformance

**`FB-RAT-WEB-007` — ACCEPTED — Phone preview.** Web Studio MUST include a phone-preview mode
covering Intent, Stage, Node Sheet, import summary, and Run timeline projections
(`LOOP_PHONE_AUTHORING_SPEC.md` §3 defines these views; this document only requires that Web Studio
render conforming projections of them, not that it reimplement their interaction model). The
preview MUST be generated from the canonical semantic model and the phone presentation rules —
never a hand-authored mock that could drift from what the phone actually renders. It MUST warn the
author when browser-only visual complexity (e.g. a densely laid-out canvas region, an unusually
large node count in one view) will collapse on the phone or require additional phone-side grouping
to stay legible, per `LOOP_PHONE_AUTHORING_SPEC.md` §16's information-density law.

## 13. Web security and publisher signing

Imported packages MUST be parsed in a restricted worker or an equivalent isolation boundary.
Markdown and asset content MUST be sanitized. No package content may execute script, load remote
active content, or access browser credentials — this is the browser-side enforcement half of the
principle that the declarative boundary is an arbitrary-code-execution boundary, not a safety
boundary; the prompt-risk and Unicode scans in §9 (`LOOP-PKG-006`/`008`) are the content-inspection
half, and neither substitutes for the other.

**Correction — publisher signing moves to the phone (`10_DUAL_VALIDATION_ADDENDUM.md` §D, "Browser
'hardware-backed' signing is not achievable").** The draft required signing keys to "be
hardware-backed or client-held." This is not achievable in a browser as stated: the only
hardware-backed browser key material is WebAuthn, and a WebAuthn signature covers
`authenticatorData || SHA-256(clientDataJSON)` — a structure WebAuthn itself chooses — not a
caller-supplied payload. It cannot produce a detached signature over an arbitrary package digest
without a bespoke envelope that every offline verifier would then need to reconstruct just to check
a signature. This document replaces the draft's requirement with the corrected flow below. The
resulting signature is carried in the package's optional `signatures/` top-level directory
(`floop-container-format.v1.json`) as a `loop-package-signature.v1` record — Ed25519 over
`packageContentDigest` plus release identity.

| From state | Event | To state | Notes |
|---|---|---|---|
| `BUILT_UNSIGNED` | Author chooses "publish to marketplace" | `AWAITING_PHONE_SIGNATURE` | Web Studio MUST NOT attempt a browser-side hardware-backed signature for marketplace publication. |
| `AWAITING_PHONE_SIGNATURE` | Author imports the unsigned package to their own phone and signs with an Android Keystore/StrongBox key | `PUBLISHER_SIGNED` | Signing happens entirely off the browser's trust boundary; this reuses the phone's existing activation-import path (`LOOP_DUAL_SURFACE_ARCHITECTURE.md` §7). |
| `PUBLISHER_SIGNED` | Author publishes | `PUBLISHED` | Satisfies §10's "required publication signature" gate. |
| `BUILT_UNSIGNED` | Author chooses "save as an unlisted draft" (`FB-RAT-WEB-009`, EXPERIMENTAL, §8) and enables the soft-key tier | `SOFT_SIGNED_DRAFT` | A non-extractable IndexedDB `CryptoKey`, explicitly labelled in the UI as a lower-trust "soft key" tier — it MUST NOT be presented as equivalent to a phone-signed, hardware-backed signature. |
| `SOFT_SIGNED_DRAFT` | Author later imports to phone and signs with Keystore/StrongBox | `PUBLISHER_SIGNED` | A soft-signed draft remains eligible for upgrade to a real publisher signature. |
| `SOFT_SIGNED_DRAFT` | Author attempts marketplace publish directly | `REJECTED_INSUFFICIENT_SIGNATURE` | The soft-key tier is sufficient only for unlisted, account-free drafts (§8) — never for marketplace publication. |

Web Studio itself **MUST NOT** silently sign on behalf of a publisher without a distinct, explicit
delegated-key contract — the soft-key tier above is the only browser-side signing this document
authorizes, it is scoped to unlisted drafts only, and it is never silent: the UI-visible "soft key"
label is load-bearing, not decorative.

## 14. Browser accessibility

Every canvas operation MUST have a tree/list alternative. Keyboard users MUST be able to navigate
nodes and ports, create an edge by command, edit conditions, inspect validation, and reorder stages
without drag-and-drop. Screen readers MUST receive semantic graph order and relationship
descriptions rather than canvas coordinates — the same "reading order follows semantic execution
order, not canvas coordinates" principle `LOOP_PHONE_AUTHORING_SPEC.md` §12.1 states for the phone
surface, restated here for the browser's own canvas.

## 15. Cross-references and open items

**Decision IDs cited in this document:** `FB-RAT-LBX-003` (§1, ACCEPTED — canonical home),
`FB-RAT-LBX-009` (§1, DEFERRED), `FB-RAT-WEB-001` (§2), `FB-RAT-WEB-002` (§5), `FB-RAT-WEB-003`
(§7, reframed), `FB-RAT-WEB-004` (§5, REJECTED), `FB-RAT-WEB-005` (§5), `FB-RAT-WEB-006` (§6),
`FB-RAT-WEB-007` (§4 forward pointer, defined §12), `FB-RAT-WEB-008` (§7, DEFERRED),
`FB-RAT-WEB-009` (§8, EXPERIMENTAL). **Proposed, not self-ratified:** `FB-RAT-WEB-010` (§9) — one
shared canonicalization/validation/packaging implementation compiled to both surfaces. Also cited
as already-decided context, not re-decided here: `FB-RAT-LBX-001`/`FB-RAT-LBX-004`
(`LOOP_DUAL_SURFACE_ARCHITECTURE.md`), `FB-RAT-LBX-002` (`LOOP_PHONE_AUTHORING_SPEC.md`),
`FB-RAT-INT-003` (no-backend law) and its forthcoming amendment `FB-RAT-MKT-001` (marketplace
scope, not yet written in this repository).

**Registries reconciled, not redefined:** `canonicalization.v1.json`, `semantic-digest.v1.json`,
`floop-container-format.v1.json`, `loop-validation-rules.v1.json` (specifically `LOOP-TEST-002`,
§6, and `LOOP-PKG-001`/`002`/`006`/`008`, §9), `capability-ids.v1.json` (specifically
`fb.model.local_inference`'s ASOM cross-reference, §7). A conflict between this document's prose
and a registry's data is this document's error — the registry wins, per
`LOOP_FROZEN_CONCEPTS_WP1L_G0.md`.

**Sibling documents this document defers to and does not restate:**
`LOOP_DUAL_SURFACE_ARCHITECTURE.md`, `LOOP_PHONE_AUTHORING_SPEC.md`, `LOOP_PACKAGE_SPEC.md`,
`LOOP_MARKETPLACE_CONTRACT.md`, `LOOP_FORK_LINEAGE_CONTRACT.md` — the last three not yet written
under `docs/ratified/loops/` as of this document.

**Not resolved by this document:** whether `FB-RAT-WEB-010` is accepted, rejected, or folded into
an existing ID (Amendments-phase agent); the marketplace privacy specification §8 requires before
any account-gated state ships; the graph-rendering library choice §3 leaves open; and the P1
ratification §7's local-runtime-view relaxation would require before it can ship as more than a
documented target shape.
