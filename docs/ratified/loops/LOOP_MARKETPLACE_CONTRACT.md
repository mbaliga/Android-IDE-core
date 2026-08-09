# Loop Marketplace Contract

> **License:** `LICENSE-PENDING` — see `docs/non_ratified/LICENSE_PENDING.md`.

**Status:** RATIFIED for `FB-RAT-MKT-001` through `FB-RAT-MKT-008` (this document's own sections,
carrying the corrections listed below). `FB-RAT-MKT-009` is carried in this document at
**PROMOTED** status: accepted, and elevated from the source pack's proposed EXPERIMENTAL fallback
to **THE DEFINITION** of the marketplace for this build-out (§11 states this explicitly and is
not optional reading). `FB-RAT-MKT-010` and `FB-RAT-MKT-011` remain **DEFERRED**
(`docs/non_ratified/DEFERRED_DECISIONS.md`). `FB-RAT-MKT-012` is **PROPOSED**, not ratified — this
document states its content because the file-specific build instruction for this document
requires it, but registration of the ID is Amendments-phase work (§12, §16). **Scope:** the
contract for publishing, discovering, and distributing LoopPackages outside a user's own devices
and Git remotes. The marketplace does not host private Fonebrew workspaces, execute user loops,
broker model calls, store operational credentials, or receive silent phone telemetry.

**Hard scope boundary — binding on this entire document, not a suggestion.** Per the build
session's master instruction (`00_MASTER_PROMPT.md`, the "Do not build" line): *"Do not build any
hosted marketplace service, accounts, reviews, or moderation storage. Emit contracts, client code
paths, and artifacts compatible with a signed static/Git-backed registry only."* Every section
below that describes publisher accounts, structured reviews, or moderation-decision storage
(§6, §8, §9, §10) states a **ratified contract** — normative text this build-out does not
contradict — for the *optional* bounded backend §2 permits, should the owner ever choose to
operate one (`08_OPEN_QUESTIONS.md` §E.1: *"reviews/moderation/publisher accounts stay behind
your explicit go"*). None of it is, on its own, something this build-out constructs. What this
build-out actually builds — contracts, client code paths, and artifacts — is exactly and only
what §11 defines: the signed static/Git-backed registry. Do not read §6–§10 as a service this
repository is implementing; read them as the interaction contract that governs *if and when* the
owner turns the optional backend on, and read §11 as the floor this build-out actually ships.

## Note on this document's structure

The extracted first-pass/second-pass draft this document replaces uses a 1–11 section sequence,
then **restarts numbering at `### 10.` after reaching `### 11.`** — the literal duplicate-numbering
defect `10_DUAL_VALIDATION_ADDENDUM.md` §B2 names for this file specifically (*"`LOOP_MARKETPLACE_
CONTRACT` restarts at §10 after §11"*). The restarted second pass repeats two topics under new
numbers (bounded-backend amendment: draft §2 and draft §10 second-pass; publisher identity: draft
§6 and draft §11 second-pass) and extends three more under continuing numbers with additional
detail (discovery: draft §7 then draft §12; reviews: draft §8 then draft §13; moderation: draft
§10 then draft §14), before adding two topics that exist only in the second pass (marketplace
availability/export, monetization boundary). Every draft section is folded into exactly one
section below; nothing is dropped. The two-check-vs-three-check moderation split (§10), the
promotion of the registry to THE definition (§11), the registry-replaceability guarantee (§12),
and the Play/legal/prompt-security scope statement (§15) are new normative text this work package
adds — none of it existed in the draft — and are flagged inline where they appear.

Any other document in this handoff pack that cites this spec by a draft section number — the
dual-surface register's `MARKET §2` (`FB-RAT-MKT-001`), `MARKET §3` (`FB-RAT-MKT-002`), `MARKET §4`
(`FB-RAT-MKT-003`), `MARKET §5` (`FB-RAT-MKT-004`), `MARKET §6` (`FB-RAT-MKT-005`), `MARKET §7`
(`FB-RAT-MKT-011`), `MARKET §8` (`FB-RAT-MKT-006`), `MARKET §9` (`FB-RAT-MKT-008`), `MARKET §10`
(`FB-RAT-MKT-007`), and `MARKET §11` (`FB-RAT-MKT-009`) — should re-anchor against this table, not
the draft:

| Draft § | Draft topic | This document's § |
|---|---|---|
| 1 | Scope | §1 |
| 2 | Bounded backend amendment | §2 |
| 3 | Privacy defaults | §3 |
| 4 | Listing and release model | §4 |
| 5 | Content policy | §5 |
| 6 | Publisher identity and keys | §6 |
| 7 | Discovery | §7 |
| 8 | Reviews | §8 |
| 9 | Result evidence | §9 |
| 10 (first pass) | Moderation | §10 |
| 11 (first pass) | Rollout | §11 |
| 10 (second pass) | Bounded-backend amendment | §2 |
| 11 (second pass) | Publisher identity | §6 |
| 12 | Discovery metadata | §7 |
| 13 | Reviews and ratings | §8 |
| 14 | Moderation | §10 |
| 15 | Marketplace availability and export | §13 |
| 16 | Monetization boundary | §14 |
| — | (not in draft — registry replaceability, `FB-RAT-MKT-012`) | §12 |
| — | (not in draft — Play/legal/prompt-security scope statement) | §15 |

**Corrections applied, beyond de-duplication (detailed where they appear):**

1. **`FB-RAT-MKT-001` never cited `FB-RAT-INT-003` in the source pack**, so ticking the
   bounded-backend amendment read as a plain checkbox with no note that it reverses a prior
   ratified rejection. §2.1 adds the supersession line verbatim, as its own clearly marked
   subsection, per `10_DUAL_VALIDATION_ADDENDUM.md` §B4.
2. **`FB-RAT-MKT-009` is promoted from EXPERIMENTAL to ACCEPTED and made the definition of the
   marketplace**, reversing the source pack's inverted risk grading (the amendment that needs a
   backend was ACCEPTED while the near-zero-ops way to satisfy it without one was merely
   EXPERIMENTAL). §11 is the promoted text.
3. **`FB-RAT-MKT-007`'s "malware-like content checks" phrase is dropped entirely** — it is not a
   real category — and replaced with three named checks: archive safety, secret scanning by a
   named tool, and prompt-risk review. §10 states the replacement; none of the three named checks
   invent new detection logic — each cross-references a rule code already frozen in
   `loop-validation-rules.v1.json` or a rule already stated in `LOOP_PACKAGE_SPEC.md`.
4. **`FB-RAT-MKT-012` is added** (PROPOSED, not self-ratified): registry base URL
   user-configurable, registry client fully disablable, index format published so third parties
   can host conformant registries. §12.
5. **The hard scope boundary** (this document's header, above) is added because the source draft
   never stated it — the draft describes a fuller marketplace contract without ever marking which
   parts of it this build-out is actually authorized to construct.

---

## 1. Scope

A LoopPackage marketplace publishes and discovers declarative LoopPackages. It MUST NOT host
private Fonebrew workspaces, execute user loops, broker model calls, store operational
credentials, or receive silent phone telemetry (`FB-RAT-MKT-008`, §9). "Marketplace," in this
document, names the whole contract in §2–§14; it does not imply this build-out constructs every
part of it — see the hard scope boundary above and §11 for what is actually built.

## 2. Bounded backend amendment (`FB-RAT-MKT-001`)

**`FB-RAT-MKT-001` — ACCEPTED.** *"Amend the no-backend law to permit an optional public
marketplace for deliberately published packages, listings, reviews, and explicitly shared
receipts; private workspace state and execution remain backend-free."* Stated in full, per
`09_DUAL_SURFACE_LOOP_BUILDER.md` §4:

> Fonebrew has **no operational backend** for private workspace state, inference, credentials,
> execution, telemetry, or synchronization. A **separately bounded marketplace** may host
> deliberately published loop packages, listings, reviews, publisher keys, moderation records, and
> explicitly shared redacted result receipts.

The service, if operated, is separate from private workspace, inference, execution, credentials,
backup, and synchronization. Marketplace use is OPTIONAL. Direct file and Git interchange (a
`.floop` file, a share-sheet payload, a user-owned Git repository) MUST remain first-class,
independent of whether any marketplace or registry is reachable — this is the same
outage-independence gate `LOOP_P0_P1_RELEASE_GATES.md` states as "operation during marketplace
outage" (P0) and "direct distribution during marketplace outage" (P1). `FB-RAT-MKT-001` is
adjacent to, and does not resolve, `FB-RAT-PORT-009` (extension marketplace, DEFERRED until
provider contracts and signing/permission rules are stable) — a LoopPackage marketplace is a
declarative-content distribution channel, not a general extension ecosystem, and `FB-RAT-MKT-004`
(§5) is the rule that keeps the two from collapsing into each other.

### 2.1. Supersession of `FB-RAT-INT-003` (verbatim)

`FB-RAT-INT-003` is **REJECTED**, and remains rejected: *"Reject shared databases, account
backends, background services, broadcasts, and automatic synchronization for v1"*
(`docs/non_ratified/REJECTED_ALTERNATIVES.md`). `FB-RAT-MKT-001` does not repeal that rejection —
it narrows it. This document states the supersession line verbatim, as its own clearly marked
subsection, per this work package's file-specific instruction and `10_DUAL_VALIDATION_ADDENDUM.md`
§B4:

> **`FB-RAT-MKT-001` partially supersedes `FB-RAT-INT-003` as to deliberately published public
> artifacts only; `INT-003` remains in force for shared databases, background sync, and any
> private workspace state.**

`docs/non_ratified/REJECTED_ALTERNATIVES.md` already carries a forward pointer to this line under
its own `FB-RAT-INT-003` entry, and names `AMENDMENTS.md` (a later work package, not yet written)
as the eventual formal amendment ledger. This section is that pointer's target — the supersession
is stated here as binding normative text now; `AMENDMENTS.md`, once it exists, is expected to
record the same line as part of the merged-register ledger, not to re-decide it.

## 3. Privacy defaults (`FB-RAT-MKT-002`)

**`FB-RAT-MKT-002` — ACCEPTED.** *"Drafts, local packages, run records, and results are private
unless the user performs an explicit publication or sharing action."* Concretely:

- Drafts and local packages MUST default to private.
- Publication MUST require an explicit action and a preview of exactly what becomes public.
- "Unlisted" MUST NOT be treated as encrypted or private unless the service contract that
  operates it says so explicitly — an unlisted release is still a public artifact reachable by
  anyone with its URL.
- Every public package MUST pass the moderation checks in §10 before publication, not after.
- Phone run data MUST NOT be uploaded automatically, under any marketplace interaction — this is
  the same MUST `FB-RAT-MKT-008` (§9) states for the sharing path specifically; this section
  states it as the general marketplace-contact default.

## 4. Listing and release model (`FB-RAT-MKT-003`)

**`FB-RAT-MKT-003` — ACCEPTED.** *"Marketplace listings are mutable presentation records;
referenced releases are immutable content-addressed artifacts."* A `LoopListing` is mutable
descriptive metadata (title, description, category, screenshots, the currently-recommended
release pointer). A `LoopRelease` is immutable and content-addressed, per the package-content
digest `LOOP_PACKAGE_SPEC.md` §4 defines — this document does not redefine that digest. A listing
MAY reference a stable channel and a pre-release channel simultaneously. Removing a listing MUST
NOT mutate, invalidate, or retroactively alter a release a user has already downloaded; the
release's identity is its digest, not its presence in any listing.

## 5. Content policy (`FB-RAT-MKT-004`)

**`FB-RAT-MKT-004` — ACCEPTED.** *"The marketplace distributes LoopPackages, not runtime plugins
or executable extensions."* Allowed marketplace content is limited to the LoopPackage
specification (`LOOP_PACKAGE_SPEC.md`). Packages containing executable payloads, credential
material, obfuscated loaders, harassment, fraud, malicious instructions, unlawful content, or
deceptive capability declarations MUST be rejected before publication or removed after. The
forbidden-executable-payload class is not redefined here — it is `LOOP-PKG-002` in
`loop-validation-rules.v1.json`, reused by reference (§10). This is the rule that keeps
`FB-RAT-MKT-004` from drifting into the general-extension-ecosystem territory `FB-RAT-PORT-009`
(DEFERRED) covers instead.

## 6. Publisher identity and keys (`FB-RAT-MKT-005`)

**`FB-RAT-MKT-005` — ACCEPTED.** *"Public releases use publisher identity, signing key
fingerprint, revocation state, and package signature; identity does not confer authority on the
phone."* A publisher profile, where one exists, has a stable ID, display name, key fingerprint(s),
key state, verification level, and its public releases. Display-name verification MUST be kept
separate from package-signature validity — a verified display name says something about the
publisher; a valid signature says something about the bytes; neither implies the other. Key
revocation marks affected releases; it MUST NOT retroactively delete local copies already
installed. The phone MUST always perform its own package validation and authority review
(`LOOP_IMPORT_ACTIVATION_CONTRACT.md` §3–§6) regardless of publisher verification level — a
publisher profile MUST NOT be presented in a way that implies Fonebrew endorsement merely because
a signature verifies.

**Realization for the registry this build-out actually ships (§11):** the static/Git registry has
no publisher-accounts service, so "publisher identity" reduces to what the signed index entry and
Git history already carry without any additional storage: the Ed25519 public key fingerprint(s)
declared in the publisher's index entry (`loop-package-signature.v1`, `LOOP_PACKAGE_SPEC.md` §11),
and the GitHub identity that opened the submission pull request, auditable in the registry
repository's own commit history. There is no separate "verification level" tier requiring a
backend to compute or store — "verification level" for this build-out is exactly and only "the
signature verifies against the fingerprint the index entry declares," a fact the client checks
locally at import time, never a trust score served from anywhere.

## 7. Discovery (`FB-RAT-MKT-011`)

Discovery filters (P0/P1) include category, language, hardware, local/offline ability, model
requirement, cloud requirement, execution target, estimated cost/duration, read/write/destructive
authority, engine version, test coverage, verification level (§6), license, publisher, and
compatibility (`LOOP_COMPATIBILITY_CONTRACT.md`). Search facets SHOULD be derived from declared
package manifest data wherever possible, to reduce misleading free-text prose taking the place of
a checkable fact.

**`FB-RAT-MKT-011` — DEFERRED.** *"Defer personalized recommendations; P1 discovery uses explicit
filters, categories, compatibility, and curation"* (`docs/non_ratified/DEFERRED_DECISIONS.md`).
Discovery MUST favor explicit filters and curated collections over personalized ranking until
this deferral is lifted; there is no unblock condition recorded yet beyond "P1 discovery
exercised with explicit filters/categories/compatibility/curation in production use" — no
algorithmic ranking, and nothing that would require an accounts backend to personalize against.

**Realization for §11:** a static browse site computes these facets once, at index-build time,
directly from the signed index and package manifests — there is no ranking service, no per-user
state, and therefore nothing for `FB-RAT-MKT-011`'s deferral to prematurely violate.

## 8. Reviews and ratings (`FB-RAT-MKT-006`)

**`FB-RAT-MKT-006` — ACCEPTED.** *"Reviews separate usefulness, reliability, understandability,
efficiency, and safety, plus a worked-for-me signal and free text."* A review, where the optional
backend supports one, declares: release identity (never a listing or a `loopId` alone — reviews
are release-specific, §4), optional verified-install/verified-run references, ratings for
usefulness, reliability, understandability, efficiency, and safety, a `workedForMe` boolean, free
text, and a moderation state (§10). Reviews MUST NOT silently migrate to a later release version
merely because the `loopId` matches — a review of `1.2.0` says nothing about `1.3.0` unless the
service explicitly carries it forward with that fact visible. The service, where operated, SHOULD
resist review spam through rate limits and moderation, and MUST NOT require phone telemetry to do
so (`FB-RAT-MKT-008`, §9) — rate-limiting by request pattern and account state is sufficient and
does not need private usage data from the phone.

**Not built in this pass.** Per the hard scope boundary above and `08_OPEN_QUESTIONS.md` §E.1,
structured reviews require a reviews database, which this build-out does not construct. This
section is the interaction contract a future optional backend MUST follow if the owner turns
reviews on; the static/Git registry (§11) carries no review mechanism of any kind.

## 9. Result evidence (`FB-RAT-MKT-008`)

**`FB-RAT-MKT-008` — ACCEPTED.** *"Marketplace contact MUST NOT upload private usage, execution
logs, prompts, files, model outputs, or run results automatically."* Shared results follow
`LOOP_RESULT_SHARING_CONTRACT.md` — that document, not this one, owns the full shape of a shared
result receipt and its redaction rules; this document only states the marketplace-facing
constraint. A marketplace or registry MAY display result counts, but only when receipt semantics
and sampling limitations are visible alongside the count; it MUST NOT imply objective success from
unsigned, unverified, or self-selected evidence. `LOOP_RESULT_SHARING_CONTRACT.md` had not been
written as of this document — this section is a pointer, not a substitute, per the same
"sibling documents this document defers to and does not restate" convention this corpus uses
throughout (§16).

## 10. Moderation and reporting (`FB-RAT-MKT-007`, corrected)

*(The source register describes this decision as "abuse reporting, malware-like content checks,
secret scanning, takedown states, and publisher-key revocation handling." Per
`02_DECISION_ANNOTATIONS.md` §4b's correction and this work package's file-specific instruction,
"malware-like content checks" is dropped entirely — it names no real, checkable category — and
replaced below with three named checks, each a cross-reference to a rule already frozen elsewhere,
not a new detector invented here.)*

**`FB-RAT-MKT-007` — ACCEPTED, as corrected.** Public publication requires abuse reporting,
takedown states, and publisher-key revocation handling, gated by three named automated checks:

1. **Archive safety.** Every submitted package MUST be rejected if it matches any adversarial
   class `floop-container-format.v1.json`'s `pathRules.adversarialClasses_MUST_reject` already
   enumerates (absolute path entries, path traversal, duplicate normalized paths, symlink escape,
   decompression-ratio bombs, declared/actual length or digest mismatches, unknown major schema
   version) or the forbidden-executable-payload / path-canonicalization rule codes
   `LOOP-PKG-002` / `LOOP-PKG-003` in `loop-validation-rules.v1.json`. This check is deterministic
   and closes the archive-format attack surface — none of it is redefined here, only reused.
2. **Secret scanning by a named tool.** Every submitted package MUST be scanned with **gitleaks**
   (MIT-licensed), per `02_DECISION_ANNOTATIONS.md` §4b's correction to this decision and
   `LOOP_PACKAGE_SPEC.md` §7's identical citation — the same tool, named once, reused everywhere it
   is required, not re-chosen per document. This realizes rule code `LOOP-PKG-001` (secret-like
   pattern match) for the publication path specifically. A free-tier scanning service (e.g.
   VirusTotal) MUST NOT be substituted — its free-tier terms are license-incompatible with a paid
   product, per the same correction.
3. **Prompt-risk review.** Every model-directed string in the package MUST be scanned per rule
   code `LOOP-PKG-008` (already frozen in `loop-validation-rules.v1.json`, and normatively
   detailed in `LOOP_PACKAGE_SPEC.md` §7) — a `WARNING`-severity heuristic flag requiring human
   review before publish, never an automatic rejection on its own. This is the check that reaches
   the attack class §5's declarative-only content policy cannot reach by itself: prompt content
   that is not executable but is still misuse-shaped.

Automated checks are advisory unless deterministic — checks 1 and 2 above are deterministic and
MAY auto-reject; check 3 is a heuristic flag and MUST NOT auto-reject on its own. Human review
governs contextual abuse (harassment, fraud, deceptive capability claims) that no automated check
can decide.

**Reporting.** A report records: reason category, target (release, listing, review, or publisher),
evidence, reporter state, decision, and appeal where the service supports one. Delisting prevents
new discovery and download through the service; it MUST NOT remotely delete a user's already
-downloaded local package. Key revocation and severe safety notices MUST be surfaced on manual
update/check actions, not pushed silently.

**Required states.** `PENDING`, `PUBLISHED`, `LIMITED`, `DELISTED`, `TAKEDOWN`, `KEY_REVOKED`,
rendered here as an explicit state table rather than a bare list — the draft names the six states
without ever stating a transition, which is not testable:

| From state | Event | To state |
|---|---|---|
| *(none — publisher action)* | Publisher submits a signed release for public publication. | `PENDING` |
| `PENDING` | Both deterministic checks (archive safety, secret scanning) pass, the prompt-risk-review flag (if any) has been human-reviewed and cleared, and the signature verifies. | `PUBLISHED` |
| `PENDING` | Either deterministic check fails, or the signature does not verify, or a human reviewer rejects a prompt-risk flag. | *(rejected before publication — not a persistent state; publisher is notified and MUST resubmit; no record of a rejected-pre-publication submission is retained as a marketplace object)* |
| `PUBLISHED` | A report is reviewed and substantiates a partial concern that does not warrant full removal (e.g. a quality or documentation defect). | `LIMITED` |
| `PUBLISHED` or `LIMITED` | A report is reviewed and substantiates removal grounds (a secret missed at publish time, a misleading capability declaration, unsafe undisclosed authority, a policy violation). | `DELISTED` |
| `PUBLISHED`, `LIMITED`, or `DELISTED` | A severe, legally compelled, or safety-critical removal is required (confirmed malicious intent, a legal takedown notice). | `TAKEDOWN` |
| `LIMITED` or `DELISTED` | An appeal is reviewed and reversed, where the service supports appeal. | `PUBLISHED` |

`KEY_REVOKED` is cross-cutting rather than a stage in the row above: it applies to a *publisher's
key*, not to one release's editorial state, and MAY be entered from any state once a release
under that key exists — a revocation marks every affected release without moving each one through
the table above individually. `PENDING` and its rejection branch have no receipt-worthy identity
beyond the submission and rejection events themselves — the draft's states begin meaningfully at
`PENDING`, not before.

**Not built in this pass.** The state table above is the ratified moderation contract for the
optional bounded backend, not a service this build-out operates — see §11 for how reporting and
"removal" are realized without any moderation storage at all.

## 11. The static/Git-backed registry — the definition of the marketplace for this build-out (`FB-RAT-MKT-009`, PROMOTED)

*(New normative text. The draft's "Rollout" section — draft §11, first pass — listed a P0 registry
step and a P1 marketplace step as sequential phases of one plan, with `FB-RAT-MKT-009` recorded
EXPERIMENTAL as if the registry were a fallback to try before the "real" marketplace. That framing
is corrected here, not merely annotated: `10_DUAL_VALIDATION_ADDENDUM.md` §B4 identifies this as
inverted risk grading — the amendment that needs a backend (`FB-RAT-MKT-001`) was ACCEPTED while
the near-zero-ops way to satisfy it without one was left EXPERIMENTAL — and both
`09_DUAL_SURFACE_LOOP_BUILDER.md` §4 and `00_MASTER_PROMPT.md`'s own build instruction resolve it
the same way.)*

**`FB-RAT-MKT-009` is PROMOTED from EXPERIMENTAL to ACCEPTED, and is THE DEFINITION of the
marketplace for this build-out — not a fallback option, not a P0 stepping-stone toward a fuller
service.** Stated as plainly as possible: everything this build-out constructs under the name
"marketplace" or "registry" is the mechanism below, and nothing else.

The registry is:

- **A signed JSON index in a public Git repository.** The index lists releases by `loopId`,
  `semanticVersion`, package-content digest (`LOOP_PACKAGE_SPEC.md` §4), publisher key
  fingerprint, and a download URL. The index itself is signed; a client verifies the index
  signature before trusting any entry in it.
- **`.floop` package assets hosted on publishers' own GitHub Releases — never
  Fonebrew-hosted user content.** Fonebrew's registry repository holds only the index and
  metadata; the bytes of every package live on infrastructure the publisher, not Fonebrew,
  controls and is responsible for. GitHub Releases carries no total-size or bandwidth cap (a
  2 GiB per-asset limit and a 1000-assets-per-release limit, neither of which bounds aggregate
  registry size), which is why this costs the registry operator effectively nothing.
- **A static browse site.** Generated from the index at build time (§7); no server-side query
  engine, no per-request computation over private state, because there is no private state to
  compute over.
- **Submission by pull request.** A publisher adds or updates their index entry via a PR against
  the registry repository; the three checks in §10 run against the submission (archive safety and
  secret scanning as required CI checks that can block merge; prompt-risk review as a flag a human
  reviewer clears before merge). Merge *is* publication; the PR and its review are the audit
  trail — nothing extra is stored.

**This satisfies `FB-RAT-MKT-002` (§3), `FB-RAT-MKT-003` (§4), `FB-RAT-MKT-004` (§5), and
`FB-RAT-MKT-008` (§9), and the marketplace-outage-independence gate (§2), with no database, no
accounts, no personal-data store, and near-zero operating cost.** And because no user content is
ever hosted by Fonebrew — every package's bytes live on the publisher's own release — **this
model carries no DSA or GDPR hosting duties at all**: there is no user-generated content stored on
Fonebrew-controlled infrastructure for those duties to attach to. This is the load-bearing fact
behind §15's Play/legal scope statement.

**State plainly: this build-out does NOT build a hosted marketplace service, accounts, reviews, or
moderation storage.** It emits contracts, client code paths, and artifacts compatible with this
static/Git registry only. This is a hard scope boundary carried over from the master build brief
verbatim (this document's header), not a discretionary implementation choice a later session is
free to widen without an owner decision.

**Mapping §10's moderation contract onto what is actually built:**

| §10 concept (optional-backend contract) | Realization in the static/Git registry |
|---|---|
| `PENDING` → `PUBLISHED` transition | An open pull request against the registry repository, merged once §10's checks pass. |
| Report | A GitHub issue opened against the registry repository, naming the target index entry. |
| `LIMITED` / `DELISTED` | The index entry is removed or amended by a follow-up PR; Git history retains the record without any separate moderation-storage system. |
| `TAKEDOWN` | Same mechanism as `DELISTED`, at higher urgency; no distinct storage. |
| `KEY_REVOKED` | A `revoked: true` / `revokedAt` field set directly on the affected index entries via PR; a client checks this field locally at browse/download/update time — no push notification, no revocation service. |
| Appeal | Ordinary GitHub issue/PR discussion on the repository; no separate appeals system. |

Nothing in this table requires inventing storage beyond the index and the Git history that already
exists the moment the registry repository exists.

## 12. Registry replaceability (`FB-RAT-MKT-012`, NEW — PROPOSED, not self-ratified)

*(New normative text, added per this work package's file-specific instruction and
`10_DUAL_VALIDATION_ADDENDUM.md` §B4's "replaceable, not just optional" finding. This is a
**PROPOSED** decision — it is stated here as the file-specific instruction requires, but this
document does not self-ratify a new stable ID; formal registration in
`docs/non_ratified/EXPERIMENTAL_DECISIONS.md`'s "Proposed new decisions" section, alongside
`FB-RAT-WS-NEW-1`, is Amendments-phase / registry-hygiene work.)*

`FB-RAT-MKT-001` (§2) makes the marketplace optional per artifact; it does not, by itself,
guarantee that the *registry itself* is anything other than a single Fonebrew-operated instance a
user must trust. **`FB-RAT-MKT-012` closes that gap — the guarantee that makes the registry
replaceable, not merely optional:**

- The registry base URL MUST be user-configurable.
- The registry client MUST be fully disablable — a user who wants no registry at all MUST be able
  to turn it off without losing any local-authoring, direct-file, or Git-transfer functionality
  (§2, §13).
- The index format (§11) MUST be published, so a third party MAY host a conformant registry of
  their own — the same index schema, the same signature scheme, a different Git repository and a
  different set of publisher releases.

This is near-zero additional engineering over §11 (a base-URL setting and a published schema),
and it directly neutralizes the brand risk to the sovereignty pitch that a single
Fonebrew-controlled registry, however lightly it operates, would otherwise carry — precedent from
Home Assistant (no central blueprint catalog; import by user-pasted URL) and n8n
(`N8N_TEMPLATES_ENABLED` / `N8N_TEMPLATES_HOST`) is directly on point.

## 13. Marketplace availability and export

Every published package MUST be downloadable as the canonical archive — no marketplace-specific
wrapper format. Creators MUST be able to export listing metadata, release records, any reviews
allowed by policy, and publisher key history — none of this is a Fonebrew-exclusive record. Total
marketplace or registry failure MUST NOT prevent direct Git or file distribution (§2, §11); package
identity is portable and is never tied to a database row alone — it is the content digest
(`LOOP_PACKAGE_SPEC.md` §4), which exists independent of any index, database, or listing.

## 14. Monetization boundary (`FB-RAT-MKT-010`)

Loop execution and local authoring remain free core, under the product's stated philosophy.

**`FB-RAT-MKT-010` — DEFERRED.** *"Defer paid loops, tipping, revenue share, and commercial
licenses until moderation, refunds, trust, and legal terms are designed"*
(`docs/non_ratified/DEFERRED_DECISIONS.md`). Future creator patronage or paid commercial listings
require separate ratification and, whenever that ratification happens, MUST NOT make package
safety information, compatibility declarations, source attribution, or local import dependent on
payment — a payment gate on any of those four would convert a sovereignty tool into a
paywalled-safety-information tool, which no future monetization decision is authorized to do
implicitly. `08_OPEN_QUESTIONS.md` §E.5 keeps this deferred and explicitly warns against letting
marketplace design foreclose it later — this section does not narrow that owner-reserved decision,
only restates the constraint that survives whichever way it resolves.

## 15. Play policy, legal, and prompt-security — explicitly out of scope here

*(New normative text; the draft contains none of this. Stated plainly per this work package's
file-specific instruction: this absence is a scope boundary to record, not an oversight to
silently patch over.)*

This document has **no Play policy layer, no legal layer, and no prompt-security layer of its
own**, and does not attempt to invent one. Two things are already true, and one thing is not yet
true:

**Already true — prompt security.** `WP-1L-G0` already froze the three P0 prompt-security
controls this document's §10 reuses by reference, as rule codes in
`loop-validation-rules.v1.json`: invisible-Unicode rejection (`LOOP-PKG-006`), the manifest egress
allowlist (`LOOP-PKG-007`), and prompt-risk static scanning (`LOOP-PKG-008`). This document does
not redefine any of them — §10's third named check *is* `LOOP-PKG-008`, cited, not restated.

**Not yet true — Play policy and legal.** No occurrence of terms-of-use acceptance, in-app
reporting-and-block controls, a moderation SLA, a DMCA agent designation, or GDPR/DSA compliance
duties exists anywhere in this document, because none of that layer has been designed. It belongs
in a dedicated `LOOP_STORE_COMPLIANCE_CONTRACT.md`, which **this session is not authorized to
write** — `10_DUAL_VALIDATION_ADDENDUM.md` §B4 names the missing pieces in full (an in-app ToU
acceptance gate before any publish/review/share action; in-app report *and block* controls on
every listing, release, review, publisher, and shared result; a written moderation SLA; an in-app
"flag this output" control wired to work for locally-run loops; a content-safety class in package
validation distinct from secret scanning; a DMCA-designated agent; a GDPR Art. 27 EU
representative; DSA Art. 16/17/11/12/13 duties, sized for a micro-enterprise per Art. 19(1)/29(1)
but not zero).

**Hard gate — record this as binding, not a suggestion:** marketplace features MUST NOT ship on
the Play distribution flavor until `LOOP_STORE_COMPLIANCE_CONTRACT.md` exists and its own gates
pass. This is independent of, and in addition to, §11's static/Git registry being the only thing
this build-out constructs at all — even once that registry client exists, its Play-flavor
availability is gated on the compliance layer this document explicitly does not attempt to supply.
The sideload/full flavor is not exempt from this gate by virtue of being sideload-only; it is only
that Play's own review process is what makes the absence of this layer immediately, structurally
unshippable there, which is why the gate is stated in Play-flavor terms.

## 16. Cross-references and open items

**Decision IDs cited in this document:** `FB-RAT-MKT-001` (§2, ACCEPTED), `FB-RAT-MKT-002` (§3,
ACCEPTED), `FB-RAT-MKT-003` (§4, ACCEPTED), `FB-RAT-MKT-004` (§5, ACCEPTED), `FB-RAT-MKT-005` (§6,
ACCEPTED), `FB-RAT-MKT-006` (§8, ACCEPTED, not built this pass), `FB-RAT-MKT-007` (§10, ACCEPTED
as corrected), `FB-RAT-MKT-008` (§9, ACCEPTED, canonical home `LOOP_RESULT_SHARING_CONTRACT.md`),
`FB-RAT-MKT-009` (§11, **PROMOTED** EXPERIMENTAL → ACCEPTED, THE DEFINITION), `FB-RAT-MKT-010`
(§14, DEFERRED), `FB-RAT-MKT-011` (§7, DEFERRED), `FB-RAT-MKT-012` (§12, **PROPOSED**, not
self-ratified). `FB-RAT-INT-003` (§2.1, REJECTED, partially superseded as stated) and
`FB-RAT-PORT-009` (§2, §5, DEFERRED, cited as adjacent context, not re-decided here) are cited as
already-ratified/already-recorded context.

**Registries reconciled, not redefined:** `loop-validation-rules.v1.json` (rule codes
`LOOP-PKG-001`, `LOOP-PKG-002`, `LOOP-PKG-003`, `LOOP-PKG-006`, `LOOP-PKG-007`, `LOOP-PKG-008` —
§5, §10, §15); `floop-container-format.v1.json` (`pathRules.adversarialClasses_MUST_reject` — §10).
This document does not duplicate their contents; a conflict between this document's prose and a
registry's data is this document's error, and the registry wins, per
`LOOP_FROZEN_CONCEPTS_WP1L_G0.md`.

**Sibling documents this document defers to and does not restate:** `LOOP_PACKAGE_SPEC.md` (§4
package-content digest, §7 the three P0 controls, §11 signatures — §4, §5, §10, §11, §13 of this
document), `LOOP_IMPORT_ACTIVATION_CONTRACT.md` (phone-side validation and authority review — §6),
`LOOP_COMPATIBILITY_CONTRACT.md` (the compatibility axis discovery filters on — §7),
`LOOP_RESULT_SHARING_CONTRACT.md` (the full shared-result-receipt shape — §9; not yet written as
of this document), `LOOP_P0_P1_RELEASE_GATES.md` (the outage-independence gate names — §2, §13).

**Not yet written, forward-pointed only:** `LoopMarketplaceContracts.kt` and the moderation
extension named alongside it (`inputs/dual_surface/DUAL_TRACEABILITY_MATRIX.md` names
`loop-release`, `loop-listing`, and `loop-review` as this decision family's schema targets, and
`LoopMarketplaceContracts.kt` — plus moderation — as its Kotlin target); `LOOP_STORE_COMPLIANCE_
CONTRACT.md` (§15 — explicitly out of scope for this session); `AMENDMENTS.md` (§2.1 — the
eventual formal amendment ledger for the `FB-RAT-MKT-001`/`FB-RAT-INT-003` supersession line).
Schema and Kotlin-contract authoring, and `AMENDMENTS.md` itself, are WP-1L scope beyond this
document and are not performed here.

**One new decision ID is proposed by this document, per explicit file-specific instruction, and is
not self-ratified:** `FB-RAT-MKT-012` (§12). Formal registration — whether it lands under that
exact numeral or is folded into the `FB-RAT-MKT-NEW-*` proposal-numbering convention
`docs/non_ratified/EXPERIMENTAL_DECISIONS.md` already uses for `FB-RAT-WS-NEW-1` — is
Amendments-phase / registry-hygiene work, not decided here. Every other gap this document needed
to close (the duplicate-numbering restart, the "malware-like content checks" phrase, the
inverted `FB-RAT-MKT-009` risk grading, the missing `FB-RAT-INT-003` citation) was closable by
cross-referencing an already-frozen registry, an already-ratified decision, or the master build
brief's own explicit instruction — none of the rest required inventing a new ID.
