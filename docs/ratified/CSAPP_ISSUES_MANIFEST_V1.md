# CSApp Issues Manifest v1 — the CSApp file lane

> **License:** `LICENSE-PENDING` — see `docs/non_ratified/LICENSE_PENDING.md`.

**Status:** RATIFIED (this document). **Scope:** the CSApp (Customer Success companion app) file
lane named in the WP-1 task brief — the shape of `issues-manifest.v1.json`, its dedupe key, and
how a `status` string is (and is not) allowed to influence Studio. Citation target for INT-013,
INT-015. Builds on `docs/ratified/MANUAL_INTEGRATION_GRAMMAR.md` (the six-step grammar every lane
follows, INT-011) and `docs/ratified/COMMON_CONVENTIONS.md` (FB-RAT-COM global rules) — neither is
repeated here.

**Constellation boundary (binding, restated from the WP-1 task brief's constellation context,
which wins on any conflict).** CSApp is a separate app, its own repo, its own build brief. Core
imports FROM it via files only — never a shared database, never IPC (`docs/ratified/
MANUAL_INTEGRATION_GRAMMAR.md` §1, INT-003/INT-004). CSApp remains fully useful standalone
(INT-002) — nothing in this document asks CSApp to change anything about how it manages issues
internally; it only specifies the shape of what CSApp already exports.

---

## 1. The exported shape (INT-013)

**INT-013 — ACCEPTED.** `schemas/integrations/issues-manifest.v1.schema.json` — `IssuesManifest`.
One file, one snapshot, of one project's CSApp issue backlog:

| Field | Type | Notes |
|---|---|---|
| `schemaVersion` | string, `^1\.\d+\.\d+$` | FB-RAT-COM-003 — unsupported major is `IMPORT_UNSUPPORTED_MAJOR`. |
| `exportId` | string | This export's own stable ID (FB-RAT-COM-002) — distinct from any issue's id. |
| `exportedAt` | date-time | UTC instant CSApp produced the export (FB-RAT-COM-004). |
| `producer.app` / `producer.version` | string / string | Field name is `app`, not `name` — mirrors the source pack's worked field list exactly. |
| `projectRef.externalId` | string | CSApp's own stable project identifier. |
| `issues[]` | array of `CsAppIssue` | May be empty — a project with no open issues is a legitimate export. |

Each `issues[]` entry:

| Field | Type | Notes |
|---|---|---|
| `id` | string | CSApp's own per-issue id, scoped to `projectRef.externalId`. |
| `title` | string | |
| `detail` | string | Free text. No `maxLength` — see `fixtures/integrations/valid/issues-manifest-oversized-valid.json` for why (§4). |
| `severity` | `SEV1`\|`SEV2`\|`SEV3`\|`SEV4` | CSApp's own closed vocabulary, SEV1 highest. |
| `reporterRef` | string | Opaque CSApp-side identity reference, preserved as-is (FB-RAT-COM-008 provenance). |
| `occurredAt` | date-time | |
| `updatedAt` | date-time | Together with `sourceRevision`, distinguishes a changed-issue re-import from a duplicate-snapshot re-import (§2). |
| `sourceRevision` | string \| null | Optional; null is valid. |
| `status` | string \| null | Descriptive only — §3. |

`IssuesManifest` is deliberately **not** wrapped in a `ContractEnvelope` (unlike this
constellation's own internal contracts) — CSApp is a fully independent app that MUST NOT need to
depend on this repo's envelope library just to write its own export file (INT-002).

## 2. Dedupe key (INT-013, ties to INT-006)

The dedupe key across imports is **`(projectRef.externalId, issues[].id)`** — a pair, not either
field alone (the same CSApp issue id could in principle repeat across different CSApp projects).
`updatedAt` plus the optional `sourceRevision` are what distinguish, for a source ID already seen
before:

- **Changed issue** — `updatedAt` (or `sourceRevision`, when present) has advanced since the prior
  import → `ImportPreview.changedRecords` / `ImportReceipt.updated`.
- **Duplicate snapshot** — the whole export's digest is byte-identical to a prior import → `IMPORT_
  DUPLICATE_SNAPSHOT`, `ImportPreview.duplicateRecords` / `ImportReceipt.duplicates` (INT-006,
  `docs/ratified/MANUAL_INTEGRATION_GRAMMAR.md` §5). See `fixtures/integrations/valid/
  issues-manifest-duplicate-resubmit-valid.json`, which is byte-for-byte identical to `fixtures/
  integrations/valid/issues-manifest-baseline-valid.json` — exactly the resubmission scenario this
  rule detects.

A same-`id` issue whose **identity data** (not just its mutable fields) conflicts with what Core
already has on file for that source ID — e.g. a different `reporterRef` or `occurredAt` on a
supposedly-unchanged issue — is `IMPORT_RECORD_CONFLICT`, not a plain "changed" record. See
`fixtures/integrations/valid/issues-manifest-conflict-identity-mismatch-valid.json`.

## 3. Source status is descriptive only (INT-015)

**INT-015 — ACCEPTED.** Source status is descriptive only; Studio maps to Incident state after
user confirmation. `CsAppIssue.status` is a free-text field (e.g. `"open"`, `"triaged"`,
`"reopened"`) — CSApp's own status vocabulary, not a closed enum this schema constrains, and NOT a
direct key into Studio's Incident lifecycle. A reader:

- **MUST NOT** auto-map `status` onto an Incident state as part of Preview/Confirm — the mapping
  is offered as a suggestion at most (an "allowed convenience" per `docs/ratified/
  MANUAL_INTEGRATION_GRAMMAR.md` §2's suggested-mapping convenience), and the user still confirms
  it explicitly.
- **MAY** display `status` verbatim alongside the neutral `CustomerIssue` record as read-only
  context.

This is the CSApp-lane instance of producer neutrality (INT-008) applied to a specific field: a
producer's own status text is data CSApp offers, not a command CSApp gets to issue about Studio's
own state machine.

## 4. Deliberately out of scope here

- **Evidence bundle (INT-014, EXPERIMENTAL).** The source pack names an "optional evidence bundle
  (attachment refs by filename plus digest)" as a possible future addition. It is NOT modeled as a
  named property in `schemas/integrations/issues-manifest.v1.schema.json` — see that schema's
  `unknownFields` description. Cross-reference `docs/non_ratified/EXPERIMENTAL_DECISIONS.md`
  (owned by a separate agent/session) — not designed here, per the WP-1 task brief's explicit
  instruction not to design it in this document.
- **Return packet (INT-017, DEFERRED).** A resolution summary sent back to CSApp after Studio
  resolves an Incident is not part of this v1 lane — CSApp's own file format has no read side for
  one, and this document does not invent one. Cross-reference `docs/non_ratified/
  DEFERRED_DECISIONS.md` (owned by a separate agent/session).
- **Oversized exports.** `detail` has no `maxLength` and `issues[]` has no `maxItems` —
  `schemas/integrations/issues-manifest.v1.schema.json` deliberately leaves per-record and
  per-export size unconstrained; a real importer applies its own byte-budget handling (mirrors
  `ExecutionRequest.budget.maxOutputBytes`'s pattern in the execution domain, applied here at the
  transport/import-pipeline layer rather than in the wire schema). See `fixtures/integrations/
  valid/issues-manifest-oversized-valid.json` (40 issues, one with an ~6000-character `detail`).

## 5. Fixtures

`fixtures/integrations/{valid,invalid,adversarial}/issues-manifest-*.json` — the full sextet
(baseline / duplicate-resubmit / conflict-identity-mismatch / partial-batch / oversized, all
structurally valid, plus one structural-failure `invalid` fixture) plus one adversarial
(`issues-manifest-producer-neutrality-task-injection.adversarial.json`, §3 of `docs/ratified/
MANUAL_INTEGRATION_GRAMMAR.md` §6's INT-008 pointer). Validated via Python `jsonschema`
`Draft202012Validator` — results in this work package's final report.
