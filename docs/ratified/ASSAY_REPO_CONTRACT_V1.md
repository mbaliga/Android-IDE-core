# Assay Repo Contract v1 — the Assay repo lane

> **License:** `LICENSE-PENDING` — see `docs/non_ratified/LICENSE_PENDING.md`.

**Status:** RATIFIED (this document). **Scope:** the Assay (on-device-first agentic security
auditing app, separate repo, own build brief) repo lane — `.assay/` layout, `assay-index.v1.json`,
`proving-tests.v1.json`, the full commit→check-branch→promote flow, and a concrete SARIF 2.1.0
profile subset (named but never specified in the source pack — designed here). Citation target
for INT-018, INT-019, INT-020. Builds on `docs/ratified/MANUAL_INTEGRATION_GRAMMAR.md` (six-step
grammar, INT-011) and `docs/ratified/COMMON_CONVENTIONS.md` — neither repeated here.

**Constellation boundary (binding).** Assay is a separate app, own repo, own build brief. Core
imports FROM it via repo artifacts only — never a shared database, never IPC (INT-003/INT-004).
Assay remains fully useful standalone (INT-002): everything in `.assay/` is real output a security
engineer can read and act on with zero Fonebrew installed.

---

## 1. Repo layout (INT-018)

**INT-018 — ACCEPTED.** Assay writes its scan output to the SCANNED repository itself (a
user-selected output branch), under `.assay/`:

```
.assay/
  assay-index.v1.json          <- schemas/integrations/assay-index.v1.schema.json (§2)
  runs/
    <run-id>/
      findings.sarif            <- SARIF 2.1.0, this document's profile subset (§4)
      findings.json              (optional) normalized companion to findings.sarif
      proving-tests.v1.json      <- schemas/integrations/proving-tests.v1.schema.json (§3)
      evidence/                  supporting artifacts a proving test or finding references
  tests/
    proving/                     generated proving-test SOURCES (the actual test code)
  README.md                      human instructions; no hidden control data
```

`assay-index.v1.json`'s `findingFiles[]`/`provingTests` entries are **summaries** (path/sha256/
count, or path/count) pointing into `runs/<runId>/` — the index itself never repeats a finding's
or proving test's full content.

## 2. `assay-index.v1.json` — required fields (INT-019)

**INT-019 — ACCEPTED.** `runId` is the stable dedupe key. `schemas/integrations/
assay-index.v1.schema.json`:

| Field | Type | Notes |
|---|---|---|
| `schemaVersion` | string, `^1\.\d+\.\d+$` | FB-RAT-COM-003. |
| `runId` | string | Stable dedupe key (INT-019) — also names `runs/<runId>/`. |
| `projectRef.gitRemote` | string | The scanned repo's remote URL — primary identity Core matches against. |
| `projectRef.fonebrewProjectHint` | string \| null | Optional convenience default only — never auto-applied (INT-020, §5). |
| `sourceCommit` | string | Commit of the SCANNED repo this run's findings apply to. |
| `assayCommit` | string | Commit ON the Assay output branch carrying this index + `runs/<runId>/` tree. |
| `tool.name` / `tool.version` | string / string | |
| `startedAt` / `finishedAt` | date-time / date-time | |
| `findingFiles[]` | array of `{path, sha256, count}` | One entry per findings output file. `sha256` MUST be recomputed and compared before trust — a mismatch is `IMPORT_DIGEST_MISMATCH`. |
| `provingTests` | `{path, count}` | Points at `runs/<runId>/proving-tests.v1.json`. |
| `completeness` | `COMPLETE`\|`PARTIAL`\|`FAILED` | Whether every configured scanner ran to completion. |
| `explanation` | string \| null | **Required non-blank** whenever `completeness` is `PARTIAL` or `FAILED` — structurally enforced (`allOf`/`if`/`then`; see `fixtures/integrations/invalid/assay-index-partial-missing-explanation.invalid.json`). A run MUST NOT silently under-report scanner coverage. |

`sourceCommit` (the scanned repo) and `assayCommit` (the output branch) are deliberately two
separate fields — the source link a promoted finding carries stays pinned to `sourceCommit` even
if the output branch later moves on with a new run.

## 3. `proving-tests.v1.json` — designed here

Named but never specified in the source pack (WP-1 task brief §6). `schemas/integrations/
proving-tests.v1.schema.json` — a container keyed to one run:

| Field | Type | Notes |
|---|---|---|
| `schemaVersion` | string | FB-RAT-COM-003. |
| `runId` | string | MUST equal the owning `AssayIndex.runId`. |
| `generatedAt` | date-time | |
| `tests[]` | array of `ProvingTestEntry` | May be empty. |

Each `ProvingTestEntry`: `testId` (FB-RAT-COM-002, and what a SARIF result's `properties.
provingTestRef` points back at — §4), `targetFindingRef` (opaque pointer into `findings.sarif`),
`testKind` (`UNIT`\|`INTEGRATION`\|`EXPLOIT_POC`\|`REGRESSION`\|`OTHER` — `EXPLOIT_POC` is a
minimal reproduction, not a weaponized artifact), `sourcePath` (relative to the repo root, under
`tests/proving/` — a reader MUST reject any `..` segment before opening it, same obligation as
`schemas/common/artifact-ref.schema.json`'s `LOCAL_FS` rule), `status`
(`PASSING`\|`FAILING`\|`NOT_RUN`\|`ERROR`). `lastRunAtUtc` is **required non-null** whenever
`status` is not `NOT_RUN` — structurally enforced (see `fixtures/integrations/invalid/
proving-tests-passing-without-lastrun.invalid.json`): a test cannot be reported PASSING without
having actually run.

## 4. SARIF 2.1.0 profile subset (designed here)

The source pack names "SARIF" but never specifies which subset of the (large) SARIF 2.1.0 spec
Assay's `findings.sarif` MUST populate for Fonebrew to consume it usefully. Pinned here: **SARIF
2.1.0**, and every `runs[].results[]` entry's `properties` bag MUST carry, at minimum:

| Property | Type | Meaning |
|---|---|---|
| `properties.scannerName` | string | Which underlying scanner actually found this (`MobSF`\|`OSV-Scanner`\|`Semgrep`\|`Gitleaks`) — disambiguates the true SOLE finding source (§6 of `docs/ratified/MANUAL_INTEGRATION_GRAMMAR.md` §3) when `findings.sarif`'s top-level `driver.name` is `"Assay"` itself (an aggregator, not a scanner). |
| `properties.ruleId` | string | The underlying scanner's own stable rule identifier — kept explicit in `properties` (not just SARIF's own top-level `ruleId`) so a normalized `findings.json` companion, or any consumer that only walks `properties` bags, does not need to separately resolve SARIF's `rules[]` catalog to get it. |
| `properties.provingTestRef` | string \| null | The `testId` from `proving-tests.v1.json` (§3) that proves/disproves this specific result, when one exists yet. Null/absent when no proving test has been generated for this finding. |

Worked example (one `results[]` entry, non-normative excerpt — this is illustration, not a schema
this repo ships, since the full SARIF 2.1.0 object model is out of scope to redefine here):

```json
{
  "ruleId": "gitleaks.generic-api-key",
  "level": "error",
  "message": { "text": "Hardcoded credential-shaped string detected." },
  "locations": [
    { "physicalLocation": { "artifactLocation": { "uri": "app/src/main/java/dev/aarso/data/Config.kt" }, "region": { "startLine": 42 } } }
  ],
  "properties": {
    "scannerName": "Gitleaks",
    "ruleId": "generic-api-key",
    "provingTestRef": "01J9F0000000000000000PT2"
  }
}
```

A reader that finds a `results[]` entry missing any of the three `properties` keys above MUST
treat it as `IMPORT_INVALID_SCANNERNAME` / `IMPORT_INVALID_RULEID` (the `IMPORT_INVALID_<FIELD>`
family, `docs/ratified/MANUAL_INTEGRATION_GRAMMAR.md` §4 / `docs/ratified/IMPORT_RECEIPT_V1.md`)
for that one result, not a whole-file rejection — `provingTestRef` being absent is not an error
(most findings have no proving test yet).

## 5. Flow (INT-020)

**INT-020 — ACCEPTED.** Studio never invokes or polls Assay. The full flow, ordered:

1. User runs Assay (locally, in Assay's own app — outside this repo entirely).
2. User explicitly commits + pushes Assay's output branch (`.assay/` tree, §1).
3. In Fonebrew Audit, user selects repo + branch and taps **Check branch** — the six-step grammar's
   step 1 (Choose source, `docs/ratified/MANUAL_INTEGRATION_GRAMMAR.md` §2), triggered by this tap,
   never by Studio noticing a push on its own.
4. Core reads the selected commit and verifies the index plus every `findingFiles[].sha256`
   against the actual bytes (steps 2–3, Snapshot/Validate).
5. Findings plus proving tests are presented (step 4, Preview).
6. User promotes selected findings into Studio Incidents with immutable source links plus proof
   refs (step 5/6, Confirm/Receipt — `docs/ratified/INCIDENT_SOURCE_AND_PROOF_CONTRACT.md`).
7. User creates remediation Tasks manually — a separate explicit action (INT-016), never
   automatic from a promoted finding.
8. After a manual Assay rerun, the user taps **Refresh**; Studio compares receipts (by `runId`,
   §2) and proposes resolve-or-reopen — a proposal, not an automatic state change.

**`fonebrewProjectHint` (§2) is a default only** — Core MUST still require the explicit repo+branch
selection and **Check branch** tap at step 3 before reading anything, per INT-012 (`docs/ratified/
MANUAL_INTEGRATION_GRAMMAR.md` §8). A hint pre-fills a picker; it does not fire a read.

## 6. Fixtures

`fixtures/integrations/valid/assay-index-valid.json` + `proving-tests-valid.json`,
`fixtures/integrations/invalid/assay-index-partial-missing-explanation.invalid.json` +
`proving-tests-passing-without-lastrun.invalid.json`. Validated via Python `jsonschema`
`Draft202012Validator` — results in this work package's final report.
