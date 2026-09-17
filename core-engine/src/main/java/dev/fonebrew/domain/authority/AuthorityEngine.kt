package dev.fonebrew.domain.authority

import dev.fonebrew.contracts.authority.AuthorityDecision
import dev.fonebrew.contracts.authority.Grant
import dev.fonebrew.contracts.authority.Principal
import dev.fonebrew.contracts.authority.PrincipalStatus
import dev.fonebrew.contracts.authority.ResourceScope
import dev.fonebrew.domain.contracts.IdGenerator
import java.time.Instant

/*
 * Package note, `domain.authority` (2026-09-15): mixed, not uniformly parked. No `ui/` file
 * imports this package directly, but [AuthorityEngine]/[AuditedAuthorityEngine]/
 * [CapabilityRegistry] ARE live today, reached indirectly via
 * [dev.fonebrew.data.execution.RunSessionDriver] (constructed from [GrantStore]/[PrincipalStore]
 * wired in [dev.fonebrew.di.AppContainer]), itself called from
 * [dev.fonebrew.ui.develop.RunFacet] — the Develop -> Run authority gate is real, not parked.
 * [SecretHandleBroker]/[InMemorySecretHandleBroker] and [SecretRedactionScanner] remain genuinely
 * unconsumed: the former awaits a `security/KeystoreSecret.kt`-backed implementation (see the
 * `secretHandleBroker` PARKED note in [dev.fonebrew.di.AppContainer]); the latter awaits whichever
 * caller first needs to prove a log/receipt is leak-free. See docs/STATE.md's "Parked substrate"
 * section.
 */

/** Read seam over wherever [Grant]s are actually stored -- this domain does not prescribe Room vs. in-memory. */
fun interface GrantStore {
    fun grantsForPrincipal(principalId: String): List<Grant>
}

/** Read seam over wherever [Principal]s are actually stored. */
fun interface PrincipalStore {
    fun principal(principalId: String): Principal?
}

/**
 * Reference [GrantStore] over a plain in-memory, mutable list -- no Room-backed grant store
 * exists yet (this WP's brief asks for the engine itself; a durable, queryable grant table is a
 * reasonable follow-up for whichever later work package first needs grants to survive a restart,
 * matching how WP-2/WP-3 each left similar "no consumer wired in yet" notes for pieces the brief
 * named but didn't yet need persisted). Thread-safe via a synchronized copy-on-read.
 */
class InMemoryGrantStore : GrantStore {
    private val grants = java.util.concurrent.CopyOnWriteArrayList<Grant>()
    fun add(grant: Grant) { grants += grant }
    fun removeById(grantId: String) { grants.removeAll { it.grantId == grantId } }
    override fun grantsForPrincipal(principalId: String): List<Grant> = grants.filter { it.principalId == principalId }
}

/** Reference [PrincipalStore] over a plain in-memory, mutable map. Same rationale as [InMemoryGrantStore]. */
class InMemoryPrincipalStore : PrincipalStore {
    private val byId = java.util.concurrent.ConcurrentHashMap<String, Principal>()
    fun add(principal: Principal) { byId[principal.principalId] = principal }
    override fun principal(principalId: String): Principal? = byId[principalId]
}

/**
 * The real policy-engine implementation `docs/ratified/CAPABILITY_AUTHORITY_MODEL.md` names as
 * "not built by this WP-1 pass... a future implementation" (that document's own closing forward
 * pointer) -- WP-4 is that future implementation. Evaluates one capability request against the
 * grant store and returns exactly one of the five [AuthorityDecision] variants (§4 of that
 * document), never anything outside that vocabulary.
 *
 * The single invariant every method below is built to protect: **this engine only ever consults
 * [GrantStore.grantsForPrincipal] for the REQUESTING principal's own id** — it never walks
 * [PrincipalStore] to an ancestor's grants to satisfy a request. That omission (not a check that
 * could be forgotten) is what makes FB-RAT-AUTH-007 (no automatic/transitive authority
 * inheritance) true of this implementation, not just of the data shapes. [ancestorHoldsCoveringGrant]
 * exists ONLY to produce a more informative deny reason when a transitive-delegation attempt is
 * detected -- it is diagnostic, called after the real decision is already `Deny`, and its result
 * never turns a `Deny` into an `Allow`.
 */
class AuthorityEngine(
    private val grants: GrantStore,
    private val principals: PrincipalStore,
    private val policyVersion: String,
    private val now: () -> Instant = Instant::now,
    private val idGenerator: () -> String = { "dec_" + IdGenerator.generate() },
) {

    /**
     * Evaluates whether [principalId] may exercise [capabilityId] against [resourceScope],
     * optionally for a declared [purpose] (required to match a grant's own
     * `constraints.purpose` when the capability's registry entry says
     * `requiresPurposeBinding == true` -- CAPABILITY_AUTHORITY_MODEL.md §7).
     *
     * @param escalateToRung When non-null and above the capability's own registry rung, this
     *   specific invocation is evaluated as if it needed [escalateToRung] instead of the
     *   capability's nominal rung -- "this specific operation's authority needs widened mid-run"
     *   (CAPABILITY_AUTHORITY_MODEL.md §4's `RequireStrongerAuthority` row). No grant in this
     *   contract can itself claim a capability at a rung other than the registry's own, so an
     *   escalation request can never be satisfied by an existing grant -- it always resolves to
     *   `RequireStrongerAuthority`, surfacing the base grant (if any) as a courtesy, never as
     *   something that silently substitutes for the missing higher-rung authority.
     */
    fun evaluate(
        requestObjectId: String,
        principalId: String,
        capabilityId: String,
        resourceScope: ResourceScope,
        purpose: String? = null,
        escalateToRung: dev.fonebrew.contracts.authority.AuthorityRung? = null,
    ): AuthorityDecision {
        val decidedAt = now()
        val decisionId = idGenerator()

        fun deny(reasonCode: String, matchedGrantId: String? = null) = AuthorityDecision.Deny(
            decisionId = decisionId, requestObjectId = requestObjectId, requestingPrincipalId = principalId,
            requestedCapabilityId = capabilityId, requestedResourceScope = resourceScope, reasonCode = reasonCode,
            policyVersion = policyVersion, decidedAtUtc = decidedAt, matchedGrantId = matchedGrantId
        )

        val capabilityEntry = CapabilityRegistry.entryFor(capabilityId)
            ?: return deny("AUTHORITY_UNKNOWN_CAPABILITY")

        val principal = principals.principal(principalId)
            ?: return deny("AUTHORITY_PRINCIPAL_NOT_FOUND")
        if (principal.status != PrincipalStatus.ACTIVE) {
            return deny("AUTHORITY_PRINCIPAL_NOT_ACTIVE")
        }

        if (escalateToRung != null && escalateToRung.ordinal > capabilityEntry.authorityRung.ordinal) {
            val baseGrant = grants.grantsForPrincipal(principalId).firstOrNull {
                capabilityId in it.capabilityIds && it.authorityRung == capabilityEntry.authorityRung &&
                    it.resourceScope == resourceScope && it.expiresAtUtc.isAfter(decidedAt)
            }
            return AuthorityDecision.RequireStrongerAuthority(
                decisionId = decisionId, requestObjectId = requestObjectId, requestingPrincipalId = principalId,
                requestedCapabilityId = capabilityId, requestedResourceScope = resourceScope,
                reasonCode = "AUTHORITY_ESCALATION_REQUIRES_STRONGER_GRANT", policyVersion = policyVersion,
                decidedAtUtc = decidedAt, requiredRung = escalateToRung, matchedGrantId = baseGrant?.grantId
            )
        }

        val requiredRung = capabilityEntry.authorityRung
        val ownGrants = grants.grantsForPrincipal(principalId)

        // Grants naming this capability at all, split into rung-consistent (its declared
        // authorityRung agrees with the capability's OWN registry rung) and rung-mismatched
        // (malformed -- fixtures/authority/adversarial/grant-authorityrung-capability-mismatch).
        // A rung-mismatched grant MUST NOT be treated as covering anything, fail-safe rather than
        // fail-open, so it is excluded from every path below except the diagnostic reason choice.
        val sameCapabilityGrants = ownGrants.filter { capabilityId in it.capabilityIds }
        val rungConsistentGrants = sameCapabilityGrants.filter { it.authorityRung == requiredRung }
        val scopeMatched = rungConsistentGrants.filter { it.resourceScope == resourceScope }

        if (scopeMatched.isEmpty()) {
            if (rungConsistentGrants.isNotEmpty()) {
                // Right capability, right rung, wrong resourceScope -- an unambiguous scope
                // mismatch (CAPABILITY_AUTHORITY_MODEL.md §7's target-binding axis).
                return deny("AUTHORITY_RESOURCE_SCOPE_MISMATCH", matchedGrantId = null)
            }
            if (sameCapabilityGrants.isNotEmpty()) {
                // Every grant naming this capability disagrees with the registry's own rung for
                // it -- a malformed/mismatched grant, not usable authority (fail-safe, §2).
                return deny("AUTHORITY_GRANT_RUNG_CAPABILITY_MISMATCH")
            }
            // No grant of the requester's own reaches this capability at any rung/scope. Before
            // defaulting to plain AUTHORITY_DEFAULT_DENY, check whether this looks like a
            // transitive-delegation attempt (an ancestor DOES hold a covering grant) purely to
            // report a more specific, honest reason -- never to allow it.
            return if (ancestorHoldsCoveringGrant(principal, capabilityId, resourceScope, requiredRung)) {
                deny("AUTHORITY_TRANSITIVE_DELEGATION_REJECTED")
            } else {
                deny("AUTHORITY_DEFAULT_DENY")
            }
        }

        // Purpose binding (CAPABILITY_AUTHORITY_MODEL.md §7).
        val purposeOk: (Grant) -> Boolean = { g ->
            if (capabilityEntry.requiresPurposeBinding) {
                purpose != null && g.constraints.purpose == purpose
            } else true
        }
        val purposeMatched = scopeMatched.filter(purposeOk)
        if (purposeMatched.isEmpty()) {
            return deny("AUTHORITY_PURPOSE_MISMATCH", matchedGrantId = scopeMatched.first().grantId)
        }

        // Expiry (§7) -- required non-null on every Grant already, so this is a straight comparison.
        val unexpired = purposeMatched.filter { it.expiresAtUtc.isAfter(decidedAt) }
        if (unexpired.isEmpty()) {
            return deny("AUTHORITY_GRANT_EXPIRED", matchedGrantId = purposeMatched.first().grantId)
        }

        val grant = unexpired.first()

        // Sandbox/redaction escape hatch: a grant MAY declare a mitigation via its own open
        // additionalConstraints bag (GrantConstraints.additionalConstraints) rather than a bare
        // Allow -- e.g. {"mitigation": "SANDBOX", "mitigationDetail": "..."}. This keeps the
        // mechanism entirely inside this Grant's own already-open extension point rather than
        // inventing a new field on Grant itself.
        val mitigationMode = grant.constraints.additionalConstraints["mitigation"] as? String
        if (mitigationMode == "REDACTION" || mitigationMode == "SANDBOX") {
            val detail = grant.constraints.additionalConstraints["mitigationDetail"] as? String
                ?: "Mitigation applied per grant ${grant.grantId}."
            return AuthorityDecision.AllowWithRedactionOrSandbox(
                decisionId = decisionId, requestObjectId = requestObjectId, requestingPrincipalId = principalId,
                requestedCapabilityId = capabilityId, requestedResourceScope = resourceScope,
                reasonCode = "AUTHORITY_ALLOWED_WITH_MITIGATION", policyVersion = policyVersion, decidedAtUtc = decidedAt,
                matchedGrantId = grant.grantId,
                redactionOrSandbox = dev.fonebrew.contracts.authority.RedactionOrSandboxDetail(
                    mode = dev.fonebrew.contracts.authority.RedactionOrSandboxMode.valueOf(mitigationMode), detail = detail
                )
            )
        }

        // Confirmation policy (FB-RAT-AUTH-006, §8).
        val needsConfirmation = when (grant.confirmationPolicy.mode) {
            dev.fonebrew.contracts.authority.ConfirmationMode.NEVER_REQUIRED -> false
            dev.fonebrew.contracts.authority.ConfirmationMode.ALWAYS_REQUIRED -> true
            dev.fonebrew.contracts.authority.ConfirmationMode.REQUIRED_ABOVE_RUNG ->
                requiredRung.ordinal >= (grant.confirmationPolicy.aboveRung?.ordinal ?: Int.MAX_VALUE)
        }
        if (needsConfirmation) {
            return AuthorityDecision.RequireConfirmation(
                decisionId = decisionId, requestObjectId = requestObjectId, requestingPrincipalId = principalId,
                requestedCapabilityId = capabilityId, requestedResourceScope = resourceScope,
                reasonCode = "AUTHORITY_CONFIRMATION_REQUIRED", policyVersion = policyVersion, decidedAtUtc = decidedAt,
                matchedGrantId = grant.grantId,
                confirmationPromptRef = "confirm.$capabilityId.${resourceScope.kind.name.lowercase()}"
            )
        }

        return AuthorityDecision.Allow(
            decisionId = decisionId, requestObjectId = requestObjectId, requestingPrincipalId = principalId,
            requestedCapabilityId = capabilityId, requestedResourceScope = resourceScope,
            reasonCode = "AUTHORITY_GRANT_MATCHED", policyVersion = policyVersion, decidedAtUtc = decidedAt,
            matchedGrantId = grant.grantId
        )
    }

    /**
     * Diagnostic-only lineage walk: true if some ancestor of [principal] (its parent, grandparent,
     * ...) holds a grant that would have covered this request. Called ONLY after [evaluate] has
     * already decided to deny for the requesting principal's own lack of coverage -- this
     * function's result can only make a deny more specifically labeled, never turn it into an
     * allow. Cycle-safe (bounded by a visited-set) against a malformed lineage.
     */
    private fun ancestorHoldsCoveringGrant(
        principal: Principal, capabilityId: String, resourceScope: ResourceScope, requiredRung: dev.fonebrew.contracts.authority.AuthorityRung,
    ): Boolean {
        val visited = mutableSetOf(principal.principalId)
        var currentParentId = principal.parentPrincipalId
        while (currentParentId != null && currentParentId !in visited) {
            visited += currentParentId
            val ancestorGrants = grants.grantsForPrincipal(currentParentId)
            if (ancestorGrants.any { g ->
                    capabilityId in g.capabilityIds && g.authorityRung == requiredRung && g.resourceScope == resourceScope
                }) {
                return true
            }
            currentParentId = principals.principal(currentParentId)?.parentPrincipalId
        }
        return false
    }
}
