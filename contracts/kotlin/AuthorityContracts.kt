// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
//
// AuthorityContracts.kt — the "authority" domain's shared wire-shape data classes and
// decision state machine.
//
// Mirrors, field-for-field, the JSON Schema documents under schemas/authority/*.schema.json:
//   Principal               -> principal.schema.json
//   Grant                   -> grant.schema.json
//   AuthorityDecisionRecord -> decision.schema.json (as the `AuthorityDecision` sealed interface
//                              below — five variants, one per schemas/authority/decision.schema.json
//                              `outcome` enum value)
// capabilityIds on Grant are drawn from schemas/loops/registries/capability-ids.v1.json's
// reverse-DNS fb.* namespace — that registry is NOT redefined here, per the WP-1 task brief's
// "cross-reference, don't redefine" instruction. If a field appears in one place, it MUST
// appear in the other, or the two have drifted and one of them is wrong. See
// docs/ratified/CAPABILITY_AUTHORITY_MODEL.md for the citations (FB-RAT-AUTH-001..007) each
// field operationalizes.
//
// Toolchain constraint (binding): kotlinc-compilable with NO third-party dependencies —
// stdlib + java.time.Instant only. No kotlinx-serialization, no kotlinx-datetime, no Android
// imports, no kotlinx-coroutines Flow (unlike the execution/workspace domains' provider
// interfaces, this file defines no streaming provider contract — an authority check is a
// single request/response, not an observed stream; the WP-1 task brief for this domain asks
// only for the sealed interface + Principal/Grant/ResourceScope data classes, not a provider
// interface, so none is invented here).
//
// COMPILATION STATUS: UNVERIFIED. kotlinc/Gradle are not available in this build
// environment — this file has been written carefully (balanced braces, matched types, no
// typos attempted) but has NOT been compiled. Do not report it as compiling; that is for
// the next session with Gradle available to confirm. This file also depends on
// contracts/kotlin/CommonContracts.kt (package dev.aarso.contracts.common) being compiled
// in the same module/source set for the shared envelope this domain's types are typically
// carried inside, though no type from that file is imported directly below (this domain's
// three shapes are self-contained).
//
// Why a sealed interface DOES appear in this file (matching the encoding style
// contracts/kotlin/ExecutionContracts.kt already established for domains with real decision/
// state machines, and exactly as the WP-1 task brief specified for this domain by name):
// `AuthorityDecision` has five mutually-exclusive variants (GRANT MODEL: "Decisions: allow /
// deny / require confirmation / require stronger authority / allow with redaction or
// sandbox"), and each variant carries a DIFFERENT set of required fields (e.g. only
// `RequireStrongerAuthority` has a non-optional `requiredRung`; only `Allow`,
// `RequireConfirmation`, and `AllowWithRedactionOrSandbox` require a non-null
// `matchedGrantId`). Kotlin's sealed interface + per-variant data class encoding expresses
// those per-variant field requirements as compile-time-checked constructor shapes, which is
// strictly stronger than the wire schema's runtime if/then conditionals (decision.schema.json)
// — a caller cannot even CONSTRUCT a `RequireStrongerAuthority` without supplying
// `requiredRung`, whereas a JSON decoder only discovers a missing field at validation time. An
// exhaustive `when` over this sealed interface is a compile-time guarantee every variant has
// handling, mirroring `ExecutionLifecycleState`'s rationale in ExecutionContracts.kt.

package dev.aarso.contracts.authority

import java.time.Instant

// =========================================================================================
// Principal — schemas/authority/principal.schema.json
// =========================================================================================

/** FB-RAT-AUTH-001: closed vocabulary from the GRANT MODEL principal list. */
enum class PrincipalKind { USER, LOCAL_AGENT_PERSONA, LOOP_RUN, COUNCIL_MEMBER, STUDIO_WORKFLOW, IMPORTED_COMPANION_ARTIFACT }

/**
 * ACTIVE: may currently be matched by a Grant. REVOKED: a human explicitly ended this
 * principal's standing — every Grant it holds MUST be treated as unusable from that instant,
 * without needing to individually revoke each Grant. EXPIRED: the principal's own natural
 * lifetime ended (e.g. a completed LOOP_RUN) — same authority effect as REVOKED, distinct
 * only for display/audit.
 */
enum class PrincipalStatus { ACTIVE, REVOKED, EXPIRED }

/**
 * Something authority can be granted to (FB-RAT-AUTH-001 — capabilities are always granted TO
 * a specific principal, never to a broad ambient trust label). `parentPrincipalId` is the
 * structural spine FB-RAT-AUTH-004 (no implicit privilege expansion) and FB-RAT-AUTH-007
 * (REJECTED — no automatic/transitive authority inheritance) are built on: every non-USER
 * principal MUST declare who spawned it, enforced below by the same rule
 * schemas/authority/principal.schema.json encodes structurally via if/then.
 *
 * Typically carried as the `payload` of a `dev.aarso.contracts.common.ContractEnvelope<Principal>`.
 *
 * @param principalId Globally unique stable ID (FB-RAT-COM-002), independent of displayName.
 * @param parentPrincipalId The principal that spawned/configured this one. MUST be null for
 *   `kind == USER` (the sole root of authority in this model) and MUST be non-blank for every
 *   other kind.
 * @param rootUserPrincipalId Optional denormalized shortcut to the USER at the root of the
 *   parentPrincipalId chain. A cache, not a source of truth — callers performing a
 *   safety-relevant check MUST walk the real chain, never trust this field alone (see the
 *   schema's own description for why: this is exactly what FB-RAT-AUTH-007 guards against).
 */
data class Principal(
    val principalId: String,
    val kind: PrincipalKind,
    val displayName: String,
    val parentPrincipalId: String?,
    val status: PrincipalStatus,
    val createdAtUtc: Instant,
    val rootUserPrincipalId: String? = null,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(principalId.isNotBlank()) { "Principal.principalId must be non-blank (FB-RAT-COM-002)." }
        require(displayName.isNotBlank()) { "Principal.displayName must be non-blank." }
        if (kind == PrincipalKind.USER) {
            require(parentPrincipalId == null) {
                "Principal.parentPrincipalId must be null for kind=USER (the sole root of authority) — got '$parentPrincipalId'."
            }
        } else {
            require(!parentPrincipalId.isNullOrBlank()) {
                "Principal.parentPrincipalId must be non-blank for kind=$kind — FB-RAT-AUTH-004 requires every non-USER principal to declare its parent."
            }
        }
    }
}

// =========================================================================================
// Shared sub-shapes — used by both Grant and AuthorityDecision
// =========================================================================================

/**
 * FB-RAT-AUTH-002's eight-rung authority ladder, in ascending order. Identical to
 * capability-ids.v1.json's top-level `authorityLadder` array. Declared in enum-declaration
 * order specifically so `.ordinal` comparisons below (`atOrBelow`) encode the ladder's actual
 * ordering — do not reorder these entries without re-checking every ordinal comparison in this
 * file.
 */
enum class AuthorityRung {
    OBSERVE, READ, PROPOSE, MODIFY_DRAFT, EXECUTE_REVERSIBLE, EXECUTE_EXTERNAL, EXECUTE_DESTRUCTIVE, PUBLISH_OR_RELEASE;

    /** True when this rung is at or below (never above) [ceiling] on the ladder. */
    fun atOrBelow(ceiling: AuthorityRung): Boolean = this.ordinal <= ceiling.ordinal
}

/** FB-RAT-AUTH-005 GRANT MODEL resource list — closed vocabulary. */
enum class ResourceKind { WORKSPACE_ROOT, FILE_GLOB, REPOSITORY, EXECUTION_TARGET, NETWORK_DOMAIN, SECRET, DEVICE, RELEASE, STORE_CHANNEL }

/**
 * FB-RAT-AUTH-005 target-binding: exactly which resource instance a Grant applies to, or an
 * AuthorityDecision was evaluated against. `locator` is kind-specific — see
 * schemas/authority/grant.schema.json's $defs/ResourceScope for the full per-kind mapping.
 * For SECRET, `locator` is a handle id — NEVER a raw secret value (binding rule: API keys are
 * never logged; see the parallel obligation on ErrorEnvelope.detail in
 * dev.aarso.contracts.common.ErrorEnvelope).
 */
data class ResourceScope(
    val kind: ResourceKind,
    val locator: String
) {
    init {
        require(locator.isNotBlank()) { "ResourceScope.locator must be non-blank." }
    }
}

// =========================================================================================
// Grant — schemas/authority/grant.schema.json
// =========================================================================================

/** FB-RAT-AUTH-005 purpose-binding + an open bag for domain-specific constraint dimensions. */
data class GrantConstraints(
    val purpose: String? = null,
    val additionalConstraints: Map<String, Any?> = emptyMap()
)

/** Whether/when exercising a Grant produces a `RequireConfirmation` decision outcome. */
enum class ConfirmationMode { NEVER_REQUIRED, ALWAYS_REQUIRED, REQUIRED_ABOVE_RUNG }

data class ConfirmationPolicy(
    val mode: ConfirmationMode,
    val aboveRung: AuthorityRung? = null
) {
    init {
        if (mode == ConfirmationMode.REQUIRED_ABOVE_RUNG) {
            require(aboveRung != null) { "ConfirmationPolicy.aboveRung must be non-null when mode == REQUIRED_ABOVE_RUNG." }
        }
    }
}

/**
 * FB-RAT-AUTH-004 (no implicit privilege expansion) + FB-RAT-AUTH-007 (REJECTED — automatic/
 * transitive delegation). `transitiveDelegationAllowed` and `requiresFreshUserApprovalForWidening`
 * are pinned constants (not merely defaults) in this contract version — there is no way to
 * construct a `DelegationRule` that violates either invariant, mirroring
 * grant.schema.json's `const` keywords on both fields.
 */
data class DelegationRule(
    val delegable: Boolean,
    val maxDelegatedRung: AuthorityRung?,
    val transitiveDelegationAllowed: Boolean = false,
    val requiresFreshUserApprovalForWidening: Boolean = true
) {
    init {
        require(!transitiveDelegationAllowed) {
            "DelegationRule.transitiveDelegationAllowed must be false (FB-RAT-AUTH-007 REJECTED — " +
                "automatic/transitive delegation is structurally disallowed in this contract version)."
        }
        require(requiresFreshUserApprovalForWidening) {
            "DelegationRule.requiresFreshUserApprovalForWidening must be true (FB-RAT-AUTH-004/007 — " +
                "widening a child's authority beyond this grant always requires an explicit, fresh user approval)."
        }
        if (delegable) {
            require(maxDelegatedRung != null) { "DelegationRule.maxDelegatedRung must be non-null when delegable == true." }
        } else {
            require(maxDelegatedRung == null) { "DelegationRule.maxDelegatedRung must be null when delegable == false." }
        }
    }
}

/**
 * A scoped authorization: WHO (principalId) may do WHAT (capabilityIds, all resolving to the
 * same authorityRung per capability-ids.v1.json) to WHICH resource (resourceScope), under WHAT
 * constraints, until WHEN (expiresAtUtc, FB-RAT-AUTH-005 — every grant is time-bound, there is
 * no non-expiring shape), with WHAT confirmation policy, and WHETHER a child principal may
 * inherit it (delegationRule, FB-RAT-AUTH-004/007).
 *
 * `grantId` is also the exact value schemas/execution/request.schema.json's
 * `authorityGrant.grantId` field references (FB-RAT-COM-012 "no contract bypass") — see
 * docs/ratified/CAPABILITY_AUTHORITY_MODEL.md §11 for that cross-domain correlation.
 *
 * Typically carried as the `payload` of a `dev.aarso.contracts.common.ContractEnvelope<Grant>`.
 */
data class Grant(
    val grantId: String,
    val principalId: String,
    val capabilityIds: List<String>,
    val authorityRung: AuthorityRung,
    val resourceScope: ResourceScope,
    val constraints: GrantConstraints,
    val expiresAtUtc: Instant,
    val confirmationPolicy: ConfirmationPolicy,
    val delegationRule: DelegationRule,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(grantId.isNotBlank()) { "Grant.grantId must be non-blank (FB-RAT-COM-002)." }
        require(principalId.isNotBlank()) { "Grant.principalId must be non-blank." }
        require(capabilityIds.isNotEmpty()) { "Grant.capabilityIds must be non-empty." }
        val capabilityIdPattern = Regex("^fb\\.[a-z0-9_]+(\\.[a-z0-9_]+)+$")
        require(capabilityIds.all { it.matches(capabilityIdPattern) }) {
            "Grant.capabilityIds must all be reverse-DNS fb.* IDs from capability-ids.v1.json — got $capabilityIds."
        }
        val maxDelegatedRung = delegationRule.maxDelegatedRung
        if (maxDelegatedRung != null) {
            require(maxDelegatedRung.atOrBelow(authorityRung)) {
                "Grant.delegationRule.maxDelegatedRung ($maxDelegatedRung) must not exceed this Grant's own " +
                    "authorityRung ($authorityRung) — FB-RAT-AUTH-004: delegation may only narrow, never widen."
            }
        }
    }
}

// =========================================================================================
// AuthorityDecision — schemas/authority/decision.schema.json
// =========================================================================================

/** Which mitigation `AllowWithRedactionOrSandbox` applied. */
enum class RedactionOrSandboxMode { REDACTION, SANDBOX }

data class RedactionOrSandboxDetail(
    val mode: RedactionOrSandboxMode,
    val detail: String
) {
    init {
        require(detail.isNotBlank()) { "RedactionOrSandboxDetail.detail must be non-blank." }
    }
}

/**
 * The evaluated outcome of one authority check (GRANT MODEL "Decisions:" list, five variants).
 * Every variant carries the same common fields (decisionId, requestObjectId,
 * requestingPrincipalId, requestedCapabilityId, requestedResourceScope, reasonCode,
 * policyVersion, decidedAtUtc, evidenceLinks) plus whatever extra field(s) that specific
 * outcome requires — see each variant's own doc comment. Typically carried as the `payload` of
 * a `dev.aarso.contracts.common.ContractEnvelope<AuthorityDecision>`; decision records are
 * append-only (FB-RAT-COM-006) — a re-evaluation is always a NEW instance, never a mutation.
 */
sealed interface AuthorityDecision {
    val decisionId: String
    val requestObjectId: String
    val requestingPrincipalId: String
    val requestedCapabilityId: String
    val requestedResourceScope: ResourceScope
    val reasonCode: String
    val policyVersion: String
    val decidedAtUtc: Instant
    val evidenceLinks: List<String>

    /** Authority granted outright. `matchedGrantId` is mandatory — no ALLOW without a citable Grant (FB-RAT-COM-012). */
    data class Allow(
        override val decisionId: String,
        override val requestObjectId: String,
        override val requestingPrincipalId: String,
        override val requestedCapabilityId: String,
        override val requestedResourceScope: ResourceScope,
        override val reasonCode: String,
        override val policyVersion: String,
        override val decidedAtUtc: Instant,
        val matchedGrantId: String,
        override val evidenceLinks: List<String> = emptyList()
    ) : AuthorityDecision {
        init {
            require(matchedGrantId.isNotBlank()) { "Allow.matchedGrantId must be non-blank — FB-RAT-COM-012, no contract bypass." }
        }
    }

    /**
     * Authority refused. `matchedGrantId` is null exactly when `reasonCode == "AUTHORITY_DEFAULT_DENY"`
     * (FB-RAT-AUTH-003 — no grant matched at all); a more specific reasonCode (e.g.
     * AUTHORITY_GRANT_EXPIRED, AUTHORITY_PURPOSE_MISMATCH) pairs with a non-null matchedGrantId
     * naming the grant that existed but failed a check. AUTHORITY_CHILD_EXCEEDS_PARENT and
     * AUTHORITY_TRANSITIVE_DELEGATION_REJECTED (FB-RAT-AUTH-004/007) typically carry a null
     * matchedGrantId too, since by definition no grant of the requester's own covers the attempt.
     */
    data class Deny(
        override val decisionId: String,
        override val requestObjectId: String,
        override val requestingPrincipalId: String,
        override val requestedCapabilityId: String,
        override val requestedResourceScope: ResourceScope,
        override val reasonCode: String,
        override val policyVersion: String,
        override val decidedAtUtc: Instant,
        val matchedGrantId: String? = null,
        override val evidenceLinks: List<String> = emptyList()
    ) : AuthorityDecision {
        init {
            if (reasonCode == "AUTHORITY_DEFAULT_DENY") {
                require(matchedGrantId == null) {
                    "Deny.matchedGrantId must be null when reasonCode == AUTHORITY_DEFAULT_DENY (FB-RAT-AUTH-003 — " +
                        "that code specifically means no grant matched at all; use a more specific reasonCode when one did but failed a check)."
                }
            }
        }
    }

    /**
     * Authority conditionally available pending a fresh user confirmation. Both `matchedGrantId`
     * and `confirmationPromptRef` are mandatory — this outcome always derives from a real Grant,
     * and always names what MUST be shown to the user (FB-RAT-COM-009 accessibility: the string
     * this resolves to at render time MUST carry text semantics, not color/icon/haptic/position alone).
     */
    data class RequireConfirmation(
        override val decisionId: String,
        override val requestObjectId: String,
        override val requestingPrincipalId: String,
        override val requestedCapabilityId: String,
        override val requestedResourceScope: ResourceScope,
        override val reasonCode: String,
        override val policyVersion: String,
        override val decidedAtUtc: Instant,
        val matchedGrantId: String,
        val confirmationPromptRef: String,
        override val evidenceLinks: List<String> = emptyList()
    ) : AuthorityDecision {
        init {
            require(matchedGrantId.isNotBlank()) { "RequireConfirmation.matchedGrantId must be non-blank." }
            require(confirmationPromptRef.isNotBlank()) { "RequireConfirmation.confirmationPromptRef must be non-blank." }
        }
    }

    /**
     * The request needs a higher authorityRung than what is currently available. `matchedGrantId`
     * MAY be null (no grant at all reaches the needed rung) or non-null (a grant exists but sits
     * below `requiredRung`, e.g. because this specific operation's authority needs widened mid-run).
     */
    data class RequireStrongerAuthority(
        override val decisionId: String,
        override val requestObjectId: String,
        override val requestingPrincipalId: String,
        override val requestedCapabilityId: String,
        override val requestedResourceScope: ResourceScope,
        override val reasonCode: String,
        override val policyVersion: String,
        override val decidedAtUtc: Instant,
        val requiredRung: AuthorityRung,
        val matchedGrantId: String? = null,
        override val evidenceLinks: List<String> = emptyList()
    ) : AuthorityDecision

    /** Authority granted, but mitigated: content is redacted before use, or the action runs sandboxed. `matchedGrantId` is mandatory, same rationale as [Allow]. */
    data class AllowWithRedactionOrSandbox(
        override val decisionId: String,
        override val requestObjectId: String,
        override val requestingPrincipalId: String,
        override val requestedCapabilityId: String,
        override val requestedResourceScope: ResourceScope,
        override val reasonCode: String,
        override val policyVersion: String,
        override val decidedAtUtc: Instant,
        val matchedGrantId: String,
        val redactionOrSandbox: RedactionOrSandboxDetail,
        override val evidenceLinks: List<String> = emptyList()
    ) : AuthorityDecision {
        init {
            require(matchedGrantId.isNotBlank()) { "AllowWithRedactionOrSandbox.matchedGrantId must be non-blank — FB-RAT-COM-012, no contract bypass." }
        }
    }
}
