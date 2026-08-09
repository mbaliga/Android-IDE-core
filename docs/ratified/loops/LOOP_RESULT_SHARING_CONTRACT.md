# Loop Result Sharing Contract

> **License:** `LICENSE-PENDING` — see `docs/non_ratified/LICENSE_PENDING.md`.

**Status:** RATIFIED (this document's own sections), with one named exception carried at its true
status: `FB-RAT-RES-007` is **DEFERRED** (`docs/non_ratified/DEFERRED_DECISIONS.md`), called out
inline at §10. **Scope:** the local-only, explicit-action process by which a user turns evidence
about one completed or terminal loop run into a redacted, previewed, consented, optionally-signed
result that leaves the phone — what MAY be included, what MUST be excluded, what a receipt's
signature can honestly be said to prove, and what happens when a shared result is later removed.
This document is the citation target for `FB-RAT-RES-001` through `FB-RAT-RES-007` and, for the
result-sharing surface specifically, `FB-RAT-MKT-008` (no phone telemetry).

This document does **not** redefine the `.floop` package's own digest, signature, or identity
fields (`LOOP_PACKAGE_SPEC.md`, `FB-RAT-PKG-*`) — a shared result's `packageContentDigest`,
`loopId`, and `semanticVersion` fields are exactly those objects, carried by reference, never
re-derived. It does not redefine the capability/authority ladder or the `PUBLISH_OR_RELEASE` rung
that gates the Share action (`CAPABILITY_AUTHORITY_MODEL.md`, `FB-RAT-AUTH-002`;
`capability-ids.v1.json`'s `fb.marketplace.publish`). It does not redefine the general execution
lifecycle a run's terminal state draws from (`EXECUTION_CONTRACT.md`, `FB-RAT-EXE-002`) or the
marketplace's own listing/review/moderation/takedown machinery (`LOOP_MARKETPLACE_CONTRACT.md`,
`FB-RAT-MKT-*`) — that document is not yet written under `docs/ratified/loops/` as of this
document; this document only states the result-sharing-specific consequences at the points where
they touch a shared result rather than a listing or a package. A conflict between this document's
prose and a frozen registry's data, or an already-ratified sibling document's decision, is this
document's error, and the other side wins.

## Note on this document's structure

The extracted source (`LOOP_RESULT_SHARING_CONTRACT.md` under
`inputs/dual_surface/specs/`) is a first-pass draft with a second, overlapping pass appended, per
`10_DUAL_VALIDATION_ADDENDUM.md` §B2 — that section names this file specifically: *"RESULT_SHARING
§6 and §13 both 'Verification claims'"*. That same first-pass/second-pass split repeats through
two further pairs the addendum's file-level note does not spell out by number but which are the
same defect class — duplicate topics under continuing numbers, not duplicate section numbers:
draft §8 ("Revocation and deletion") and draft §14 ("Removal and retention") state overlapping
service obligations for a shared result that has to come down; draft §9 ("Aggregation warning")
and draft §15 ("Aggregate statistics") state overlapping constraints on ever publishing aggregate
success/failure numbers. Draft §7's nine numbered pipeline steps (§90–§98, a stray global
numbering artifact from the source docx, not a defect in its own right) and draft §10's "Allowlist
construction" restate the same construction-then-consent flow draft §2 (Trigger) and draft §3
(Preview) already state as separate short sections — folded into one state machine below (§3)
rather than kept as four separate prose fragments, so the flow a shared result actually goes
through is stated once, as a table, not scattered across four headings and a bare numbered list.

Every one of the 15 draft sections is folded into exactly one section below; nothing is dropped.

| Draft § | Draft topic | This document's § |
|---|---|---|
| 1 | Objective | §1 |
| 2 | Trigger | §2, §3 |
| 3 | Preview | §3 |
| 4 | Default allowlist | §4 |
| 5 | Default denylist | §5 |
| 6 | Verification claims (first pass) | §6 |
| 7 | Redaction pipeline (steps 90–98) | §3 |
| 8 | Revocation and deletion | §9 |
| 9 | Aggregation warning | §10 |
| 10 | Allowlist construction | §3 |
| 11 | Consent receipt | §7 |
| 12 | Local and remote ratios | §8 |
| 13 | Verification claims (second pass) | §6 |
| 14 | Removal and retention | §9 |
| 15 | Aggregate statistics | §10 |

**Corrections applied, beyond de-duplication (detailed where they appear):**

1. **§6 and §13 merged into one Verification Claims section (§6), and `FB-RAT-RES-004` is
   corrected, not just relabeled.** The source's claim — *"a runtime-signed result proves that the
   stated Fonebrew runtime signed the redacted summary"* — is unsupported: nothing in either pass,
   or anywhere else in the handoff pack, specifies where the signing key that would make this true
   comes from. `10_DUAL_VALIDATION_ADDENDUM.md` §B5 traces every implementable option and finds
   each fails the stated claim (detailed at §6 below). The label `RUNTIME_ATTESTED` is renamed
   **`SELF_SIGNED_RECEIPT`**, the claim is narrowed to what a self-generated key can actually prove
   ("the same key signed these N receipts" — pseudonymous continuity, never runtime authenticity),
   and a normative key-generation/storage/rotation clause is added — none of which the source
   states at all.
2. **§8 and §14 merged into one Revocation/Deletion/Removal section (§9).** Neither draft pass, nor
   any decision ID assigned to this document, fully covers the service-side obligation this section
   states; the gap is flagged inline as `FB-RAT-RES-008`, **PROPOSED, not self-ratified**, for the
   Amendments-phase agent to accept, reject, or fold into an existing ID (§9, §11).
3. **§9 and §15 merged into one Aggregate Statistics section (§10), kept at its true status.**
   `FB-RAT-RES-007` is **DEFERRED** — this section states the *conditions* a future aggregation
   would have to satisfy, per the source, but is not a live specification a marketplace surface may
   build against yet.
4. **§2 (Trigger), §3 (Preview), §7 (redaction pipeline, steps 90–98), and §10 (allowlist
   construction) are merged into one state machine (§3)**, rendered as a from-state/event/to-state
   table rather than the source's mix of prose and a bare numbered list.
5. **A coarse-bucketing requirement is added to §4 (Default allowlist) that the source never
   states at all.** Durations, retry totals, per-node counts, and token/cost aggregates are a
   high-dimensional quasi-identifier when reported as exact values alongside an exact
   `packageContentDigest` — USENIX Security 2024 work on token-length side channels demonstrates
   that unbucketed length/count metadata alone, with no plaintext, can reconstruct substantial
   LLM-generated content. §4 states the bucketing requirement normatively.
6. **"node-state counts and stable public node IDs where permitted" (draft §4) is given a concrete
   reading of "where permitted."** New normative text, not stated in either draft pass: node IDs
   are stable and public per `FB-RAT-PKG-009`, but that only means they are *safe to reuse as
   identifiers* — it does not mean they are safe to *disclose* for a private, unpublished loop.
   §4 states the actual gate: node IDs from a loop that has itself been publicly published.

---

## 1. Objective

**`FB-RAT-MKT-008` — ACCEPTED.** *"Marketplace contact MUST NOT upload private usage, execution
logs, prompts, files, model outputs, or run results automatically."* This document exists to make
that law concrete for the one surface where a run result legitimately does leave the phone: a
user-initiated share. Every section below is a constraint this document imposes **on top of**
`FB-RAT-MKT-008`, never a carve-out from it — nothing here authorizes automatic upload, and every
default (§4, §5) is chosen so that sharing useful evidence about a loop's run never requires
turning Fonebrew into a telemetry client or leaking private work to do it.

`FB-RAT-MKT-008`'s repository effect is assigned to this document specifically because "marketplace
contact" for a *run result* (as opposed to a package listing, review, or publisher record — the
marketplace's own concerns, `LOOP_MARKETPLACE_CONTRACT.md`) is entirely this document's surface.
`10_DUAL_VALIDATION_ADDENDUM.md` §F separately notes `FB-RAT-MKT-008` as a candidate for eventual
promotion into `docs/ratified/COMMON_CONVENTIONS.md` as a general constellation-wide law rather
than a marketplace-domain one (it is "NOT a duplicate" of anything already in that document,
per the addendum) — that relocation is not performed here; it is a forward pointer for whichever
future ratification pass owns `COMMON_CONVENTIONS.md`, not a decision this document is authorized
to make on its own.

## 2. Trigger

**`FB-RAT-RES-001` — ACCEPTED.** *"Run evidence is shared only through an explicit Share Result
action with a field-by-field preview."* Sharing begins only when the user selects **Share result**
from a completed or terminal run. **No background queue, retry, marketplace polling, or automatic
upload is permitted** — this is `FB-RAT-MKT-008` (§1) applied at the point a share actually starts.

This is structurally enforced, not merely a UI convention: publishing a result requires the
`fb.marketplace.publish` capability (`capability-ids.v1.json`), whose `authorityRung` is
`PUBLISH_OR_RELEASE` — the top of the eight-rung ladder (`CAPABILITY_AUTHORITY_MODEL.md` §2,
`FB-RAT-AUTH-002`) — and which that registry states explicitly **MUST NOT** be held by a loop node
("marketplace publish is never automatable"). A loop cannot request its own result be shared as
part of its own execution; only a human, outside any run, can invoke §3's flow below.

## 3. The share flow

*(Merges draft §2 "Trigger," draft §3 "Preview," draft §7's nine numbered pipeline steps
[§90–§98], and draft §10 "Allowlist construction" — the pack's central duplication for this file
per `10_DUAL_VALIDATION_ADDENDUM.md` §B2, resolved to one state machine rather than four
overlapping fragments.)*

**`FB-RAT-RES-006` — ACCEPTED.** *"Result export applies schema allowlisting, secret scanning,
path/identifier redaction, preview, and a signed final digest."* The table below is that pipeline,
entered only from §2's explicit user action, never from any other event.

| From state | Event | To state | Notes |
|---|---|---|---|
| *(none — user action)* | User selects **Share result** on a completed or terminal run (§2). | `SHARE_INITIATED` | Nothing is constructed yet. |
| `SHARE_INITIATED` | The share builder starts from an **empty document** and adds only fields on the default or user-approved-optional allowlist (§4). It never starts from a full raw run log and redacts down — draft §10's "assumes the result is safe" failure mode is exactly what allowlist-first construction rules out. | `ALLOWLISTED_SUMMARY_BUILT` | none |
| `ALLOWLISTED_SUMMARY_BUILT` | Remove any field not on the allowlist; apply the coarse-bucketing requirement (§4) to duration, retry, per-node-count, and token/cost fields. | `UNAPPROVED_FIELDS_REMOVED` | none |
| `UNAPPROVED_FIELDS_REMOVED` | Scan the remaining content — including any free-text additions, artifact attachments, and verifier evidence the user adds, each scanned and previewed independently of the default fields — for secrets and private identifiers (§5), reusing the same secret-like-pattern detection class `LOOP-PKG-001` (`loop-validation-rules.v1.json`) applies at package-import time. | `SECRET_SCANNED` | scan finding record, if any |
| `SECRET_SCANNED` | Normalize and hash evidence (correctness-evidence digests, attachment digests — §6). | `EVIDENCE_NORMALIZED_HASHED` | none |
| `EVIDENCE_NORMALIZED_HASHED` | Show the preview (below). | `PREVIEWED` | none — a preview is a read, not a mutation |
| `PREVIEWED` | User cancels, or removes optional evidence and the builder re-enters the pipeline to re-apply allowlisting/scanning/normalization to the changed content. | `ALLOWLISTED_SUMMARY_BUILT` | Any consent already obtained in a prior pass through `PREVIEWED` is invalidated — §7 restates this as the consent rule. |
| `PREVIEWED` | User grants explicit approval to the exact previewed content. | `CONSENT_OBTAINED` | consent receipt fields recorded (§7) |
| `CONSENT_OBTAINED` | Sign the final digest where a signing key is available (§6). | `RECEIPT_SIGNED` | signature, or an explicit unsigned marker if no key is available — never silently omitted |
| `RECEIPT_SIGNED` | Upload to the selected destination. | `UPLOADED` | upload confirmation |
| `UPLOADED` | Store the local sharing receipt. | `RECEIPT_STORED` | full local sharing receipt (§7) |

**Terminal states:**

| From state | Event | To state |
|---|---|---|
| any non-terminal state above | User explicitly cancels. | `CANCELLED` — nothing uploaded, nothing retained beyond the in-progress draft the user was editing |
| `UPLOADED` | Store succeeds. | `RECEIPT_STORED` |

**Preview content (entering `PREVIEWED`).** The preview MUST list every included field and
attachment, every redaction performed, the destination, the visibility level (§7), and the
package/release identity (`loopId`, `semanticVersion`, `packageContentDigest` —
`LOOP_PACKAGE_SPEC.md` §2, §4, not re-derived here). The user MAY cancel or remove any optional
evidence at this point, per the table above.

## 4. Default allowlist

**`FB-RAT-RES-002` — ACCEPTED.** *"Default shared results include package/version/digest, engine
version, terminal state, node state summary, duration, aggregate cost/tokens, local/cloud ratio,
and capability categories."* The full default-included set, each field grounded in an already-
frozen registry or already-ratified document rather than invented fresh here:

| Field | Grounded in |
|---|---|
| result schema version | Not a separate field — this is `ContractEnvelope.schemaVersion` (`FB-RAT-COM-003`, `docs/ratified/COMMON_CONVENTIONS.md` §3), which only the outer envelope carries. A shared result is carried as `ContractEnvelope<LoopRunResultSummary>` (schema not yet written — WP-1L schema-authoring scope beyond this document). |
| loop ID, release version, and package digest | `loopId`, `semanticVersion`, `packageContentDigest` (`LOOP_PACKAGE_SPEC.md` §2, §4) |
| Fonebrew app and engine versions | `ContractEnvelope.producer.version` (app) and `engineVersion` (`capability-ids.v1.json`) — two independently-versioned axes, per `LOOP_PACKAGE_SPEC.md` §12 |
| terminal state and terminal reason category | the loop run's own terminal-state vocabulary — an adaptation of `EXECUTION_CONTRACT.md`'s twelve-state machine (§2, `FB-RAT-EXE-002`) via `GraphRunLedger` (`EXECUTION_CONTRACT.md` §10), not yet consolidated under a dedicated ratified loop-run document as of this writing; "terminal reason category" MUST itself be one of a small closed set of coarse categories, never a free-text reason |
| node-state counts and stable public node IDs where permitted | node IDs are stable per `FB-RAT-PKG-009` (`LOOP_PACKAGE_SPEC.md` §8), but stability is not the same question as disclosure safety — **"where permitted" MUST be read as: only when the loop being reported on has itself been publicly published.** Including node IDs from a private, unpublished loop would disclose its internal structure even though the loop's own content is never shared; per-node counts are subject to the bucketing requirement below regardless |
| duration and retry totals | **MUST be bucketed** — see below |
| aggregate token/cost totals | **MUST be bucketed** — see below |
| local/cloud/remote ratio | §8 |
| execution target categories, not host identity | `ExecutionTarget.type` (`schemas/execution/target.schema.json`) collapsed to coarse groups — never `displayName` or `hostFingerprint`, which that schema already treats as non-identity/host-secret respectively |
| capability categories | the eight-value `category` enum in `capability-ids.v1.json` (`workspace`/`execution`/`device`/`network`/`secret`/`model`/`marketplace`/`release`) — never the specific reverse-DNS capability IDs a run actually requested |
| verifier outcomes | §6, `FB-RAT-RES-005` |
| user ratings and worked-for-me response | a boolean/enum outcome the sharing user attaches to their own result at share time; distinct from the marketplace's own structured-review scheme for listings (`FB-RAT-MKT-006`, owned by `LOOP_MARKETPLACE_CONTRACT.md`, not restated here) |

**Coarse bucketing is a normative requirement, not an option, for duration, retry-total,
per-node-count, and token/cost-aggregate fields.** This is new normative text the source does not
state at all (§ "Corrections applied" item 5, above). The reason: `packageContentDigest`, `loopId`,
and `semanticVersion` remain — and MUST remain — exact, because exact identity matching is their
entire purpose (compatibility evaluation, dedup, aggregate cohort grouping, §10). Reported
*alongside* exact identity, exact-valued durations, retry counts, per-node counts, and token/cost
totals form a high-dimensional quasi-identifier that can fingerprint an individual run, and in
aggregate, an individual sharing user — USENIX Security 2024 research on token-length side
channels demonstrated that unbucketed length/count metadata *alone*, without any plaintext content,
is sufficient to reconstruct substantial portions of an LLM's generated output. A field this
document lists as bucketed MUST be reported as one of a small number of coarse, versioned ranges
(a `bucketingVersion`, following the same pattern as `semanticDigestVersion` and
`rulesetVersion` elsewhere in this constellation) rather than as a raw value. This document fixes
*which* fields are bucketed and *that* the scheme is versioned; the exact bucket boundaries are
schema-authoring work (WP-1L scope beyond this document) and are not invented here.

## 5. Default denylist

**`FB-RAT-RES-003` — ACCEPTED.** *"Exclude prompts, responses, repository and file identities,
file contents, credentials, hostnames, device IDs, terminal logs, artifacts, and user identity
unless deliberately added."* The full default-excluded set:

- prompt and model response content;
- repository, branch, file, symbol, project, and organization names;
- source code and diffs;
- file contents or paths;
- raw logs and terminal output;
- credentials, aliases, tokens, keys, and hostnames;
- IP/MAC/device serial/USB identity;
- generated artifacts;
- user identity, unless explicitly attached;
- private comments and human decisions.

Every one of these MUST be absent from the allowlisted summary §3 constructs by construction (the
allowlist is additive, §3), and is additionally the target class the secret-and-identifier scan
(§3, `SECRET_SCANNED`) checks for in any user-added free text, attachment, or evidence — a field on
this list surviving into a shared result is a defect in either step, not an acceptable edge case.

## 6. Verification claims

*(Merges draft §6 and draft §13, both "Verification claims" — the pack's named duplicate for this
file per `10_DUAL_VALIDATION_ADDENDUM.md` §B2 — into one section, with the correction
`10_DUAL_VALIDATION_ADDENDUM.md` §B5 requires applied as normative text, not merely noted.)*

**`FB-RAT-RES-004` — ACCEPTED, operative text corrected.** The register's shorthand: *"A verified
shared receipt proves that a compatible Fonebrew runtime signed the declared run summary for a
package digest; it does not prove objective correctness."* The second half of that sentence is
sound and stands. **The first half is not supportable as stated, and this document does not carry
it forward.**

**Why.** `10_DUAL_VALIDATION_ADDENDUM.md` §B5: nothing in the source pack, in either draft pass, or
anywhere else in the handoff pack says where the signing key that would make "a compatible
Fonebrew runtime signed this" true actually comes from — every mention of key custody in the pack
concerns *publisher* keys (`LOOP_PACKAGE_SPEC.md` §11, Ed25519 over the package content), a
completely different key from whatever would sign a *shared result*. Every implementable option
for that second key fails the claim:

- a **self-generated Android Keystore key**, created on-device with no external registration, is
  self-signed — it has no root of trust, and nothing stops any installation (compatible or not)
  from minting one and signing anything with it;
- an **app-embedded secret** is trivially extractable from a sideloaded APK — and sideload is this
  product's primary distribution channel (`CLAUDE.md`, `full` flavor), not an edge case to design
  around;
- **Android hardware key attestation** (`attestationApplicationId`, `verifiedBootState`,
  `deviceLocked`, StrongBox) is the one option that actually would bind a signature to a specific
  binary on specific hardware — and it cuts directly against this product's sovereignty and
  sideload-first posture (binding rule 1–2, `CLAUDE.md`), so it is not adopted here.

**What this document requires instead.** The label `RUNTIME_ATTESTED` is renamed
**`SELF_SIGNED_RECEIPT`**, and the only claim a verifier MAY draw from a valid signature is:
**the same key signed these N receipts** — pseudonymous continuity of a key holder across shares —
**never** that a genuine Fonebrew binary, a specific device, or an unmodified runtime produced any
of them. A marketplace or any other consumer of a shared result **MUST NOT** present
`SELF_SIGNED_RECEIPT` as evidence of runtime authenticity, app integrity, or correctness of any
kind — it is evidence of one thing only: that whoever holds this key produced this receipt and,
if other receipts share the key, produced those too.

**Key generation, storage, rotation (new normative text — neither draft pass states any of this).**

- The receipt-signing keypair is generated on-device, on first use of the Share flow, via Android
  Keystore (`security/KeystoreSecret.kt` — the same subsystem binding rule 5 requires for API-key
  encryption, reused here for a distinct key with a distinct purpose). The private key **MUST NOT**
  be exportable and **MUST NOT** leave the device.
- This key is **separate from** any provider API key and from the publisher signing key
  `LOOP_PACKAGE_SPEC.md` §11 governs — it signs only share receipts, nothing else.
- Fonebrew **MUST NOT** require or perform hardware key attestation for this key, consistent with
  the sideload-first, sovereignty-first posture above — this is a deliberate non-goal, not an
  oversight.
- The user MAY rotate or regenerate this key (e.g. explicit action in Settings, or implicitly on
  factory reset). Rotation breaks continuity: a verifier encountering receipts under a new key
  **MUST** treat them as unlinked from the old key's history, never silently unioned with it.
  Multiple devices under the same user produce independent keys; no cross-device unification is
  implied or attempted.

**`FB-RAT-RES-005` — ACCEPTED.** *"Correctness claims require loop-declared verification evidence
and indicate which verifiers passed, failed, or were unavailable."* `correctnessEvidence` lists
loop-specific verifiers (the same `verifier` object `LOOP_PACKAGE_SPEC.md` §8 already names as an
identity-stable object type, not redefined here), each with: verifier ID, implementation identity,
evidence digest, result, and an explicit unavailable state — `UNAVAILABLE` MUST be used rather than
omitting the verifier entirely or guessing a result, mirroring the same honesty-under-uncertainty
principle `FB-RAT-COM-007`'s `sideEffectState: UNKNOWN` states generally
(`docs/ratified/COMMON_CONVENTIONS.md` §7). A marketplace or any other display surface MUST label
this evidence honestly and MUST NOT imply a verifier ran when its result is `UNAVAILABLE`.

**The four-label scheme** (kept as the source structures it — the addendum notes it mirrors
MLCommons' verified/unverified regime, and that comparison is sound):

| Label | What it proves | What it does NOT prove |
|---|---|---|
| `SELF_SIGNED_RECEIPT` (renamed from `RUNTIME_ATTESTED`) | The same signing key produced this receipt and any others under it (pseudonymous continuity, above). | That a compatible Fonebrew runtime, a specific device, or an unmodified app produced it; that the loop is safe, correct, representative, or suitable for another user. |
| `FIXTURE_VERIFIED` | The package's own packaged fixtures passed (`LOOP-TEST-001`/`LOOP-TEST-002`, `loop-validation-rules.v1.json`; `LOOP_PACKAGE_SPEC.md` §10, `LOOP_IMPORT_ACTIVATION_CONTRACT.md` §7). | That the loop behaves the same way against a real, live target — a passing simulation is evidence against its own fixtures only. |
| `TARGET_VERIFIED` | Declared real-target verifiers passed, with evidence included or referenced. | Correctness for any target, input, or environment other than the one actually exercised. |
| `USER_REPORTED` | The user supplied an outcome without machine-verifiable proof. | Anything beyond one person's stated experience — no verification claim whatsoever. |

A marketplace or any other consumer **MUST** display these four labels separately and **MUST
NEVER** convert `SELF_SIGNED_RECEIPT` — or any of the other three — into a general correctness
guarantee. `LOOP_MARKETPLACE_CONTRACT.md` (not yet written as of this document) owns how these
labels render in a listing; this document owns only what each one is honestly permitted to claim.

## 7. Consent receipt

Consent records: the exact share digest, the destination service, the selected optional fields,
the selected evidence digests, account/publisher identity if any, time (`FB-RAT-COM-004`), app
version, and whether the share is public, unlisted, or private-account. **Editing a share after
`PREVIEWED` invalidates consent and requires another `PREVIEWED` pass** — this is the consent-side
statement of the `PREVIEWED`→`ALLOWLISTED_SUMMARY_BUILT` edge §3's table already states; consent
is never carried forward across a changed preview.

The local sharing receipt written on entry to `RECEIPT_STORED` (§3) specializes the same
provenance-minimum discipline `FB-RAT-COM-008` states generally
(`docs/ratified/COMMON_CONVENTIONS.md` §8) and `FB-RAT-IMP-009` specializes for package activation
(`LOOP_IMPORT_ACTIVATION_CONTRACT.md` §10) — for the share case, the receipt is this section's
field list plus the §6 verification label(s) attached and the §3 destination/upload confirmation.
The concrete `loop-run-summary.v1` (the redacted share input this section and §4/§5 govern, kept
distinct from the complete local `loop-run.v1` record per `10_DUAL_VALIDATION_ADDENDUM.md` §E) and
a sharing-receipt schema are not yet written as of this document — schema authoring is WP-1L scope
beyond this file.

## 8. Local and remote ratios

Ratios summarize declared execution-target classes (§4's "execution target categories, not host
identity" — `ExecutionTarget.type`, `schemas/execution/target.schema.json`, collapsed to coarse
groups: local (`LOCAL_ANDROID`), remote (`SSH_HOST`, `RASPBERRY_PI`), and CI (`GITHUB_ACTIONS`,
`GITEA_ACTIONS`)) and **MUST NOT** expose provider names, `displayName`, or `hostFingerprint` by
default — those remain local-only per that schema's own identity/secrecy treatment of those fields.
The computation method and denominator **MUST** be versioned, the same way `bucketingVersion` (§4)
and `rulesetVersion`/`semanticDigestVersion` (`LOOP_PACKAGE_SPEC.md` §4–§5) are versioned elsewhere
in this constellation. Zero-work runs (no node executed) and mixed-target runs (some nodes local,
some remote) **MUST NOT** be allowed to produce a misleading ratio — a run with zero eligible work
reports "no ratio computed," never a default-to-zero percentage that would misrepresent it as
"100% local."

## 9. Revocation, deletion, and removal

*(Merges draft §8 "Revocation and deletion" with draft §14 "Removal and retention" — the pack's
second duplicate-topic pair for this file, per this document's own structure note above, not
separately named in `10_DUAL_VALIDATION_ADDENDUM.md` §B2's file-specific callout but the same
defect class.)*

A destination service that hosts a shared result MUST support deletion or delisting requests for
it. Deletion **cannot** retract copies already downloaded or independently republished by a third
party — this limitation MUST be disclosed to the requesting user at the point they request removal,
not discovered by them later. Removal does not rewrite third-party copies already exported; the
service stops serving the canonical record and marks any dependent aggregate counts according to
the aggregation policy §10 states. Because raw private run data was never uploaded in the first
place (§1, §5) — only the allowlisted, redacted summary §3's pipeline produced — there is no
private data left to separately purge on removal; removal is a hosting-and-discoverability action,
not a data-deletion action in the GDPR/"right to erasure" sense over content that was never sent.

**`FB-RAT-RES-008` — PROPOSED, not yet ratified.** This obligation is stated above as normative
text because the source draft states it in both passes and it is a real, load-bearing requirement
for any hosting destination — but no decision ID assigned to this document (`FB-RAT-RES-001`
through `FB-RAT-RES-007`) actually covers it, and it is a distinct question from `FB-RAT-MKT-007`
(moderation/takedown for marketplace *listings*, owned by `LOOP_MARKETPLACE_CONTRACT.md`) — a
shared *result* can be hosted, and need removing, independently of any package listing it might be
attached to. This gap is recorded here as a proposal for the Amendments-phase agent to accept,
reject, or fold into an existing ID; this document does not self-ratify it. **Proposed shape:**
promote the paragraph above — deletion/delisting support, the undiscoverable-third-party-copy
disclosure, and the "never uploaded, nothing left to purge" distinction — to a numbered decision
in the `RES-*` sequence.

## 10. Aggregate statistics

*(Merges draft §9 "Aggregation warning" with draft §15 "Aggregate statistics" — the pack's third
duplicate-topic pair for this file, same defect class as §9 above.)*

**`FB-RAT-RES-007` — DEFERRED** (`docs/non_ratified/DEFERRED_DECISIONS.md`). *"Defer public
aggregate success, intervention, and failure statistics until sampling bias and verification
semantics are defined."* Public success rates, intervention rates, and failure rates computed
across shared results are **not a live specification** as of this document — no marketplace or
other surface may build against the paragraphs below as shipped behavior. They are recorded,
per the source, as the **conditions that would have to hold** before this deferral could be
lifted by a future ratification pass, not as a feature this document authorizes:

- shared results are opt-in and self-selected — a sample of results, never a census of runs;
- results come from environments that differ (device, engine version, model bindings) and were
  produced under different verification policies (§6's four labels are not interchangeable
  evidence strength);
- any future aggregation MUST disclose: the denominator, the receipt-verification mix (how many
  results in the aggregate carry each of §6's four labels), the compatibility cohort the aggregate
  was computed over (engine-version range, capability profile), and the missing-result bias this
  kind of self-selected sample necessarily carries;
- aggregates, if ever published, are computed **only** from explicitly shared records (§1, §2) and
  MUST state sample size, version range, compatibility profile, and verification mix alongside any
  number — and MUST NOT be presented as representative of all installations or all runs, published
  or not.

Until `FB-RAT-RES-007` is lifted, the only aggregate-shaped output this document's flow (§3)
produces is the per-share local receipt (§7) and whatever counting a hosting destination does on
its own behalf outside this document's scope — no in-app surface built against this document may
display a cross-user success rate, intervention rate, or failure rate.

## 11. Cross-references and open items

**Decision IDs cited in this document:** `FB-RAT-RES-001` (§2, §3), `FB-RAT-RES-002` (§4),
`FB-RAT-RES-003` (§5), `FB-RAT-RES-004` (§6, operative text corrected per
`10_DUAL_VALIDATION_ADDENDUM.md` §B5), `FB-RAT-RES-005` (§6), `FB-RAT-RES-006` (§3),
`FB-RAT-RES-007` (§10, DEFERRED), `FB-RAT-MKT-008` (§1, §2). `FB-RAT-AUTH-002`, `FB-RAT-COM-003`,
`FB-RAT-COM-004`, `FB-RAT-COM-007`, `FB-RAT-COM-008`, `FB-RAT-EXE-002`, `FB-RAT-PKG-009`,
`FB-RAT-IMP-009`, and `FB-RAT-MKT-006`/`FB-RAT-MKT-007` are cited as already-ratified context this
document specializes or contrasts against, not re-decided here.

**Registries reconciled, not redefined:** `capability-ids.v1.json` (`fb.marketplace.publish`,
`engineVersion`, the `category` enum — §2, §4); `loop-validation-rules.v1.json` (`LOOP-PKG-001`,
`LOOP-TEST-001`/`002` — §3, §6); `schemas/execution/target.schema.json` (`ExecutionTarget.type` —
§4, §8).

**Sibling documents this document defers to and does not restate:** `LOOP_PACKAGE_SPEC.md` (§3,
§4, §6 of this document — package identity, digests, node-identity stability, signature model);
`CAPABILITY_AUTHORITY_MODEL.md` (§2 — the authority ladder the Share action's capability draws
from); `EXECUTION_CONTRACT.md` (§4 — the execution-lifecycle state machine the loop run's terminal
state adapts); `docs/ratified/COMMON_CONVENTIONS.md` (§1, §4, §6, §7 — envelope/time/provenance/
error conventions this document's fields specialize). `LOOP_MARKETPLACE_CONTRACT.md` is cited by
name (§1, §6, §9) but not yet written under `docs/ratified/loops/` as of this document — a reader
who needs its content should look for it as a separate WP-1L output, not expect this document to
have absorbed it.

**Not yet written, forward-pointed only:** `loop-run.v1` (the complete local run record) and
`loop-run-summary.v1` (the redacted share input this document governs) JSON Schemas, and the
sharing-receipt shape (§7) — schema and Kotlin-contract authoring is WP-1L scope beyond this
document, not performed here. The `bucketingVersion` scheme's exact bucket boundaries (§4) are the
same kind of forward pointer.

**One new decision ID is proposed by this document:** `FB-RAT-RES-008` (§9, revocation/deletion/
removal service obligation) — **PROPOSED, not self-ratified**, for the Amendments-phase agent.
Every other gap this file needed to close — the verification-claim key-provenance correction, the
coarse-bucketing requirement, the three duplicate-topic merges — was closable by cross-referencing
an already-frozen registry, an already-ratified decision, or the validation addendum's own stated
correction, and did not require inventing a further `FB-RAT-RES-NEW-*` slot.
