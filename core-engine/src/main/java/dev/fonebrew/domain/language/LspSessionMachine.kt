package dev.fonebrew.domain.language

import dev.fonebrew.contracts.language.LspCapability

/**
 * WP-9: an LSP client's session lifecycle plus capability negotiation, made real. No prior
 * ratified spec names this state table (see `LanguageLaneContracts.kt`'s header) — it is derived
 * directly from LSP's own lifecycle (`initialize` → capability exchange → `initialized` →
 * requests → `shutdown`/`exit`) plus this codebase's crash-recovery posture (`CrashRecovery`,
 * per `CLAUDE.md`), fail-closed in the same style as every other state machine this build-out has
 * written (`DocumentBufferMachine`, `TouchConnectionGrammar`, ...): anything not an explicit
 * table row is [Result.Rejected].
 */
enum class LspSessionState { NOT_STARTED, STARTING, NEGOTIATING, READY, DEGRADED, CRASHED, RESTARTING, STOPPED }

object LspSessionMachine {

    sealed interface Event {
        object Start : Event
        data class ServerRespondedToInitialize(val serverCapabilities: Set<LspCapability>) : Event
        object InitializedNotificationSent : Event
        /** A request timed out or returned a protocol error, but the process is still alive -- degrade, don't kill. */
        object RequestFailed : Event
        object Recovered : Event
        object ProcessCrashed : Event
        object RestartRequested : Event
        object RestartSucceeded : Event
        object Stop : Event
    }

    sealed interface Result {
        data class Advanced(val state: LspSessionState) : Result
        data class Rejected(val reason: String) : Result
    }

    fun apply(state: LspSessionState, event: Event): Result = when (state) {
        LspSessionState.NOT_STARTED -> when (event) {
            Event.Start -> Result.Advanced(LspSessionState.STARTING)
            else -> reject(state, event)
        }
        LspSessionState.STARTING -> when (event) {
            is Event.ServerRespondedToInitialize -> Result.Advanced(LspSessionState.NEGOTIATING)
            Event.ProcessCrashed -> Result.Advanced(LspSessionState.CRASHED)
            else -> reject(state, event)
        }
        LspSessionState.NEGOTIATING -> when (event) {
            Event.InitializedNotificationSent -> Result.Advanced(LspSessionState.READY)
            Event.ProcessCrashed -> Result.Advanced(LspSessionState.CRASHED)
            else -> reject(state, event)
        }
        LspSessionState.READY -> when (event) {
            Event.RequestFailed -> Result.Advanced(LspSessionState.DEGRADED)
            Event.ProcessCrashed -> Result.Advanced(LspSessionState.CRASHED)
            Event.Stop -> Result.Advanced(LspSessionState.STOPPED)
            else -> reject(state, event)
        }
        LspSessionState.DEGRADED -> when (event) {
            Event.Recovered -> Result.Advanced(LspSessionState.READY)
            Event.ProcessCrashed -> Result.Advanced(LspSessionState.CRASHED)
            Event.Stop -> Result.Advanced(LspSessionState.STOPPED)
            else -> reject(state, event)
        }
        LspSessionState.CRASHED -> when (event) {
            Event.RestartRequested -> Result.Advanced(LspSessionState.RESTARTING)
            Event.Stop -> Result.Advanced(LspSessionState.STOPPED)
            else -> reject(state, event)
        }
        LspSessionState.RESTARTING -> when (event) {
            Event.RestartSucceeded -> Result.Advanced(LspSessionState.STARTING)
            Event.ProcessCrashed -> Result.Advanced(LspSessionState.CRASHED)
            else -> reject(state, event)
        }
        LspSessionState.STOPPED -> reject(state, event)
    }

    /**
     * The negotiated capability set is the intersection of what the client is willing to use and
     * what the server actually declared during `initialize` -- neither side's unilateral wish
     * list. A capability absent from the negotiated set MUST NOT be invoked; the caller degrades
     * gracefully (e.g. no rename support offered in the UI) rather than sending a request the
     * server never claimed to handle.
     */
    fun negotiate(clientWants: Set<LspCapability>, serverDeclares: Set<LspCapability>): Set<LspCapability> =
        clientWants intersect serverDeclares

    private fun reject(state: LspSessionState, event: Event): Result.Rejected =
        Result.Rejected("LspSessionMachine: event ${event::class.simpleName} is not legal from state $state.")
}
