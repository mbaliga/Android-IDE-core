package dev.fonebrew.domain.language

import dev.fonebrew.contracts.language.DapCapability

/**
 * WP-9: the DAP host contract's session lifecycle plus capability negotiation, made real. Same
 * derivation posture as [LspSessionMachine]'s header explains, adapted to DAP's own handshake
 * (`initialize` → capabilities → `launch`/`attach` → running/stopped-at-breakpoint → `terminate`),
 * fail-closed identically: anything not an explicit table row is [Result.Rejected].
 */
enum class DapSessionState { NOT_STARTED, STARTING, NEGOTIATING, LAUNCHING, RUNNING, STOPPED_AT_BREAKPOINT, TERMINATING, TERMINATED, CRASHED }

object DapSessionMachine {

    sealed interface Event {
        object Start : Event
        data class AdapterRespondedToInitialize(val adapterCapabilities: Set<DapCapability>) : Event
        object LaunchOrAttachRequested : Event
        object LaunchSucceeded : Event
        object BreakpointHit : Event
        object ContinueRequested : Event
        object TerminateRequested : Event
        object TerminateCompleted : Event
        object ProcessCrashed : Event
    }

    sealed interface Result {
        data class Advanced(val state: DapSessionState) : Result
        data class Rejected(val reason: String) : Result
    }

    fun apply(state: DapSessionState, event: Event): Result = when (state) {
        DapSessionState.NOT_STARTED -> when (event) {
            Event.Start -> Result.Advanced(DapSessionState.STARTING)
            else -> reject(state, event)
        }
        DapSessionState.STARTING -> when (event) {
            is Event.AdapterRespondedToInitialize -> Result.Advanced(DapSessionState.NEGOTIATING)
            Event.ProcessCrashed -> Result.Advanced(DapSessionState.CRASHED)
            else -> reject(state, event)
        }
        DapSessionState.NEGOTIATING -> when (event) {
            Event.LaunchOrAttachRequested -> Result.Advanced(DapSessionState.LAUNCHING)
            Event.ProcessCrashed -> Result.Advanced(DapSessionState.CRASHED)
            else -> reject(state, event)
        }
        DapSessionState.LAUNCHING -> when (event) {
            Event.LaunchSucceeded -> Result.Advanced(DapSessionState.RUNNING)
            Event.ProcessCrashed -> Result.Advanced(DapSessionState.CRASHED)
            else -> reject(state, event)
        }
        DapSessionState.RUNNING -> when (event) {
            Event.BreakpointHit -> Result.Advanced(DapSessionState.STOPPED_AT_BREAKPOINT)
            Event.TerminateRequested -> Result.Advanced(DapSessionState.TERMINATING)
            Event.ProcessCrashed -> Result.Advanced(DapSessionState.CRASHED)
            else -> reject(state, event)
        }
        DapSessionState.STOPPED_AT_BREAKPOINT -> when (event) {
            Event.ContinueRequested -> Result.Advanced(DapSessionState.RUNNING)
            Event.TerminateRequested -> Result.Advanced(DapSessionState.TERMINATING)
            Event.ProcessCrashed -> Result.Advanced(DapSessionState.CRASHED)
            else -> reject(state, event)
        }
        DapSessionState.TERMINATING -> when (event) {
            Event.TerminateCompleted -> Result.Advanced(DapSessionState.TERMINATED)
            Event.ProcessCrashed -> Result.Advanced(DapSessionState.CRASHED)
            else -> reject(state, event)
        }
        DapSessionState.TERMINATED, DapSessionState.CRASHED -> reject(state, event)
    }

    /** Same posture as [LspSessionMachine.negotiate]: the intersection, never either side's unilateral wish list. */
    fun negotiate(hostWants: Set<DapCapability>, adapterDeclares: Set<DapCapability>): Set<DapCapability> =
        hostWants intersect adapterDeclares

    private fun reject(state: DapSessionState, event: Event): Result.Rejected =
        Result.Rejected("DapSessionMachine: event ${event::class.simpleName} is not legal from state $state.")
}
