package dev.aarso.domain.language

import dev.aarso.contracts.language.BuiltInLanguagePacks
import dev.aarso.contracts.language.LspCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LspSessionMachineTest {

    private fun advanced(state: LspSessionState, event: LspSessionMachine.Event): LspSessionState {
        val result = LspSessionMachine.apply(state, event)
        assertTrue("expected Advanced for $event from $state, got $result", result is LspSessionMachine.Result.Advanced)
        return (result as LspSessionMachine.Result.Advanced).state
    }

    private fun rejected(state: LspSessionState, event: LspSessionMachine.Event) {
        assertTrue(LspSessionMachine.apply(state, event) is LspSessionMachine.Result.Rejected)
    }

    @Test
    fun `the full happy path visits every state from NOT_STARTED to READY`() {
        var state = LspSessionState.NOT_STARTED
        state = advanced(state, LspSessionMachine.Event.Start)
        assertEquals(LspSessionState.STARTING, state)
        state = advanced(state, LspSessionMachine.Event.ServerRespondedToInitialize(setOf(LspCapability.HOVER)))
        assertEquals(LspSessionState.NEGOTIATING, state)
        state = advanced(state, LspSessionMachine.Event.InitializedNotificationSent)
        assertEquals(LspSessionState.READY, state)
    }

    @Test
    fun `a request failure degrades a READY session, and recovery returns it to READY`() {
        var state = LspSessionState.READY
        state = advanced(state, LspSessionMachine.Event.RequestFailed)
        assertEquals(LspSessionState.DEGRADED, state)
        state = advanced(state, LspSessionMachine.Event.Recovered)
        assertEquals(LspSessionState.READY, state)
    }

    @Test
    fun `a full crash-and-restart recovery cycle returns to READY through the same handshake as first start`() {
        var state = LspSessionState.READY
        state = advanced(state, LspSessionMachine.Event.ProcessCrashed)
        assertEquals(LspSessionState.CRASHED, state)
        state = advanced(state, LspSessionMachine.Event.RestartRequested)
        assertEquals(LspSessionState.RESTARTING, state)
        state = advanced(state, LspSessionMachine.Event.RestartSucceeded)
        assertEquals(LspSessionState.STARTING, state) // restart re-enters the real initialize handshake, not a shortcut
        state = advanced(state, LspSessionMachine.Event.ServerRespondedToInitialize(setOf(LspCapability.DIAGNOSTICS)))
        assertEquals(LspSessionState.NEGOTIATING, state)
        state = advanced(state, LspSessionMachine.Event.InitializedNotificationSent)
        assertEquals(LspSessionState.READY, state)
    }

    @Test
    fun `a crash can occur mid-handshake too, from STARTING or NEGOTIATING, not just from READY`() {
        assertEquals(LspSessionState.CRASHED, advanced(LspSessionState.STARTING, LspSessionMachine.Event.ProcessCrashed))
        assertEquals(LspSessionState.CRASHED, advanced(LspSessionState.NEGOTIATING, LspSessionMachine.Event.ProcessCrashed))
    }

    @Test
    fun `STOPPED accepts no further event, and Stop is illegal before READY or DEGRADED`() {
        rejected(LspSessionState.STOPPED, LspSessionMachine.Event.Start)
        rejected(LspSessionState.NOT_STARTED, LspSessionMachine.Event.Stop)
        rejected(LspSessionState.STARTING, LspSessionMachine.Event.Stop)
    }

    @Test
    fun `negotiate is the intersection of client wants and server declares, never either side's unilateral wish list`() {
        val clientWants = setOf(LspCapability.HOVER, LspCapability.RENAME, LspCapability.WORKSPACE_SYMBOLS)
        val negotiated = LspSessionMachine.negotiate(clientWants, BuiltInLanguagePacks.TYPESCRIPT.declaredLspCapabilities)
        // TypeScript declares HOVER and RENAME but not WORKSPACE_SYMBOLS.
        assertEquals(setOf(LspCapability.HOVER, LspCapability.RENAME), negotiated)
    }

    @Test
    fun `negotiating against Python's declared set correctly omits RENAME, which Python doesn't declare`() {
        val clientWants = setOf(LspCapability.HOVER, LspCapability.RENAME, LspCapability.DOCUMENT_SYMBOLS)
        val negotiated = LspSessionMachine.negotiate(clientWants, BuiltInLanguagePacks.PYTHON.declaredLspCapabilities)
        assertEquals(setOf(LspCapability.HOVER, LspCapability.DOCUMENT_SYMBOLS), negotiated)
    }
}
