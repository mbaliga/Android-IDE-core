# Loop Dual-Surface Architecture

> **License:** `LICENSE-PENDING` — see `docs/non_ratified/LICENSE_PENDING.md`.

**Status:** RATIFIED (this document). **Scope:** the overall two-surface architecture for the
Fonebrew loop system — what the phone app and the browser Web Studio each own, what they share,
how work moves between them, and how each surface degrades when the other is unreachable. This is
the citation target for `FB-RAT-LBX-001` and `FB-RAT-LBX-004`. Per-surface authoring detail
(phone: `LOOP_PHONE_AUTHORING_SPEC.md`, `FB-RAT-LBX-002`/`FB-RAT-PHN-*`; browser:
`LOOP_WEB_STUDIO_SPEC.md`, `FB-RAT-LBX-003`/`FB-RAT-WEB-*`), package byte layout
(`LOOP_PACKAGE_SPEC.md`, `FB-RAT-PKG-*`), import/activation (`LOOP_IMPORT_ACTIVATION_CONTRACT.md`,
`FB-RAT-IMP-*`), compatibility (`LOOP_COMPATIBILITY_CONTRACT.md`, `FB-RAT-CMP-*`), marketplace
(`LOOP_MARKETPLACE_CONTRACT.md`, `FB-RAT-MKT-*`), result sharing
(`LOOP_RESULT_SHARING_CONTRACT.md`, `FB-RAT-RES-*`), and fork lineage
(`LOOP_FORK_LINEAGE_CONTRACT.md`, `FB-RAT-LIN-*`) are each a **separate, sibling normative
document** under `docs/ratified/loops/` — most not yet written as of this document. This document
does not restate their content and does not cite their decision IDs beyond the pointer above.

**Source and cleanup note.** This document is the single-numbered-sequence replacement for the
Appendix E draft `LOOP_DUAL_SURFACE_ARCHITECTURE.md` in the Fonebrew handoff pack's dual-surface
input set, per `10_DUAL_VALIDATION_ADDENDUM.md` §B2 ("every Appendix E spec is a first draft with
a contradictory second pass appended... WP-1L must de-duplicate each spec into one numbered
sequence and re-anchor every register citation"). Unlike most of its 11 siblings in that input
set, **this particular source file was not itself split into a duplicated first/second pass** —
its thirteen `### N.` headings (`1. Objective` through `13. Degraded operation`) form one clean
ascending sequence with no repeated section number and no repeated topic under a later number.
Its defects, applied as corrections below, are narrower and different in kind:

1. **Corrupted global list numbering.** The source's §9 (here, §9 unchanged) numbers its
   conformance checklist 55–61 — a leftover from the handoff pack's whole-document
   docx→markdown list numbering, not a meaningful ordinal (`10_DUAL_VALIDATION_ADDENDUM.md` §H:
   "ordered-list numbering is corrupted throughout the docx→md conversion... item *order* is
   intact — only labels are wrong"). §9 below renumbers it 1–7 as a local, self-contained sequence.
2. **Four terminology gaps**, now closed by the WP-1L-G0 frozen registries. The source names
   "canonicalization and digest algorithm," "validation rule IDs and severities," and
   "compatibility vocabulary" in its shared-semantic-core list (§2 below) without defining any of
   them — `10_DUAL_VALIDATION_ADDENDUM.md` §B1 documents this as one of the pack's four load-bearing
   undefined concepts. §2.1 below reconciles the source's informal terms against the six registries
   `LOOP_FROZEN_CONCEPTS_WP1L_G0.md` froze, per that document's own instruction that the frozen
   registries win over the source pack's naming.
3. **One scope violation.** The source's §12 (unchanged here — this document keeps the source's
   top-level section numbers throughout; only §9's inner list is renumbered, per point 1 above)
   specifies an optional browser-side "package build service," which
   `10_DUAL_VALIDATION_ADDENDUM.md` §B4 identifies as exceeding `FB-RAT-MKT-001`'s marketplace
   exception and reopening the no-backend law (`FB-RAT-INT-003`,
   `docs/ratified/MANUAL_INTEGRATION_GRAMMAR.md`) that this constellation otherwise holds. §12
   below strikes it and states the corrected rule normatively.

No other content changes were needed. Every `MUST`/`SHOULD`/`MAY` below is this document's own
normative language, not a verbatim carry-over from the source's descriptive bullets.

---

## 1. Objective

Fonebrew MUST provide one portable loop system across a phone-first native product and a
browser-first authoring/publishing product. Neither surface MAY treat the other as a subordinate
player, and neither surface MAY depend on hidden or automatic synchronization between them (the
transfer model in §7 and the ownership rules in §11 are the only sanctioned channels).

This is **`FB-RAT-LBX-001` — ACCEPTED — One semantic loop system**: *"Use one canonical loop
language, package model, validation model, and runtime contract across phone and browser
authoring surfaces."* The rest of this document is the architecture that decision implies: what
"one canonical loop language, package model, validation model, and runtime contract" concretely
means once each is registry-defined (§2.1), what each surface owns (§3–§4), and how they stay in
sync without ever being *the same running process* (§5–§13).

## 2. Shared semantic core

Both surfaces MUST implement the same semantics for:

- object identities and semantic versions;
- the LoopDefinition and LoopPackage schemas (schema authoring is WP-1L scope beyond this
  document; not yet written as of this document — see §2.1);
- the canonicalization profile and the two digest algorithms built on it (§2.1);
- validation rule IDs and severities (§2.1);
- the semantic graph diff operation model (specified in `LOOP_FORK_LINEAGE_CONTRACT.md`,
  `FB-RAT-LIN-004`; not restated here);
- fixture and simulation formats (schema authoring is WP-1L scope beyond this document);
- the compatibility vocabulary (§2.1);
- package signature verification (byte format and signature suite are `LOOP_PACKAGE_SPEC.md`
  scope, `FB-RAT-PKG-005`; not yet written as of this document);
- lineage and release identity (specified in `LOOP_FORK_LINEAGE_CONTRACT.md`,
  `FB-RAT-LIN-001`/`FB-RAT-LIN-006`; not restated here);
- terminal-state and receipt vocabulary (a unified `Receipt` envelope is PROPOSED — see §14).

The implementation language MAY differ between the phone and the browser. Conformance MUST be
tested by golden packages and expected digests (§9), never by sharing a compiled binary or
requiring the browser runtime as a phone dependency.

### 2.1 Terminology reconciliation — this document's vocabulary vs. the frozen registries

`LOOP_FROZEN_CONCEPTS_WP1L_G0.md` (WP-1L-G0, GATE CLEARED 2026-08-07) froze six registries under
`schemas/loops/registries/` as the concrete, versioned implementation of concepts §2 names only
informally. Per that document's own status, **the registries win**: where this document's inherited
vocabulary differs from a registry's name or splits a registry's scope differently, the registry
is authoritative and this table is the reconciliation, not a rename of the registry.

| Term as used in §2 (inherited from the source spec) | Frozen registry / registries | `registryId` | Reconciliation |
|---|---|---|---|
| "canonicalization ... algorithm" | `canonicalization.v1.json` | `fb-loop-canon-1` | One canonicalization profile, no "or a versioned equivalent" escape hatch. Both digests below are computed over this profile's output. |
| "... digest algorithm" (source names this as one item) | `semantic-digest.v1.json` **and** `floop-container-format.v1.json`'s `packageContentDigest` | `fb-semantic-digest-1` / (field of `fb-floop-container-1`) | The source's singular "digest algorithm" is actually **two distinct digests that MUST NOT be conflated**: `packageContentDigest` covers the whole archive's canonical file list (path, length, SHA-256 per file); `semanticDigest` covers only `definition.json` minus a versioned presentation/layout exclusion list. A layout-only edit changes the former and MUST NOT change the latter. |
| "validation rule IDs and severities" | `loop-validation-rules.v1.json` | `loop-validation-rules.v1` | Direct match. 24 stable codes across the 10 declared namespaces; rule codes are API — meaning cannot be silently repurposed, severity changes only through a documented `rulesetVersion` bump. |
| "compatibility vocabulary" | `capability-ids.v1.json` **and** `model-capability-vocabulary.v1.json` | `capability-ids.v1` / `model-capability-vocabulary.v1` | The source's single term covers two axes the registries deliberately keep separate: `capability-ids` is the reverse-DNS namespace (`fb.repo.read`, `fb.ci.dispatch`, `fb.device.flash`, …) for the authority/side-effect capability a node requests, cross-referenced to an authority-ladder rung; `model-capability-vocabulary` is the feature-tag vocabulary a model-inference node negotiates against a bound model. A capability request and a model-feature requirement are never the same field. |
| the `.floop` container (implied throughout — see §6, §7) | `floop-container-format.v1.json` | `fb-floop-container-1` | ZIP (stored/deflate only), canonical path rules, required top-level layout. The container **bytes** are frozen; the public-facing `.floop` **extension string** remains `FB-RAT-LBX-010` (EXPERIMENTAL, owner-pending naming/branding review) — freezing the format did not freeze the name. |

None of the six registries are owner-reserved decisions; WP-1L-G0 explicitly authorized freezing
them. Where this document or a sibling names one of these concepts again, it MUST use the
registry's `registryId`/file name, not a fresh synonym.

## 3. Phone responsibilities

The phone MUST be authoritative for:

- local draft ownership and crash recovery;
- capability discovery;
- model, repository, target, device, and secret binding;
- authority grants;
- the final compatibility report (`FB-RAT-CMP-005`, specified in `LOOP_COMPATIBILITY_CONTRACT.md`
  — cited here only as the pointer this document's "final compatibility report" bullet resolves
  to, not restated);
- activation and installation;
- execution and intervention;
- run evidence and receipts;
- local forks and edits;
- explicit result sharing.

Detailed phone-surface UI and interaction design is out of scope for this document — see
`LOOP_PHONE_AUTHORING_SPEC.md`.

## 4. Browser responsibilities

The browser MUST be authoritative only for the state the user deliberately stores there:

- browser draft revisions;
- graph layout and documentation workspace;
- fixtures and simulation outputs;
- package build results (client-side only — see §12);
- publication listing drafts;
- public or private account content, when accounts are enabled.

The browser MUST NOT be authoritative for phone installation, bindings, secrets, authority, or
run state. Detailed browser-surface UI and interaction design is out of scope for this document —
see `LOOP_WEB_STUDIO_SPEC.md`.

## 5. UX divergence

**`FB-RAT-LBX-004` — ACCEPTED — Semantic equivalence, UX divergence**: *"Both surfaces preserve
semantic equivalence but MAY use different information architecture and interaction patterns."*
Semantic equivalence — identical canonicalized definitions, identical digests, identical
validation findings by stable rule code (§9) — does NOT require UI equivalence, and the two
surfaces SHOULD diverge where their input modalities differ:

- the phone SHOULD optimize for vertical reading, interruption resilience, one-handed control,
  sheets, a tap connection grammar, and run intervention;
- the browser SHOULD optimize for large-canvas navigation, bulk editing, matrix tests, multiple
  inspectors, and publishing workflows.

Neither surface's interaction design is normative here; `FB-RAT-LBX-004` licenses the divergence,
it does not specify it. The specifics live in `LOOP_PHONE_AUTHORING_SPEC.md` and
`LOOP_WEB_STUDIO_SPEC.md` respectively.

## 6. Data boundary

Transferable between surfaces:

- declarative package content;
- package identity and signatures;
- explicit listing metadata;
- deliberately shared result receipts;
- fork lineage.

Non-transferable by default — a surface MUST NOT transmit any of the following to the other
surface or to any network endpoint without an explicit, separate user action distinct from an
ordinary transfer (§7):

- API keys and raw secrets;
- Keystore aliases;
- private host/repository credentials;
- local binding profiles;
- private workspace content;
- private prompts and model outputs;
- run logs and artifacts;
- device identifiers.

## 7. Transfer channels

The P0 (v1) transfer channels are:

- a `.floop` file;
- the Android share sheet / "Open with Fonebrew";
- a marketplace or unlisted release URL;
- a QR code containing a URL plus the expected package digest — the QR MUST NOT carry the
  package itself;
- a user-owned Git repository.

Every transfer MUST be user-initiated. A remembered source MAY simplify a later manual import
(e.g., a "check for update" affordance) but MUST NOT be polled automatically — automatic or
background cross-surface synchronization is out of scope for v1 by design, not by omission.

## 8. Failure model

Each surface MUST degrade independently. The following table is authoritative; no surface's
implementation MAY treat a peer's unavailability as a reason to block functionality this table
marks as continuing.

| Condition | Required behavior |
|---|---|
| Browser unavailable | The app MUST remain fully functional for installed packages and local drafts. |
| Marketplace unavailable | Direct file, Git, and QR/URL package transfer MUST remain usable wherever the package bytes are already available. |
| Phone offline | Local authoring, validation, supported simulation, and compatible execution MUST continue. |
| Transfer interrupted | No partial installation MAY result; the receiving surface MUST resume the transfer or restart from a verified snapshot. |
| Schema mismatch | The receiving surface MUST produce a compatibility report and MUST NOT execute the package. |

## 9. Cross-surface golden conformance

For every golden package, both surfaces MUST agree on every row below. (The source draft numbered
this checklist 55–61 — a whole-document docx→markdown list-numbering artifact unrelated to any
other cross-reference in this pack, per `10_DUAL_VALIDATION_ADDENDUM.md` §H. It is renumbered 1–7
here as its own local sequence.)

| # | Step | Cross-surface requirement |
|---|---|---|
| 1 | Import the golden package | Both surfaces MUST accept it without modification. |
| 2 | Export without semantic edits | Both surfaces MUST reproduce byte-identical `definition.json` canonicalization. |
| 3 | Compare canonical digest | `packageContentDigest` MUST match across surfaces (§2.1). |
| 4 | Compare validation findings by stable rule ID | Findings MUST match by `loop-validation-rules.v1` code, not by message text (§2.1). |
| 5 | Compare semantic graph digest | `semanticDigest` MUST match across surfaces (§2.1). |
| 6 | Compare fixture simulation results | Terminal state and declared outputs MUST match. |
| 7 | Confirm phone-side understandability | Phone views MUST remain understandable using only package-carried metadata, without any browser-only metadata. |

## 10. Canonical compiler boundary

Each surface MUST maintain its own local authoring model and presentation state, then invoke a
conforming compiler that emits the canonical definition, the package file inventory, the semantic
digest, the validation report, and the build receipt. The two surfaces' compiler implementations
need not share code, but they MUST share the golden vectors this document's §9 checks against.
This is what keeps the browser runtime from becoming a phone dependency while still preventing
semantic drift between the two compilers — and it is the boundary §12 depends on: a build service
that runs on a browser-side backend rather than through this client-side compiler boundary would
make that backend an unauthorized second source of package identity.

## 11. Ownership rules

- A phone-created draft is owned locally unless explicitly exported or published.
- A browser-created draft is owned by the browser workspace only when deliberately saved; export
  MUST remain possible without phone activation.
- Import MUST create a local snapshot, not a live link.
- Source URLs, marketplace release IDs, and Git refs are provenance only; they MUST NOT be treated
  as synchronization authority.
- Remembering an origin MAY support a manual "check for update" affordance, but background
  polling MUST NOT occur in v1 (same constraint as §7).

## 12. Service decomposition

Recommended browser-side service boundaries are: a static Web Studio client, object storage for
immutable packages, a registry API for listings/releases, a publisher identity/key service, a
review/result service, and a moderation service. None of these services MAY call a private phone
runtime; the phone client MUST talk to them only for an explicit browse, download, publish,
review, or share action initiated by the user.

**Correction applied (`10_DUAL_VALIDATION_ADDENDUM.md` §B4).** The source draft additionally
listed a "package build service or reproducible client build" as one interchangeable option among
these boundaries. That option is struck. **Package build MUST be a reproducible client-side build
on both surfaces; a server-side package build service MUST NOT exist.** Two reasons, both
load-bearing:

1. **It would make the browser backend the compiler §10 exists to prevent being.** If a
   server-side service — not the local, golden-vector-conforming compiler each surface runs
   client-side — produces the package bytes, then §9's cross-surface digest-parity gate
   (`packageContentDigest`, `semanticDigest`) stops proving what it claims to prove: two builds of
   bit-identical input could then legitimately diverge by which backend happened to build them.
2. **It is a scope violation against the constellation's no-backend law.** `FB-RAT-INT-003`
   (`docs/ratified/MANUAL_INTEGRATION_GRAMMAR.md`) rejects shared databases, account backends,
   background services, broadcasts, and automatic sync as integration mechanisms. `FB-RAT-MKT-001`
   (specified in the forthcoming `LOOP_MARKETPLACE_CONTRACT.md`) is understood to carve out a
   narrow, deliberate-publication exception to that law for the marketplace's own listing,
   release, and review storage — not for compilation. A browser-side build service is not a
   deliberately-published artifact; it is infrastructure standing between a user's local draft and
   the package that represents it, which is exactly the shape `FB-RAT-INT-003` exists to prohibit.
   The registry API, object storage, and identity/moderation services listed above stay in scope
   because they store or serve already-built, already-signed, already-published artifacts — they
   do not produce package identity the way a build service would.

The registry API item in this list is P1-scope, not P0 (per `10_DUAL_VALIDATION_ADDENDUM.md` §B2's
resolution of the pack's internal P0/P1 conflict on this point); this document does not restate
the marketplace rollout gate itself — see `docs/release-gates/LOOP_P0_P1_RELEASE_GATES.md`.

## 13. Degraded operation

The app MUST retain local drafts, installed packages, bindings, run history, and export
capability when all web services listed in §12 are unavailable. A browser draft SHOULD be
exportable as a package or source bundle before account sign-in. A listing page SHOULD expose
package digest, signature, permissions, compatibility, and documentation even when interactive
previews fail.

## 14. Cross-references and open items

**Decision IDs cited in this document:** `FB-RAT-LBX-001` (§1, §2), `FB-RAT-LBX-004` (§5).
No other `FB-RAT-*` ID is ratified by this document; `FB-RAT-INT-003` (§12) and the six
`schemas/loops/registries/*.v1.json` registries (§2.1) are cited as already-ratified/already-frozen
context, not re-decided here.

**Registries reconciled, not redefined:** `floop-container-format.v1.json`,
`semantic-digest.v1.json`, `canonicalization.v1.json`, `loop-validation-rules.v1.json`,
`capability-ids.v1.json`, `model-capability-vocabulary.v1.json` — see §2.1. This document does not
duplicate their contents; a conflict between this document's prose and a registry's data is this
document's error, and the registry wins per `LOOP_FROZEN_CONCEPTS_WP1L_G0.md`.

**Sibling documents this document defers to and does not restate:** `LOOP_PHONE_AUTHORING_SPEC.md`,
`LOOP_WEB_STUDIO_SPEC.md`, `LOOP_PACKAGE_SPEC.md`, `LOOP_IMPORT_ACTIVATION_CONTRACT.md`,
`LOOP_COMPATIBILITY_CONTRACT.md`, `LOOP_MARKETPLACE_CONTRACT.md`,
`LOOP_RESULT_SHARING_CONTRACT.md`, `LOOP_FORK_LINEAGE_CONTRACT.md`, and the engine-level
`LOOP_ENGINEERING_SPEC_V2.1` successor document. None of these existed under `docs/ratified/loops/`
as of this document; a reader who needs their content should look for them as separate WP-1L
outputs, not expect this document to have absorbed them.

**PROPOSED, not self-ratified:** a single `Receipt` envelope (`receiptType` enum, typed payload)
in `contracts/kotlin/CommonContracts.kt`, unifying the nine receipt kinds the merged corpus implies
(build, installation, activation, node, run, authority/budget extension, migration, sharing,
consent), per `10_DUAL_VALIDATION_ADDENDUM.md` §E. `CommonContracts.kt` currently has
`ProducerReceiptRef` and forward-pointer interfaces (`ImportReceiptPayload`,
`ExecutionReceiptPayload`) but no unified envelope. This is flagged for the Amendments-phase agent
to assign a decision ID to, not adopted here.
