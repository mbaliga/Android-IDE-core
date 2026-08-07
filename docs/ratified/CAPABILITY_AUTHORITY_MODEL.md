# Capability Authority Model — the `authority` domain

> **License:** `LICENSE-PENDING` — see `docs/non_ratified/LICENSE_PENDING.md`.

**Status:** RATIFIED (this document). Scope: the `authority` domain named in the WP-1 task
brief — `Principal`, `Grant`, `AuthorityDecisionRecord`, and the five-variant `AuthorityDecision`
state machine every capability check in the constellation resolves to. This document is the
citation target for `FB-RAT-AUTH-001`…`FB-RAT-AUTH-007`. It is the **general** authority-and-
capability spec; `schemas/loops/registries/capability-ids.v1.json` (frozen, `WP-1L-G0`) is the
**concrete, reverse-DNS `fb.*` capability-ID registry** this model is written against — one
conformant instance of the ladder this document defines, not a competing design. Likewise
`schemas/loops/registries/loop-validation-rules.v1.json`'s `LOOP-CAP-*` rule codes are the loop
engine's own enforcement surface for the same invariants this document states generally (see
§10.1). Neither registry is redefined here.

**Why this domain matters now.** Per the WP-0 survey (`docs/WP0_SURVEY.md` §1(c)) and
`docs/ratified/EXECUTION_CONTRACT.md` §6, no repo in the constellation has an authority/
capability-ladder engine today — `ExecutionRequest.authorityGrant` (`schemas/execution/
request.schema.json`) is a deliberately minimal `{grantId, scopes}` reference INTO this domain,
left unexpanded on purpose so the execution domain would not have to invent (and potentially
conflict with) this design. This document and its schemas are that expansion. Package paths cited
below use the real `core-engine/src/main/java/dev/aarso/...` location (WP-0 survey correction to
the stale `app/src/main/java/...` claim in both repos' `CLAUDE.md`), and the real CI gate command
is `./gradlew --no-daemon :core-engine:testFullDebugUnitTest :core-engine:testPlayDebugUnitTest
:core-engine:checkLicense` (read from `.github/workflows/ci.yml`, not from either stale
`CLAUDE.md`).

This document builds on, and does not repeat, `docs/ratified/COMMON_CONVENTIONS.md`'s
`ContractEnvelope`/`ErrorEnvelope` — every top-level shape below is typically carried as the
`payload` of a `ContractEnvelope<T>` (`schemas/common/envelope.schema.json`).

---

## 1. Capability model (FB-RAT-AUTH-001)

**FB-RAT-AUTH-001 — ACCEPTED.** Capability model: scoped capabilities, not broad trust labels
(e.g. "agent can use terminal").

Nothing in this constellation is ever authorized by a coarse label like "trusted agent" or "can
use terminal." Every authorization is a `Grant` (§3.2) naming one **principal** (§3.1, WHO), one
or more **capability IDs** from the `fb.*` reverse-DNS namespace (WHAT — never a free-text verb),
and one **resource scope** (§3.2, WHICH). A principal with zero grants can do nothing; a principal
with a `fb.repo.read` grant scoped to one repository cannot read a different repository, cannot
write to that same repository, and cannot use a secret — even though, informally, "the agent" is
the same running process in all three cases. This is what "scoped, not broad" cashes out to
operationally.

## 2. Authority ladder (FB-RAT-AUTH-002)

**FB-RAT-AUTH-002 — ACCEPTED.** Authority ladder: `OBSERVE`, `READ`, `PROPOSE`, `MODIFY_DRAFT`,
`EXECUTE_REVERSIBLE`, `EXECUTE_EXTERNAL`, `EXECUTE_DESTRUCTIVE`, `PUBLISH_OR_RELEASE`.

Eight rungs, strictly ordered low-to-high. Every `Grant.authorityRung` and every
`AuthorityDecisionRecord.requiredRung` (schemas below) is one of these eight values —
`contracts/kotlin/AuthorityContracts.kt`'s `AuthorityRung` enum declares them in this exact
order specifically so `.ordinal` comparisons encode the ladder correctly.

| # | Rung | What it means | Representative `fb.*` capabilities (capability-ids.v1.json) |
|---|---|---|---|
| 1 | `OBSERVE` | See that something exists; no content, no side effects. | `fb.workspace.observe` |
| 2 | `READ` | Read content/history. No mutation. | `fb.repo.read`, `fb.marketplace.import` |
| 3 | `PROPOSE` | Produce a proposal a human must approve; no write lands on its own. | `fb.repo.propose_change`, `fb.device.usb_permission` |
| 4 | `MODIFY_DRAFT` | Write to an uncommitted draft/buffer — reversible, not yet committed. | `fb.repo.write_draft` |
| 5 | `EXECUTE_REVERSIBLE` | A persisted side effect that is still reversible (e.g. `git revert`). | `fb.repo.commit`, `fb.exec.local_process`, `fb.device.serial_read`, `fb.model.local_inference` |
| 6 | `EXECUTE_EXTERNAL` | A side effect visible to a third party outside this device. | `fb.repo.push_remote`, `fb.exec.ssh_remote`, `fb.ci.dispatch`, `fb.network.egress`, `fb.secret.use`, `fb.model.cloud_inference` |
| 7 | `EXECUTE_DESTRUCTIVE` | Not cleanly reversible; requires visible destructive-authority evidence (§8). | `fb.repo.history_rewrite`, `fb.device.flash` |
| 8 | `PUBLISH_OR_RELEASE` | Deliberately shares something outward; user-only rung, never held by an automated loop node. | `fb.marketplace.publish`, `fb.release.publish` |

A `Grant` is scoped to exactly one rung: `Grant.capabilityIds` MUST all resolve to the same
`authorityRung` in capability-ids.v1.json (`schemas/authority/grant.schema.json`'s own
description; see §10.1 and `fixtures/authority/adversarial/
grant-authorityrung-capability-mismatch.adversarial.json` for what happens when they disagree). A
policy needing capabilities across multiple rungs issues multiple grants, one per rung — this
keeps "does this delegation exceed its ceiling" (§6) a single field comparison, never a
per-capability registry lookup at delegation time.

## 3. Object model

### 3.1 `Principal` — `schemas/authority/principal.schema.json`

Something authority can be granted to. Six kinds, verbatim from the GRANT MODEL principal list:
`USER`, `LOCAL_AGENT_PERSONA`, `LOOP_RUN`, `COUNCIL_MEMBER`, `STUDIO_WORKFLOW`,
`IMPORTED_COMPANION_ARTIFACT`. `USER` is the sole root of authority — every other kind MUST
declare a non-null `parentPrincipalId` (structurally enforced via an `allOf`/if/then pair; `USER`
itself MUST declare `parentPrincipalId: null`). This single field is the spine §6 and §9 are built
on: it is what lets a policy engine walk from any automated principal back to the accountable
human. `status` (`ACTIVE`/`REVOKED`/`EXPIRED`) means every grant a non-`ACTIVE` principal holds is
unusable without individually revoking each one.

### 3.2 `Grant` — `schemas/authority/grant.schema.json`

The seven-part shape named in the WP-1 task brief's GRANT MODEL, field-for-field:

| GRANT MODEL part | Field(s) |
|---|---|
| principal | `principalId` (references `Principal.principalId`) |
| actions | `capabilityIds` (reverse-DNS `fb.*`, capability-ids.v1.json) + `authorityRung` |
| resource scope | `resourceScope` (`$defs/ResourceScope` — `kind` + `locator`) |
| constraints | `constraints` (`purpose` + open `additionalConstraints` bag) |
| expiry | `expiresAtUtc` — **required, non-null on every Grant** (§7) |
| confirmation policy | `confirmationPolicy` (`$defs/ConfirmationPolicy`) |
| delegation rule | `delegationRule` (`$defs/DelegationRule`) |

`resourceScope.kind` is one of the nine GRANT MODEL resources: `WORKSPACE_ROOT`, `FILE_GLOB`,
`REPOSITORY`, `EXECUTION_TARGET`, `NETWORK_DOMAIN`, `SECRET`, `DEVICE`, `RELEASE`,
`STORE_CHANNEL`. `delegationRule` is where §6/§9 become structurally enforced, not just prose —
see those sections.

### 3.3 `AuthorityDecisionRecord` — `schemas/authority/decision.schema.json`

The append-only (FB-RAT-COM-006) audit record of evaluating ONE capability request against the
grant store: which principal, which single `fb.*` capability, which resource, which `Grant`
matched (if any), and which of the five outcomes (§4) resulted, with a stable `reasonCode` and the
`policyVersion` it was evaluated under (so "policy decisions are deterministic" is checkable after
the fact — re-running the same request against the same `policyVersion` MUST reproduce the same
outcome). Mirrors `contracts/kotlin/AuthorityContracts.kt`'s `AuthorityDecision` sealed interface
1:1 — see §4 for the per-variant field requirements, enforced there at compile time in Kotlin and
via `if`/`then` in the schema.

## 4. The decision table

**GRANT MODEL — ACCEPTED.** Decisions: allow / deny / require confirmation / require stronger
authority / allow with redaction or sandbox.

| Outcome | `contracts/kotlin/AuthorityContracts.kt` variant | Fires when | Structurally required fields | `matchedGrantId` |
|---|---|---|---|---|
| **ALLOW** | `AuthorityDecision.Allow` | A `Grant` exists whose `principalId`, `capabilityIds`, `resourceScope`, `constraints.purpose` (§7), and `expiresAtUtc` (§7) all cover the request, at or above the needed rung, with no confirmation/redaction/sandbox condition triggered. | — | **required, non-null** |
| **DENY** | `AuthorityDecision.Deny` | Default (§5, no grant matched at all — `reasonCode: AUTHORITY_DEFAULT_DENY`, `matchedGrantId` MUST be `null`), OR a grant existed but failed a check: expired (§7), wrong purpose/target (§7), principal not `ACTIVE`, lineage never held sufficient rung (§6), or a transitive-delegation attempt (§9). | — | `null` only for `AUTHORITY_DEFAULT_DENY`; non-null otherwise |
| **REQUIRE_CONFIRMATION** | `AuthorityDecision.RequireConfirmation` | A grant covers the request, but `Grant.confirmationPolicy` says this specific use needs a fresh, explicit user confirmation before proceeding (FB-RAT-AUTH-006, §8). | `confirmationPromptRef` (non-blank; FB-RAT-COM-009 — must carry text semantics) | **required, non-null** |
| **REQUIRE_STRONGER_AUTHORITY** | `AuthorityDecision.RequireStrongerAuthority` | The request needs a rung no held grant (or no grant at all) reaches. | `requiredRung` | optional (may cite a grant that exists but sits below the needed rung, or be `null`) |
| **ALLOW_WITH_REDACTION_OR_SANDBOX** | `AuthorityDecision.AllowWithRedactionOrSandbox` | Authority is granted, but mitigated: sensitive content is stripped before the action proceeds (`REDACTION` — e.g. the secret-leak pattern already guarded against at `fixtures/execution/adversarial/receipt-secret-leak-in-log-excerpt`), or the action runs inside an isolated/bounded context regardless of what it attempts (`SANDBOX` — e.g. an imported companion artifact's first activation, `IMPORTED_COMPANION_ARTIFACT`). | `redactionOrSandbox` (`mode` + `detail`) | **required, non-null** |

Both the JSON Schema (`decision.schema.json`'s `allOf`/`if`/`then` blocks) and the Kotlin sealed
interface (per-variant constructor shapes) enforce the "structurally required fields" column —
doubly, at both the wire-validation layer and the compile-time layer, on purpose.

## 5. Default deny (FB-RAT-AUTH-003)

**FB-RAT-AUTH-003 — ACCEPTED.** Default deny: deny when no matching grant exists.

There is no ambient-allow fallback anywhere in this model — absence of a matching `Grant` IS the
deny condition, not a fallback of last resort checked only after some other rule fails. A
completely novel `(principalId, capabilityId, resourceScope)` triple that has never been the
subject of any grant MUST resolve to `Deny(reasonCode = "AUTHORITY_DEFAULT_DENY", matchedGrantId =
null)` — structurally enforced by `decision.schema.json`'s `if`/`then` (a record claiming
`AUTHORITY_DEFAULT_DENY` cannot carry a non-null `matchedGrantId`) and demonstrated end-to-end in
`fixtures/authority/adversarial/decision-default-deny-unmatched.adversarial.json`. This is a case
JSON Schema structural validation can partially check (the shape-level consistency) but cannot
fully prove (that the search over the grant store was actually exhaustive) — see that fixture's
sibling `.expected.txt`.

## 6. No implicit privilege expansion (FB-RAT-AUTH-004)

**FB-RAT-AUTH-004 — ACCEPTED.** No implicit privilege expansion: child agents/tools cannot
receive more authority than parent without explicit user escalation.

Enforced at **three** independent layers, deliberately redundant:

1. **Principal lineage is mandatory** (§3.1) — every non-`USER` principal names its parent, so
   "walk to the accountable human and check what was actually granted along the way" is always
   possible.
2. **`Grant.delegationRule.maxDelegatedRung` cannot exceed the grant's own `authorityRung`,
   structurally.** `schemas/authority/grant.schema.json`'s top-level `allOf` contains one
   `if`/`then` block per rung (eight total), each constraining `maxDelegatedRung`'s allowed enum
   values to "this rung and everything below it, or `null`." A grant declaring
   `authorityRung: EXECUTE_REVERSIBLE` with `delegationRule.maxDelegatedRung: EXECUTE_DESTRUCTIVE`
   is not a policy question — it is not valid JSON against this schema at all. Mirrored in Kotlin
   by `Grant`'s `init` block calling `AuthorityRung.atOrBelow`. See
   `fixtures/authority/invalid/grant-delegation-rung-widening.invalid.json`.
3. **A decision-time check that no `Deny`-worthy fixture can pass structural review alone.**
   `fixtures/authority/adversarial/decision-child-exceeds-parent.adversarial.json` models a
   `LOOP_RUN` principal requesting `fb.device.flash` (`EXECUTE_DESTRUCTIVE`) when neither it nor
   its `USER` parent ever held a grant reaching that rung for that resource — schema-valid on its
   face, and exactly the kind of check only a real lineage-walking policy engine can perform
   correctly (see that fixture's `.expected.txt`).

## 7. Purpose and target binding (FB-RAT-AUTH-005)

**FB-RAT-AUTH-005 — ACCEPTED.** Purpose and target binding: secret/capability access is
purpose-bound, target-bound, time-bound, revocable.

Four independent axes, each with its own field:

- **Target-bound** — `Grant.resourceScope`. A grant scoped to `{kind: NETWORK_DOMAIN, locator:
  "127.0.0.1:11435"}` (the ASOM daemon, §10.2) does not cover `{kind: EXECUTION_TARGET, locator:
  "target-local-android-this-phone"}` (a local shell operation), even for the exact same
  `principalId` and `capabilityId`.
- **Purpose-bound** — `Grant.constraints.purpose`. Independent of target-binding: two requests can
  target the identical resource and still disagree on purpose. This is the axis
  `fixtures/authority/adversarial/decision-purpose-crossing-secret-use.adversarial.json`
  specifically demonstrates — **"a model key must be unusable by a shell operation"** (this
  document's own invariant list) is exactly the purpose-mismatch case: a `fb.secret.use` grant
  purpose-bound to `"asom-model-provider-authentication"` MUST NOT authorize an unrelated
  shell/network operation, even when both nominally involve "using a secret."
- **Time-bound** — `Grant.expiresAtUtc`, **required non-null on every Grant** (there is no
  representable non-expiring grant in this contract). A request evaluated at or after a matched
  grant's `expiresAtUtc` MUST decide `Deny(reasonCode = "AUTHORITY_GRANT_EXPIRED")`, **never
  silently renew** — renewal is always a fresh `Grant` with a fresh `grantId`, issued through the
  same explicit path as the original. See
  `fixtures/authority/adversarial/decision-request-against-expired-grant.adversarial.json`: JSON
  Schema's `date-time` format has no "is A after B" comparison keyword, so this is unavoidably a
  policy-engine runtime obligation, not a schema-checkable one.
- **Revocable** — `Principal.status` (§3.1) and, implicitly, a `Grant`'s own removal from the
  grant store (this contract does not model a separate `Grant.revoked` flag — a revoked grant is
  a grant no longer present for a decision engine to match against; `Principal.status: REVOKED`
  covers the "revoke everything this principal holds, at once" case named in the invariants list).

## 8. Visible high-risk authority (FB-RAT-AUTH-006)

**FB-RAT-AUTH-006 — ACCEPTED.** Visible high-risk authority: device destruction, Git history
rewrite, releases, store actions require visible authority evidence.

Concretely, the four operation classes named in the decision map onto specific `fb.*` capabilities
and rungs: `fb.device.flash` and `fb.repo.history_rewrite` (`EXECUTE_DESTRUCTIVE`), and
`fb.marketplace.publish`/`fb.release.publish` (`PUBLISH_OR_RELEASE`, user-only — a loop node MUST
NOT hold this capability per capability-ids.v1.json's own description). "Visible authority
evidence" is operationalized as `Grant.confirmationPolicy.mode: ALWAYS_REQUIRED` (or
`REQUIRED_ABOVE_RUNG` with `aboveRung` set at or below the operation's own rung) for any grant
covering these capabilities — a policy issuing such a grant with `mode: NEVER_REQUIRED` is
schema-valid (this document does not hard-pin confirmation policy per capability, since that
per-capability mapping lives in the external registry, not this general model) but is a policy
authoring mistake this document flags as one to catch at grant-issuance review, not something
`grant.schema.json` can catch by itself without embedding capability-ids.v1.json's rung-per-ID
table directly (the same limitation noted in §2 and demonstrated in
`fixtures/authority/adversarial/grant-authorityrung-capability-mismatch.adversarial.json`).

## 9. Rejected: automatic authority inheritance (FB-RAT-AUTH-007)

**FB-RAT-AUTH-007 — REJECTED.** Automatic authority inheritance: reject automatic/transitive
delegation that can expand scope without user approval — this MUST land as an adversarial
fixture (a privilege-escalation attempt via transitive delegation that a real implementation
must deny), not just prose.

Structural enforcement, not merely a rejected decision recorded in prose:

- `DelegationRule.transitiveDelegationAllowed` is `const: false` in `grant.schema.json` and a
  `require(!transitiveDelegationAllowed)` invariant in `AuthorityContracts.kt` — no schema-valid,
  no compilable `Grant` in this contract can claim to support multi-hop inheritance.
  `fixtures/authority/invalid/grant-transitive-delegation-flag-rejected.invalid.json` shows the
  structural rejection directly.
- `DelegationRule.requiresFreshUserApprovalForWidening` is `const: true`, same treatment.
  `fixtures/authority/invalid/grant-widening-approval-flag-rejected.invalid.json`.
- The **required adversarial fixture** the decision text itself demands:
  `fixtures/authority/adversarial/decision-transitive-delegation-attempt.adversarial.json` — a
  three-hop-deep `STUDIO_WORKFLOW` principal (spawned by a `LOOP_RUN`, spawned by a
  `LOCAL_AGENT_PERSONA`, spawned by the `USER`) attempts `fb.repo.push_remote` by relying on an
  ancestor's grant instead of holding its own. `matchedGrantId` is `null` because — by
  construction, since no `Grant` anywhere can set `transitiveDelegationAllowed: true` — no grant
  legitimately covers this. `reasonCode: AUTHORITY_TRANSITIVE_DELEGATION_REJECTED`. The record is
  schema-valid; only a real policy engine test proves the *engine* refuses the temptation to walk
  the ancestor chain and treat some ancestor's grant as if it applied transitively — see that
  fixture's `.expected.txt`.

## 10. Cross-domain integration (owned elsewhere, referenced here)

### 10.1 Loop engineering (`schemas/loops/registries/`)

`capability-ids.v1.json` is the concrete `fb.*` registry this document's ladder (§2) is written
against; every `Grant.capabilityIds` entry and every `AuthorityDecisionRecord.requestedCapabilityId`
in this domain's schemas is a string from that registry's namespace (pattern-checked here,
existence-checked there). `loop-validation-rules.v1.json`'s `LOOP-CAP-001` ("node requests a
capability unavailable/prohibited on this target") and `LOOP-CAP-002` ("binding would WIDEN
authority beyond the package's declared request... a binding may only narrow requested authority,
never widen it") are the loop engine's own build-time enforcement of exactly this document's §6 —
`LOOP-CAP-002` is the loop-specific instance of the same narrowing invariant
`grant.schema.json`'s `delegationRule.maxDelegatedRung` ceiling enforces generally.

### 10.2 ASOM (03_CONSTELLATION_CONTEXT addendum)

ASOM at `127.0.0.1:11435` is a ratified network-local model-provider capability with AIDL-verified
pairing. It maps onto this model as **two separate grants**, not one — `fb.secret.use`
(`EXECUTE_EXTERNAL`) and `fb.model.local_inference` (`EXECUTE_REVERSIBLE`) sit at different rungs
in capability-ids.v1.json, and per §2 a single `Grant` MUST NOT mix rungs. Each grant's
`resourceScope` is `{kind: NETWORK_DOMAIN, locator: "127.0.0.1:11435"}` and its
`constraints.purpose` is `"asom-model-provider-authentication"` (or equivalent). This is
deliberately a **purpose-bound network+secret capability, not a general network grant** — a
`fb.network.egress` grant to some other host does not cover ASOM, and an ASOM grant does not
cover general egress. AIDL pairing
itself is the documented exception to the reject-v1-app-to-app-IPC rule (`FB-RAT-INT-004`, owned
by the integration domain, not restated here) — it is a daemon relationship, not a
companion-import relationship, which is why `resourceScope.kind` here is `NETWORK_DOMAIN` and not
some future companion-artifact resource kind.
`fixtures/authority/valid/grant-secret-use-purpose-bound-valid.json` and
`fixtures/authority/adversarial/decision-purpose-crossing-secret-use.adversarial.json` model this
exact grant and the purpose-crossing attempt it must reject.

### 10.3 Execution (`schemas/execution/request.schema.json`)

`ExecutionRequest.authorityGrant` is `{grantId, scopes}` — `grantId` is the **exact same string**
as this domain's `Grant.grantId`; `scopes` is a narrower, target-specific refinement (matching
`ExecutionTarget`'s own `CapabilityManifest.supportedOperations` vocabulary where applicable, per
that schema's own field description) and SHOULD be a subset of the referenced `Grant.capabilityIds`
— never a superset, same narrowing rule as §6. `docs/ratified/EXECUTION_CONTRACT.md` §6 names this
domain as the "separate, create-new domain" its minimal reference points into; this document and
its schemas are that domain.

## 11. No contract bypass (FB-RAT-COM-012)

Every outcome in §4 that grants any authority at all — `ALLOW`, `REQUIRE_CONFIRMATION`,
`ALLOW_WITH_REDACTION_OR_SANDBOX` — requires a non-null `matchedGrantId`, structurally
(`decision.schema.json`'s `if`/`then`, and the Kotlin sealed-interface variants' non-nullable
`matchedGrantId` constructor parameters). There is no shape of `AuthorityDecision` representing
"allowed, for no citable reason" — an engine that wants to permit something MUST be able to name
the `Grant` it derived that permission from, every time. This is this domain's own concrete
instance of `FB-RAT-COM-012` ("a feature bypassing envelope/errors/authority/receipts is a
prototype, not architecture") — applied reflexively, to authority's own decision record, not only
to the other domains that consume this one (§10.3).

## 12. Conformance test-class coverage

Following `COMMON_CONVENTIONS.md` §11's eight-class table, applied to this domain's three schemas.

| # | Test class | Coverage in this domain | JVM-testable |
|---|---|---|---|
| 1 | Golden serialization | `fixtures/authority/valid/*.json` — one fixture per `Principal` kind extreme (`USER` root, non-`USER` child), and per `Grant`/`AuthorityDecisionRecord` shape variant exercised (delegable vs. non-delegable, `ALLOW` vs. `REQUIRE_CONFIRMATION`). | **YES** |
| 2 | State transition | The five-outcome decision table (§4); structurally, `decision.schema.json`'s five `if`/`then` blocks are the state-machine-adjacent rules a single-snapshot schema *can* enforce, doubled by the Kotlin sealed interface's per-variant constructor shapes. | **YES** for snapshot-level consistency; **N/A** beyond that — a decision record is not a multi-step state machine like `ExecutionHandle`, it is a one-shot evaluation result. |
| 3 | Adversarial | `fixtures/authority/adversarial/*` — the full required privilege-escalation pack (child-exceeds-parent, transitive delegation, purpose-crossing secret use, default deny, expired grant) plus one bonus cross-registry integrity case (`authorityRung`/`capabilityIds` mismatch). Structurally-catchable rejections (AUTH-007's two `const` flags, the delegation-rung ceiling, missing per-outcome required fields, the USER/non-USER `parentPrincipalId` rule) live in `invalid/` instead, per this repo's established valid/invalid/adversarial split. | **YES** |
| 4 | Provider conformance | No provider interface is defined in this domain (§ header note in `AuthorityContracts.kt` — the WP-1 task brief asks for the sealed interface + three data classes only, not a policy-engine provider contract). A future policy-engine implementation's conformance against this document's rules (§4–§9) is that implementation's own test suite, not this domain's fixtures. | **NO** (nothing to test yet — no provider exists) |
| 5 | Recovery | Not applicable — `AuthorityDecisionRecord` is an append-only, one-shot audit record (§3.3), not a resumable process with its own recovery path. | **NO** |
| 6 | Performance | No device/emulator exists in this build container. | **NO** |
| 7 | Accessibility | `RequireConfirmation.confirmationPromptRef` and `Deny`'s implicit expectation that a real UI surface renders `reasonCode` as legible text (FB-RAT-COM-009) are JVM-testable structurally (non-blank checks); actual screen-reader/assistive-tech behavior is device-only. | **PARTIAL** |
| 8 | Compatibility | Every top-level shape here inherits `ContractEnvelope`'s `schemaVersion`/`unknownFields` handling from `COMMON_CONVENTIONS.md` §3 — no separate compatibility mechanism is introduced in this domain. | **YES** (inherited) |

---

## Forward pointers (owned elsewhere, not restated here)

- **`FB-RAT-INT-004`** (AIDL pairing as the exception to reject-v1-app-to-app-IPC) — integration
  domain, referenced in §10.2 only for how ASOM's daemon relationship is framed, not restated.
- **A real policy-engine implementation** (the code that actually walks `Principal` lineage,
  matches `Grant`s, and emits `AuthorityDecisionRecord`s) — not built by this WP-1 pass, which
  specifies the contracts and the fixtures a future implementation must satisfy, per the "write
  fixtures before implementation" Definition-of-Ready rule (`FB-RAT-COM-*` global rule 10).
- **Capability-ids.v1.json's own evolution** (new `fb.*` IDs, MINOR-additive per its own
  `bumpRule`) — owned by the loop-engineering `WP-1L-G0` freeze, cross-referenced throughout this
  document, never redefined here.
