# WP-7 Gate Report — Integration lanes R1-R4 (CSApp/Assay/Studio)

**Scope:** `06_WORK_PACKAGES.md`'s WP-7 entry, implementing (not designing — WP-1 already ratified
every contract this pass implements against: `MANUAL_INTEGRATION_GRAMMAR.md`,
`CSAPP_ISSUES_MANIFEST_V1.md`, `ASSAY_REPO_CONTRACT_V1.md`, `IMPORT_RECEIPT_V1.md`) the release
sequence that document's §9 names: **R1** (freeze the common grammar — envelope, errors,
`ImportPreview`/`ImportReceipt`), **R2** (CSApp file lane), **R3** (Assay repo lane). **R4**
(Studio continuity — Incident/Task lenses, Watch, proof-gated resolution) is **correctly out of
scope for this repo**, not a gap: the responsibility matrix (`MANUAL_INTEGRATION_GRAMMAR.md` §3)
explicitly lists "Studio priority/boards/incident workflow" under Core's own **"must not own"**
column — R4 belongs in `Android-IDE-Studio`, a separate repo, and building it here would itself be
the exact "contract bypass" that document's §3 warns against. **R5** (return packets) stays
DEFERRED per WP-1's own decision, untouched.

**Run date:** 2026-08-07. **Method:** the same real-compile-and-test standard every gate since
WP-2 — `./gradlew :core-engine:testFullDebugUnitTest`.

## Gate verdict: **GREEN** — one real design bug found and fixed on the first real build attempt

---

## 0. What was built

- **`domain/contracts/IntegrationsCodec.kt`** (R1) — JSON encode/decode for every
  `dev.fonebrew.contracts.integrations` wire shape (`IssuesManifest`, `AssayIndex`, `ProvingTests`,
  `ImportPreview`, `ImportReceipt`, `ImportErrorCode`'s closed-plus-open vocabulary), same
  unknown-field-preserving pattern as WP-2/WP-3/WP-4's codecs. **Round-tripped against the REAL
  WP-1 fixture files** (`fixtures/integrations/valid/*.json`, embedded verbatim in the test —
  this session's toolchain has no established pattern for a JVM test reading repo-root fixture
  files at runtime, so the real fixture content is copied in rather than loaded from disk), not
  hand-invented test data.
- **`domain/integrations/ImportReceiptBuilder.kt`** (R1) — the shared Confirm→Receipt step (§2,
  steps 5–6) both lanes use. Core assembles the receipt shape; it never invents an Incident id
  itself (`incidentIdFor` is a caller-supplied seam — the responsibility-matrix boundary made real
  in code, not just documented).
- **`domain/integrations/CsAppImportLane.kt`** (R2) — the CSApp file lane's Validate/Preview
  logic: whole-source duplicate-snapshot short-circuit, per-issue new/changed/conflicting/
  duplicate classification against the `(projectExternalId, issueId)` dedupe key (§2 of the
  ratified doc), and tolerant per-record JSON parsing so one issue with a bad `severity` or an
  unparsable timestamp is rejected individually (`IMPORT_INVALID_SEVERITY`/
  `IMPORT_INVALID_TIMESTAMP`) without failing the whole manifest — see §2 below for why this
  needed its own parsing path, not just the strict codec.
- **`domain/integrations/SarifResults.kt`** (R3) — a parser for exactly the SARIF 2.1.0 profile
  subset `ASSAY_REPO_CONTRACT_V1.md` §4 pins (not a general SARIF library, matching that
  document's own "out of scope to redefine here" scoping): validates every `results[]` entry
  carries `properties.scannerName`/`properties.ruleId`, rejecting one malformed result at a time
  (never the whole file) exactly as §4 specifies.
- **`domain/integrations/AssayImportLane.kt`** (R3) — the Assay repo lane's Validate/Preview
  logic: `runId`-keyed dedupe (a whole run is either new — every parsed finding becomes a new
  record — or already-imported — every finding becomes a duplicate pointing at the prior receipt;
  Assay's model has no per-finding "changed" concept across runs, unlike CSApp's per-issue one),
  finding-file digest verification (`IMPORT_DIGEST_MISMATCH`), and the `completeness != COMPLETE`
  → `isPartialSource` flag (`IMPORT_PARTIAL_SOURCE`'s "explicit partial-import confirmation"
  requirement, surfaced for a caller to act on).

Five new test files, 35 new tests.

## 1. `core-engine` JVM gate — PASS (after one real design-bug fix)

```
./gradlew --no-daemon :core-engine:testFullDebugUnitTest
BUILD SUCCESSFUL in 1m 35s
```

**1394 tests, 0 failures, 0 errors, 1 skipped** (the same pre-existing skip every gate since WP-1L
has reported), up from WP-6's 1359-test baseline.

## 2. A real design bug: `validateSchemaVersion` was dead code as first written

The first draft of both `CsAppImportLane.validateSchemaVersion(manifest: IssuesManifest)` and
`AssayImportLane.validateSchemaVersion(index: AssayIndex)` took an **already-constructed** contract
object and checked its `schemaVersion` field. Both failed for real the moment a test tried to
exercise the "unsupported major" path: `IssuesManifest`/`AssayIndex` each carry their own `init{}`
block enforcing `schemaVersion` matches `^1\.\d+\.\d+$` (WP-1's own contract, unrelated to this
pass) — so **no instance of either type can ever exist in memory with an unsupported major
version**. A function that only ever receives a fully-constructed, already-valid instance can
never observe the very condition it exists to detect. Root-caused and fixed by changing both
functions' signatures to take the **raw JSON string**, checked *before* attempting the strict
decode (`IntegrationsCodec.decodeIssuesManifest`/`decodeAssayIndex`, which themselves construct the
throwing contract types) — matching exactly the same "the strict Kotlin type can't hold an invalid
value, so record/source-level validation has to happen at the JSON layer first" pattern
`CsAppImportLane.parseIssuesTolerant` already needed for per-record severity/timestamp validation
(§3). Both `` `schemaVersion major 2 is UnsupportedMajor` `` tests are the regression coverage.

## 3. Per-record tolerant parsing — a second, deliberate instance of the same pattern

`CsAppImportLane.parseIssuesTolerant` exists because `docs/ratified/MANUAL_INTEGRATION_GRAMMAR.md`
§4's `IMPORT_INVALID_<FIELD>` family requires rejecting **one bad record**, not the whole manifest
— but `CsAppIssue.severity: IssueSeverity` is a closed Kotlin enum that cannot hold `"SEV9"` at
all. `parseIssuesTolerant` therefore parses the raw JSON array issue-by-issue, catching an invalid
severity or unparsable timestamp per record and only constructing a real `CsAppIssue` for the
records that pass — proven by `` `tolerant parsing rejects only the record with an invalid
severity, keeping the rest` `` (one good, one bad issue in the same array → one valid, one
rejected, not a whole-batch failure).

## 4. Assay lane — the SARIF profile subset, dedupe, and digest verification, all real

`SarifResultsTest` parses `ASSAY_REPO_CONTRACT_V1.md` §4's own worked example verbatim and asserts
every field extracts correctly (`scannerName`, the properties-level `ruleId` — deliberately NOT
SARIF's own top-level `ruleId`, per that section's rationale — `provingTestRef`, location URIs),
plus both individual-result-rejection cases (missing `scannerName`/`ruleId`) and the
"absent `provingTestRef` is never an error" case. `AssayImportLaneTest` proves the `runId`-keyed
dedupe both directions (never-imported → all-new; already-imported → all-duplicate-pointing-at-
prior-receipt) and that a finding-file whose recomputed sha256 doesn't match — or was never
fetched at all — is rejected with `IMPORT_DIGEST_MISMATCH`, never silently trusted.

## 5. Real-world grounding: both source repos checked, both are pre-alpha

A background research pass confirmed `mbaliga/assay` and `mbaliga/csapp` are both real GitHub
repos (created 2026-08-01) but genuinely pre-alpha: `assay` has no source beyond a LICENSE file;
`csapp` is a real Kotlin/Compose Android project (package `com.mbaliga.csapp`, confirming
`IntegrationContracts.kt`'s deliberate choice to keep `CsAppProducerRef` un-unified with this
constellation's own `ProducerRef` shape — genuinely independent codebases) with **no
`issues-manifest.json` exported anywhere yet**. Notably, `csapp`'s own GitHub Issue #3 —
*"Release gate: prove the frozen manifest against the exact Studio importer"* — shows the other
side of this integration is already blocked on and aware of this exact contract, real (if early)
cross-repo coordination, not a speculative integration nobody asked for. This confirms testing
against the WP-1 corpus's own fixtures (§0) was the only viable option this pass — there is no live
external sample to import yet.

## 6. What's still genuinely unverified / honestly out of scope

- **R4 (Studio continuity)** — correctly not built here; belongs in `Android-IDE-Studio` per the
  responsibility matrix (see the header). Flagged, not silently skipped.
- **No live import pipeline wiring** — `CsAppImportLane`/`AssayImportLane`/`ImportReceiptBuilder`
  are real, tested domain logic with no `AppContainer` consumer yet, matching the "domain/data
  layer ready, live-surface wiring is a later pass" pattern every WP since WP-2 has left for
  pieces with no natural default. A real Snapshot step (reading actual file bytes / actual repo
  commits via `GitContentsApi`) also isn't wired here — this pass is Validate/Preview/Receipt
  logic against already-parsed inputs, not the full six-step grammar's I/O ends.
- **No real Room-backed `DedupeIndex`/`RunDedupeIndex`** — both are `fun interface` seams (same
  pattern as WP-4's `GrantStore`), tested against fakes; a persistent implementation is a future
  pass once a real UI surface needs cross-session dedupe.
- **`AuditFinding`/`CustomerIssue` projection is not itself persisted** — `projectToCustomerIssue`/
  `projectToAuditFinding` are pure functions producing the neutral record shape; nothing writes
  them anywhere yet (same "no consumer" gap as above).
