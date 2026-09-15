package dev.fonebrew.domain.loop.authoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exhaustive coverage of [GatewayConditionPresenter] — the gateway condition editor's
 *  grammar-driving layer (asoc-reachability audit, 2026-09-15: DefineCondition/ChooseLabel/
 *  RequestPreview previously had zero production senders). Every event path this presenter
 *  exposes, including cancel from each reachable state, plus [GatewayConditionPresenter
 *  .previewFor]'s honesty about what [dev.fonebrew.domain.loop.ConditionGatewayPolicy] actually
 *  matches. */
class GatewayConditionPresenterTest {

    private fun advanced(result: TouchConnectionGrammar.Result): TouchConnectionGrammar.ConnectionDraft {
        assertTrue("expected Advanced, got $result", result is TouchConnectionGrammar.Result.Advanced)
        return (result as TouchConnectionGrammar.Result.Advanced).draft
    }

    private fun rejected(result: TouchConnectionGrammar.Result) {
        assertTrue("expected Rejected, got $result", result is TouchConnectionGrammar.Result.Rejected)
    }

    // ── start() rebuilds the pre-edit gesture as real grammar transitions ──────────────────

    @Test fun `start reaches DESTINATION_CHOSEN with source and destination recorded`() {
        val draft = GatewayConditionPresenter.start("gate-1", "end-1")
        assertEquals(ConnectionDraftState.DESTINATION_CHOSEN, draft.state)
        assertEquals("gate-1", draft.sourceNodeId)
        assertEquals("end-1", draft.destinationNodeId)
        assertTrue(!draft.isNewDestinationNode)
    }

    @Test fun `start threads isNewDestinationNode through to the draft`() {
        val draft = GatewayConditionPresenter.start("gate-1", "new-1", isNewDestinationNode = true)
        assertTrue(draft.isNewDestinationNode)
    }

    // ── the no-condition happy path: label only, straight to PREVIEWING then COMMITTED ─────

    @Test fun `a quick label with no condition text skips straight to PREVIEWING then commits`() {
        var draft = GatewayConditionPresenter.start("gate-1", "end-1")
        draft = advanced(GatewayConditionPresenter.chooseLabel(draft, "approve", condition = null))
        assertEquals(ConnectionDraftState.LABELED, draft.state)
        assertTrue(!draft.gatewayRequiresCondition)
        assertEquals("approve", draft.label)

        draft = advanced(GatewayConditionPresenter.requestPreview(draft))
        assertEquals(ConnectionDraftState.PREVIEWING, draft.state)

        draft = advanced(GatewayConditionPresenter.commit(draft))
        assertEquals(ConnectionDraftState.COMMITTED, draft.state)
    }

    @Test fun `blank condition text is treated the same as no condition at all`() {
        var draft = GatewayConditionPresenter.start("gate-1", "end-1")
        draft = advanced(GatewayConditionPresenter.chooseLabel(draft, "else", condition = "   "))
        assertTrue(!draft.gatewayRequiresCondition)
        draft = advanced(GatewayConditionPresenter.requestPreview(draft)) // would reject if requiresCondition were true
        assertEquals(ConnectionDraftState.PREVIEWING, draft.state)
    }

    // ── the condition path: label + condition, CONDITIONED before PREVIEWING ───────────────

    @Test fun `a condition text routes LABELED to CONDITIONED, and skipping DefineCondition is rejected`() {
        var draft = GatewayConditionPresenter.start("gate-1", "end-1")
        draft = advanced(GatewayConditionPresenter.chooseLabel(draft, "approve", condition = "score > 0.8"))
        assertEquals(ConnectionDraftState.LABELED, draft.state)
        assertTrue(draft.gatewayRequiresCondition)

        rejected(GatewayConditionPresenter.requestPreview(draft)) // must DefineCondition first

        draft = advanced(GatewayConditionPresenter.defineCondition(draft, "score > 0.8"))
        assertEquals(ConnectionDraftState.CONDITIONED, draft.state)
        assertEquals("score > 0.8", draft.conditionExpression)

        draft = advanced(GatewayConditionPresenter.requestPreview(draft))
        assertEquals(ConnectionDraftState.PREVIEWING, draft.state)

        draft = advanced(GatewayConditionPresenter.commit(draft))
        assertEquals(ConnectionDraftState.COMMITTED, draft.state)
    }

    @Test fun `defineCondition is illegal before a label is chosen`() {
        val draft = GatewayConditionPresenter.start("gate-1", "end-1")
        rejected(GatewayConditionPresenter.defineCondition(draft, "x"))
    }

    // ── cancel from every state this presenter can actually reach ──────────────────────────

    @Test fun `cancel discards from DESTINATION_CHOSEN`() {
        val draft = GatewayConditionPresenter.start("gate-1", "end-1")
        val cancelled = advanced(GatewayConditionPresenter.cancel(draft))
        assertEquals(ConnectionDraftState.IDLE, cancelled.state)
        assertNull(cancelled.sourceNodeId)
    }

    @Test fun `cancel discards from LABELED`() {
        var draft = GatewayConditionPresenter.start("gate-1", "end-1")
        draft = advanced(GatewayConditionPresenter.chooseLabel(draft, "approve", condition = null))
        val cancelled = advanced(GatewayConditionPresenter.cancel(draft))
        assertEquals(ConnectionDraftState.IDLE, cancelled.state)
    }

    @Test fun `cancel discards from CONDITIONED`() {
        var draft = GatewayConditionPresenter.start("gate-1", "end-1")
        draft = advanced(GatewayConditionPresenter.chooseLabel(draft, "approve", condition = "x"))
        draft = advanced(GatewayConditionPresenter.defineCondition(draft, "x"))
        val cancelled = advanced(GatewayConditionPresenter.cancel(draft))
        assertEquals(ConnectionDraftState.IDLE, cancelled.state)
    }

    @Test fun `cancel discards from PREVIEWING`() {
        var draft = GatewayConditionPresenter.start("gate-1", "end-1")
        draft = advanced(GatewayConditionPresenter.chooseLabel(draft, "approve", condition = null))
        draft = advanced(GatewayConditionPresenter.requestPreview(draft))
        val cancelled = advanced(GatewayConditionPresenter.cancel(draft))
        assertEquals(ConnectionDraftState.IDLE, cancelled.state)
    }

    @Test fun `cancel is illegal once COMMITTED, and commit cannot fire twice`() {
        var draft = GatewayConditionPresenter.start("gate-1", "end-1")
        draft = advanced(GatewayConditionPresenter.chooseLabel(draft, "approve", condition = null))
        draft = advanced(GatewayConditionPresenter.requestPreview(draft))
        draft = advanced(GatewayConditionPresenter.commit(draft))
        rejected(GatewayConditionPresenter.cancel(draft))
        rejected(GatewayConditionPresenter.commit(draft))
    }

    // ── previewFor: honest about what ConditionGatewayPolicy actually matches ──────────────

    @Test fun `previewFor picks the draft edge by its recognised condition literal`() {
        var draft = GatewayConditionPresenter.start("gate-1", "end-1")
        draft = advanced(GatewayConditionPresenter.chooseLabel(draft, "approve", condition = "approved"))
        draft = advanced(GatewayConditionPresenter.defineCondition(draft, "approved"))

        val result = GatewayConditionPresenter.previewFor(draft, existingEdges = emptyList(), sampleOutput = "APPROVE looks good")
        assertEquals(GatewayConditionPresenter.DRAFT_KEY, result.chosenKey)
    }

    @Test fun `previewFor picks an existing edge over the draft when the sample favors it`() {
        var draft = GatewayConditionPresenter.start("gate-1", "prop-1")
        draft = advanced(GatewayConditionPresenter.chooseLabel(draft, "refine", condition = "!approved"))
        draft = advanced(GatewayConditionPresenter.defineCondition(draft, "!approved"))

        val existing = listOf(GatewayConditionPresenter.CandidateEdge("edge-approve", "approve", "approved"))
        val result = GatewayConditionPresenter.previewFor(draft, existing, sampleOutput = "APPROVE ship it")
        assertEquals("edge-approve", result.chosenKey)
    }

    @Test fun `a free-form condition ConditionGatewayPolicy does not recognise honestly falls to the default branch`() {
        // "score > 0.8" is not "approved"/"!approved" and not a recognised name literal either —
        // ConditionGatewayPolicy has no expression evaluator, so this must NOT be reported as if
        // it were evaluated true; it can only ever be chosen as an unconditioned default branch.
        var draft = GatewayConditionPresenter.start("gate-1", "end-1")
        draft = advanced(GatewayConditionPresenter.chooseLabel(draft, "high score", condition = "score > 0.8"))
        draft = advanced(GatewayConditionPresenter.defineCondition(draft, "score > 0.8"))

        // A second, recognised edge exists alongside the unrecognised free-form one.
        val existing = listOf(GatewayConditionPresenter.CandidateEdge("edge-approve", "approve", "approved"))
        val result = GatewayConditionPresenter.previewFor(draft, existing, sampleOutput = "APPROVE, score is 0.95")
        // The recognised "approve" edge wins on an APPROVE-prefixed sample — the free-form
        // condition never gets special treatment it isn't owed.
        assertEquals("edge-approve", result.chosenKey)
    }

    @Test fun `previewFor still resolves the unrecognised draft edge as the default when nothing else matches`() {
        var draft = GatewayConditionPresenter.start("gate-1", "end-1")
        draft = advanced(GatewayConditionPresenter.chooseLabel(draft, "high score", condition = "score > 0.8"))
        draft = advanced(GatewayConditionPresenter.defineCondition(draft, "score > 0.8"))

        val result = GatewayConditionPresenter.previewFor(draft, existingEdges = emptyList(), sampleOutput = "needs work")
        // No recognised match anywhere -> the one unconditioned edge (condition set, but not a
        // recognised literal, so ConditionGatewayPolicy treats it as the plain default) is taken.
        assertEquals(GatewayConditionPresenter.DRAFT_KEY, result.chosenKey)
    }

    @Test fun `previewFor without a label yet still reports a result, never throws`() {
        val draft = GatewayConditionPresenter.start("gate-1", "end-1")
        val result = GatewayConditionPresenter.previewFor(draft, emptyList(), sampleOutput = "anything")
        assertEquals(GatewayConditionPresenter.DRAFT_KEY, result.chosenKey) // the only edge -> the default
    }
}
