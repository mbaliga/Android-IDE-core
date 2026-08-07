# Manual Integration Grammar — the `integration` domain

> **License:** `LICENSE-PENDING` — see `docs/non_ratified/LICENSE_PENDING.md`.

**Status:** RATIFIED (this document). **Scope:** the `integration` domain named in the WP-1 task
brief — the one shared grammar every cross-app import lane (CSApp's file lane, Assay's repo lane)
follows, the Core/Studio/CSApp/Assay responsibility split, and the import error taxonomy every
lane's `rejected[]` entries draw from. This is the citation target for INT-001, 002, 005, 006,
007, 008, 009, 010, 011, 012, 022, 025, 026. Per-lane specifics live in their own documents and
are not repeated here: `docs/ratified/CSAPP_ISSUES_MANIFEST_V1.md` (CSApp), `docs/ratified/
ASSAY_REPO_CONTRACT_V1.md` (Assay), `docs/ratified/IMPORT_RECEIPT_V1.md` (the receipt shape),
`docs/ratified/INCIDENT_SOURCE_AND_PROOF_CONTRACT.md` (what an import produces downstream).

**Why this domain is genuinely greenfield.** Per the WP-0 survey (`docs/WP0_SURVEY.md` §1(f)),
none of the four constellation repos contains any "CSApp" or "Assay" reference today — no code,
no docs, no vocabulary. This document does not extend or correct any existing artifact; it is the
first one. It builds on, and does not repeat, `docs/ratified/COMMON_CONVENTIONS.md`'s
`ContractEnvelope`/`ErrorEnvelope`/`ArtifactRef` shapes and the FB-RAT-COM-001..012 global rules —
every schema this domain defines (`schemas/integrations/*.schema.json`) cites those rules inline
rather than restating them here.

**A note on scope of citation.** INT-005, INT-009, and INT-010 are named in the WP-1 task brief's
decision-ID list for this document without accompanying prose (unlike INT-001/006/007/008/011/
012/022, which the brief spells out verbatim). This document attaches them to the load-bearing
step-level obligations they most plausibly cover — INT-005 to "Choose source" being itself an
explicit, distinct user action from "Snapshot" (§2, step 1→2), INT-009 to the Validate step's
schema-version/producer/project-match/warnings/errors checklist (§2, step 3), INT-010 to the
Preview step's before-any-mutation guarantee (§2, step 4). This mapping is this document's own
placement, not a restatement of pack text this session was given — flagged here explicitly so the
non-ratified-registers agent can correct it if the source pack's real intent differs.

---

## 1. Non-negotiable boundaries

**INT-001 — ACCEPTED.** Every cross-app transfer begins with an explicit user action: Export /
Select file / Select branch / Refresh / Preview / Confirm. There is no code path in this domain
that reads a CSApp or Assay artifact as a side effect of anything else — no background sync, no
app-open auto-refresh, no push-triggered pull.

**INT-002 — ACCEPTED.** Assay and CSApp remain fully useful standalone. Neither app's own value
proposition depends on Fonebrew ever importing from it — the manifest/index files this domain
reads are each app's own native export/output, not a Fonebrew-specific format bolted on.

**INT-003 — REJECTED (cross-reference only).** Shared databases, account backends, background
services, broadcasts, automatic sync are explicitly rejected integration mechanisms. This document
states the prohibition; a separate agent/session is filing the full rationale into `docs/
non_ratified/REJECTED_ALTERNATIVES.md` — not restated here.

**INT-004 — REJECTED (cross-reference only).** v1 app-to-app IPC is explicitly rejected. Same
cross-reference as INT-003 — `docs/non_ratified/REJECTED_ALTERNATIVES.md`, not restated here.

Both rejections are why every schema in `schemas/integrations/*.schema.json` is a **file-shaped
artifact** (a manifest, an index, a preview, a receipt) rather than an API request/response pair —
there is no live channel between apps to design an API over in the first place.

## 2. The six-step manual import grammar (INT-011)

**INT-011 — ACCEPTED.** One grammar, for every lane (CSApp file lane and Assay repo lane alike).
No lane skips a step or reorders it.

| # | Step | What happens | Who can act |
|---|---|---|---|
| 1 | Choose source | User picks a file, a repo+branch, or a remembered shortcut (INT-012, §8). Nothing is read yet — choosing is not importing (INT-005). | User only |
| 2 | Snapshot | Core reads the exact file/commit bytes at this instant and computes their digest (FB-RAT-COM-005). This is the byte-for-byte content the rest of the flow reasons about, even if the source changes a moment later. | Core, triggered by the user's choice in step 1 |
| 3 | Validate | Core checks schema version (reject unknown MAJOR — IMPORT_UNSUPPORTED_MAJOR), producer, project match (never auto-mapped — IMPORT_PROJECT_MISMATCH), and collects warnings/errors per record (INT-009). Nothing is created or changed yet. | Core |
| 4 | Preview | Core computes new / changed / duplicate / conflicting / rejected records — `schemas/integrations/import-preview.v1.schema.json` — strictly BEFORE any mutation (INT-010). This is what the user reviews. | Core computes, user reviews |
| 5 | Confirm | User selects which records to import; explicit approval is required for a partial import (INT-007) — there is no default "import everything" that fires without this tap. | User only |
| 6 | Receipt | Core writes an append-only `ImportReceipt` (`schemas/integrations/import-receipt.v1.schema.json`, FB-RAT-COM-006) recording source digest, decisions, resulting objects, errors, and backlinks. | Core |

**Allowed conveniences (INT-011, no automatic sync introduced by any of them):**

- Remembered source (INT-012, §8) — a shortcut into step 1, never a trigger to skip to step 2.
- Diff-since-last-import, but only after an explicit user-tapped Refresh — Refresh re-enters the
  grammar at step 1, it does not create a background poll.
- Suggested mapping at Validate/Preview time, which the user still confirms — a suggestion is not
  an auto-map (consistent with IMPORT_PROJECT_MISMATCH's "never auto-map" rule, §5).
- Batch-select safe new records at Confirm, with separate conflict/rejection counts shown
  alongside — the user still sees and acts on the full picture, not just a success count.
- Next-action offers after a Receipt: Create task / Add to Watch / Copy rerun command / Open
  source. Each is itself a fresh explicit user action, not something the receipt triggers on its
  own (INT-016, INT-022, §6/§7).
- Watch reminders (INT-022, §7).

## 3. Responsibility matrix

Who owns what across the four cooperating apps. "Must not own" entries are as binding as "owns" —
a design that gives one of these components a capability in its "must not own" column is a
contract bypass (FB-RAT-COM-012, §9 of `docs/ratified/COMMON_CONVENTIONS.md`), not a convenience.

| Component | Owns | Must not own |
|---|---|---|
| **Core** (this repo) | Schema parsers, validation, dedupe primitives, neutral `CustomerIssue`/`AuditFinding`/`ProofRef` records, import preview + receipts, repo/file access | Studio priority/boards/incident workflow, release policy, producer control |
| **Studio** | Business & Audit import UX, Incident/Task lenses, source backlinks, proof-gated resolution, Watch, guided next steps | Background sync, producer data mutation, hidden task creation, faking external reruns |
| **CSApp** | Issue capture, severity, `reporterRef`, `occurredAt`, local issue management, explicit export | Studio Tasks/Incidents, repo mutations, background transfer |
| **Assay** | Scanning (MobSF/OSV-Scanner/Semgrep/Gitleaks as the **SOLE** finding sources), SARIF/JSON findings, proving tests, result index, explicit commit/push to a user-selected branch | Studio incident lifecycle, remediation decisions, release gates, auto-import |

## 4. Import error taxonomy

Every lane's `rejected[]`/`rejectedRecords[]` entry (`schemas/integrations/import-preview.v1.
schema.json`, `import-receipt.v1.schema.json`) carries an `errorCode` drawn from this table. Codes
are stable across releases (FB-RAT-COM-007) — never localized, never reused for a different
meaning.

| Code | Meaning | Required UX |
|---|---|---|
| `IMPORT_UNSUPPORTED_MAJOR` | Unknown major schema version | Block; show supported versions; source untouched |
| `IMPORT_PROJECT_MISMATCH` | Source names a different project | Explicit remap or cancel; never auto-map |
| `IMPORT_DIGEST_MISMATCH` | Index digest does not equal file bytes | Block file; mark source integrity failure |
| `IMPORT_DUPLICATE_SNAPSHOT` | Digest already imported | Show prior receipt; view-only |
| `IMPORT_RECORD_CONFLICT` | Same source ID, incompatible identity data | Require user choice; retain both values in receipt |
| `IMPORT_PARTIAL_SOURCE` | Producer marked run/export partial | Warn; explicit partial-import confirmation |
| `IMPORT_SOURCE_UNAVAILABLE` | File/branch/commit unreadable | Explain manual recovery; do not imply sync failure |
| `PROOF_MISSING` | Resolution requires absent proof | Keep unresolved or permanent override receipt (`docs/ratified/INCIDENT_SOURCE_AND_PROOF_CONTRACT.md` §3) |
| `IMPORT_INVALID_<FIELD>` | **Registry-hygiene fix, this document.** A specific record-level field failed validation (e.g. `IMPORT_INVALID_SEVERITY`, `IMPORT_INVALID_TIMESTAMP`) | Reject that one record only; show the offending field + value; the rest of the batch is unaffected |

**Registry hygiene note.** The source handoff pack uses `IMPORT_INVALID_SEVERITY` in a worked
`ImportReceipt` example but never lists the `IMPORT_INVALID_<FIELD>` family in its own error
table — this is the gap this row fixes. `IMPORT_INVALID_<FIELD>` is a **family**, not a single
code: `<FIELD>` is the SCREAMING_SNAKE_CASE name of the specific record-level field that failed
(e.g. a CSApp issue with `severity: "SEV9"` produces `IMPORT_INVALID_SEVERITY`; one with an
unparsable `occurredAt` produces `IMPORT_INVALID_TIMESTAMP`). `schemas/integrations/import-
preview.v1.schema.json` and `import-receipt.v1.schema.json` both encode this structurally as
`$defs/ImportErrorCode`, a `oneOf` of the eight fixed codes above plus the pattern
`^IMPORT_INVALID_[A-Z0-9_]+$` — a code outside both fails schema validation (see `fixtures/
integrations/invalid/import-receipt-bad-decision-and-errorcode.invalid.json`, which uses a made-up
code matching neither branch). The same family + fix is restated in `docs/ratified/
IMPORT_RECEIPT_V1.md` per the task brief's explicit "add to this table AND to
IMPORT_RECEIPT_V1.md" instruction — this is the one piece of content this domain's docs
deliberately duplicate rather than cross-reference, because a reader who opens only the receipt
doc still needs the family definition to make sense of a receipt's `rejected[].errorCode`.

**A record-level `IMPORT_INVALID_<FIELD>` rejection excludes only that one record** — it is not a
source-level failure like `IMPORT_UNSUPPORTED_MAJOR`/`IMPORT_DIGEST_MISMATCH`, which block the
whole file. This is why it lives in `rejectedRecords[]`/`rejected[]` (per-record arrays) rather
than as a Validate-step, whole-source block reason.

## 5. Idempotent import & producer neutrality (INT-006, 007, 008)

**INT-006 — ACCEPTED.** Idempotent import: re-importing the same snapshot changes nothing, points
at the prior receipt. Operationalized as `IMPORT_DUPLICATE_SNAPSHOT` (§4) plus `ImportPreview.
duplicateRecords`/`ImportReceipt.duplicates`, each carrying `priorReceiptId` — a reader MUST NOT
create a second Incident for a source record it has already imported at the same digest.

**INT-007 — ACCEPTED.** No silent partial import. `ImportReceipt.decision` (`docs/ratified/
IMPORT_RECEIPT_V1.md`) is `FULL` or `PARTIAL`; `PARTIAL` MUST correspond to an explicit
Confirm-step user choice (step 5, §2) — never an implicit default when, say, a network hiccup
truncates a batch. A receipt cannot describe "we imported some of it and didn't tell you."

**INT-008 — ACCEPTED.** Producer neutrality: CSApp never writes Studio Tasks; Assay never mutates
Incidents. See `fixtures/integrations/adversarial/
issues-manifest-producer-neutrality-task-injection.adversarial.json` for the concrete violation
attempt this rule forbids — a CSApp export that stuffs a direct Task-creation instruction into its
payload, which a conformant Core reader MUST treat as inert passthrough, never as authority.

## 6. Task creation is a separate action (INT-016)

**INT-016 — ACCEPTED.** Imports create/update Incidents, NEVER Tasks — Task creation is a separate
explicit user action. `schemas/integrations/import-receipt.v1.schema.json`'s `created[]`/
`updated[]` entries are typed as `{sourceId, incidentId}` by construction — there is no field
shape in this domain's receipt an import could use to create a Task even if a producer asked it
to. Full detail (unified Incident model, source badges, proof gate) lives in `docs/ratified/
INCIDENT_SOURCE_AND_PROOF_CONTRACT.md`, not repeated here.

## 7. Watch is reminder-only (INT-022)

**INT-022 — ACCEPTED.** Watch is reminder-only. A Watch entry (Studio-owned, §3) never triggers a
read of CSApp/Assay on its own — it is a user-visible nudge ("you might want to re-check this
source") that still routes back through step 1 (Choose source / Refresh) of the grammar (§2) when
the user acts on it. A Watch entry that silently re-read a source on a timer would be exactly the
background-sync/automatic-sync pattern INT-003 rejects (§1).

## 8. Studio remembered shortcuts (INT-012)

**INT-012 — ACCEPTED.** Studio may remember source URIs/repo+branch shortcuts but MUST NOT read
them until the user taps Import or Refresh. A remembered shortcut is convenience data for step 1
of the grammar (§2) only — populating a picker, prefilling a field — never an autonomous trigger
into step 2 (Snapshot). Storing a shortcut is not itself a read; only the explicit tap is.

## 9. Release sequence (INT-026)

**INT-026 — documentation only; a later work package executes this.** Ordered:

1. **R1** — freeze the common grammar (envelope, errors, `ImportPreview`, `ImportReceipt`, source
   snapshot). This document plus `schemas/integrations/import-preview.v1.schema.json` and
   `import-receipt.v1.schema.json` are that freeze.
2. **R2** — CSApp file lane (`docs/ratified/CSAPP_ISSUES_MANIFEST_V1.md`,
   `schemas/integrations/issues-manifest.v1.schema.json`).
3. **R3** — Assay repo lane (`docs/ratified/ASSAY_REPO_CONTRACT_V1.md`,
   `schemas/integrations/assay-index.v1.schema.json` + `proving-tests.v1.schema.json`).
4. **R4** — Studio continuity (Incident/Task lenses, Watch, proof-gated resolution — `docs/
   ratified/INCIDENT_SOURCE_AND_PROOF_CONTRACT.md`).
5. **R5** — optional manual return packets (**DEFERRED, INT-027** — cross-reference `docs/
   non_ratified/DEFERRED_DECISIONS.md`, owned by a separate agent/session, not designed here).

---

## Forward pointers (owned elsewhere, not restated here)

- **CSApp's exact wire shape, dedupe key, status-mapping rule** — `docs/ratified/
  CSAPP_ISSUES_MANIFEST_V1.md` (INT-013, INT-015).
- **Assay's repo layout, flow, SARIF profile** — `docs/ratified/ASSAY_REPO_CONTRACT_V1.md`
  (INT-018, INT-019, INT-020).
- **`ImportReceipt`'s full field-by-field shape and worked example** — `docs/ratified/
  IMPORT_RECEIPT_V1.md` (INT-024).
- **What an import produces downstream — unified Incident, proof gate, Audit-surface design
  note** — `docs/ratified/INCIDENT_SOURCE_AND_PROOF_CONTRACT.md` (INT-016, INT-021, INT-023).
- **INT-003/004's full rejection rationale** — `docs/non_ratified/REJECTED_ALTERNATIVES.md`,
  owned by a separate agent/session, not restated here.
- **INT-014's evidence-bundle experiment / INT-027's return-packet deferral** — `docs/
  non_ratified/EXPERIMENTAL_DECISIONS.md` / `docs/non_ratified/DEFERRED_DECISIONS.md`, owned by
  separate agents/sessions, not restated here.
