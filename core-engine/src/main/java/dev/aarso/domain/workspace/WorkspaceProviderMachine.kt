package dev.aarso.domain.workspace

import dev.aarso.contracts.workspace.WorkspaceProviderState

/**
 * The WorkspaceProviderState transition table (WORKSPACE_KERNEL_SPEC.md §3.2), the same
 * fail-closed shape as [DocumentBufferMachine] for the other named state machine in this domain.
 */
object WorkspaceProviderMachine {

    sealed interface Event {
        object Configure : Event
        object ConnectSucceeded : Event
        data class AuthRequiredDetected(val detail: String? = null) : Event
        data class ConnectFailed(val detail: String? = null) : Event
        data class HealthCheckDegraded(val detail: String? = null) : Event
        object HealthCheckRecovered : Event
        data class AuthExpired(val detail: String? = null) : Event
        object DisconnectRequested : Event
        data class ConnectionLost(val detail: String? = null) : Event
        object Reauthenticated : Event
        object ReconnectRequested : Event
        object Retry : Event
    }

    sealed interface Result {
        data class Allowed(val next: WorkspaceProviderState) : Result
        data class Rejected(val reason: String) : Result
    }

    fun transition(current: WorkspaceProviderState, event: Event): Result = when (current) {
        is WorkspaceProviderState.Unconfigured -> when (event) {
            is Event.Configure -> Result.Allowed(WorkspaceProviderState.Connecting)
            else -> reject(current, event)
        }
        is WorkspaceProviderState.Connecting -> when (event) {
            is Event.ConnectSucceeded -> Result.Allowed(WorkspaceProviderState.Ready)
            is Event.AuthRequiredDetected -> Result.Allowed(WorkspaceProviderState.AuthRequired(event.detail))
            is Event.ConnectFailed -> Result.Allowed(WorkspaceProviderState.Failed(event.detail))
            else -> reject(current, event)
        }
        is WorkspaceProviderState.Ready -> when (event) {
            is Event.HealthCheckDegraded -> Result.Allowed(WorkspaceProviderState.Degraded(event.detail))
            is Event.AuthExpired -> Result.Allowed(WorkspaceProviderState.AuthRequired(event.detail))
            is Event.DisconnectRequested -> Result.Allowed(WorkspaceProviderState.Disconnected())
            is Event.ConnectionLost -> Result.Allowed(WorkspaceProviderState.Disconnected(event.detail))
            else -> reject(current, event)
        }
        is WorkspaceProviderState.Degraded -> when (event) {
            is Event.HealthCheckRecovered -> Result.Allowed(WorkspaceProviderState.Ready)
            is Event.AuthExpired -> Result.Allowed(WorkspaceProviderState.AuthRequired(event.detail))
            is Event.ConnectionLost -> Result.Allowed(WorkspaceProviderState.Disconnected(event.detail))
            else -> reject(current, event)
        }
        is WorkspaceProviderState.AuthRequired -> when (event) {
            is Event.Reauthenticated -> Result.Allowed(WorkspaceProviderState.Connecting)
            else -> reject(current, event)
        }
        is WorkspaceProviderState.Disconnected -> when (event) {
            is Event.ReconnectRequested -> Result.Allowed(WorkspaceProviderState.Connecting)
            else -> reject(current, event)
        }
        is WorkspaceProviderState.Failed -> when (event) {
            is Event.Retry -> Result.Allowed(WorkspaceProviderState.Connecting)
            else -> reject(current, event)
        }
    }

    private fun reject(current: WorkspaceProviderState, event: Event): Result.Rejected =
        Result.Rejected(
            "WorkspaceProviderMachine: event ${event::class.simpleName} is not legal from state " +
                "${current::class.simpleName} (WORKSPACE_KERNEL_SPEC.md §3.2)."
        )
}
