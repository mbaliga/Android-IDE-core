# Loop Fork and Lineage Contract

> **License:** `LICENSE-PENDING` — see `docs/non_ratified/LICENSE_PENDING.md`.

**Status:** RATIFIED (this document's own sections), with one named exception carried at its true
status: `FB-RAT-LIN-007` is **DEFERRED** (`docs/non_ratified/DEFERRED_DECISIONS.md`), called out
inline at §7. **Scope:** what happens the moment a user edits an installed, immutable loop
release — fork identity, the lineage provenance graph, attribution and license carry-through
(including subloop extraction), how a newer upstream release is compared and selectively applied
without ever forcing a merge, how the resulting semantic diff is reported as separate categories
with authority/verification changes highlighted independently of ordinary changes, and how a
derivative is published and owned. This document is the citation target for `FB-RAT-LIN-001`
through `FB-RAT-LIN-007` and, per `LOOP_WEB_STUDIO_SPEC.md` §4 and
`LOOP_DUAL_SURFACE_ARCHITECTURE.md` §2, is the sole owner of the semantic-graph-diff operation
model both the phone and Web Studio implement — neither sibling document restates it.

This document does **not** redefine: the `.floop` container, canonicalization, or the two digest
algorithms (`schemas/loops/registries/`, indexed by `LOOP_FROZEN_CONCEPTS_WP1L_G0.md`); package
identity, versioning, signatures, or portable node/edge identity (`LOOP_PACKAGE_SPEC.md`); the
authority ladder and grant model (`CAPABILITY_AUTHORITY_MODEL.md`); compatibility outcome levels
(`LOOP_COMPATIBILITY_CONTRACT.md`); marketplace listing structure or publisher identity
(`LOOP_MARKETPLACE_CONTRACT.md`); or the import/activation state machine and installed-release
immutability (`LOOP_IMPORT_ACTIVATION_CONTRACT.md`, `FB-RAT-IMP-006`). A conflict between this
document's prose and any of those is this document's error, and the other side wins.

## Note on this document's structure

The extracted first-pass/second-pass draft this document replaces uses a `### 1.` through
`### 13.` sequence with **no literal duplicate section number and no numbering restart** — unlike
most of its siblings in the same input set (`10_DUAL_VALIDATION_ADDENDUM.md` §B2 names the
literal-duplicate-number and restart defects in `LOOP_PACKAGE_SPEC` and `LOOP_MARKETPLACE_CONTRACT`
specifically, not this file). What this file has instead is §B2's other named defect pattern —
**"duplicate topics under continuing numbers"** — spread across its back half rather than
concentrated in one restart. Four topic pairs restate each other under two different, non-adjacent
numbers:

1. **Publication ownership.** Draft §3's closing sentence ("A published derivative receives its
   own stable loop ID unless publisher policy explicitly transfers ownership") and draft §12
   ("Publication ownership": "A published derivative receives a new loop family ID unless
   ownership of the original family is formally transferred... A publisher cannot claim parent
   verified results as evidence for materially changed behavior") state the same rule twice —
   §12 is the fuller second pass, adding the listing-disclosure and verified-results-claim
   details §3 never mentions. Merged below at §11.
2. **Attribution surviving extraction.** Draft §4's one-line guarantee ("does not strip
   attribution when flattening a graph or extracting subloops") and draft §10 ("Subloop
   extraction": preserves attribution, licenses, node provenance, tests, capability requests, and
   semantic links) restate the same guarantee — draft §10 at length, draft §4 in passing. Merged
   below at §5.
3. **No forced upstream merge, stated three times.** Draft §5 ("Upstream updates") gives the fork
   owner's five options and the top-line no-auto-merge rule; draft §8 ("Merge boundary") restates
   the no-auto-merge rule and adds the operation-level-apply mechanism draft §5 never gives
   (exactly `FB-RAT-LIN-007`'s deferral); draft §11 ("Upstream comparison safety") restates the
   mechanism a third time and folds in a second, narrower pass over draft §6's high-risk-diff
   callout. Kept as three sections below (§6, §7, §10) because each pass does carry genuinely new
   content once the repetition is stripped — but the repeated top-line rule itself is stated once,
   at §6, and cross-referenced rather than re-asserted at §7 and §10.
4. **Authority/verification highlighting, stated twice.** Draft §6's closing sentence ("Authority
   widening and verification weakening are high-risk diff classes and appear separately") and
   draft §11's fuller version ("Authority widening, verification weakening, destructive-path
   introduction, and schema breakage appear as blocking review groups regardless of the number of
   ordinary graph edits") are one `FB-RAT-LIN-005` rule, not two — draft §11 adds two more
   conditions (destructive-path introduction, schema breakage) the draft §6 sentence never names.
   Merged below at §9, split out from the ordinary nine-category diff report (§8) specifically
   because the register cites both `FB-RAT-LIN-004` and `FB-RAT-LIN-005` against the same draft
   §6 — this document gives them separate sections precisely so a reader can tell which decision
   a given diff finding belongs to.

Nothing from the 13 draft sections is dropped; every sentence is folded into exactly one section
below.

| Draft § | Draft topic | This document's § |
|---|---|---|
| 1 | Purpose | §1 |
| 2 | Objects | §2 |
| 3 | Fork creation | §3 |
| 4 | Attribution and license | §5 |
| 5 | Upstream updates | §6 |
| 6 | Semantic diff dimensions | §8, §9 |
| 7 | Derivative publication | §11 |
| 8 | Merge boundary | §7 |
| 9 | Lineage identity graph | §4 |
| 10 | Subloop extraction | §5 |
| 11 | Upstream comparison safety | §9, §10 |
| 12 | Publication ownership | §11 |
| 13 | History preservation | §12 |

Any other document or register row that cites this file by a draft section number — the
dual-surface register's `LINEAGE §3` (`FB-RAT-LIN-001`), `LINEAGE §4` (`FB-RAT-LIN-002`),
`LINEAGE §5` (`FB-RAT-LIN-003`), `LINEAGE §6` (`FB-RAT-LIN-004` and `FB-RAT-LIN-005`, previously
sharing one draft section), `LINEAGE §7` (`FB-RAT-LIN-006`), and `LINEAGE §8` (`FB-RAT-LIN-007`) —
should re-anchor against the table above, not the draft: those seven decisions now live at §3,
§5, §6, §8, §9, §11, and §7 of this document respectively. §4 and §10 are new section numbers with
no independent `FB-RAT-LIN-*` citation of their own — they carry supporting normative detail for
`FB-RAT-LIN-001`/`FB-RAT-LIN-002` and `FB-RAT-LIN-003`/`FB-RAT-LIN-005`/`FB-RAT-LIN-007`
respectively, flagged inline where they appear.

---

## 1. Purpose

Fonebrew MUST make remixing an installed loop release safe and attributable while preserving the
fork owner's local ownership of their own draft, the immutability of every installed release, and
explicit, user-directed handling of every upstream change. This document specifies: fork identity
and creation (§3), the lineage provenance graph (§4), attribution and license carry-through
including subloop extraction (§5), how upstream updates are offered and never forced (§6–§7), how
a comparison is reported as a categorized semantic diff with authority/verification changes
highlighted independently (§8–§9), the state machine that governs selective application of an
upstream change (§10), and how a derivative is published and owned (§11–§12).

## 2. Objects

A fork-lineage record set MUST track the following object kinds. This document does not define
their JSON Schema shapes — `loop-fork-lineage` and `LoopResultAndLineageContracts.kt` are WP-1L
scope beyond this document and are not yet written as of this document
(`inputs/dual_surface/DUAL_TRACEABILITY_MATRIX.md` names both as this decision family's
implementation artifacts).

- **Parent reference** — the four-part immutable release identity `LOOP_ENGINEERING_SPEC_V2.1`
  §14 already fixes: `{loopId, semanticVersion, packageDigest, publisherKeyFingerprint}`. Not
  redefined here; every lineage object below names a parent by this tuple, never by a mutable
  pointer such as a listing URL.
- **Fork draft identity** — the `draftId` (`LOOP_ENGINEERING_SPEC_V2.1` §14) minted at fork time
  (§3). Never reused after deletion (§12).
- **Lineage event** — an append-only record (`FB-RAT-COM-006`) of a fork-affecting action. *(New
  normative vocabulary — the draft never enumerates event kinds; this document adds it because
  §10's state machine needs named events to transition on.)* At minimum: `FORK_CREATED`,
  `UPSTREAM_COMPARED`, `UPSTREAM_OPERATION_APPLIED`, `FORK_REVISION_CREATED_FROM_UPSTREAM`,
  `SUBLOOP_EXTRACTED`, `DERIVATIVE_PUBLISHED`, `LOCAL_FORK_DELETED`. Each event's actor MUST be
  recorded using `LOOP_ENGINEERING_SPEC_V2.1` §15's existing actor-class vocabulary — `USER`,
  `DISTILLER`, `AI_PROPOSAL`, `IMPORT_MIGRATION`, `UPSTREAM_APPLY` — reused, not reinvented, since
  a lineage event and a draft revision (§15 of that document) are the same kind of fact.
- **Semantic diff** — the nine-category comparison object §8 defines, plus the independently
  highlighted blocking review group §9 defines.
- **Applied upstream change set** — the ordered list of individually validated operations §10's
  state machine walks through, each with its own before/after semantic digest.
- **Contribution/author record** — one per contributing author, referenced by both a parent
  release and any derivative that carries that author's work forward.
- **License and attribution record** — the SPDX-style license identifier(s) and required notices
  §5 governs.

## 3. Fork identity and creation

**`FB-RAT-LIN-001` — ACCEPTED.** *"Editing an immutable installed release creates a new draft
identity while preserving origin loop ID, release version, and package digest."*

Editing an installed release (`LOOP_IMPORT_ACTIVATION_CONTRACT.md` §3's `INSTALLED` state;
enforced as `LOOP-ID-002` in `loop-validation-rules.v1.json`) MUST NOT mutate the installed
package's bytes. It MUST instead create a new fork draft that:

- receives a new, never-reused `draftId` (§2);
- records the parent's full release identity as a Parent reference (§2) —
  `parentLoopId`, `parentSemanticVersion`, `parentPackageDigest`;
- records fork time (`FB-RAT-COM-004`, UTC), the initiating actor (§2's actor-class vocabulary),
  and an optional, user-supplied fork reason;
- leaves the installed parent release byte-identical, independently re-installable, and
  independently re-activatable.

This is the fork-creation enforcement point for `LOOP-LINEAGE-001` (`loop-validation-rules.v1.json`:
*"Fork of '{parentLoopId}'@{parentDigest} is missing parent attribution... Record parent release
identity, applied upstream changes, and authorship per ForkLineage"*, `WARNING` severity) — a fork
draft that does not carry the Parent reference and actor record above is exactly the condition
that rule code detects; it is not redefined here, only named at its point of origin.

**Local family ID retention.** A fork MAY retain the same local, unpublished loop family ID
(`loopId`, `LOOP_ENGINEERING_SPEC_V2.1` §14) as its parent only while it remains that same
owner's unpublished draft history. The moment a fork is published (§11), it MUST receive its own
stable loop family ID, distinct from its parent's, unless the parent publisher's policy explicitly
transfers ownership of the parent family to the fork's publisher — §11 states the disclosure
obligation that a transfer does not exempt a publisher from.

## 4. Lineage identity graph

*(Draft §9's structural content, not literally duplicated elsewhere in the draft — kept as its
own section because it states a graph-shape invariant, not a restated rule. Supports
`FB-RAT-LIN-001`; not independently cited by the register.)*

Lineage MUST be represented as a directed acyclic provenance graph, even though the loop
control-flow graph a `LoopDefinition` itself describes MAY legally contain bounded cycles
(`LOOP-GRAPH-004`, `loop-validation-rules.v1.json`) — these are two unrelated graphs over the same
object and MUST NOT be confused with each other. A lineage event edge (§2) MUST reference
immutable parent release identities (§3's Parent reference) and the semantic-diff digest (§8)
that produced it, never a mutable pointer such as a listing or profile URL.

A derivative with more than one parent MUST be created only through an explicit composition or
import operation — never an implicit merge, which §6's no-forced-merge rule already forbids for
the single-parent case and this section extends to the multi-parent case — and MUST record each
parent's individual contribution separately, using the same per-category structure §8 defines for
a two-party diff.

## 5. Attribution, license, and subloop extraction

**`FB-RAT-LIN-002` — ACCEPTED.** *"Forks retain required attribution and license provenance;
incompatible derivative publication is blocked."*

Every fork, and every subloop extracted from a fork or a release, MUST retain required author and
source notices (§2's Contribution/author record and License and attribution record) without
alteration. Flattening a graph (collapsing subloops into their parent for editing or export) and
extracting a subloop into its own reusable unit are both presentation/refactoring operations,
never license-erasure operations. Both operations MUST:

- preserve attribution;
- preserve the license(s) governing the affected content;
- preserve node provenance — which parent release or local draft each node's content originated
  from;
- preserve the tests and capability requests attached to the affected nodes;
- preserve a semantic link back to the source release or local draft the content was taken from.

A newly extracted subloop MUST receive its own stable loop identity (`loopId`) and its own
declared input/output boundary — it is a new object, not an alias of the region it was extracted
from, consistent with `FB-RAT-PKG-009`'s "identity is never inferred from resemblance"
(`LOOP_PACKAGE_SPEC.md` §8). Extraction or flattening MUST NOT be used as a mechanism to erase a
restrictive license's obligations: extracting a copyleft-licensed subloop into an
apparently-independent package does not release that content from its original license.

**Publication license compatibility.** A derivative's declared license MUST be checked for
compatibility with its parent's license before the derivative may publish (§11). A derivative
whose declared license is incompatible with its parent's license MUST be blocked from publication.
This is the publish-time enforcement point for `LOOP-LINEAGE-002`
(`loop-validation-rules.v1.json`: *"Declared license '{license}' is incompatible with parent
release '{parentLoopId}' license '{parentLicense}'... Choose a compatible license or remove the
derivative claim"*, `ERROR` severity) — not redefined here, only named at the point it fires.

## 6. Upstream updates — no forced merge

**`FB-RAT-LIN-003` — ACCEPTED.** *"Upstream updates are explicit comparisons; users choose whether
to ignore, inspect, selectively apply, or rebase their fork."*

When a fork's parent publishes a newer release, Fonebrew MUST present exactly the following
choices to the fork owner and MUST NOT take any of them automatically:

| Choice | Effect |
|---|---|
| Ignore | No comparison is computed; the local draft is left untouched. |
| Inspect | Compute the semantic diff (§8) against the new parent release, without applying anything. |
| Apply selected changes | Enter the selective-apply state machine (§10) for one or more individual diff operations. |
| Create a new fork revision from upstream | Fork the new parent release directly as a new revision, independent of the current draft's own edits. |
| Manually rebase | Owner-performed, outside this document's automated flow; MAY use the diff (§8) as reference material. |

**No automatic merge may change a local draft, under any circumstance.** This is the single
top-line rule §7 and §10 make operational — it is stated once, here, and not re-asserted as an
independent claim in either of those sections. A stale or conflicting upstream comparison MUST be
reported as an explicit conflict; it MUST NOT be silently resolved by last-write-wins. This is the
fork-lineage instance of `LOOP_ENGINEERING_SPEC_V2.1` §15's general draft-concurrency rule ("a
stale mutation is rejected with a semantic conflict report; it is not last-write-wins"), not a
second, independent invariant.

## 7. Merge boundary — automated semantic merge is deferred

**`FB-RAT-LIN-007` — DEFERRED** (`docs/non_ratified/DEFERRED_DECISIONS.md`; formal registration of
this row is Amendments-phase / registry-hygiene work, not performed here). *"Defer automatic graph
merging; v1 supports comparison and selective application with validation after every
operation."*

Automatic semantic graph merging — resolving two divergent graphs into one without an explicit,
per-operation user decision — MUST NOT be implemented in v1. What v1 MUST support instead is
**operation-level selective apply** (§6, §10):

- each accepted upstream operation MUST be applied to a new local revision individually, never
  batched or applied silently as a group;
- full validation MUST run after each accepted operation, before the next is offered (§10's
  `VALIDATING_REVISION` state);
- a final package/semantic digest MUST be computed once the selected operations for a session are
  applied;
- conflicts MUST be explicit and MUST NOT be resolved by last-write-wins (§6).

This deferral blocks only a hypothetical automatic merge that would resolve a conflict without a
user decision. It does not block, and is fully satisfied by, §10's selective-apply state machine.

## 8. Semantic graph diff dimensions

**`FB-RAT-LIN-004` — ACCEPTED.** *"Lineage comparison reports node, edge, schema, capability,
authority, budget, test, documentation, and compatibility changes separately."*

A semantic diff between two release/draft snapshots MUST report the following nine categories as
nine separate findings groups — **never flattened into one undifferentiated change list.** This
is the file-specific correction this document makes normative: the draft's own §6 lists eleven
raw bullets without ever stating that they must be kept apart in the reported diff; the table
below is that missing normative statement, reconciled against the register's nine named
categories.

| # | Category | Covers |
|---|---|---|
| 1 | Documentation | Objective text, documentation references |
| 2 | Schema | Input/output JSON Schema changes |
| 3 | Node | Node additions/removals/type changes, implementation references |
| 4 | Edge | Edges, gateways, cycle structure |
| 5 | Capability | Requested capability ID set (`capability-ids.v1.json`) |
| 6 | Authority | Authority-rung changes on requested capabilities (`CAPABILITY_AUTHORITY_MODEL.md` §2 ladder) — additionally surfaced independently per §9 |
| 7 | Budget | Budgets and retry behavior |
| 8 | Test | Tests/fixtures, verification policy, and terminal-state mapping |
| 9 | Compatibility | Target constraints, model requirements, and engine-version range (`LOOP_COMPATIBILITY_CONTRACT.md` §3 outcome levels — not redefined here) |

A change to the package's declared license (§5) MUST additionally be surfaced as its own flag
alongside these nine categories, rather than folded into Documentation — a license change carries
publication-blocking consequences (`LOOP-LINEAGE-002`, §5) that a documentation change does not,
and collapsing the two would hide that distinction from the fork owner.

Every category above depends on stable node/edge/schema/binding-slot identity (`FB-RAT-PKG-009`,
`LOOP_PACKAGE_SPEC.md` §8) — a diff is only meaningful because the same object carries the same ID
across both snapshots being compared. This document relies on that identity rule; it does not
re-derive it.

## 9. Authority and verification diff — highlighted independently

**`FB-RAT-LIN-005` — ACCEPTED.** *"Any upstream or fork change that widens capabilities or side
effects is highlighted independently of ordinary graph changes."*

Four diff conditions MUST be pulled out of the ordinary nine-category report (§8) into a separate,
always-visible, **blocking review group**, regardless of how many ordinary (non-blocking) changes
accompany them in the same comparison:

| Condition | Definition |
|---|---|
| Authority widening | Any requested capability's authority rung (`CAPABILITY_AUTHORITY_MODEL.md` §2 ladder) moves higher, or a new capability is requested at a rung above the loop's prior maximum. |
| Verification weakening | A verifier is removed, its severity is lowered, or a terminal-state mapping that previously required verification no longer does. |
| Destructive-path introduction | A new node or edge makes an `EXECUTE_DESTRUCTIVE`-rung capability reachable that was not reachable before. |
| Schema breakage | An input/output schema change that is not backward-compatible per `LOOP_COMPATIBILITY_CONTRACT.md` §7's forward/backward compatibility rules. |

This blocking review group MUST be shown to, and explicitly acknowledged by, the fork owner
before any operation from that comparison may be applied (§10) — independent of, and in addition
to, the ordinary per-category review of §8's other findings. Acknowledging the group's findings
MUST NOT be bundled into any single "approve all" action that also covers ordinary §8 findings.

This is the loop-domain, upstream-comparison-time instance of the same invariant `LOOP-COMPAT-002`
(`loop-validation-rules.v1.json`) enforces at update/install time
(`FB-RAT-IMP-008`, `LOOP_IMPORT_ACTIVATION_CONTRACT.md` §9) — one invariant with two enforcement
points (comparison-time here, install-time there), not two independent rules that could drift
apart.

## 10. Upstream comparison and selective-apply state machine

*(New normative structure: operationalizes §6, §7, and §9 as one flow. The draft states this same
flow three times, as three overlapping prose passes — §5's five-option list, §8's operation-level
merge-boundary description, §11's immutable-snapshot comparison-safety procedure — none of them as
an explicit state machine. This section merges all three into one table, per the file-specific
instruction that every state machine in this corpus be rendered as an explicit
from-state/event/to-state table, never bare prose or arrows.)*

Comparison MUST occur only against immutable snapshots — the fork's own parent-at-fork-time
release and the newly published parent release (§3's Parent reference) — never against another
fork owner's mutable draft.

| From state | Event | To state |
|---|---|---|
| *(none — external)* | A parent loop publishes a release newer than the one this fork's lineage currently references. | `UPSTREAM_AVAILABLE` |
| `UPSTREAM_AVAILABLE` | Fork owner chooses Ignore (§6). | `NO_PENDING_UPSTREAM` |
| `UPSTREAM_AVAILABLE` | Fork owner chooses Inspect (§6); comparison runs against the two immutable snapshots above. | `COMPARING` |
| `UPSTREAM_AVAILABLE` | Fork owner chooses "create a new fork revision from upstream" (§6) — a wholesale new fork, not a selective apply. | `FORK_REVISION_CREATED` |
| `UPSTREAM_AVAILABLE` | Fork owner chooses "manually rebase" (§6) — owner-performed, outside this machine's remaining states. | `REBASED` |
| `COMPARING` | Semantic diff computed and categorized into the nine §8 categories, plus the independent §9 blocking review group evaluated. | `DIFF_READY` |
| `DIFF_READY` | No §9 blocking-review-group finding is present. | `SELECTING_OPERATIONS` |
| `DIFF_READY` | At least one §9 blocking-review-group finding is present. | `REVIEWING_HIGH_RISK` |
| `REVIEWING_HIGH_RISK` | Fork owner explicitly acknowledges every §9 finding individually. | `SELECTING_OPERATIONS` |
| `SELECTING_OPERATIONS` | Fork owner selects exactly one diff operation to apply (§7's operation-level rule — never a batch). | `APPLYING_OPERATION` |
| `APPLYING_OPERATION` | Operation applied to a new local revision; full validation and tests run (§7). | `VALIDATING_REVISION` |
| `VALIDATING_REVISION` | Validation and tests pass, and further operations remain unselected. | `SELECTING_OPERATIONS` |
| `VALIDATING_REVISION` | Validation or tests fail. | `OPERATION_REJECTED` |
| `OPERATION_REJECTED` | Rejected operation discarded; the prior local revision is left unchanged. | `SELECTING_OPERATIONS` |
| `SELECTING_OPERATIONS` | Fork owner ends the session with zero or more operations committed since `DIFF_READY`; final digest computed (§7). | `REVISION_COMMITTED` |

**Terminal states** — `NO_PENDING_UPSTREAM`, `FORK_REVISION_CREATED`, `REBASED`, and
`REVISION_COMMITTED` — each return the fork to "no pending comparison" for the release just
compared; a subsequently published parent release re-enters the table at `UPSTREAM_AVAILABLE`
independently, as a new instance of the machine.

No transition in this table may be taken by any actor other than an explicit fork-owner action or
the Fonebrew validation pipeline reacting to that action — this is §6's no-forced-merge rule
restated as the state machine's own invariant: there is no edge above that both changes the local
draft and requires no owner action to reach it.

## 11. Derivative publication and publication ownership

**`FB-RAT-LIN-006` — ACCEPTED.** *"Published derivatives declare parent release, fork reason,
authorship contributions, license, and optional upstream relationship."*

A derivative listing (`LOOP_MARKETPLACE_CONTRACT.md` §4's `LoopListing`, not redefined here) MUST
declare:

- the parent release identity (§3's Parent reference);
- the fork reason, if one was recorded at fork time (§3);
- authors and their individual contributions (§2's Contribution/author record);
- the derivative's own license, checked for parent compatibility (§5, `LOOP-LINEAGE-002`);
- which tests are inherited unchanged from the parent, and which are changed or newly added;
- whether upstream contribution back to the parent is intended.

**Publication ownership.** A published derivative MUST receive a new, stable loop family ID
(`loopId`), distinct from its parent's, unless ownership of the parent family is formally
transferred to the derivative's publisher (§3). The parent publisher does **not** control the
derivative's publication, content, or listing merely by virtue of being the parent — publishing a
fork is never subject to parent approval — except where license or trademark rules independently
apply.

The listing MUST visibly state that it is a derivative, MUST identify the parent release(s) it
derives from, and MUST keep original and derivative authorship visually separated — never
presented as if the derivative's authors wrote the parent's contribution, or vice versa. A
publisher MUST NOT cite a parent release's verified results or receipts
(`LOOP_RESULT_SHARING_CONTRACT.md`) as evidence for the derivative's own behavior once that
behavior has materially changed. §8's diff categories are the concrete test for "materially
changed": any non-empty diff finding outside the Documentation category, or any §9
blocking-review-group finding, counts as material and disqualifies the parent's results as
standalone evidence for the derivative.

## 12. History preservation

Deleting a local fork MUST remove that fork's local working state, entirely under the user's
control, but MUST NOT rewrite, delete, or otherwise alter any published lineage record or any
parent release's own history — lineage events are append-only (`FB-RAT-COM-006`), exactly as every
other receipt in this constellation is (`docs/ratified/COMMON_CONVENTIONS.md` §6).

Exported lineage MUST be plain, documented data — no proprietary or opaque format. A phone MUST be
able to reconstruct why a fork exists, who contributed what, and what it diverged from, entirely
from its own local lineage records, without contacting any marketplace or network endpoint. This
is the fork-lineage domain's instance of the same offline-first, no-hidden-backend posture
`FB-RAT-MKT-002` (private by default) and `FB-RAT-LBX-002` (phone-primary) already establish
elsewhere in this constellation — not re-decided here.

## 13. Cross-references and open items

**Decision IDs cited in this document:** `FB-RAT-LIN-001` (§3, ACCEPTED), `FB-RAT-LIN-002` (§5,
ACCEPTED), `FB-RAT-LIN-003` (§6, ACCEPTED), `FB-RAT-LIN-004` (§8, ACCEPTED), `FB-RAT-LIN-005` (§9,
ACCEPTED), `FB-RAT-LIN-006` (§11, ACCEPTED), `FB-RAT-LIN-007` (§7, DEFERRED). `FB-RAT-PKG-009`
(§5, §8), `FB-RAT-IMP-006`/`FB-RAT-IMP-008` (header, §3, §9), `FB-RAT-COM-004`/`FB-RAT-COM-006`
(§3, §12), `FB-RAT-MKT-002`/`FB-RAT-LBX-002` (§12) are cited as already-ratified context this
document specializes or relies on, not re-decided here.

**Registries reconciled, not redefined:** `loop-validation-rules.v1.json` (rule codes
`LOOP-ID-002`, `LOOP-LINEAGE-001`, `LOOP-LINEAGE-002`, `LOOP-COMPAT-002`, `LOOP-GRAPH-004` — §3,
§4, §5, §9); `capability-ids.v1.json` (§2, §8, §9); `semantic-digest.v1.json` and
`canonicalization.v1.json` (§3, §10, referenced via the digest concepts they define, not
restated). This document does not duplicate their contents; a conflict between this document's
prose and a registry's data is this document's error, and the registry wins, per
`LOOP_FROZEN_CONCEPTS_WP1L_G0.md`.

**Sibling documents this document defers to and does not restate:** `LOOP_PACKAGE_SPEC.md` (§4
digests, §8 portable node identity, §12 versioning and immutability — §3, §5, §8 of this
document); `LOOP_IMPORT_ACTIVATION_CONTRACT.md` (the `INSTALLED` state and the authority-change
update gate — §3, §9); `CAPABILITY_AUTHORITY_MODEL.md` (the authority ladder — §8, §9);
`LOOP_COMPATIBILITY_CONTRACT.md` (compatibility outcome levels and forward/backward compatibility
— §8, §9); `LOOP_MARKETPLACE_CONTRACT.md` (listing structure and publisher identity — §11);
`LOOP_RESULT_SHARING_CONTRACT.md` (the shared-result receipt a publisher's verified-results claim
refers to — §11); `LOOP_ENGINEERING_SPEC_V2.1.md` (`ForkLineage` §2.9, release identity and draft
actor classes §14–§15 — §2, §3, §6).

**Not yet written, forward-pointed only:** the `loop-fork-lineage` JSON Schema and
`LoopResultAndLineageContracts.kt` (`inputs/dual_surface/DUAL_TRACEABILITY_MATRIX.md` names both
as this decision family's implementation artifacts) — schema and Kotlin-contract authoring is
WP-1L scope beyond this document, not performed here.

**No new decision ID is proposed by this document.** Every rule this document states resolves to
an existing `FB-RAT-LIN-*` ID from the dual-surface register; nothing here required inventing a
`PROPOSED` ID for the Amendments-phase agent.
