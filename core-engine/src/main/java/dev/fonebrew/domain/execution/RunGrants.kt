package dev.fonebrew.domain.execution

import dev.fonebrew.contracts.authority.ConfirmationMode
import dev.fonebrew.contracts.authority.ConfirmationPolicy
import dev.fonebrew.contracts.authority.DelegationRule
import dev.fonebrew.contracts.authority.Grant
import dev.fonebrew.contracts.authority.GrantConstraints
import dev.fonebrew.contracts.authority.ResourceKind
import dev.fonebrew.contracts.authority.ResourceScope
import dev.fonebrew.domain.authority.CapabilityRegistry
import dev.fonebrew.domain.contracts.IdGenerator
import dev.fonebrew.domain.provenance.ProvenanceState
import java.time.Duration
import java.time.Instant

/**
 * The Run panel's own, narrow [Grant] issuance -- the explicit, target-scoped consent behind
 * every "Allow this target to run commands" tap (never a blanket grant, never silent). Pure:
 * building a [Grant] is a plain data-construction step; only adding it to the store is I/O,
 * which stays the caller's job (`InMemoryGrantStore.add`).
 *
 * Two policy choices this makes on the user's behalf, both defensible defaults rather than
 * hidden magic:
 *  - **Scope**: exactly the one [dev.fonebrew.domain.execution.RunTarget] tapped (`resourceScope`
 *    locks to `target.targetId`) and exactly its own capability -- never "any execution target".
 *  - **Confirmation**: [RunTarget.provenance] `LOCAL` (this phone, never left the device) gets
 *    `NEVER_REQUIRED` once granted; everything watched (SSH, CI -- binding rule 2) gets
 *    `ALWAYS_REQUIRED`, so a remote/CI run always needs a fresh tap even after the target itself
 *    is trusted. The grant lasts [DEFAULT_TTL] -- long enough not to re-prompt every session,
 *    short enough that a stale grant does not outlive the phone's own trust window silently.
 */
object RunGrants {
    /** [dev.fonebrew.contracts.authority.GrantConstraints.purpose] every Run-issued grant and
     *  every Run authority check both use -- CAPABILITY_AUTHORITY_MODEL.md §7 purpose binding
     *  only matches when the check's own `purpose` argument equals the grant's. */
    const val PURPOSE: String = "develop.run"

    val DEFAULT_TTL: Duration = Duration.ofHours(24)

    fun defaultGrantFor(
        target: RunTarget,
        principalId: String,
        now: Instant,
        ttl: Duration = DEFAULT_TTL,
        idGenerator: () -> String = { "grant_run_" + IdGenerator.generate() },
    ): Grant {
        val entry = CapabilityRegistry.entryFor(target.capabilityId)
            ?: error("RunGrants: '${target.capabilityId}' is not in CapabilityRegistry -- RunTarget declared a capability WP-4 does not know.")
        return Grant(
            grantId = idGenerator(),
            principalId = principalId,
            capabilityIds = listOf(target.capabilityId),
            authorityRung = entry.authorityRung,
            resourceScope = ResourceScope(ResourceKind.EXECUTION_TARGET, target.targetId),
            constraints = GrantConstraints(purpose = if (entry.requiresPurposeBinding) PURPOSE else null),
            expiresAtUtc = now.plus(ttl),
            confirmationPolicy = if (target.provenance == ProvenanceState.LOCAL) {
                ConfirmationPolicy(ConfirmationMode.NEVER_REQUIRED)
            } else {
                ConfirmationPolicy(ConfirmationMode.ALWAYS_REQUIRED)
            },
            delegationRule = DelegationRule(delegable = false, maxDelegatedRung = null),
        )
    }
}
