package dev.aarso.domain.loop.authoring

/**
 * `FB-RAT-PHN-004` (§7, `LOOP_PHONE_AUTHORING_SPEC.md`) -- the normative tap connection grammar,
 * made real. `FB-RAT-PHN-003` (same section) REJECTS drag-a-wire as the primary or only path to a
 * connection; this state table is that primary path. Every row of the spec's table is one branch
 * below; anything not in that table is [Result.Rejected] -- no default fall-through silently
 * accepts an illegal transition, the same fail-closed posture as [dev.aarso.domain.workspace.
 * DocumentBufferMachine]. No draft mutation occurs before [ConnectionDraftState.COMMITTED] -- the
 * spec's own "cancellation from any intermediate state is a pure discard" guarantee, which this
 * object enforces structurally rather than by convention: [Event.Commit] is the only event that
 * carries the accumulated draft into a new edge, and it is legal from exactly one state.
 */
enum class ConnectionDraftState {
    IDLE, SOURCE_SELECTED, AWAITING_DESTINATION, DESTINATION_CHOSEN, LABELED, CONDITIONED, PREVIEWING, COMMITTED
}

object TouchConnectionGrammar {

    sealed interface Event {
        data class SelectSource(val sourceNodeId: String) : Event
        object ConnectFromHere : Event
        data class ChooseDestination(val destinationNodeId: String, val isNewNode: Boolean = false) : Event
        data class ChooseLabel(val outputPort: String?, val label: String) : Event
        data class DefineCondition(val conditionExpression: String) : Event
        object RequestPreview : Event
        object Commit : Event
        object Cancel : Event
    }

    /** Accumulated connection-draft state -- everything gathered so far, never applied until [ConnectionDraftState.COMMITTED]. */
    data class ConnectionDraft(
        val state: ConnectionDraftState = ConnectionDraftState.IDLE,
        val sourceNodeId: String? = null,
        val destinationNodeId: String? = null,
        val isNewDestinationNode: Boolean = false,
        val outputPort: String? = null,
        val label: String? = null,
        val conditionExpression: String? = null,
        /** Set by the caller before [Event.ChooseLabel]: whether the gateway this connection extends requires a condition (§5). */
        val gatewayRequiresCondition: Boolean = false,
    )

    sealed interface Result {
        data class Advanced(val draft: ConnectionDraft) : Result
        data class Rejected(val reason: String) : Result
    }

    fun start(): ConnectionDraft = ConnectionDraft()

    fun apply(draft: ConnectionDraft, event: Event): Result {
        if (event is Event.Cancel) {
            return if (draft.state == ConnectionDraftState.IDLE) {
                reject(draft.state, event)
            } else {
                // "No draft mutation occurs before COMMITTED, so cancellation from any
                // intermediate state is a pure discard" -- COMMITTED itself is terminal, not
                // cancellable (there is nothing left to discard once the edge already exists).
                if (draft.state == ConnectionDraftState.COMMITTED) reject(draft.state, event)
                else Result.Advanced(ConnectionDraft())
            }
        }
        return when (draft.state) {
            ConnectionDraftState.IDLE -> when (event) {
                is Event.SelectSource -> Result.Advanced(
                    draft.copy(state = ConnectionDraftState.SOURCE_SELECTED, sourceNodeId = event.sourceNodeId)
                )
                else -> reject(draft.state, event)
            }
            ConnectionDraftState.SOURCE_SELECTED -> when (event) {
                Event.ConnectFromHere -> Result.Advanced(draft.copy(state = ConnectionDraftState.AWAITING_DESTINATION))
                else -> reject(draft.state, event)
            }
            ConnectionDraftState.AWAITING_DESTINATION -> when (event) {
                is Event.ChooseDestination -> Result.Advanced(
                    draft.copy(
                        state = ConnectionDraftState.DESTINATION_CHOSEN,
                        destinationNodeId = event.destinationNodeId,
                        isNewDestinationNode = event.isNewNode,
                    )
                )
                else -> reject(draft.state, event)
            }
            ConnectionDraftState.DESTINATION_CHOSEN -> when (event) {
                is Event.ChooseLabel -> Result.Advanced(
                    draft.copy(state = ConnectionDraftState.LABELED, outputPort = event.outputPort, label = event.label)
                )
                else -> reject(draft.state, event)
            }
            ConnectionDraftState.LABELED -> when (event) {
                is Event.DefineCondition ->
                    if (draft.gatewayRequiresCondition) {
                        Result.Advanced(draft.copy(state = ConnectionDraftState.CONDITIONED, conditionExpression = event.conditionExpression))
                    } else {
                        Result.Rejected("TouchConnectionGrammar: DefineCondition is only legal when gatewayRequiresCondition is true (§5).")
                    }
                Event.RequestPreview ->
                    // "Skipped (falls through directly to PREVIEWING) when no condition is required."
                    if (!draft.gatewayRequiresCondition) Result.Advanced(draft.copy(state = ConnectionDraftState.PREVIEWING))
                    else Result.Rejected("TouchConnectionGrammar: RequestPreview from LABELED requires a condition first when gatewayRequiresCondition is true (§5).")
                else -> reject(draft.state, event)
            }
            ConnectionDraftState.CONDITIONED -> when (event) {
                Event.RequestPreview -> Result.Advanced(draft.copy(state = ConnectionDraftState.PREVIEWING))
                else -> reject(draft.state, event)
            }
            ConnectionDraftState.PREVIEWING -> when (event) {
                Event.Commit -> Result.Advanced(draft.copy(state = ConnectionDraftState.COMMITTED))
                else -> reject(draft.state, event)
            }
            ConnectionDraftState.COMMITTED -> reject(draft.state, event)
        }
    }

    /**
     * Belt-and-suspenders check, same discipline as [dev.aarso.domain.loop.LoopInstallationDriver]'s
     * internal `reject()`: is `to` a legal direct successor of `from`, independent of any
     * particular [ConnectionDraft] payload? Used by tests to re-derive legality across a full
     * visited-state sequence, not just per-event.
     */
    fun isValidTransition(from: ConnectionDraftState, to: ConnectionDraftState): Boolean {
        if (to == ConnectionDraftState.IDLE && from != ConnectionDraftState.IDLE && from != ConnectionDraftState.COMMITTED) return true
        return when (from) {
            ConnectionDraftState.IDLE -> to == ConnectionDraftState.SOURCE_SELECTED
            ConnectionDraftState.SOURCE_SELECTED -> to == ConnectionDraftState.AWAITING_DESTINATION
            ConnectionDraftState.AWAITING_DESTINATION -> to == ConnectionDraftState.DESTINATION_CHOSEN
            ConnectionDraftState.DESTINATION_CHOSEN -> to == ConnectionDraftState.LABELED
            ConnectionDraftState.LABELED -> to == ConnectionDraftState.CONDITIONED || to == ConnectionDraftState.PREVIEWING
            ConnectionDraftState.CONDITIONED -> to == ConnectionDraftState.PREVIEWING
            ConnectionDraftState.PREVIEWING -> to == ConnectionDraftState.COMMITTED
            ConnectionDraftState.COMMITTED -> false
        }
    }

    private fun reject(state: ConnectionDraftState, event: Event): Result.Rejected =
        Result.Rejected(
            "TouchConnectionGrammar: event ${event::class.simpleName} is not legal from state $state (LOOP_PHONE_AUTHORING_SPEC.md §7)."
        )
}
