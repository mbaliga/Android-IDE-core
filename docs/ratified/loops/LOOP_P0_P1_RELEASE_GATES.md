# Loop P0/P1 Release Gates

> **License:** `LICENSE-PENDING` — see `docs/non_ratified/LICENSE_PENDING.md`.

**Status:** This document is the **canonical ratification home for `FB-RAT-PHN-007`** (§3.2 below
— the dual-surface register's own target-doc column names this file for that ID, and
`LOOP_PHONE_AUTHORING_SPEC.md` §15 states the same pointer and defers the acceptance scenario's
formal ratification here). No other `FB-RAT-*` ID is ratified by this document. Every other gate
below cites a decision ID whose canonical ratification home is a sibling document — reused by
reference, checked for exit criteria here, never re-decided here. **Scope:** the measurable exit
criteria that gate a P0 (initial release) or P1 (post-launch) milestone across the whole
dual-surface loop builder — phone, Web Studio, package, import/activation, compatibility,
marketplace, result sharing, and fork lineage. This document does not define any object, digest,
rule code, or state machine — it only states which already-defined behaviors must hold, to what
threshold, before a milestone ships.

## Note on this document's structure

*(Per this work package's file-specific instruction. Four corrections applied, detailed below:
the P0/P1 registry contradiction, the inapplicable ordered-list renumbering instruction, a
performance-figure reconciliation with a sibling document, and a cross-repository path
inconsistency this document cannot fix by itself.)*

The extracted draft this document replaces is **not** duplicate-numbered the way most of this
corpus's other source files are — it uses bare bullet lists throughout, with no numbered sections
and no `§` cross-references of its own to collide. Its first-pass/second-pass defect instead takes
the shape `10_DUAL_VALIDATION_ADDENDUM.md` §B2 describes generally: **the same gate set stated
twice**, once as prose bullets under six-then-four named headings ("P0 measurable gates" /
"P1 measurable gates") and again as a ten-row-then-nine-row **quantitative gate table**, organized
under different area names, at different levels of specificity, and — for three P0 rows (browser
independence, package immutability, secret boundary) and three P1 rows (moderation, hardware loop,
scale) — stating gates the prose pass never mentions at all. Nothing below drops either pass: this
document merges both into one gate catalog per milestone (§3 for P0, §5 for P1), organized by area,
so each measurable exit criterion is stated exactly once, at the more specific of the two source
statements, citing the frozen registry or ratified decision it draws its threshold from.

| Draft heading | This document's section |
|---|---|
| P0 scope (Phone builder, Web Studio) | §2.1, §2.2 |
| P0 scope (Registry) | §2.3 — **CORRECTED**, moved to §4.1 (P1) |
| P0 measurable gates (Semantic equivalence) | §3.1 |
| P0 measurable gates (Phone completeness) | §3.2 |
| P0 measurable gates (Import safety) | §3.4 |
| P0 measurable gates (Package determinism) | §3.10 |
| P0 measurable gates (Accessibility) | §3.6 |
| P0 measurable gates (Performance reference) | §3.11 |
| P0 quantitative gate table (Cross-surface digest, Validation parity) | folded into §3.1 |
| P0 quantitative gate table (Phone completeness, Crash recovery) | folded into §3.2, §3.3 |
| P0 quantitative gate table (Import safety, Authority) | folded into §3.4, §3.5 |
| P0 quantitative gate table (Accessibility) | folded into §3.6 |
| P0 quantitative gate table (Browser independence) | §3.7 — table-only, not in prose pass |
| P0 quantitative gate table (Package immutability) | §3.8 — table-only, not in prose pass |
| P0 quantitative gate table (Secret boundary) | §3.9 — table-only, not in prose pass |
| P1 scope | §4 |
| P1 measurable gates (Marketplace, Result sharing, Fork lineage, Pointer mode) | §5.1–§5.4 |
| P1 quantitative gate table (Marketplace provenance, Reviews) | folded into §5.1 |
| P1 quantitative gate table (Result privacy) | folded into §5.2 |
| P1 quantitative gate table (Fork lineage) | folded into §5.3 |
| P1 quantitative gate table (Moderation) | §5.1 — table-only, not in prose pass |
| P1 quantitative gate table (Hardware loop) | §5.5 — table-only, not in prose pass |
| P1 quantitative gate table (Scale) | §5.6 — table-only, not in prose pass |
| P1 quantitative gate table (Update safety) | §5.7 — table-only, not in prose pass |
| P1 quantitative gate table (Availability) | §5.8 — table-only, not in prose pass |
| No-ship conditions | §6 |
| Exit evidence | §7 |

**Correction 1 — the Registry is P1, not P0 (stated exactly where the draft contradicts it, §2.3
below).** The draft's own "P0 scope" list contains a `#### Registry` block (signed immutable
releases, public/unlisted listing, tags/search, publisher key fingerprint, report action). This
contradicts the draft's own roadmap framing elsewhere in the wider handoff pack (`§82`–`§85` of
the source report, outside this extracted file, which place the registry in the P1 rollout phase)
and `10_DUAL_VALIDATION_ADDENDUM.md` §B2's explicit finding: *"is the registry P0 or P1?
`LOOP_MARKETPLACE_CONTRACT §11` and `LOOP_P0_P1_RELEASE_GATES` 'P0 scope' both contain a Registry
block [implying P0]... Resolve to P1."* This document resolves it to **P1**, for three
independent, load-bearing reasons, not merely the addendum's say-so:

1. **The roadmap and this file's own gate sequencing outvote the two stray P0 mentions.** A P0
   milestone gated on "20 participants complete the phone-only acceptance scenario" and "100
   golden packages produce identical digests on phone and web" (§3.1, §3.2) names no marketplace
   or registry precondition anywhere in its measurable gates — only in the unmeasured scope bullet
   list, which is exactly the kind of unratified aspiration a scope list can carry ahead of what
   its own gates actually require.
2. **The master build brief's hard scope boundary excludes a hosted marketplace service from this
   build-out entirely**, let alone as a P0 milestone — `LOOP_MARKETPLACE_CONTRACT.md`'s header
   states this verbatim: *"Do not build any hosted marketplace service, accounts, reviews, or
   moderation storage... What this build-out actually builds... is exactly and only what §11
   defines: the signed static/Git-backed registry."* A P0 gate cannot depend on a service the
   build-out is not authorized to construct at all.
3. **`FB-RAT-MKT-009` — the decision that defines the registry — was never proposed as P0 in its
   own register row.** `LOOP_MARKETPLACE_CONTRACT.md` §11 promotes `FB-RAT-MKT-009` from
   EXPERIMENTAL to ACCEPTED and states it as *"THE DEFINITION of the marketplace for this
   build-out — not a fallback option, not a P0 stepping-stone toward a fuller service."* Nothing in
   that promotion, or in the decision it promotes, claims P0 status; the promotion is about the
   registry's *design* (static/Git, no backend), not its *milestone*.

The registry's correct placement is §4.1 below, alongside `FB-RAT-MKT-009` (PROMOTED, P1) and
`FB-RAT-MKT-012` (registry replaceability, PROPOSED, P1). Left unresolved, a session reading only
the draft's P0 scope list would start building a hosted service the roadmap, the master build
brief, and the decision's own register row all agree does not belong in P0 — this is the exact
failure mode `10_DUAL_VALIDATION_ADDENDUM.md` §B2 warns against.

**Correction 2 — the corrupted ordered-list renumbering instruction does not apply to this
document, verified, not silently skipped.** `10_DUAL_VALIDATION_ADDENDUM.md`'s closing note lists
five ordered lists across the handoff pack with corrupted numeric labels from the docx→markdown
conversion: node categories (rendered as items 37–44, should be 1–8), build steps (45–54, should
be 1–10), import steps (75–84, should be 1–10), redaction steps (90–98, should be 1–9), and owner
sign-off items (17–26, should be 1–10). This document's own source file was searched for all five
patterns and for any numbered list at all (`grep -nE "^[0-9]+\."` over the extracted draft returns
no matches) — **none of the five corrupted lists occur in this file.** They belong to, and are the
responsibility of, the sessions assigned to their actual source files: node categories to
`LOOP_ENGINEERING_SPEC_V2.1`, build steps to `LOOP_PHONE_AUTHORING_SPEC` (draft §64, confirmed by
grep), import steps to `LOOP_IMPORT_ACTIVATION_CONTRACT`, redaction steps to
`LOOP_RESULT_SHARING_CONTRACT`, and owner sign-off items most likely to `LOOP_END_TO_END_JOURNEYS`
or `LOOP_MARKETPLACE_CONTRACT`. This document states the verification rather than silently
dropping the instruction, per this corpus's honesty convention.

**Correction 3 — performance-reference figures reconciled with `LOOP_PHONE_AUTHORING_SPEC.md`
§17, not restated as a second, competing set.** The draft's P0 "Performance reference" bullets
(Stage View 60 fps at 200 visible stages; Graph overview interactive at 500 nodes; edit journal
acknowledgement p95 <100 ms; local 20 MB package validation p95 <3 seconds) are exactly the figures
`LOOP_PHONE_AUTHORING_SPEC.md` §17 already flags as conflicting with its own authoritative phone
performance budget table (Graph View 60 fps at 250 nodes degrading to 500; autosave acknowledgement
under 150 ms; package import preview under 2 seconds excluding network download) — that document
states plainly: *"That other figure is not ratified by this document and MUST NOT be quoted as an
alternative to the table above until the two are reconciled into one set — reconciliation is out of
scope for this document and is flagged here, not resolved here."* `10_DUAL_VALIDATION_ADDENDUM.md`
§H names the same conflict and asks for reconciliation before either set is quoted. This document
is the other half of that conflict, so it performs the reconciliation §17 deferred: §3.11 below
does **not** restate the draft's four figures as a second, competing performance gate. It states the
performance-reference gate as a pointer to `LOOP_PHONE_AUTHORING_SPEC.md` §17 — the single
authoritative phone performance budget — and records which of the draft's four figures §17 already
covers (under different, reconciled numbers) and which it does not cover at all.

**Correction 4 — a cross-repository path inconsistency, flagged, not silently fixed.** Two
already-ratified sibling documents — `LOOP_DUAL_SURFACE_ARCHITECTURE.md` §12 and
`LOOP_PHONE_AUTHORING_SPEC.md` §15 — forward-point to this document at
`docs/release-gates/LOOP_P0_P1_RELEASE_GATES.md`, matching the path the dual-surface register's own
`FB-RAT-PHN-007` row and `10_DUAL_VALIDATION_ADDENDUM.md`'s merged artifact list (§E) both use. This
work package's explicit build instruction places this file at
**`docs/ratified/loops/LOOP_P0_P1_RELEASE_GATES.md`** instead — alongside all nine other
dual-surface contracts and `LOOP_FROZEN_CONCEPTS_WP1L_G0.md`, which is also where the other nine
contracts' own cross-references to each other already resolve. This document is written at the path
its own build instruction specifies; it does not edit the two sibling files carrying the stale
`docs/release-gates/` pointer, because doing so is outside this document's assignment. **This is
flagged here for the Amendments-phase agent**, and is restated in this task's final report: the two
stale forward pointers should be corrected to `docs/ratified/loops/LOOP_P0_P1_RELEASE_GATES.md` to
match where this file actually lives.

---

## 1. Purpose and normative force

MUST/SHOULD/MAY are used throughout per `FB-RAT-COM-001` (`docs/ratified/COMMON_CONVENTIONS.md`
§1) and are not redefined here. A **gate** in this document is a measurable, testable exit
criterion that blocks a milestone until met; a **target** or **budget** (§3.11) is a
verify-rather-than-assume figure for owner confirmation on real hardware, not itself a pass/fail
release blocker in this build container. This distinction matters concretely: this repository
compiles and JVM-tests everything but has **no device, emulator, board, or SSH host**
(`CLAUDE.md` "Environment honesty," binding rule 6) — every gate below that requires a real phone,
browser, or hardware board is stated as a MUST for the milestone, but its current status in this
container is "specified and testable," not "verified passing." §7 states the evidence discipline
that keeps that distinction honest at exit time.

Two milestones are gated: **P0** (§2 scope, §3 gates) is the initial release; **P1** (§4 scope,
§5 gates) is the first post-launch milestone. §6 states conditions that block shipping *any*
milestone regardless of P0/P1 classification. Gates in this document specialize, and do not
redefine, the decisions and registries they cite — a conflict between this document's prose and a
cited registry's data or a cited decision's ratified text is this document's error, and the other
side wins, per `LOOP_FROZEN_CONCEPTS_WP1L_G0.md`'s standing rule.

## 2. P0 scope

### 2.1 Phone builder (P0)

The phone builder MUST ship, at P0: Intent, Stage, Graph, Node Sheet, and Run views
(`FB-RAT-PHN-001`); the tap connection grammar and its accessible alternatives
(`FB-RAT-PHN-003` REJECTED for drag-as-primary, `FB-RAT-PHN-004` ACCEPTED for the tap grammar);
Distiller draft creation and semantic diff review (`FB-RAT-PHN-006`); static validation, fixture
simulation, and run receipts; package import/export and immutable installation
(`LOOP_IMPORT_ACTIVATION_CONTRACT.md`); local binding/authority activation
(`FB-RAT-AUTH-002`, `FB-RAT-IMP-005`); and complete phone-only editing and repair — the base
claim `FB-RAT-PHN-007` (§3.2) makes measurable.

### 2.2 Web Studio (P0)

Web Studio MUST ship, at P0: the dense graph editor and inspectors (`FB-RAT-LBX-003`); schema,
binding, and capability editors; fixture simulation and fault tests; a deterministic package build
on both surfaces, never a server-side build service (`LOOP_DUAL_SURFACE_ARCHITECTURE.md` §12,
correction applied there); download, unlisted-URL, and QR transfer (QR carries the URL and
expected digest, never the package bytes); and a phone preview of the built package before
publication (`LOOP_WEB_STUDIO_SPEC.md` §12).

### 2.3 Registry — NOT P0 (CORRECTED)

*(This is the exact point where the draft's own P0 scope list contained a `#### Registry` block —
signed immutable releases, basic public/unlisted listing, tags/search, publisher key fingerprint,
report action, no ratings or public aggregates required. That block is removed from P0 scope here,
not silently — see "Correction 1" above for the full reasoning.)*

**CORRECTED: the registry (marketplace) is a P1 milestone item, not P0.** No hosted marketplace
service, listing index, or publisher-key infrastructure is a P0 exit criterion for this build-out.
The registry's content — now correctly scoped — is restated at §4.1, where it belongs alongside
the P1 decisions that actually govern it (`FB-RAT-MKT-009` PROMOTED, `FB-RAT-MKT-012` PROPOSED).
No P0 gate in §3 depends on registry availability; §3.7 (browser independence) and §3.8 (package
immutability) are stated so that they hold **regardless of whether a registry exists at all**,
which is the intended P0-time invariant a mis-scoped P0 registry item would have obscured.

## 3. P0 gate catalog

Ten gates. Each MUST hold before P0 ships. Ordered by the draft's own quantitative-gate-table
sequence, with the corresponding prose-pass detail folded in and duplicate coverage removed.

### 3.1 Semantic equivalence (cross-surface digest and validation parity)

- **Cross-surface digest.** 100 golden packages MUST produce identical `packageContentDigest` and
  identical `semanticDigest` on phone and Web Studio (`floop-container-format.v1.json`,
  `semantic-digest.v1.json`, `canonicalization.v1.json` — `fb-loop-canon-1`). This is
  `LOOP_DUAL_SURFACE_ARCHITECTURE.md` §9's cross-surface golden conformance checklist, rows 2, 3,
  and 5, and `LOOP_PACKAGE_SPEC.md` §5's `BUILD_VERIFIED_DETERMINISTIC` outcome (not restated
  here — see that section's from-state/event/to-state table), machine-proven for the reference
  canonicalization implementation over 1,000 repeated runs
  (`LOOP_FROZEN_CONCEPTS_WP1L_G0.md`).
- **Validation parity.** Validation finding sets MUST match by stable `loop-validation-rules.v1`
  rule code, never by message text, except documented platform-only findings
  (`LOOP_DUAL_SURFACE_ARCHITECTURE.md` §9 row 4).
- **Import/export round trip.** A round trip MUST lose zero supported semantic fields and MUST
  preserve unknown safe minor fields and stable IDs (`LOOP_WEB_STUDIO_SPEC.md` §11) — required
  behavior, because it is what lets a newer package schema pass safely through an older
  implementation of either surface (`FB-RAT-COM-006` forward-compatibility framing).
- **Status of the two-implementation obligation.** This gate is currently checked against **two**
  independently hand-written implementations reconciled by shared golden vectors
  (`LOOP_DUAL_SURFACE_ARCHITECTURE.md` §10). `FB-RAT-WEB-010` (`LOOP_WEB_STUDIO_SPEC.md` §9,
  PROPOSED, not yet ratified) would replace this with one implementation compiled to both
  surfaces, reducing the golden corpus to a regression suite rather than a reconciliation
  mechanism. **Until `FB-RAT-WEB-010` is ratified, rejected, or folded into another ID, this gate
  is checked as written: full digest-and-validation parity between two independent
  implementations.** If `FB-RAT-WEB-010` is ratified, this gate's mechanism changes but its
  threshold (100% match) does not.

### 3.2 Phone completeness — `FB-RAT-PHN-007`, ratified here

**`FB-RAT-PHN-007` — ACCEPTED.** *"A user MUST be able to create, validate, simulate, bind, run,
repair, fork, and export a branching loop without browser, monitor, mouse, or keyboard."* This
document is this decision's canonical ratification home, per the dual-surface register's own
target-doc column and `LOOP_PHONE_AUTHORING_SPEC.md` §15's forward pointer; the acceptance
scenario that operationalizes the gate is specified in full at `LOOP_PHONE_AUTHORING_SPEC.md`
§15.1 (the ten-step base scenario: create a loop with at least seven nodes, one gateway, one
bounded retry cycle, one human decision, one model slot, and one typed tool; validate; repair;
run fixtures; bind a local model and a CI target; approve scoped authority; execute; inspect
receipts; fork; export) and §15.2 (the amendment requiring phone-side authoring of a two-field
JSON Schema with an `enum` and one other constraint, a prompt field over 500 characters, and
documentation fields — the amendment that makes `FB-RAT-PHN-007` actually tested where authoring
is hardest, corrected in `LOOP_PHONE_AUTHORING_SPEC.md` per this same handoff pack's finding that
the base scenario never exercised schema, long-prompt, or documentation authoring). This document
does not restate that scenario; it states the measurable exit criteria the scenario is scored
against:

- 20 participants MUST complete the phone-only acceptance scenario (§15.1 as amended by §15.2);
  ≥90% MUST complete it without facilitator intervention after onboarding.
- No required action in the scenario MAY depend on drag precision, hover, hardware keyboard, or
  external display (`FB-RAT-PHN-003`, `FB-RAT-PHN-010` EXPERIMENTAL pointer-mode features excluded
  from any required path).
- Median time to connect two existing stages MUST be ≤20 seconds after the first-run tutorial.

### 3.3 Crash recovery and draft durability

100 forced process deaths at randomized authoring states MUST lose zero accepted draft revisions.
Drafts are append-only revisions (`FB-RAT-COM-006`, append-only facts with idempotency keys); a
durability gate without a reversibility gate is a distinct concern this document does not own —
`LOOP_PHONE_AUTHORING_SPEC.md`'s undo/redo requirement covers reversibility and is cited there, not
here. This gate covers loss, not reversibility: an accepted revision surviving a forced death MUST
still be exactly the revision that was accepted, never a partially-written one.

### 3.4 Import safety

100% of the following fixture classes MUST fail before activation, with zero model, tool, network,
or device calls observed during the attempt:

- Every adversarial class `floop-container-format.v1.json`'s `pathRules.adversarialClasses_MUST_
  reject` enumerates: absolute path entry, path traversal, duplicate normalized paths, symlink
  escape, decompression-ratio bomb, declared/actual length mismatch, declared/actual digest
  mismatch, unknown major schema version.
- Invalid signature and forbidden-executable-payload fixtures — rule codes `LOOP-PKG-002`
  (forbidden executable payload) and `LOOP-PKG-003` (path/canonicalization error) in
  `loop-validation-rules.v1.json`.
- Secret and Unicode-smuggling fixtures — rule codes `LOOP-PKG-001` (secret-like pattern) and
  `LOOP-PKG-006` (invisible/control Unicode, non-dismissible).

**Zero model/tool/network/device calls during import** is not a separate aspiration — it is rule
code `LOOP-PKG-004` in `loop-validation-rules.v1.json` (*"Import/preview pipeline observably
invoked model, tool, shell, remote, or device access... before activation"*), and this gate's
threshold is that `LOOP-PKG-004` fires zero times across the full import fixture corpus. The
eleven-state import/activation machine these fixtures are checked against (`ACQUIRING` through
`INSTALLED`, five distinct terminal conditions) is owned and stated in full by
`LOOP_IMPORT_ACTIVATION_CONTRACT.md` §3 — not restated here.

### 3.5 Authority

Authority widening MUST always force a fresh approval, and no package request MAY become a grant
without an approval receipt (`FB-RAT-AUTH-002` authority ladder; `FB-RAT-AUTH-004` no implicit
privilege expansion; `FB-RAT-IMP-005`, `LOOP_IMPORT_ACTIVATION_CONTRACT.md` §6). Rule code
`LOOP-CAP-002` (*"Binding for slot would WIDEN authority beyond the package's declared
request"*) MUST fire on every binding-time widening attempt in the fixture corpus, with 100%
detection. Editing an installation MUST NOT change its stored package digest — rule code
`LOOP-ID-002` (*"Attempted mutation of installed package bytes"*) is the same invariant §3.8 states
for published releases, applied here to local installations specifically.

### 3.6 Accessibility

All authoring and import actions MUST be operable through taps and TalkBack; branch/rejoin and
run states MUST have textual semantics (`FB-RAT-COM-009`, accessibility semantics); no color-only
signal MAY be the sole carrier of validation, provenance, authority, or node state. `FB-RAT-PHN-005`
is the decision this specializes, split in `LOOP_PHONE_AUTHORING_SPEC.md` §12.3 into five named,
separately sized deliverables (semantics contract for stage/branch/rejoin; a custom-action
inventory per structural verb; a screen-reader outline view; a TalkBack + Switch Access +
200%-font matrix; a recorded TalkBack-only run of the §3.2 acceptance scenario) rather than one
undersized bullet — this gate is met only when all five are met, not when the general claim reads
plausible.

### 3.7 Browser independence

Installed loops and local drafts MUST remain usable during total web/marketplace outage — no P0
gate above may be read as depending on registry or marketplace reachability, consistent with §2.3's
correction. This is `LOOP_DUAL_SURFACE_ARCHITECTURE.md` §8's failure-model table (*"Browser
unavailable: the app MUST remain fully functional for installed packages and local drafts"*;
*"Marketplace unavailable: direct file, Git, and QR/URL package transfer MUST remain usable"*) and
§13's degraded-operation guarantee, applied here as an exit gate rather than an architectural
property alone. `LOOP_MARKETPLACE_CONTRACT.md` §2 and §13 state the same invariant from the
marketplace side and cross-reference this section by name.

### 3.8 Package immutability

Published bytes for a release identity MUST NOT change; a digest mismatch MUST be a hard failure,
never a warning. This is rule code `LOOP-ID-002` (*"Attempted mutation of installed package bytes
for release {loopId}@{semanticVersion} (digest {packageDigest})"*) in
`loop-validation-rules.v1.json`, and the same digest-is-identity principle `LOOP_PACKAGE_SPEC.md`
§4 freezes for `packageContentDigest`/`semanticDigest` and `LOOP_FORK_LINEAGE_CONTRACT.md` §3
(`FB-RAT-LIN-001`) applies at fork time — editing an installed release MUST create a new draft,
never mutate the installed one. §5.1 below (P1, `FB-RAT-MKT-003`) extends this same invariant to
published marketplace releases specifically; this P0 gate is the general case that holds with or
without a marketplace.

### 3.9 Secret boundary

Zero raw secrets or local secret aliases MAY appear in package, listing, shared-result, or test
fixtures. This is rule code `LOOP-PKG-001` (secret-like pattern match) in
`loop-validation-rules.v1.json`, scanned with a named tool — gitleaks, per
`LOOP_PACKAGE_SPEC.md` §7 and `LOOP_MARKETPLACE_CONTRACT.md` §10's identical citation, the same
tool named once and reused everywhere it is required. Secrets MUST be resolved by reference at
runtime (a Keystore-held handle, `fb.secret.use` in `capability-ids.v1.json`), never embedded —
the binding-placeholder mechanism `LOOP_PACKAGE_SPEC.md` §9 defines is precisely that
reference-by-name alternative.

### 3.10 Package determinism

Repeated builds from identical canonical source MUST produce identical `packageContentDigest` and
`semanticDigest` across 1,000 runs; browser and phone builders MUST agree on canonical digest for
all golden fixtures. This is not a target — it is `semantic-digest.v1.json`'s stated conformance
rule, already machine-proven for the reference canonicalization implementation
(`LOOP_FROZEN_CONCEPTS_WP1L_G0.md`, "Proof so far"), and it is exactly the five-state build
machine — `BUILD_STARTED`, `DIGESTS_COMPUTED`, `DIGESTS_COMPARED`, terminating at either
`BUILD_VERIFIED_DETERMINISTIC` or `BUILD_NONDETERMINISM_DETECTED`, then `RECEIPT_RECORDED` —
`LOOP_PACKAGE_SPEC.md` §5 states in full as a from-state/event/to-state table (not restated here).
§3.1's cross-surface digest gate and this gate check the same
underlying invariant from two angles — repeatability on one surface (this gate) and agreement
across both surfaces (§3.1) — and both MUST hold independently; one passing does not imply the
other.

### 3.11 Performance reference

*(Reconciled with `LOOP_PHONE_AUTHORING_SPEC.md` §17 per "Correction 3" above — this section is a
pointer, not a second figure set.)*

**The authoritative phone performance budget is `LOOP_PHONE_AUTHORING_SPEC.md` §17.** This gate
requires that budget be verified on the owner's reference phone before P0 ships; it does not
restate the numbers. For traceability against the draft's four original figures:

| Draft figure (this document's source draft) | Status against `LOOP_PHONE_AUTHORING_SPEC.md` §17 |
|---|---|
| Stage View 60 fps for 200 visible stages with virtualization | Superseded — §17 states Graph View 60 fps for 250 visible nodes, degrading to 500, as the reconciled figure for graph-density rendering; no separate Stage View fps budget is carried forward. |
| Graph overview interactive at 500 nodes | Consistent — §17's Graph View budget already degrades to exactly 500 nodes; not a second figure, the same one. |
| Accepted edit journal acknowledgement p95 <100 ms | Not directly covered — §17's nearest figure is autosave acknowledgement under 150 ms, a related but distinct metric (autosave vs. edit-journal event ack). Not reconciled to a single number by this document; §17 remains authoritative until a future revision states the journal-specific figure explicitly. |
| Local package validation of a 20 MB package p95 <3 seconds | Not directly covered — §17's nearest figures are "validation first blocker surfaced under 750 ms" and "package import preview under 2 seconds, excluding network download," neither of which is the same measurement as validating a 20 MB package end-to-end. Not reconciled to a single number by this document. |

None of these figures are shipped claims; this build container has no device or emulator
(`CLAUDE.md` "Environment honesty," binding rule 6), so none of them — reconciled or not — have
been measured yet. §7 states the evidence discipline this gate is exit-checked against.

## 4. P1 scope

### 4.1 Registry (marketplace) — correctly placed here, per §2.3

The registry MUST ship, at P1, as **exactly** the mechanism `LOOP_MARKETPLACE_CONTRACT.md` §11
defines under `FB-RAT-MKT-009` (PROMOTED from EXPERIMENTAL to ACCEPTED, and stated there as *"THE
DEFINITION of the marketplace for this build-out"*): a signed JSON index in a public Git
repository; `.floop` package assets hosted on publishers' own GitHub Releases, never
Fonebrew-hosted; a static browse site generated from the index at build time; and submission by
pull request, gated by the three named checks §5.1 below states as a gate. Signed immutable
releases, basic public/unlisted listing, tags/search, publisher key fingerprint, and a report
action — the draft's original P0 Registry bullets — are all present in this P1 definition; only
their milestone changed. Registry base URL configurability and full client disablement
(`FB-RAT-MKT-012`, PROPOSED, not self-ratified — `LOOP_MARKETPLACE_CONTRACT.md` §12) are also P1
scope, not P0.

### 4.2 Pointer and external-display mode

Pointer/external-display multi-pane mode (`FB-RAT-PHN-010`, EXPERIMENTAL, not yet
owner-verified) MUST ship as an accelerator layer only — `LOOP_PHONE_AUTHORING_SPEC.md` states the
prohibition this scope item depends on: no reachable app state may exist only under
`POINTER_LAYOUT` with no phone-reachable equivalent.

### 4.3 Marketplace, sharing, and lineage surfaces

P1 scope also includes: public marketplace profiles and structured reviews (`FB-RAT-MKT-006`, not
built as a hosted service this pass — `LOOP_MARKETPLACE_CONTRACT.md` §8 states the interaction
contract a future optional backend follows); fork lineage and selective upstream apply
(`FB-RAT-LIN-001` through `FB-RAT-LIN-006`); explicit shared results (`FB-RAT-RES-001` through
`FB-RAT-RES-006`); compatibility evidence and curated collections (`FB-RAT-CMP-007`
EXPERIMENTAL; `FB-RAT-MKT-011` DEFERRED for personalized recommendations specifically — curation
and explicit filters remain in scope, ranking does not); advanced test matrices, branch coverage,
and replay import; and optional encrypted paired transfer after separate approval.

## 5. P1 gate catalog

Eight gates. Each MUST hold before P1 ships.

### 5.1 Marketplace

No published package MAY bypass schema, secret, executable, signature, or policy checks. This is
`LOOP_MARKETPLACE_CONTRACT.md` §10's three named, deterministic-or-heuristic checks
(`FB-RAT-MKT-007`, corrected — archive safety against `floop-container-format.v1.json`'s
adversarial classes and rule codes `LOOP-PKG-002`/`LOOP-PKG-003`; secret scanning via gitleaks,
rule code `LOOP-PKG-001`; prompt-risk review, rule code `LOOP-PKG-008`, heuristic, MUST NOT
auto-reject on its own), gating the transition from `PENDING` to `PUBLISHED` in that section's
state table (not restated here). Every stable release MUST carry a valid package digest, publisher
signature, license, compatibility declaration, and immutable download (`FB-RAT-MKT-003`,
listing/release model, §3.8's package-immutability invariant applied to published releases
specifically). Reviews MUST bind to an exact release, never a listing or `loopId` alone
(`FB-RAT-MKT-006`, §4 no-migration-by-loopId-match rule); verified badges MUST require
corresponding installation/result references. Report, triage, delist, key-revoke, appeal, and
local-notice journeys MUST pass without any remote-deletion capability — `LOOP_MARKETPLACE_
CONTRACT.md` §10's `PENDING`/`PUBLISHED`/`LIMITED`/`DELISTED`/`TAKEDOWN`/`KEY_REVOKED` state table,
realized for the actual static/Git registry by that section's "mapping §10's moderation contract
onto what is actually built" table (§11) — a GitHub issue and a follow-up PR, no moderation
storage. **This gate's takedown/key-revocation SLO is not yet measurable**: no numeric SLO exists
yet — `LOOP_MARKETPLACE_CONTRACT.md` §15 states plainly that a written moderation SLA is one of
the Play-policy-layer pieces *"this session is not authorized to write."* Until that SLA exists,
this sub-gate is stated as a MUST with an undefined threshold, not silently omitted; offline
clients MUST display data freshness regardless.

### 5.2 Result sharing

Default exports MUST contain zero seeded private identifiers across an adversarial corpus —
`FB-RAT-RES-003`'s default denylist (exclude prompts, responses, repository and file identities).
Preview and final serialized bytes MUST match field-for-field — `FB-RAT-RES-006`'s share-flow
state machine (`LOOP_RESULT_SHARING_CONTRACT.md` §3) structurally guarantees this: any change
after `PREVIEWED` re-enters the pipeline at `ALLOWLISTED_SUMMARY_BUILT` and invalidates prior
consent, so a `CONSENT_OBTAINED` receipt can never diverge from what was shown. Signed receipt
verification MUST succeed across an independent implementation, using the corrected claim
`FB-RAT-RES-004` states: the label is `SELF_SIGNED_RECEIPT`, not `RUNTIME_ATTESTED`, and the only
claim a verifier MAY draw is that the same signing key produced this receipt and any others under
it — pseudonymous continuity, never runtime authenticity or correctness
(`10_DUAL_VALIDATION_ADDENDUM.md` §B5's correction, already applied at its canonical home).
Correctness claims MUST NOT render without verifier evidence state — `FB-RAT-RES-005`, and rule
code `LOOP-VERIFY-002` (*"Terminal state claims SUCCEEDED_VERIFIED but verifier has no passing
result tied to this run's inputs/digest/artifacts"*) in `loop-validation-rules.v1.json` is the
mechanism that makes this checkable.

### 5.3 Fork lineage

Fork creation MUST preserve parent identity, digest, and license — `FB-RAT-LIN-001` (fork identity
and creation: editing an immutable release creates a new draft, never mutates the parent) and
`FB-RAT-LIN-002` (attribution and license provenance), rule code `LOOP-LINEAGE-001` (missing
parent attribution) and `LOOP-LINEAGE-002` (incompatible declared license). Semantic diff MUST
classify all golden changes correctly — `FB-RAT-LIN-004`'s nine-category comparison
(`LOOP_FORK_LINEAGE_CONTRACT.md` §8). Authority widening and verification weakening MUST receive
high-risk classification in 100% of fixtures — `FB-RAT-LIN-005` (§9), pulled into a separate,
always-visible, blocking review group independent of ordinary changes; this is the same invariant
rule code `LOOP-COMPAT-002` enforces at install/update time (`FB-RAT-IMP-008`,
`LOOP_IMPORT_ACTIVATION_CONTRACT.md` §9) — one invariant, two enforcement points, checked here at
comparison time. Selective apply MUST NOT mutate the parent or bypass final validation —
`LOOP_FORK_LINEAGE_CONTRACT.md` §10's upstream-comparison/selective-apply state machine (from
`APPLYING_OPERATION` to `VALIDATING_REVISION`, with a failed validation returning to
`SELECTING_OPERATIONS` by way of `OPERATION_REJECTED`, never silently committing) is the
mechanism, not restated here; §6's no-forced-merge rule (`FB-RAT-LIN-003`) is the invariant that state machine
encodes.

### 5.4 Pointer mode

All pointer actions MUST retain touch equivalents — the same prohibition §4.2 states as scope
(`FB-RAT-PHN-010`). Monitor disconnect MUST lose zero draft/run state —
`LOOP_PHONE_AUTHORING_SPEC.md`'s transition table from `POINTER_LAYOUT` to `PHONE_LAYOUT` states
this MUST restore an understandable phone layout with no data loss (not restated here). Keyboard-only
authoring MUST cover graph, inspector, tests, package, and publication preparation
(`LOOP_WEB_STUDIO_SPEC.md` §10's publication-preparation checklist).

### 5.5 Hardware loop

A web-authored build/flash/verify loop MUST transfer to, and execute from, the phone with device
preflight and evidence. This gate composes three already-ratified pieces from outside the
dual-surface register, none redefined here: `FB-RAT-DEV-001` (one Device Broker arbitrating
flash/serial/debug/USB permission, `DEVICE_STATE_AND_SAFETY_SPEC.md` §1); the flash contract's
mandatory preflight step (`FB-RAT-DEV-004`, board/firmware/layout/power validation before any
destructive action, `DEVICE_STATE_AND_SAFETY_SPEC.md` §4) and verification-before-good-state rule
(`FB-RAT-DEV-005`); and real-device conformance (`FB-RAT-DEV-008`, protocol unit tests are
insufficient on their own). The loop-domain capability that reaches this authority rung is
`fb.device.flash` (`EXECUTE_DESTRUCTIVE`, `capability-ids.v1.json`) — a loop requesting it MUST
clear this document's §3.5/§5.3 authority-widening gates before the device-layer preflight ever
runs.

### 5.6 Scale

Phone authoring MUST remain usable at a 500-node graph; Web Studio MUST remain usable at a
2,000-node reference graph, with documented grouping requirements. The phone figure is consistent
with, not a duplicate of, `LOOP_PHONE_AUTHORING_SPEC.md` §17's P0 performance budget (Graph View
60 fps at 250 nodes, degrading to 500) — §17's degraded-rendering ceiling and this gate's usability
floor name the same number for the same reason, and §3.11 above does not need to reconcile them
further.

### 5.7 Update safety

Authority widening and verifier weakening MUST be detected in 100% of golden update cases. This is
the same rule code §5.3 cites for fork comparisons, applied here at install/update time: `FB-RAT-
IMP-008` (`LOOP_IMPORT_ACTIVATION_CONTRACT.md` §9) and rule code `LOOP-COMPAT-002` in
`loop-validation-rules.v1.json` (*"Update widens authority, weakens a verifier, adds an external
target, adds a secret slot, increases budget, or adds a destructive path without fresh
approval"*) — one invariant, checked at two points (`LOOP_FORK_LINEAGE_CONTRACT.md` §9's
comparison-time enforcement is §5.3; this is the install-time enforcement), never two independent
rules that could drift apart.

### 5.8 Availability

Direct file/Git distribution and local execution MUST remain possible during a total marketplace
outage — the P1 extension of §3.7's P0 browser-independence gate, applied specifically to the
registry once it exists (`LOOP_MARKETPLACE_CONTRACT.md` §2, §13: total marketplace or registry
failure MUST NOT prevent direct Git or file distribution; package identity is the content digest,
never a database row). This gate is satisfied by construction, not by a fallback path added later:
the registry §4.1 defines has no database and no accounts to fail — `FB-RAT-MKT-012`'s
replaceability guarantee (registry base URL configurable, registry client fully disablable, index
format published) is the mechanism that keeps this gate true even if a specific registry instance
disappears entirely.

## 6. No-ship conditions

The following MUST NOT be true of any shipped milestone, P0 or P1, regardless of which gates above
otherwise pass — each is a hard failure, not a severity-weighted finding:

| Condition | Decision or rule this violates |
|---|---|
| A browser is required to edit an imported loop. | `FB-RAT-LBX-002` — phone-primary product law; complete phone-only editing is §2.1/§3.2 scope, not an optional path. |
| A package executes during import. | Rule code `LOOP-PKG-004` — import/preview MUST NOT observably invoke model, tool, shell, remote, or device access before activation (§3.4). |
| A marketplace package contains executable code. | `FB-RAT-MKT-004` — the marketplace distributes LoopPackages, not runtime plugins or executable extensions; rule code `LOOP-PKG-002` (§3.4, §5.1). |
| Authority is granted from publisher reputation alone. | `FB-RAT-MKT-005` — identity does not confer authority on the phone; the phone MUST always perform its own validation and authority review regardless of publisher verification level. |
| Run evidence is uploaded automatically. | `FB-RAT-RES-001` (share only through an explicit Share Result action) and `FB-RAT-MKT-008` (marketplace contact MUST NOT upload private usage, execution logs, prompts, files, model outputs, or run results automatically). |
| Cross-surface digest disagreement is present. | §3.1 — this is a conformance failure to fix, never a warning to suppress; `LOOP_PACKAGE_SPEC.md` §5 states a mismatch means the two compilers disagree about semantics, not that one build is "newer." |
| An installed release is mutated in place. | Rule code `LOOP-ID-002` (§3.8) — digest mismatch on an installed release is a hard failure by construction, not a policy choice a milestone could waive. |

## 7. Exit evidence

Each gate above requires, at exit: a reproducible test identifier; the target device/browser
matrix it was run against; the build commit; the package digest(s) involved; output receipts;
failures, if any, recorded rather than silently cleared; and an explicit owner-verification
status. **A screenshot or a demo is not sufficient evidence for a semantic, safety, or recovery
gate** (§3.1, §3.3, §3.4, §3.5, §3.8, §5.2, §5.3, §5.7) — those require the reproducible artifact
itself, not a recording of one run.

This corpus's standing eight-class conformance discipline (`FB-RAT-COM-010`/`FB-RAT-DIST-004`,
`docs/ratified/COMMON_CONVENTIONS.md` §11) applies here without modification: gates whose evidence
is JVM-testable in this container (digest determinism, adversarial archive rejection,
no-execution-during-import, authority-widening detection, unknown-field round-trip, validation
determinism, semantic-diff classification, default-share privacy, Unicode-smuggling rejection —
`10_DUAL_VALIDATION_ADDENDUM.md` §H's own classification) MAY be exit-checked in-session; gates
requiring a human study (§3.2's 20-participant scenario), a reference phone
(§3.11, §5.6), a live registry (§5.1, §5.8), or a real device board (§5.5) MUST be recorded as
**owner-verification pending**, never as passing, until the owner performs and records that
verification on real hardware — consistent with this repository's standing environment-honesty
rule (`CLAUDE.md` binding rule 6): never claim on-device, on-hardware, or human-study behavior
works from inside this build container.

## 8. Cross-references and open items

**Decision IDs cited in this document:** `FB-RAT-PHN-007` (§3.2, **ACCEPTED, ratified here**).
No other ID is ratified by this document. Cited as already-ratified context from sibling
documents, not re-decided here: `FB-RAT-LBX-001`/`FB-RAT-LBX-002`/`FB-RAT-LBX-003`
(`LOOP_DUAL_SURFACE_ARCHITECTURE.md`, `LOOP_WEB_STUDIO_SPEC.md`), `FB-RAT-PHN-001`/`003`/`004`/
`005`/`006`/`010` (`LOOP_PHONE_AUTHORING_SPEC.md`), `FB-RAT-WEB-010` (`LOOP_WEB_STUDIO_SPEC.md`
§9, **PROPOSED**, not yet ratified — §3.1 notes its effect if ratified), `FB-RAT-PKG-004`/`007`
(`LOOP_PACKAGE_SPEC.md`), `FB-RAT-IMP-005`/`008` (`LOOP_IMPORT_ACTIVATION_CONTRACT.md`),
`FB-RAT-AUTH-002`/`004` (`CAPABILITY_AUTHORITY_MODEL.md`), `FB-RAT-CMP-007`
(`LOOP_COMPATIBILITY_CONTRACT.md`, EXPERIMENTAL), `FB-RAT-MKT-003`/`004`/`005`/`006`/`007`/`009`/
`011`/`012` (`LOOP_MARKETPLACE_CONTRACT.md` — `009` PROMOTED, `011` DEFERRED, `012` PROPOSED),
`FB-RAT-RES-001`/`003`/`004`/`005`/`006` (`LOOP_RESULT_SHARING_CONTRACT.md`), `FB-RAT-LIN-001`
through `006` (`LOOP_FORK_LINEAGE_CONTRACT.md`), `FB-RAT-COM-001`/`006`/`009`/`010`
(`COMMON_CONVENTIONS.md`), `FB-RAT-DIST-004` (`DISTRIBUTION_CAPABILITY_SPLIT.md`), and
`FB-RAT-DEV-001`/`004`/`005`/`008` (`DEVICE_STATE_AND_SAFETY_SPEC.md`, cited for §5.5 only, an
adjacent non-loop family this gate composes rather than redefines).

**Registries reconciled, not redefined:** `floop-container-format.v1.json`
(`pathRules.adversarialClasses_MUST_reject` — §3.4), `semantic-digest.v1.json` and
`canonicalization.v1.json` (§3.1, §3.10), `loop-validation-rules.v1.json` (rule codes
`LOOP-ID-002`, `LOOP-CAP-002`, `LOOP-COMPAT-002`, `LOOP-PKG-001`/`002`/`003`/`004`/`006`/`008`,
`LOOP-LINEAGE-001`/`002`, `LOOP-VERIFY-002` — §3.4–§3.9, §5.1–§5.3, §5.7), `capability-ids.v1.json`
(`fb.secret.use` §3.9, `fb.device.flash` §5.5). A conflict between this document's prose and a
registry's data is this document's error, and the registry wins, per
`LOOP_FROZEN_CONCEPTS_WP1L_G0.md`.

**Sibling documents this document defers to and does not restate:** `LOOP_DUAL_SURFACE_
ARCHITECTURE.md` (§8 failure model, §9 conformance checklist, §10 compiler boundary, §12 service
decomposition and P0/P1 registry note — §2.3, §3.1, §3.7 of this document); `LOOP_PHONE_
AUTHORING_SPEC.md` (§12.3 accessibility deliverables, §15 phone-completeness scenario, §17
performance budget — §3.2, §3.6, §3.11); `LOOP_WEB_STUDIO_SPEC.md` (§9 shared-implementation
proposal, §10 publication checklist — §3.1, §5.4); `LOOP_PACKAGE_SPEC.md` (§4 digests, §5 build
determinism, §7 secret/Unicode controls — §3.1, §3.8–§3.10); `LOOP_IMPORT_ACTIVATION_CONTRACT.md`
(§3 the eleven-state import machine — §3.4); `LOOP_MARKETPLACE_CONTRACT.md` (§10 moderation state
table, §11 registry definition, §12 replaceability, §15 Play/legal scope gap — §4.1, §5.1, §5.8);
`LOOP_RESULT_SHARING_CONTRACT.md` (§3 share-flow state machine, §6 verification claims — §5.2);
`LOOP_FORK_LINEAGE_CONTRACT.md` (§8 diff dimensions, §9 authority/verification diff, §10
selective-apply state machine — §5.3); `DEVICE_STATE_AND_SAFETY_SPEC.md` (§1, §4, §8 — §5.5, an
adjacent family, composed not restated).

**Open items, not resolved by this document:** whether `FB-RAT-WEB-010` (§3.1) is accepted,
rejected, or folded into another ID; the two draft figures §3.11's table marks "not directly
covered" (edit-journal-acknowledgement and 20 MB-package-validation budgets), which remain
unreconciled to a single number until a future revision of `LOOP_PHONE_AUTHORING_SPEC.md` §17
states them explicitly; the moderation SLO §5.1 flags as not yet numerically defined, pending
`LOOP_STORE_COMPLIANCE_CONTRACT.md`; and Correction 4's cross-repository path inconsistency
(`LOOP_DUAL_SURFACE_ARCHITECTURE.md` §12 and `LOOP_PHONE_AUTHORING_SPEC.md` §15 both still point
at `docs/release-gates/LOOP_P0_P1_RELEASE_GATES.md` rather than this file's actual path),
flagged for the Amendments-phase agent to correct in those two sibling files. **No new decision ID
is proposed by this document** — every correction this document needed to make (the P0/P1
registry contradiction, the inapplicable renumbering instruction, the performance-figure
reconciliation) was closable by cross-referencing an already-frozen registry, an already-ratified
sibling decision, or the master build brief's own explicit scope boundary; none of it required
inventing a new `FB-RAT-*` ID.
