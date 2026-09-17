package dev.fonebrew.domain.loop.authoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exhaustive coverage of LOOP_PHONE_AUTHORING_SPEC.md §8's proposal-review table (FB-RAT-PHN-006). */
class SemanticDiffProposalTest {

    private fun proposal(vararg ops: SemanticDiffOperation) = SemanticDiffProposal(
        proposalId = "prop-1", baseRevisionId = "rev-1", operations = ops.toList(),
        validationBefore = ValidationSummaryRef(emptyList()), validationAfter = ValidationSummaryRef(emptyList()),
    )

    @Test
    fun `BASE_REVISION to PROPOSED to APPLIED, the full approve path`() {
        val (state0, proposal0) = SemanticDiffProposalMachine.start()
        assertEquals(ProposalDraftState.BASE_REVISION, state0)

        val p = proposal(SemanticDiffOperation(DiffOperationKind.NODE_ADDED, "n1", "add a verifier node"))
        val r1 = SemanticDiffProposalMachine.apply(state0, proposal0, SemanticDiffProposalMachine.Event.Propose(p))
        assertTrue(r1 is SemanticDiffProposalMachine.Result.Advanced)
        val (state1, proposal1) = (r1 as SemanticDiffProposalMachine.Result.Advanced).let { it.state to it.proposal }
        assertEquals(ProposalDraftState.PROPOSED, state1)

        val r2 = SemanticDiffProposalMachine.apply(state1, proposal1, SemanticDiffProposalMachine.Event.Approve)
        assertTrue(r2 is SemanticDiffProposalMachine.Result.Advanced)
        assertEquals(ProposalDraftState.APPLIED, (r2 as SemanticDiffProposalMachine.Result.Advanced).state)
        assertEquals(p, r2.proposal) // the proposal that was actually applied is exactly the approved one
    }

    @Test
    fun `rejecting a proposal discards it, equivalent to BASE_REVISION, with no proposal carried forward`() {
        val p = proposal(SemanticDiffOperation(DiffOperationKind.SCHEMA_CHANGED, "n1", "widen enum"))
        val r = SemanticDiffProposalMachine.apply(ProposalDraftState.PROPOSED, p, SemanticDiffProposalMachine.Event.Reject)
        assertTrue(r is SemanticDiffProposalMachine.Result.Advanced)
        val advanced = r as SemanticDiffProposalMachine.Result.Advanced
        assertEquals(ProposalDraftState.DISCARDED, advanced.state)
        assertEquals(null, advanced.proposal)
    }

    @Test
    fun `editing a proposal is a PROPOSED self-loop that re-diffs, not a state change`() {
        val original = proposal(SemanticDiffOperation(DiffOperationKind.NODE_ADDED, "n1", "add node"))
        val revised = proposal(
            SemanticDiffOperation(DiffOperationKind.NODE_ADDED, "n1", "add node"),
            SemanticDiffOperation(DiffOperationKind.TEST_CHANGED, "n1", "add a fixture"),
        )
        val r = SemanticDiffProposalMachine.apply(ProposalDraftState.PROPOSED, original, SemanticDiffProposalMachine.Event.EditProposal(revised))
        assertTrue(r is SemanticDiffProposalMachine.Result.Advanced)
        val advanced = r as SemanticDiffProposalMachine.Result.Advanced
        assertEquals(ProposalDraftState.PROPOSED, advanced.state)
        assertEquals(2, advanced.proposal?.operations?.size)
    }

    @Test
    fun `APPLIED and DISCARDED are terminal -- no further event is legal`() {
        assertTrue(SemanticDiffProposalMachine.apply(ProposalDraftState.APPLIED, null, SemanticDiffProposalMachine.Event.Approve) is SemanticDiffProposalMachine.Result.Rejected)
        assertTrue(SemanticDiffProposalMachine.apply(ProposalDraftState.DISCARDED, null, SemanticDiffProposalMachine.Event.Reject) is SemanticDiffProposalMachine.Result.Rejected)
    }

    @Test
    fun `Propose is illegal from PROPOSED, and Approve-Reject are illegal from BASE_REVISION`() {
        val p = proposal(SemanticDiffOperation(DiffOperationKind.BUDGET_CHANGED, "n1", "raise step budget"))
        assertTrue(SemanticDiffProposalMachine.apply(ProposalDraftState.PROPOSED, p, SemanticDiffProposalMachine.Event.Propose(p)) is SemanticDiffProposalMachine.Result.Rejected)
        assertTrue(SemanticDiffProposalMachine.apply(ProposalDraftState.BASE_REVISION, null, SemanticDiffProposalMachine.Event.Approve) is SemanticDiffProposalMachine.Result.Rejected)
        assertTrue(SemanticDiffProposalMachine.apply(ProposalDraftState.BASE_REVISION, null, SemanticDiffProposalMachine.Event.Reject) is SemanticDiffProposalMachine.Result.Rejected)
    }

    @Test
    fun `groupedForReview separates authority-widening operations from ordinary ones`() {
        val ordinary = SemanticDiffOperation(DiffOperationKind.NODE_ADDED, "n1", "add a deterministic transform")
        val widening = SemanticDiffOperation(DiffOperationKind.CAPABILITY_OR_SIDE_EFFECT_CHANGED, "n2", "request fb.exec.local_command", isAuthorityWidening = true)
        val (wideningOps, ordinaryOps) = proposal(ordinary, widening).groupedForReview()
        assertEquals(listOf(widening), wideningOps)
        assertEquals(listOf(ordinary), ordinaryOps)
    }

    @Test
    fun `isValidTransition matches the table exactly, including the PROPOSED self-loop`() {
        assertTrue(SemanticDiffProposalMachine.isValidTransition(ProposalDraftState.BASE_REVISION, ProposalDraftState.PROPOSED))
        assertTrue(SemanticDiffProposalMachine.isValidTransition(ProposalDraftState.PROPOSED, ProposalDraftState.PROPOSED))
        assertTrue(SemanticDiffProposalMachine.isValidTransition(ProposalDraftState.PROPOSED, ProposalDraftState.APPLIED))
        assertTrue(SemanticDiffProposalMachine.isValidTransition(ProposalDraftState.PROPOSED, ProposalDraftState.DISCARDED))
        assertTrue(!SemanticDiffProposalMachine.isValidTransition(ProposalDraftState.BASE_REVISION, ProposalDraftState.APPLIED))
        assertTrue(!SemanticDiffProposalMachine.isValidTransition(ProposalDraftState.APPLIED, ProposalDraftState.PROPOSED))
    }
}
