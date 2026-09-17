package dev.fonebrew.domain.workspace

import dev.fonebrew.contracts.workspace.WorkspaceProviderState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exhaustive coverage of WORKSPACE_KERNEL_SPEC.md §3.2's from-state/event/to-state table. */
class WorkspaceProviderMachineTest {

    private fun allowed(current: WorkspaceProviderState, event: WorkspaceProviderMachine.Event): WorkspaceProviderState {
        val result = WorkspaceProviderMachine.transition(current, event)
        assertTrue("expected Allowed for $event from $current, got $result", result is WorkspaceProviderMachine.Result.Allowed)
        return (result as WorkspaceProviderMachine.Result.Allowed).next
    }

    @Test
    fun `Unconfigured to Connecting via Configure`() {
        assertEquals(WorkspaceProviderState.Connecting, allowed(WorkspaceProviderState.Unconfigured, WorkspaceProviderMachine.Event.Configure))
    }

    @Test
    fun `Connecting to Ready, AuthRequired, or Failed`() {
        assertEquals(WorkspaceProviderState.Ready, allowed(WorkspaceProviderState.Connecting, WorkspaceProviderMachine.Event.ConnectSucceeded))
        assertEquals(WorkspaceProviderState.AuthRequired("need login"), allowed(WorkspaceProviderState.Connecting, WorkspaceProviderMachine.Event.AuthRequiredDetected("need login")))
        assertEquals(WorkspaceProviderState.Failed("timeout"), allowed(WorkspaceProviderState.Connecting, WorkspaceProviderMachine.Event.ConnectFailed("timeout")))
    }

    @Test
    fun `Ready to Degraded, AuthRequired, or Disconnected`() {
        assertEquals(WorkspaceProviderState.Degraded("slow"), allowed(WorkspaceProviderState.Ready, WorkspaceProviderMachine.Event.HealthCheckDegraded("slow")))
        assertEquals(WorkspaceProviderState.AuthRequired("expired"), allowed(WorkspaceProviderState.Ready, WorkspaceProviderMachine.Event.AuthExpired("expired")))
        assertEquals(WorkspaceProviderState.Disconnected(), allowed(WorkspaceProviderState.Ready, WorkspaceProviderMachine.Event.DisconnectRequested))
        assertEquals(WorkspaceProviderState.Disconnected("dropped"), allowed(WorkspaceProviderState.Ready, WorkspaceProviderMachine.Event.ConnectionLost("dropped")))
    }

    @Test
    fun `Degraded to Ready, AuthRequired, or Disconnected`() {
        assertEquals(WorkspaceProviderState.Ready, allowed(WorkspaceProviderState.Degraded(), WorkspaceProviderMachine.Event.HealthCheckRecovered))
        assertEquals(WorkspaceProviderState.AuthRequired(), allowed(WorkspaceProviderState.Degraded(), WorkspaceProviderMachine.Event.AuthExpired()))
        assertEquals(WorkspaceProviderState.Disconnected("dropped"), allowed(WorkspaceProviderState.Degraded(), WorkspaceProviderMachine.Event.ConnectionLost("dropped")))
    }

    @Test
    fun `AuthRequired to Connecting via Reauthenticated`() {
        assertEquals(WorkspaceProviderState.Connecting, allowed(WorkspaceProviderState.AuthRequired(), WorkspaceProviderMachine.Event.Reauthenticated))
    }

    @Test
    fun `Disconnected to Connecting via ReconnectRequested`() {
        assertEquals(WorkspaceProviderState.Connecting, allowed(WorkspaceProviderState.Disconnected(), WorkspaceProviderMachine.Event.ReconnectRequested))
    }

    @Test
    fun `Failed to Connecting via Retry`() {
        assertEquals(WorkspaceProviderState.Connecting, allowed(WorkspaceProviderState.Failed(), WorkspaceProviderMachine.Event.Retry))
    }

    @Test
    fun `illegal transitions are rejected`() {
        val illegal = listOf(
            WorkspaceProviderState.Unconfigured to WorkspaceProviderMachine.Event.ConnectSucceeded,
            WorkspaceProviderState.Connecting to WorkspaceProviderMachine.Event.HealthCheckDegraded(),
            WorkspaceProviderState.Ready to WorkspaceProviderMachine.Event.Configure,
            WorkspaceProviderState.Degraded() to WorkspaceProviderMachine.Event.DisconnectRequested,
            WorkspaceProviderState.AuthRequired() to WorkspaceProviderMachine.Event.ConnectSucceeded,
            WorkspaceProviderState.Disconnected() to WorkspaceProviderMachine.Event.Reauthenticated,
            WorkspaceProviderState.Failed() to WorkspaceProviderMachine.Event.ReconnectRequested,
        )
        for ((state, event) in illegal) {
            val result = WorkspaceProviderMachine.transition(state, event)
            assertTrue("expected Rejected for $event from $state, got $result", result is WorkspaceProviderMachine.Result.Rejected)
        }
    }
}
