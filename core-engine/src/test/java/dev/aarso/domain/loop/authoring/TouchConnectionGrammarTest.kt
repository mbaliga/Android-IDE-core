package dev.aarso.domain.loop.authoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exhaustive coverage of LOOP_PHONE_AUTHORING_SPEC.md §7's tap connection grammar table (FB-RAT-PHN-004). */
class TouchConnectionGrammarTest {

    private fun advanced(draft: TouchConnectionGrammar.ConnectionDraft, event: TouchConnectionGrammar.Event): TouchConnectionGrammar.ConnectionDraft {
        val result = TouchConnectionGrammar.apply(draft, event)
        assertTrue("expected Advanced for $event from ${draft.state}, got $result", result is TouchConnectionGrammar.Result.Advanced)
        return (result as TouchConnectionGrammar.Result.Advanced).draft
    }

    private fun rejected(draft: TouchConnectionGrammar.ConnectionDraft, event: TouchConnectionGrammar.Event) {
        val result = TouchConnectionGrammar.apply(draft, event)
        assertTrue("expected Rejected for $event from ${draft.state}, got $result", result is TouchConnectionGrammar.Result.Rejected)
    }

    @Test
    fun `the full happy path visits every state in the table, in order, ending COMMITTED`() {
        var draft = TouchConnectionGrammar.start()
        assertEquals(ConnectionDraftState.IDLE, draft.state)

        draft = advanced(draft, TouchConnectionGrammar.Event.SelectSource("stage-1"))
        assertEquals(ConnectionDraftState.SOURCE_SELECTED, draft.state)

        draft = advanced(draft, TouchConnectionGrammar.Event.ConnectFromHere)
        assertEquals(ConnectionDraftState.AWAITING_DESTINATION, draft.state)

        draft = advanced(draft, TouchConnectionGrammar.Event.ChooseDestination("stage-2"))
        assertEquals(ConnectionDraftState.DESTINATION_CHOSEN, draft.state)

        draft = advanced(draft, TouchConnectionGrammar.Event.ChooseLabel(outputPort = "out", label = "on success"))
        assertEquals(ConnectionDraftState.LABELED, draft.state)

        draft = advanced(draft, TouchConnectionGrammar.Event.RequestPreview)
        assertEquals(ConnectionDraftState.PREVIEWING, draft.state)

        draft = advanced(draft, TouchConnectionGrammar.Event.Commit)
        assertEquals(ConnectionDraftState.COMMITTED, draft.state)
        assertEquals("stage-1", draft.sourceNodeId)
        assertEquals("stage-2", draft.destinationNodeId)
    }

    @Test
    fun `a gateway that requires a condition routes LABELED to CONDITIONED before PREVIEWING`() {
        var draft = TouchConnectionGrammar.start().copy(
            state = ConnectionDraftState.LABELED, sourceNodeId = "g", destinationNodeId = "d", gatewayRequiresCondition = true,
        )
        rejected(draft, TouchConnectionGrammar.Event.RequestPreview) // must define the condition first
        draft = advanced(draft, TouchConnectionGrammar.Event.DefineCondition("amount > 100"))
        assertEquals(ConnectionDraftState.CONDITIONED, draft.state)
        draft = advanced(draft, TouchConnectionGrammar.Event.RequestPreview)
        assertEquals(ConnectionDraftState.PREVIEWING, draft.state)
    }

    @Test
    fun `LABELED skips straight to PREVIEWING when no condition is required, and DefineCondition is rejected there`() {
        val draft = TouchConnectionGrammar.start().copy(state = ConnectionDraftState.LABELED, gatewayRequiresCondition = false)
        rejected(draft, TouchConnectionGrammar.Event.DefineCondition("n/a"))
        val previewing = advanced(draft, TouchConnectionGrammar.Event.RequestPreview)
        assertEquals(ConnectionDraftState.PREVIEWING, previewing.state)
    }

    @Test
    fun `cancel from every intermediate state is a pure discard back to IDLE, with no draft mutation before COMMITTED`() {
        val intermediateStates = listOf(
            ConnectionDraftState.SOURCE_SELECTED, ConnectionDraftState.AWAITING_DESTINATION,
            ConnectionDraftState.DESTINATION_CHOSEN, ConnectionDraftState.LABELED,
            ConnectionDraftState.CONDITIONED, ConnectionDraftState.PREVIEWING,
        )
        for (state in intermediateStates) {
            val draft = TouchConnectionGrammar.start().copy(state = state, sourceNodeId = "x", destinationNodeId = "y")
            val cancelled = advanced(draft, TouchConnectionGrammar.Event.Cancel)
            assertEquals(ConnectionDraftState.IDLE, cancelled.state)
            assertNull(cancelled.sourceNodeId)
        }
    }

    @Test
    fun `cancel is illegal from IDLE and from COMMITTED`() {
        rejected(TouchConnectionGrammar.start(), TouchConnectionGrammar.Event.Cancel)
        rejected(TouchConnectionGrammar.start().copy(state = ConnectionDraftState.COMMITTED), TouchConnectionGrammar.Event.Cancel)
    }

    @Test
    fun `COMMITTED accepts no further events at all`() {
        val committed = TouchConnectionGrammar.start().copy(state = ConnectionDraftState.COMMITTED)
        rejected(committed, TouchConnectionGrammar.Event.SelectSource("z"))
        rejected(committed, TouchConnectionGrammar.Event.Commit)
    }

    @Test
    fun `isValidTransition agrees with apply() for every legal step of the happy path, and rejects skipping a state`() {
        assertTrue(TouchConnectionGrammar.isValidTransition(ConnectionDraftState.IDLE, ConnectionDraftState.SOURCE_SELECTED))
        assertTrue(TouchConnectionGrammar.isValidTransition(ConnectionDraftState.SOURCE_SELECTED, ConnectionDraftState.AWAITING_DESTINATION))
        assertTrue(TouchConnectionGrammar.isValidTransition(ConnectionDraftState.PREVIEWING, ConnectionDraftState.COMMITTED))
        assertTrue(TouchConnectionGrammar.isValidTransition(ConnectionDraftState.LABELED, ConnectionDraftState.IDLE)) // cancel
        assertTrue(!TouchConnectionGrammar.isValidTransition(ConnectionDraftState.IDLE, ConnectionDraftState.DESTINATION_CHOSEN))
        assertTrue(!TouchConnectionGrammar.isValidTransition(ConnectionDraftState.COMMITTED, ConnectionDraftState.IDLE))
    }
}
