package dev.fonebrew.domain.loop.authoring

/**
 * `FB-RAT-PHN-008` (§9, `LOOP_PHONE_AUTHORING_SPEC.md`) -- the phone Run View's action-legality
 * table, made real. This is explicitly **not** the canonical loop run-state machine (that is
 * [dev.fonebrew.contracts.loops.RunState], the 17-state machine [dev.fonebrew.domain.loop.LoopRunDriver]
 * (WP-8) drives around `GraphRunner`) -- the spec's own words: "it is not the canonical loop
 * run-state machine, which is owned by the loop execution/run contract." [RunViewStateClass] is a
 * deliberately coarser set of *interaction* classes the Run View groups the real run states into,
 * for the purpose of one question only: which intervention actions are legal to show right now.
 *
 * The core PHN-008 guarantee -- "structural editing during a run is prohibited... never alter an
 * active run by any other path" -- is enforced by construction here: there is no action in
 * [RunViewAction] for arbitrary graph mutation, so every attempt at one is [Result.Rejected] by
 * the same fail-closed default every other branch below falls through to.
 */
sealed interface RunViewStateClass {
    object AwaitingAuthority : RunViewStateClass
    object AwaitingDecision : RunViewStateClass
    object Running : RunViewStateClass
    object Paused : RunViewStateClass

    /**
     * @param isFailure false for a plain cancellation or a clean authority denial with no failure involved.
     * @param failedNodeIsIdempotent only meaningful when [isFailure] -- gates [RunViewAction.RETRY_FAILED_NODE].
     * @param hasDeclaredRecoveryPath gates [RunViewAction.CHOOSE_RECOVERY_PATH] -- "only declared recovery
     *   paths are offered; the surface never improvises one" (§9). Can be true after a denial too,
     *   matching the spec table's "TERMINAL(recovery path or CANCELLED)" row for `DENY_AUTHORITY`.
     */
    data class Terminal(
        val isFailure: Boolean,
        val failedNodeIsIdempotent: Boolean = false,
        val hasDeclaredRecoveryPath: Boolean = false,
    ) : RunViewStateClass
}

enum class RunViewAction {
    APPROVE_AUTHORITY, DENY_AUTHORITY, PROVIDE_DECISION, INSPECT,
    REQUEST_PAUSE, RESUME, CANCEL, RETRY_FAILED_NODE, CHOOSE_RECOVERY_PATH,
}

object RunViewActionGuard {

    sealed interface Result {
        data class Advanced(val state: RunViewStateClass) : Result
        data class Rejected(val reason: String) : Result
    }

    /**
     * @param runtimeSupportsSafePause "Not every runtime/target supports safe suspension -- this
     *   is a conditional transition, not a guarantee" (§9); only consulted for [RunViewAction.REQUEST_PAUSE].
     * @param denialHasDeclaredRecoveryPath only consulted for [RunViewAction.DENY_AUTHORITY].
     */
    fun apply(
        state: RunViewStateClass,
        action: RunViewAction,
        runtimeSupportsSafePause: Boolean = true,
        denialHasDeclaredRecoveryPath: Boolean = false,
    ): Result = when (state) {
        RunViewStateClass.AwaitingAuthority -> when (action) {
            RunViewAction.APPROVE_AUTHORITY -> Result.Advanced(RunViewStateClass.Running)
            RunViewAction.DENY_AUTHORITY -> Result.Advanced(
                RunViewStateClass.Terminal(isFailure = false, hasDeclaredRecoveryPath = denialHasDeclaredRecoveryPath)
            )
            else -> reject(state, action)
        }
        RunViewStateClass.AwaitingDecision -> when (action) {
            RunViewAction.PROVIDE_DECISION -> Result.Advanced(RunViewStateClass.Running)
            else -> reject(state, action)
        }
        RunViewStateClass.Running -> when (action) {
            RunViewAction.INSPECT -> Result.Advanced(RunViewStateClass.Running)
            RunViewAction.REQUEST_PAUSE ->
                if (runtimeSupportsSafePause) Result.Advanced(RunViewStateClass.Paused)
                else Result.Rejected("RunViewActionGuard: this runtime/target does not support safe suspension (§9).")
            RunViewAction.CANCEL -> Result.Advanced(RunViewStateClass.Terminal(isFailure = false))
            else -> reject(state, action)
        }
        RunViewStateClass.Paused -> when (action) {
            RunViewAction.RESUME -> Result.Advanced(RunViewStateClass.Running)
            RunViewAction.CANCEL -> Result.Advanced(RunViewStateClass.Terminal(isFailure = false))
            else -> reject(state, action)
        }
        is RunViewStateClass.Terminal -> when (action) {
            RunViewAction.RETRY_FAILED_NODE ->
                if (state.isFailure && state.failedNodeIsIdempotent) Result.Advanced(RunViewStateClass.Running)
                else Result.Rejected("RunViewActionGuard: retry is only offered where the failed node is declared idempotent (§9).")
            RunViewAction.CHOOSE_RECOVERY_PATH ->
                if (state.hasDeclaredRecoveryPath) Result.Advanced(RunViewStateClass.Running)
                else Result.Rejected("RunViewActionGuard: only a declared recovery path may be chosen -- the surface never improvises one (§9).")
            else -> reject(state, action)
        }
    }

    /**
     * §9's one action that "reaches into editing": legal from ANY state, including terminal, and
     * "it never mutates the run it forked from." Modeled outside [apply] since it does not change
     * [RunViewStateClass] at all -- it starts a fresh [ProposalDraftState.BASE_REVISION] /
     * [ConnectionDraftState.IDLE]-shaped editing session elsewhere, a different machine entirely.
     */
    fun canForkFromReceipt(state: RunViewStateClass): Boolean = true

    private fun reject(state: RunViewStateClass, action: RunViewAction): Result.Rejected = Result.Rejected(
        "RunViewActionGuard: action $action is not legal from state $state (LOOP_PHONE_AUTHORING_SPEC.md §9) -- " +
            "structural editing during a run is prohibited (FB-RAT-PHN-008); only declared intervention actions are legal."
    )
}
