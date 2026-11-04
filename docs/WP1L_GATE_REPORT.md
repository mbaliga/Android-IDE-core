# WP-1L Gate Report — loop contract corpus v2.1

**Scope:** the WP-1L pass's output — 12 deduped dual-surface loop specs plus the engineering
spec v2.1 under `docs/ratified/loops/` (one per dual-surface spec, per the task brief), 21 loop
schemas under `schemas/loops/` (5 groups), 8 Kotlin contract files under `contracts/kotlin/
Loop*.kt`, fixtures under `fixtures/loops/`, and `AMENDMENTS.md`. **Explicitly excludes**
`schemas/loops/registries/` and `schemas/loops/fixtures/canonicalization/` (WP-1L-G0, a prior
gate — confirmed present/untouched here, not re-validated) and everything WP-1 already gated
(`docs/WP1_GATE_REPORT.md`, not re-litigated).

**Run date:** 2026-08-07. **Method:** independent re-validation via Bash + Python `jsonschema`
4.26.0 + `referencing` (offline `$ref` resolution against a registry built from every `$id`
under `schemas/`) — the WP-1L pass's own summary was treated as a claim to verify, not ground
truth, same standard as the WP-1 gate before it.

## Gate verdict: **YELLOW — green on every testable schema/fixture/marker item; two genuine, unfixed corpus defects found and named; two structural items UNVERIFIED for the same sandbox reasons as WP-1**

Every schema is structurally valid, every fixture behaves exactly as required, every
`LICENSE-PENDING` marker is present, `AMENDMENTS.md`'s seven required subsections are all present
and substantive, and — the single most important cross-document check for this work package —
**the eleven-state import/activation machine appears IDENTICALLY in
`LOOP_IMPORT_ACTIVATION_CONTRACT.md` and the `loop-installation.v1` schema enum: same 11 state
names, same order, same 5 terminal states.** No drift. That said, this gate's decision-ID
cross-check (necessarily corpus-internal — see §5) found two genuine, previously-unreported
defects in the WP-1L authoring pass, neither of which is a "cheap, safe, single-token" fix, so
neither was silently patched — both are named below for the owner/next session.

---

## 0. Files on disk — confirmed by listing, not trusted from the pass summary

```
docs/ratified/loops/          14 files total (13 new this pass + LOOP_FROZEN_CONCEPTS_WP1L_G0.md,
                               confirmed untouched — git-tracked from WP-1L-G0, zero diff)
schemas/loops/                21 in-scope *.schema.json (new this pass) + registries/ (6 files) +
                               fixtures/canonicalization/ (25 files) — both confirmed present,
                               git-tracked from WP-1L-G0, zero diff, not re-validated
contracts/kotlin/Loop*.kt     8 files
fixtures/loops/                169 files (127 *.json + 42 *.expected.txt)
```

**No stray `LOOP_ENGINEERING_SPEC.md` (v1.0, old name) exists anywhere under `docs/ratified/`.**
Only `docs/ratified/loops/LOOP_ENGINEERING_SPEC_V2.1.md` exists; a repo-wide
`find . -iname "LOOP_ENGINEERING_SPEC.md"` returns nothing. Matches the doc's own header claim
("v1.0 was never emitted into this repository ... a repo-wide search confirms no such file
exists"), independently reproduced here.

**211 files total written this pass** (13 docs + 21 schemas + 8 Kotlin + 169 fixture files),
plus this report and the regenerated traceability matrix as gate output.

## 1. Schemas validate — PASS (21/21)

Every schema under `schemas/loops/*.schema.json` (21 files, `registries/` and
`fixtures/canonicalization/` excluded) loaded with `json.load` and passed
`jsonschema.Draft202012Validator.check_schema()` with zero exceptions. All 21 declare
`$schema` as a 2020-12 dialect URI.

## 2. Fixtures behave as required — PASS (127/127 JSON files; 42/42 adversarial `.expected.txt` present and substantive)

Built an offline `referencing.Registry` from every `$id`-bearing schema under `schemas/`
(51 resources — the loop schemas cross-reference `schemas/common/*` and `schemas/authority/*`
by `$id`, e.g. `AuthorityDiff`'s `oneOf` branches into `common/artifact-ref.schema.json`), then
re-validated every fixture against its schema by directory-name-to-schema-stem mapping (all 21
fixture directories map 1:1 to the 21 schema files; confirmed no orphan directory on either side).

| Schema | valid (PASS) | invalid (rejected) | adversarial (structural PASS + `.expected.txt`) |
|---|---|---|---|
| authority-diff | 2/2 | 2/2 | 2/2 |
| binding-profile | 2/2 | 2/2 | 2/2 |
| loop-activation-receipt | 2/2 | 2/2 | 2/2 |
| loop-build-receipt | 2/2 | 2/2 | 2/2 |
| loop-compatibility-report | 2/2 | 3/3 | 2/2 |
| loop-definition.v2 | 2/2 | 2/2 | 2/2 |
| loop-draft | 2/2 | 2/2 | 2/2 |
| loop-fork-lineage | 2/2 | 2/2 | 2/2 |
| loop-installation | 2/2 | 2/2 | 2/2 |
| loop-listing | 2/2 | 2/2 | 2/2 |
| loop-package-manifest | 2/2 | 2/2 | 2/2 |
| loop-package-signature | 2/2 | 2/2 | 2/2 |
| loop-release | 2/2 | 2/2 | 2/2 |
| loop-result-share | 2/2 | 2/2 | 2/2 |
| loop-review | 2/2 | 2/2 | 2/2 |
| loop-run | 2/2 | 2/2 | 2/2 |
| loop-run-summary | 2/2 | 2/2 | 2/2 |
| loop-semantic-diff | 2/2 | 2/2 | 2/2 |
| loop-validation-report | 2/2 | 2/2 | 2/2 |
| package-fixture-record | 2/2 | 2/2 | 2/2 |
| transfer-envelope | 2/2 | 2/2 | 2/2 |
| **Total** | **42/42** | **43/43** | **42/42** |

`loop-compatibility-report` has 3 invalid fixtures, not 2 (`only-9-axes-missing-policy`,
`overall-outcome-disagrees-with-worst-axis`, `phone-authoritative-false`) — everything else is
2/2/2. **Zero fixtures behaved unexpectedly**: no invalid fixture validated when it shouldn't
have (nor did any rely on a thin/placeholder `.expected.txt` in place of an actual schema
rejection), no valid fixture failed, no adversarial fixture was schema-rejected, and all 42
adversarial `.expected.txt` siblings are present and substantive (>20 chars, spot-checked across
several — each states the specific semantic rule JSON Schema structurally cannot catch, matching
the WP-1 gate's bar).

## 3. LICENSE-PENDING markers — PASS, no fixes needed (scope: docs + schemas + Kotlin, matching the WP-1 gate's precedent — fixture JSON instances are out of scope, same as WP-1)

- `docs/ratified/loops/*.md` (13 files, `LOOP_FROZEN_CONCEPTS_WP1L_G0.md` excluded — prior gate):
  all carry `> **License:** \`LICENSE-PENDING\` — see \`docs/non_ratified/LICENSE_PENDING.md\`.`
  directly under the H1.
- `schemas/loops/*.schema.json` (21 files): all carry a top-level `$comment` starting
  `LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md.`
- `contracts/kotlin/Loop*.kt` (8 files): all carry the marker as the first line-comment.

Every marker was present and correctly formatted on first check — nothing needed adding. (Fixture
JSON instances under `fixtures/loops/` were **not** checked for this marker, matching the scope
`docs/WP1_GATE_REPORT.md` §3 established: pure data instances of a schema are not in the marker's
scope, and stamping an extra field into 127 fixture files risks tripping `additionalProperties:
false` on schemas that define one.)

## 4. AMENDMENTS.md — all seven required subsections present and substantive, PASS

| # | Required subsection | Present | Substance check |
|---|---|---|---|
| 1 | `FB-RAT-INT-003` supersession line, verbatim | Yes (§1) | The quoted line matches `LOOP_MARKETPLACE_CONTRACT.md` §2.1 word-for-word (only the source's bold/backtick markdown decoration is dropped, which §1 itself discloses: "already ratified, word-for-word (with backtick styling around the two IDs)") |
| 2 | The three v2.1 deltas | Yes (§2, §2.1–§2.3) | Node categories 5→8; the 8-value base `LoopResult` → 17-state v2.1 run machine (two semantic changes named); `LOOP-004` vs `PKG-008` fixture-requirement resolution |
| 3 | `Write = {MODIFY_DRAFT, EXECUTE_REVERSIBLE}` mapping row | Yes (§3) | Full 7-bucket table + explicit "this is a UI grouping, never a real ladder rung" clarification |
| 4 | `LoopRunState` ↔ `ExecutionState` mapping | Yes (§4) | States the gap (17-state loop machine has no `VERIFYING`/`QUEUED`) and maps it |
| 5 | `derivesFrom` table (~13 restated decisions) | Yes (§5) | 10-row table, each with parent ID, relationship type (restates/partially restates/narrower case), and reasoning |
| 6 | `StudioApp`/`WebLoopStudio` glossary fix | Yes (§6) | Both terms defined, a concrete pre-existing ambiguous usage named (`MANUAL_INTEGRATION_GRAMMAR.md` §8) |
| 7 | Proposed-new-decisions section | Yes (§7) | 4 IDs collected (`FB-RAT-PHN-011`, `FB-RAT-WEB-010`, `FB-RAT-MKT-012`, `FB-RAT-RES-008`), explicitly not self-ratified |

No missing subsection. All seven read as real analytical content, not headers with placeholder text.

## 5. Decision-ID cross-check — corpus-internal only (source register not present in this session); two genuine defects found, neither fixed

**Why corpus-internal, stated honestly:** `inputs/RATIFICATION_REGISTER.md` (the external 109-ID
register Part A of `docs/TRACEABILITY_MATRIX.md` verified against) and the `inputs/dual_surface/
specs/`, `fonebrew_handoff/*.md`, `DUAL_RATIFICATION_REGISTER.md` files the loop corpus's own
documents repeatedly cite as their source **do not exist anywhere on this filesystem** — confirmed
by a repo-wide and root-level search. This gate's ID cross-check is therefore built entirely from
each document's own self-declared "Decision IDs cited in this document" footer and per-ID
`— ACCEPTED/REJECTED/DEFERRED/EXPERIMENTAL/PROPOSED` statements — internally consistent, but
**not** provably matching an external register. Full method and per-artifact ownership table:
`docs/TRACEABILITY_MATRIX.md` Part B.

**Rule-code cross-check (separate from decision IDs) — clean.** All 28 `LOOP-*` validation rule
codes in `schemas/loops/registries/loop-validation-rules.v1.json` were cross-checked against every
`LOOP-*` code referenced in `schemas/loops/*.schema.json` and `fixtures/loops/**`: **zero dangling
references** (every code the new corpus uses is registry-defined). Two registry codes are unused
by this corpus so far — not a defect, just noted: `LOOP-CAP-002`, `LOOP-SCHEMA-001`.

**Defect 1 — `FB-RAT-LBX-002` is dangling: cited as ACCEPTED in 9 of 13 documents, ratified in zero.**
`FB-RAT-LBX-002` ("the phone app is the primary, complete, untethered authoring and execution
surface") is treated as already-decided, load-bearing context throughout the corpus, but its
"canonical home" pointer is circular and no document's own self-declared citation list claims it:
`LOOP_PHONE_AUTHORING_SPEC.md` points to `LOOP_DUAL_SURFACE_ARCHITECTURE.md`;
`LOOP_DUAL_SURFACE_ARCHITECTURE.md`'s own footer explicitly lists only `FB-RAT-LBX-001`/`-004`
("No other `FB-RAT-*` ID is ratified by this document") and its body groups LBX-002 back under the
phone doc; `LOOP_WEB_STUDIO_SPEC.md` points to the phone doc; `LOOP_P0_P1_RELEASE_GATES.md` points
to both at once. No document contains the proper `` `FB-RAT-LBX-002` — ACCEPTED `` statement with
defining normative text every other owned ID gets. **Not fixed here** — inventing the missing
ratification text would itself violate the corpus's own `FB-RAT-COM-002` rule ("stable IDs are
never invented by the document that needs one"). Flagged for the owner/next session.

**Defect 2 — 14 DEFERRED/EXPERIMENTAL/REJECTED loop IDs cite a `docs/non_ratified/*.md` home that doesn't contain them.**
Every one of `FB-RAT-CMP-007`, `FB-RAT-PHN-010`, `FB-RAT-PKG-010` (→ `EXPERIMENTAL_DECISIONS.md`,
3 missing), `FB-RAT-IMP-010`, `FB-RAT-LIN-007`, `FB-RAT-RES-007`, `FB-RAT-WEB-008`,
`FB-RAT-LBX-009`, `FB-RAT-MKT-010`, `FB-RAT-MKT-011` (→ `DEFERRED_DECISIONS.md`, 7 missing), and
`FB-RAT-PKG-003`, `FB-RAT-WEB-004`, `FB-RAT-PHN-003`, `FB-RAT-LBX-005` (→
`REJECTED_ALTERNATIVES.md`, 4 missing) explicitly names a `docs/non_ratified/*.md` file as its
true status home — none of the 14 rows actually exist there (verified by direct grep, 0 hits each).
This is systemic, not a one-off: the WP-1L authoring pass consistently wrote the promise but the
WP-1-era register files were never updated with the loop domain's non-ACCEPTED IDs. **Not fixed
here** — each row needs synthesized topic/decision/calibration-condition text matching that
register's own schema, which is real editorial work under time pressure, not a token-level fix
(same bar `docs/WP1_GATE_REPORT.md` §4 used to leave its `INT-NNN` gap unfixed). Full per-ID list:
`docs/TRACEABILITY_MATRIX.md` Part B, Finding 2.

**Everything else resolves cleanly.** 68 of the 69 dual-surface-register `ACCEPTED` IDs referenced
by this corpus land in exactly one self-declaring artifact (LBX-002 is the 69th — Defect 1). Every
double-appearing ID this gate found (`FB-RAT-MKT-008`, `FB-RAT-PHN-007`, `FB-RAT-PHN-009`,
`FB-RAT-WEB-005`, `FB-RAT-PKG-003`) is a **deliberately resolved** restated/cross-filed decision —
both citing documents agree, in their own words, on the single true canonical home. No unresolved
double-ownership beyond Defect 1.

**Minor format note (not counted as a defect):** `LOOP_PHONE_AUTHORING_SPEC.md` is the one
substantive WP-1L document without a closing "Decision IDs cited in this document" footer that
every one of its 12 siblings carries — its ownership is still fully recoverable from inline
per-section declarations (used throughout this gate), but the omission is worth the next session
closing for consistency.

## 6. The eleven-state import/activation machine — IDENTICAL across both documents (the single most important check for this work package)

**`LOOP_IMPORT_ACTIVATION_CONTRACT.md` §3**, main-path sequence (11, in order):
`ACQUIRING → SNAPSHOTTED → CONTAINER_VERIFIED → PARSED_VALIDATED → COMPATIBILITY_EVALUATED →
PREVIEWED → WAITING_BINDINGS → WAITING_AUTHORITY → READY_TO_SIMULATE → INSTALLABLE → INSTALLED`,
plus 5 terminal states reachable from various non-terminal points: `CANCELLED, REJECTED_UNSAFE,
REJECTED_POLICY, BLOCKED_INCOMPATIBLE, FAILED_SAFE`.

**`schemas/loops/loop-installation.schema.json`'s `InstallationState` enum** (16 values, verbatim):
```
ACQUIRING, SNAPSHOTTED, CONTAINER_VERIFIED, PARSED_VALIDATED, COMPATIBILITY_EVALUATED, PREVIEWED,
WAITING_BINDINGS, WAITING_AUTHORITY, READY_TO_SIMULATE, INSTALLABLE, INSTALLED, CANCELLED,
REJECTED_UNSAFE, REJECTED_POLICY, BLOCKED_INCOMPATIBLE, FAILED_SAFE
```

**Result: IDENTICAL.** Same 11 main-path state names in the same order, same 5 terminal state
names in the same order, confirmed by direct comparison (not by trusting either document's own
"copied verbatim" claim — both independently transcribed and diffed here). No drift found. This
was checked first and separately from the general decision-ID cross-check because the task
explicitly named it the single most important cross-document consistency check for this whole
work package — it is a **real pass**, not a nitpick avoided.

## 7. UNVERIFIED items (restated briefly, same sandbox limits as WP-1, not re-litigated)

- **Kotlin compilation** — no `kotlinc`/Gradle toolchain in this sandbox. The 8 `Loop*.kt` files
  were not lexically re-scanned by this gate the way WP-1's gate scanned its 6 (out of this
  session's time budget); this is an explicit gap, not a claim of "checked and clean." Run
  `./gradlew :core-engine:compileFullDebugKotlin` (per `HANDOFF_STATE.md`'s WP-0 correction on the
  real module path) once a toolchain is available.
- **Unknown-field round-trip preservation** — genuinely requires a real Kotlin deserializer;
  cannot be established from static fixtures. Same blocker as WP-1 §6.

---

## Summary against the task brief's 9 items

| # | Item | Verdict |
|---|---|---|
| 1 | File listing (docs/schemas/kotlin/fixtures) | **PASS** — 211 files confirmed on disk by direct listing; WP-1L-G0's registries/canonicalization fixtures confirmed present and untouched (zero git diff) |
| 2 | No stray v1.0 `LOOP_ENGINEERING_SPEC.md` | **PASS** — only `_V2.1.md` exists anywhere under `docs/ratified/` |
| 3 | Schema structural validation | **PASS** — 21/21 |
| 4 | Fixture valid/invalid/adversarial behavior | **PASS** — 42/42 valid, 43/43 invalid correctly rejected, 42/42 adversarial structurally-valid-with-substantive-`.expected.txt` |
| 5 | `LOOP-*` rule-code cross-check | **PASS** — 0 dangling; 2 registry codes unused (not a defect) |
| 6 | `AMENDMENTS.md` seven subsections | **PASS** — all seven present and substantive |
| 7 | `LICENSE-PENDING` markers | **PASS** — all present, scope matches WP-1 precedent (docs/schemas/Kotlin; fixtures excluded) |
| 8 | Traceability matrix regenerated, decision IDs cross-checked | **YELLOW** — Part B added to `docs/TRACEABILITY_MATRIX.md` without disturbing Part A; corpus-internal only (source register absent from this session, stated explicitly); 2 genuine defects found and named (LBX-002 dangling; 14 IDs' non_ratified home missing their row), neither silently fixed |
| 9 | 11-state machine identity check | **PASS** — identical state names, order, and terminals in both documents |

**No item is reported green that is not actually green.** The two UNVERIFIED items carry forward
the same structural sandbox limit WP-1 named. The two genuine defects this gate found (LBX-002's
dangling ratification; the 14 missing non-ratified register rows) are real, are not silently
patched, and are not dressed up as something smaller than they are — they were left for the owner
because fixing either correctly requires synthesizing new normative content under time pressure,
not a mechanical edit.

## Appendix — WP-1L files on disk (`schemas/loops/registries/`, `schemas/loops/fixtures/canonicalization/`, `LOOP_FROZEN_CONCEPTS_WP1L_G0.md` excluded — WP-1L-G0, prior gate)

```
docs/ratified/loops/AMENDMENTS.md
docs/ratified/loops/LOOP_COMPATIBILITY_CONTRACT.md
docs/ratified/loops/LOOP_DUAL_SURFACE_ARCHITECTURE.md
docs/ratified/loops/LOOP_END_TO_END_JOURNEYS.md
docs/ratified/loops/LOOP_ENGINEERING_SPEC_V2.1.md
docs/ratified/loops/LOOP_FORK_LINEAGE_CONTRACT.md
docs/ratified/loops/LOOP_IMPORT_ACTIVATION_CONTRACT.md
docs/ratified/loops/LOOP_MARKETPLACE_CONTRACT.md
docs/ratified/loops/LOOP_P0_P1_RELEASE_GATES.md
docs/ratified/loops/LOOP_PACKAGE_SPEC.md
docs/ratified/loops/LOOP_PHONE_AUTHORING_SPEC.md
docs/ratified/loops/LOOP_RESULT_SHARING_CONTRACT.md
docs/ratified/loops/LOOP_WEB_STUDIO_SPEC.md
schemas/loops/authority-diff.schema.json
schemas/loops/binding-profile.schema.json
schemas/loops/loop-activation-receipt.schema.json
schemas/loops/loop-build-receipt.schema.json
schemas/loops/loop-compatibility-report.schema.json
schemas/loops/loop-definition.v2.schema.json
schemas/loops/loop-draft.schema.json
schemas/loops/loop-fork-lineage.schema.json
schemas/loops/loop-installation.schema.json
schemas/loops/loop-listing.schema.json
schemas/loops/loop-package-manifest.schema.json
schemas/loops/loop-package-signature.schema.json
schemas/loops/loop-release.schema.json
schemas/loops/loop-result-share.schema.json
schemas/loops/loop-review.schema.json
schemas/loops/loop-run-summary.schema.json
schemas/loops/loop-run.schema.json
schemas/loops/loop-semantic-diff.schema.json
schemas/loops/loop-validation-report.schema.json
schemas/loops/package-fixture-record.schema.json
schemas/loops/transfer-envelope.schema.json
contracts/kotlin/LoopActivationContracts.kt
contracts/kotlin/LoopAuthoringContracts.kt
contracts/kotlin/LoopCompatibilityContracts.kt
contracts/kotlin/LoopDefinitionContracts.kt
contracts/kotlin/LoopMarketplaceContracts.kt
contracts/kotlin/LoopPackageContracts.kt
contracts/kotlin/LoopResultAndLineageContracts.kt
contracts/kotlin/LoopRuntimeContracts.kt
fixtures/loops/  (169 files: 127 *.json across valid/invalid/adversarial + 42 sibling
                  *.expected.txt for the 42 adversarial fixtures — 21 schema-named
                  subdirectories, 2 valid + 2-3 invalid + 2 adversarial each; see §2 table)
```

Gate output (not part of the WP-1L pass's own corpus):
```
docs/TRACEABILITY_MATRIX.md   (Part B appended; Part A untouched)
docs/WP1L_GATE_REPORT.md
```

## Addendum (WP-2, 2026-08-07) — real compile found and fixed a defect this gate could not see

§7 of this report named "8 `Loop*.kt` files were not lexically re-scanned by this gate" as an
explicit gap. During WP-2, a real Android SDK + JDK toolchain became available in this sandbox
(`scripts/setup-android-sdk.sh` plus a `LANG=C.utf8` locale fix), and `contracts/kotlin/` — all 14
files, this corpus's 8 plus WP-1's 6 — was wired into `core-engine`'s build as a real compiled
source directory. **First compile attempt failed**, for exactly the reason a lexical scan cannot
catch: `LoopAuthoringContracts.kt` and `LoopPackageContracts.kt` both declare
`data class ValidationFinding` in the same package (`dev.fonebrew.contracts.loops`) — a genuine
Kotlin redeclaration. Notably, **three separate WP-1L authoring-phase agents had already spotted
this exact collision while writing their own files** and left explicit comments about it —
`LoopActivationContracts.kt`'s header comment named it a "KNOWN PRE-EXISTING PACKAGE CONFLICT,"
and `LoopMarketplaceContracts.kt`'s header called it a "NAME COLLISION WARNING" — but every agent
correctly treated reconciling two OTHER files' declarations as out of its own assigned scope, and
the cross-check gate that ran afterward validated schemas, fixtures, and rule-code references but
never actually invoked a Kotlin compiler, so the flagged-but-unresolved collision survived the
gate.

**Fix:** kept `LoopAuthoringContracts.kt`'s `ValidationFinding`/`FindingSeverity` as canonical (it
carries a `remediation` field, matching `loop-validation-rules.v1.json`'s registry shape more
closely than the duplicate did), and replaced `LoopPackageContracts.kt`'s duplicate
`LoopValidationSeverity` enum with `typealias LoopValidationSeverity = FindingSeverity` — so
`LoopActivationContracts.kt` and `LoopCompatibilityContracts.kt`, which both reference
`LoopValidationSeverity` directly, needed no changes at all. All three files' explanatory comments
were updated to state the collision is resolved rather than pending. After the fix:
**`:core-engine:compileFullDebugKotlin` BUILD SUCCESSFUL** (all 14 files), and
**`:core-engine:testFullDebugUnitTest` BUILD SUCCESSFUL, 1214 tests, 0 failures, 1 ignored** —
identical to the pre-change baseline.

**Lesson for future gates in this corpus, stated plainly:** a structural/lexical cross-check (JSON
validity, brace-balance scanning, decision-ID citation matching) is necessary but not sufficient
for a Kotlin source corpus split across many parallel-authored files sharing one package — only an
actual compiler invocation catches a same-package redeclaration, and multiple agents correctly
identifying and flagging a risk is not the same as the risk being resolved. §7's "Kotlin
compilation... UNVERIFIED" line in the summary table above should now be read as **superseded** by
this addendum, not as still-open.
