# Incident Source & Proof Contract — what an import produces downstream

> **License:** `LICENSE-PENDING` — see `docs/non_ratified/LICENSE_PENDING.md`.

**Status:** RATIFIED (this document). **Scope:** the unified `Incident` object model both import
lanes feed, proof-gated resolution, and release readiness's read-only relationship to it. Citation
target for INT-016, INT-021, INT-023. Builds on `docs/ratified/MANUAL_INTEGRATION_GRAMMAR.md`
(six-step grammar, responsibility matrix, error taxonomy) and `docs/ratified/IMPORT_RECEIPT_V1.md`
(`created[]`/`updated[]` entries this document's Incidents are the target of) — neither repeated
here. This document does not itself define a new JSON Schema file (the WP-1 task brief's schema
list for this domain is CSApp/Assay/preview/receipt/proving-tests only, §6) — `Incident` is a
Studio-owned object per the responsibility matrix (`docs/ratified/MANUAL_INTEGRATION_GRAMMAR.md`
§3), and this document specifies the CONTRACT Studio's Incident model must honor from the import
side, not Studio's own storage shape.

**A note on scope of citation.** INT-021 is named in the WP-1 task brief's decision-ID list for
this document without accompanying prose. Given its position in the brief's own text — directly
between INT-020 ("Studio never invokes or polls Assay," `docs/ratified/ASSAY_REPO_CONTRACT_V1.md`
§5) and the "INCIDENT AND PROOF CONTRACT" section's proof-gated-resolution paragraph that
immediately follows it — this document attaches INT-021 to that proof-gated-resolution rule (§2).
Flagged here explicitly, same as INT-005/009/010 in `docs/ratified/MANUAL_INTEGRATION_GRAMMAR.md`,
so the non-ratified-registers agent can correct the mapping if the source pack's real intent
differs.

---

## 1. Unified Incident model (INT-016)

**INT-016 — ACCEPTED (restated from `docs/ratified/MANUAL_INTEGRATION_GRAMMAR.md` §6, elaborated
here).** Incidents are created/updated only from imports, or manually by a Studio user — never
from any other path. One `Incident` unifies records from both sources:

| Facet | CSApp-sourced | Assay-sourced |
|---|---|---|
| Source badge | `CSAPP` | `ASSAY` |
| Severity | CSApp's `SEV1`..`SEV4` (`docs/ratified/CSAPP_ISSUES_MANIFEST_V1.md` §1) | Derived from the SARIF result's `level`/rule severity (`docs/ratified/ASSAY_REPO_CONTRACT_V1.md` §4) — a DIFFERENT closed vocabulary, not coerced into `SEV1`..`SEV4` at the wire layer; Studio's own Incident display normalizes both for presentation, this document does not collapse them into one shared wire enum. |
| Proof state | Not applicable at import time — a CSApp issue carries no proving test | §2 |
| Task links | Populated only by a later, separate, explicit user action (INT-016) | same |
| Decisions | Recorded per the owning `ImportReceipt` (`docs/ratified/IMPORT_RECEIPT_V1.md`) plus any manual edits Studio itself allows | same |
| Resolution gate | Not proof-gated (no proving-test concept in the CSApp lane) | §2 — proof-gated |

**Producer neutrality, restated for this object specifically (INT-008):** CSApp never writes
Incidents' Task links; Assay never mutates an Incident's CSApp-sourced fields, or vice versa.
`ImportReceipt.created[]`/`updated[]` entries are always `{sourceId, incidentId}` — never a Task
id — by construction of that schema (`docs/ratified/IMPORT_RECEIPT_V1.md` §1), which is what
makes "imports never create Tasks" a shape fact, not just a policy sentence.

## 2. Proof-gated resolution (INT-021)

**INT-021 — ACCEPTED.** An Incident's resolution is gated on proof for Assay-sourced Incidents:

- A **proving test** (`schemas/integrations/proving-tests.v1.schema.json`, `docs/ratified/
  ASSAY_REPO_CONTRACT_V1.md` §3) with `status: PASSING`, imported and linked via its `testId`
  (matching the finding's `properties.provingTestRef`, §4 of that document), **MAY satisfy** the
  proof requirement for resolving the Incident that finding backs.
- **Missing proof blocks resolution** — `PROOF_MISSING` (`docs/ratified/
  MANUAL_INTEGRATION_GRAMMAR.md` §4's error taxonomy) is the state, not a hard wall with no way
  forward. Two ways out, both explicit:
  1. **Keep unresolved.** The default — an Incident with no satisfying proof simply stays open.
  2. **Permanent override receipt.** A Studio user MAY explicitly record an override — a durable,
     append-only record (mirrors `docs/ratified/IMPORT_RECEIPT_V1.md`'s append-only pattern,
     FB-RAT-COM-006) stating who overrode the proof requirement, when, and why, attached to the
     Incident permanently. This document does not mint a new top-level schema file for it (out of
     scope per this domain's schema list, §6 of the WP-1 task brief) — `contracts/kotlin/
     IntegrationContracts.kt`'s `ProofRef` sealed type carries a `PermanentOverride` case
     (`recordedBy`, `recordedAtUtc`, `justification`) as the in-memory shape a future Studio-owned
     schema would serialize; see that file's KDoc for the exact fields.
  - An override is **permanent** — it does not expire, and it does not silently disappear if the
    Incident is later reopened; a reopened Incident that still lacks real proof shows the override
    on record, not a clean slate.

A CSApp-sourced Incident has no proving-test concept at all (§1) — its resolution is a plain
Studio-side state change with no proof gate. Proof-gating is specific to the Assay lane, because
only Assay produces the proving-test artifact a gate could check.

## 3. Owner design correction — Audit surface (DESIGN NOTE, locked)

> **DESIGN NOTE, not a decision this document is making or re-litigating.** The owner has locked
> the following correction to how the Audit surface (Studio's Assay-facing UI) presents
> Assay-sourced Incidents: **the Audit surface is a to-do list with quick-insert prompt triggers**
> — not a dashboard, not a severity-sorted table as the primary view. This document states the
> correction as given, because the WP-1 task brief instructs recording it here; it does not
> attempt to further specify the to-do-list UI itself (screen layout, exact prompt-trigger
> wording, etc.) — that is Studio's own UX design work, out of scope for a Core-owned contract
> document, and not something this session is positioned to design well without the owner's
> direct input on it.

This design note constrains Studio's presentation, not this domain's wire contracts — every
schema in `schemas/integrations/*.schema.json` is unaffected by it; it is recorded here purely so
a future Studio-side session implementing the Audit surface has the correction on file next to the
contract it presents.

## 4. Release readiness is read-only (INT-023)

**INT-023 — ACCEPTED.** Release readiness reads incidents/proof but never writes policy back to
producers. A release-readiness check (wherever Studio implements one) MAY read an Incident's
severity, proof state, and resolution status to decide whether a release is ready — it MUST NOT:

- Write anything back into a CSApp export or an Assay repo output (`.assay/` tree) — those are the
  producers' own artifacts, read-only from Core's perspective always (INT-002, INT-008).
- Silently change an Incident's proof state or resolution to make a release "look" ready — any
  state change goes through the same explicit-user-action discipline as every other Studio
  mutation (INT-001).
- Treat "release readiness says not ready" as authority to auto-resolve, auto-override, or
  auto-suppress the blocking Incident — a human decision (§2's keep-unresolved / permanent
  override choice) is still required.

This is the release-readiness-specific instance of the same read-only, no-contract-bypass posture
`docs/ratified/ASSAY_REPO_CONTRACT_V1.md` §5 states for the Audit flow generally (FB-RAT-COM-012).

---

## Forward pointers (owned elsewhere, not restated here)

- **Studio's own Incident storage schema, board/list UI, the Audit to-do-list surface's exact
  design** — Studio-owned (`docs/ratified/MANUAL_INTEGRATION_GRAMMAR.md` §3's responsibility
  matrix), not designed in this Core-owned document.
- **`PermanentOverride`'s exact wire shape as a standalone schema file** — not minted in this work
  package (out of scope, §6 of the WP-1 task brief); `contracts/kotlin/IntegrationContracts.kt`'s
  `ProofRef` sealed type is the in-memory shape a future schema would mirror.
- **`IMPORT_INVALID_<FIELD>` and the rest of the error taxonomy `PROOF_MISSING` belongs to** —
  `docs/ratified/MANUAL_INTEGRATION_GRAMMAR.md` §4 / `docs/ratified/IMPORT_RECEIPT_V1.md` §2.
