package dev.fonebrew.domain.execution

import dev.fonebrew.contracts.authority.AuthorityDecision

/**
 * The Run panel's own coarse read of a raw [AuthorityDecision] -- "what should the panel show
 * next", not a re-implementation of [dev.fonebrew.domain.authority.AuthorityEngine]'s policy.
 * Pure and total over the five decision variants (matching [ExecutionLifecycleState]'s own
 * exhaustive-`when` rationale in the execution contracts file), so a run can never fall through
 * to executing without a classified decision behind it.
 */
sealed interface RunAuthorityUiState {
    /** A citable grant already covers this -- proceed straight to execute() with [grantId]. */
    data class Allowed(val grantId: String) : RunAuthorityUiState

    /** A grant covers this but its own policy demands a fresh tap first (FB-RAT-AUTH-006) --
     *  the confirm itself is the affirmative action; no re-evaluation is needed after it. */
    data class NeedsConfirmation(val grantId: String, val promptRef: String) : RunAuthorityUiState

    /** No grant reaches this yet (first run against this target, or a prior grant expired) --
     *  offer the target-scoped consent affordance ([RunGrants.defaultGrantFor]), never a bypass. */
    data class NeedsGrant(val reasonCode: String) : RunAuthorityUiState
}

object RunAuthorityGate {
    fun classify(decision: AuthorityDecision): RunAuthorityUiState = when (decision) {
        is AuthorityDecision.Allow -> RunAuthorityUiState.Allowed(decision.matchedGrantId)
        is AuthorityDecision.AllowWithRedactionOrSandbox -> RunAuthorityUiState.Allowed(decision.matchedGrantId)
        is AuthorityDecision.RequireConfirmation ->
            RunAuthorityUiState.NeedsConfirmation(decision.matchedGrantId, decision.confirmationPromptRef)
        is AuthorityDecision.Deny -> RunAuthorityUiState.NeedsGrant(decision.reasonCode)
        // Never reached in practice -- the Run panel never requests an escalated rung -- but a
        // stronger-authority outcome is still "no grant reaches this", so it takes the same path.
        is AuthorityDecision.RequireStrongerAuthority -> RunAuthorityUiState.NeedsGrant(decision.reasonCode)
    }
}
