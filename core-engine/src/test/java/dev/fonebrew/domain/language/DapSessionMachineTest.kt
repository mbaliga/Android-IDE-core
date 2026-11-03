package dev.fonebrew.domain.language

import dev.fonebrew.contracts.language.BuiltInLanguagePacks
import dev.fonebrew.contracts.language.DapCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DapSessionMachineTest {

    private fun advanced(state: DapSessionState, event: DapSessionMachine.Event): DapSessionState {
        val result = DapSessionMachine.apply(state, event)
        assertTrue("expected Advanced for $event from $state, got $result", result is DapSessionMachine.Result.Advanced)
        return (result as DapSessionMachine.Result.Advanced).state
    }

    private fun rejected(state: DapSessionState, event: DapSessionMachine.Event) {
        assertTrue(DapSessionMachine.apply(state, event) is DapSessionMachine.Result.Rejected)
    }

    @Test
    fun `the full happy path visits every state from NOT_STARTED through a breakpoint hit to termination`() {
        var state = DapSessionState.NOT_STARTED
        state = advanced(state, DapSessionMachine.Event.Start)
        assertEquals(DapSessionState.STARTING, state)
        state = advanced(state, DapSessionMachine.Event.AdapterRespondedToInitialize(setOf(DapCapability.SET_BREAKPOINTS)))
        assertEquals(DapSessionState.NEGOTIATING, state)
        state = advanced(state, DapSessionMachine.Event.LaunchOrAttachRequested)
        assertEquals(DapSessionState.LAUNCHING, state)
        state = advanced(state, DapSessionMachine.Event.LaunchSucceeded)
        assertEquals(DapSessionState.RUNNING, state)
        state = advanced(state, DapSessionMachine.Event.BreakpointHit)
        assertEquals(DapSessionState.STOPPED_AT_BREAKPOINT, state)
        state = advanced(state, DapSessionMachine.Event.ContinueRequested)
        assertEquals(DapSessionState.RUNNING, state)
        state = advanced(state, DapSessionMachine.Event.TerminateRequested)
        assertEquals(DapSessionState.TERMINATING, state)
        state = advanced(state, DapSessionMachine.Event.TerminateCompleted)
        assertEquals(DapSessionState.TERMINATED, state)
    }

    @Test
    fun `a crash can occur from any pre-terminal state -- STARTING, NEGOTIATING, LAUNCHING, RUNNING, and STOPPED_AT_BREAKPOINT`() {
        assertEquals(DapSessionState.CRASHED, advanced(DapSessionState.STARTING, DapSessionMachine.Event.ProcessCrashed))
        assertEquals(DapSessionState.CRASHED, advanced(DapSessionState.NEGOTIATING, DapSessionMachine.Event.ProcessCrashed))
        assertEquals(DapSessionState.CRASHED, advanced(DapSessionState.LAUNCHING, DapSessionMachine.Event.ProcessCrashed))
        assertEquals(DapSessionState.CRASHED, advanced(DapSessionState.RUNNING, DapSessionMachine.Event.ProcessCrashed))
        assertEquals(DapSessionState.CRASHED, advanced(DapSessionState.STOPPED_AT_BREAKPOINT, DapSessionMachine.Event.ProcessCrashed))
    }

    @Test
    fun `TERMINATED and CRASHED are both terminal -- no further event is legal from either`() {
        rejected(DapSessionState.TERMINATED, DapSessionMachine.Event.Start)
        rejected(DapSessionState.CRASHED, DapSessionMachine.Event.Start)
        rejected(DapSessionState.CRASHED, DapSessionMachine.Event.LaunchOrAttachRequested)
    }

    @Test
    fun `terminate is reachable from RUNNING directly, without requiring a breakpoint hit first`() {
        assertEquals(DapSessionState.TERMINATING, advanced(DapSessionState.RUNNING, DapSessionMachine.Event.TerminateRequested))
    }

    @Test
    fun `negotiate is the intersection, correctly omitting a capability the adapter never declared`() {
        val hostWants = setOf(DapCapability.SET_BREAKPOINTS, DapCapability.RESTART, DapCapability.TERMINATE)
        // Python's declared DAP capabilities (debugpy) do not include RESTART.
        val negotiated = DapSessionMachine.negotiate(hostWants, BuiltInLanguagePacks.PYTHON.declaredDapCapabilities)
        assertEquals(setOf(DapCapability.SET_BREAKPOINTS, DapCapability.TERMINATE), negotiated)
    }
}
