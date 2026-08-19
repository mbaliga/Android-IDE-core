package dev.fonebrew.domain.workspace

import dev.fonebrew.contracts.common.ErrorEnvelope
import dev.fonebrew.contracts.workspace.BufferConflict
import dev.fonebrew.contracts.workspace.DocumentBufferState

/**
 * The DocumentBufferState transition table (WORKSPACE_KERNEL_SPEC.md §3.1), made real: given a
 * current state and an event, either the legal next state or a rejection naming why. Pure
 * function, no I/O -- the actual open/save/discard side effects live in [LocalWorkspaceProvider]
 * and [dev.fonebrew.data.RoomWorkspaceJournal]; this is only the state-shape law both must obey.
 */
object DocumentBufferMachine {

    /** One legal or illegal request to move a [DocumentBufferState] forward. */
    sealed interface Event {
        object Open : Event
        object Edit : Event
        object Close : Event
        object SaveRequested : Event
        data class RemoteRevisionChanged(val conflict: BufferConflict) : Event
        object Discard : Event
        object SaveSucceeded : Event
        data class SaveFailed(val error: ErrorEnvelope) : Event
        object RetrySave : Event
        object BeginMerge : Event
        object MergeResolved : Event
    }

    sealed interface Result {
        data class Allowed(val next: DocumentBufferState) : Result
        data class Rejected(val reason: String) : Result
    }

    /**
     * Applies [event] to [current]. Every row of the WORKSPACE_KERNEL_SPEC.md §3.1 table is one
     * branch below; anything not in that table is [Result.Rejected] -- there is no default
     * fall-through that silently accepts an illegal transition.
     */
    fun transition(current: DocumentBufferState, event: Event): Result = when (current) {
        is DocumentBufferState.Closed -> when (event) {
            is Event.Open -> Result.Allowed(DocumentBufferState.OpenClean)
            else -> reject(current, event)
        }
        is DocumentBufferState.OpenClean -> when (event) {
            is Event.Edit -> Result.Allowed(DocumentBufferState.OpenDirty)
            is Event.Close -> Result.Allowed(DocumentBufferState.Closed)
            else -> reject(current, event)
        }
        is DocumentBufferState.OpenDirty -> when (event) {
            is Event.SaveRequested -> Result.Allowed(DocumentBufferState.Saving)
            is Event.RemoteRevisionChanged -> Result.Allowed(DocumentBufferState.Conflicted(event.conflict))
            is Event.Discard -> Result.Allowed(DocumentBufferState.Closed)
            else -> reject(current, event)
        }
        is DocumentBufferState.Saving -> when (event) {
            is Event.SaveSucceeded -> Result.Allowed(DocumentBufferState.OpenClean)
            is Event.SaveFailed -> Result.Allowed(DocumentBufferState.SaveFailed(event.error))
            else -> reject(current, event)
        }
        is DocumentBufferState.SaveFailed -> when (event) {
            is Event.RetrySave -> Result.Allowed(DocumentBufferState.Saving)
            is Event.Edit -> Result.Allowed(DocumentBufferState.OpenDirty)
            is Event.Discard -> Result.Allowed(DocumentBufferState.Closed)
            else -> reject(current, event)
        }
        is DocumentBufferState.Conflicted -> when (event) {
            is Event.BeginMerge -> Result.Allowed(DocumentBufferState.Merging(current.conflict))
            is Event.Discard -> Result.Allowed(DocumentBufferState.Closed)
            else -> reject(current, event)
        }
        is DocumentBufferState.Merging -> when (event) {
            is Event.MergeResolved -> Result.Allowed(DocumentBufferState.OpenDirty)
            else -> reject(current, event)
        }
    }

    private fun reject(current: DocumentBufferState, event: Event): Result.Rejected =
        Result.Rejected(
            "DocumentBufferMachine: event ${event::class.simpleName} is not legal from state " +
                "${current::class.simpleName} (WORKSPACE_KERNEL_SPEC.md §3.1)."
        )
}
