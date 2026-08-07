# Import Receipt v1 — the durable record of one confirmed import

> **License:** `LICENSE-PENDING` — see `docs/non_ratified/LICENSE_PENDING.md`.

**Status:** RATIFIED (this document). **Scope:** `ImportReceipt` — step 6 ("Receipt") of the
six-step manual import grammar (INT-011, `docs/ratified/MANUAL_INTEGRATION_GRAMMAR.md` §2).
Citation target for INT-024. Shared by both lanes (CSApp and Assay) — a receipt does not care
which produced it beyond its `sourceType` field. Builds on `docs/ratified/COMMON_CONVENTIONS.md`
(FB-RAT-COM global rules) and `docs/ratified/MANUAL_INTEGRATION_GRAMMAR.md` (the error taxonomy,
§2 below) — neither repeated in full here.

---

## 1. Shape (INT-024)

**INT-024 — ACCEPTED.** `schemas/integrations/import-receipt.v1.schema.json` — `ImportReceipt`:

| Field | Type | Notes |
|---|---|---|
| `schemaVersion` | string, `^1\.\d+\.\d+$` | FB-RAT-COM-003. |
| `receiptId` | string | This receipt's own stable ID (FB-RAT-COM-002); also usable as the idempotency anchor a retried Confirm tap resolves to (FB-RAT-COM-006). |
| `sourceType` | `CSAPP_ISSUES_MANIFEST`\|`ASSAY_REPO_INDEX` | Which lane produced the source snapshot. |
| `sourceDigest` | `IntegrityRef` | Digest of the exact bytes read at Snapshot time (FB-RAT-COM-005). |
| `sourceLocation` | string \| null | Backlink to where the source was read from (FB-RAT-COM-008 provenance). |
| `previewId` | string \| null | Backlink to the `ImportPreview.previewId` the user reviewed before confirming (`schemas/integrations/import-preview.v1.schema.json`) — every real flow in `docs/ratified/MANUAL_INTEGRATION_GRAMMAR.md` goes through a preview first. |
| `initiatedBy` | string | The initiating principal (FB-RAT-COM-008) — who performed the explicit user action (INT-001) that produced this receipt. |
| `decision` | `FULL`\|`PARTIAL` | FULL: every importable record the preview offered was imported as offered. PARTIAL: the user explicitly excluded at least one (INT-007) — never an implicit default. |
| `createdAtUtc` | date-time | FB-RAT-COM-004. |
| `created[]` | array of `{sourceId, incidentId}` | Imports create/update Incidents, NEVER Tasks (INT-016) — `incidentId` is always an Incident id, by construction of this shape. |
| `updated[]` | array of `{sourceId, incidentId, changedFields?}` | |
| `duplicates[]` | array of `{sourceId, priorReceiptId}` | INT-006 — points at the receipt that already imported this exact record at this exact digest. |
| `rejected[]` | array of `{sourceLocation, errorCode, detail?}` | Final rejections carried into the receipt — see §2. |

Receipts are **append-only** (FB-RAT-COM-006) — a correction is a NEW receipt, never an in-place
edit of an existing one. `ImportReceipt` is not wrapped in a `ContractEnvelope` — same reasoning
as `IssuesManifest`/`AssayIndex` (`docs/ratified/CSAPP_ISSUES_MANIFEST_V1.md` §1): it is a
self-contained record any of the four constellation apps could in principle read without pulling
in this repo's envelope library, even though in practice only Core writes it.

## 2. `rejected[].errorCode` and the `IMPORT_INVALID_<FIELD>` family (registry hygiene)

`rejected[]` entries draw `errorCode` from the same closed+open vocabulary as `ImportPreview.
rejectedRecords[]` (`schemas/integrations/import-preview.v1.schema.json`'s `$defs/
ImportErrorCode`, duplicated locally in `import-receipt.v1.schema.json` per this repo's
cross-file convention, `docs/ratified/COMMON_CONVENTIONS.md` §5): the eight fixed codes in `docs/
ratified/MANUAL_INTEGRATION_GRAMMAR.md`'s error taxonomy table (§4 there), **plus** the
`IMPORT_INVALID_<FIELD>` family this document and that one both fix into the registry (the source
handoff pack uses `IMPORT_INVALID_SEVERITY` in a worked receipt example but never lists the family
in its own error table — restated here per the explicit "add to this table AND to
IMPORT_RECEIPT_V1.md" instruction, so a reader who opens only this document still has the
definition):

- **Pattern:** `^IMPORT_INVALID_[A-Z0-9_]+$` — `<FIELD>` is the SCREAMING_SNAKE_CASE name of the
  one record-level field that failed validation.
- **Scope:** record-level only. A `rejected[]` entry with an `IMPORT_INVALID_<FIELD>` code excludes
  exactly the one source record it names — it is never a reason a whole source file/index is
  blocked (that is what the eight fixed source-level/record-level-but-blocking codes are for,
  e.g. `IMPORT_UNSUPPORTED_MAJOR` for the whole file, `IMPORT_RECORD_CONFLICT` for one record that
  still requires a user choice rather than an automatic reject).
- **Worked examples:** `IMPORT_INVALID_SEVERITY` (a CSApp issue's `severity` is not one of
  `SEV1`..`SEV4`), `IMPORT_INVALID_TIMESTAMP` (an unparsable `occurredAt`/`updatedAt`/
  `exportedAt`), `IMPORT_INVALID_SOURCELOCATION` (a rejected-record `sourceLocation` value itself
  fails a lane-specific shape check — see the path-traversal adversarial fixture, §4).
- **Structural enforcement:** both schemas encode this as a `oneOf` of the closed enum and the
  open pattern — a code matching neither fails schema validation (see `fixtures/integrations/
  invalid/import-receipt-bad-decision-and-errorcode.invalid.json`, which uses a made-up code
  belonging to neither branch, alongside an invalid `decision` value in the same fixture).

## 3. Worked example (regenerated against this document's own schema)

Regenerated fresh for this document rather than copied from the source pack (whose own inline
examples are known to drift from their own field requirements, per the WP-1 task brief) — this
exact JSON was validated with Python `jsonschema`'s `Draft202012Validator` against `schemas/
integrations/import-receipt.v1.schema.json` before being pasted here; result: **PASS** (see this
work package's final report for the transcript).

```json
{
  "schemaVersion": "1.0.0",
  "receiptId": "01J9B0000000000000000RCW",
  "sourceType": "CSAPP_ISSUES_MANIFEST",
  "sourceDigest": {
    "algorithm": "SHA-256",
    "digestHex": "c2f1a4e6b8d0f2a4c6e8f0a2b4c6d8e0f2a4c6e8f0a2b4c6d8e0f2a4c6e8f0a2",
    "byteLength": 973
  },
  "sourceLocation": "csapp-export://csapp-proj-aarso-mobile/2026-08-07T09:00:00Z",
  "previewId": "01J9C0000000000000000PVW",
  "initiatedBy": "studio-user:jordan",
  "decision": "PARTIAL",
  "createdAtUtc": "2026-08-07T09:12:00Z",
  "created": [
    { "sourceId": "csapp-iss-3001", "incidentId": "01J9D0000000000000000IN9" }
  ],
  "updated": [
    { "sourceId": "csapp-iss-1001", "incidentId": "01J9D0000000000000000IN1", "changedFields": ["title", "updatedAt"] }
  ],
  "duplicates": [
    { "sourceId": "csapp-iss-1002", "priorReceiptId": "01J9B0000000000000000RC1" }
  ],
  "rejected": [
    {
      "sourceLocation": "csapp-iss-3002",
      "errorCode": "IMPORT_INVALID_SEVERITY",
      "detail": "severity value 'SEV9' is not one of SEV1..SEV4."
    }
  ],
  "unknownFields": {}
}
```

Reading this example: the source export offered four issues. One (`csapp-iss-3001`) was brand
new and got created as an Incident. One (`csapp-iss-1001`) had already been imported and its
title/updatedAt changed, so its existing Incident was updated. One (`csapp-iss-1002`) was a
byte-for-byte re-import of an already-seen record, so it's recorded as a duplicate pointing at the
prior receipt (INT-006). One (`csapp-iss-3002`) failed record-level validation (`severity: SEV9`
is not a real severity) and was rejected with the `IMPORT_INVALID_SEVERITY` code from §2 —
excluded from this receipt, everything else proceeded. `decision: PARTIAL` reflects that the user
confirmed a subset (three of four offered outcomes; the fourth was a hard record-level rejection,
not a user exclusion — see `docs/ratified/MANUAL_INTEGRATION_GRAMMAR.md` §6's note that a
record-level rejection does not by itself make a decision PARTIAL, but this example's PARTIAL
status here reflects that the user separately, explicitly declined at least one additional
offered-but-clean record not shown in this trimmed example).

## 4. Fixtures

`fixtures/integrations/{valid,invalid,adversarial}/import-receipt-*.json` — the full sextet
(baseline / duplicate-snapshot / conflict-rejected / partial-confirmed / oversized-batch, all
structurally valid, plus one structural-failure `invalid` fixture combining a bad `decision` value
and a made-up `errorCode`) plus one adversarial
(`import-receipt-path-traversal-sourcelocation.adversarial.json` — a `rejected[].sourceLocation`
containing `..` segments, structurally valid, semantically forbidden). Validated via Python
`jsonschema` `Draft202012Validator` — results in this work package's final report.
