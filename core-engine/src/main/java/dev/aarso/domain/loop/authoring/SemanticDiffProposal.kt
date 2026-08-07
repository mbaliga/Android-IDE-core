package dev.aarso.domain.loop.authoring

/**
 * `FB-RAT-PHN-006` (§8, `LOOP_PHONE_AUTHORING_SPEC.md`) -- Distiller/AI-assisted graph changes MUST
 * produce an inspectable semantic diff and require explicit approval before the draft changes.
 * [SemanticDiffOperation] is the review model the spec's bullet list describes (nodes added/
 * removed/replaced/reordered; edges and conditions changed; schemas changed; new capabilities or
 * side effects; budget changes; test changes; expected-behavior change); [SemanticDiffProposalMachine]
 * is the four-state table in §8 that governs when those operations may actually reach the draft.
 */
enum class DiffOperationKind {
    NODE_ADDED, NODE_REMOVED, NODE_REPLACED, NODE_REORDERED,
    EDGE_OR_CONDITION_CHANGED, SCHEMA_CHANGED,
    CAPABILITY_OR_SIDE_EFFECT_CHANGED, BUDGET_CHANGED, TEST_CHANGED, EXPECTED_BEHAVIOR_CHANGED,
}

/**
 * One entry in a proposal's operation list. [isAuthorityWidening] flags an operation that expands
 * requested capabilities, authority rung, or side-effect class -- §8 requires these be "visually
 * separated from ordinary graph edits, not interleaved with them in one undifferentiated list."
 */
data class SemanticDiffOperation(
    val kind: DiffOperationKind,
    val targetId: String,
    val description: String,
    val isAuthorityWidening: Boolean = false,
) {
    init {
        require(targetId.isNotBlank()) { "SemanticDiffOperation.targetId must be non-blank." }
        require(description.isNotBlank()) { "SemanticDiffOperation.description must be non-blank -- the review list has nothing to show otherwise." }
    }
}

/** Validation summary references, before and after -- §8's "validation results before and after." Opaque finding-code lists; this module doesn't re-implement the validator. */
data class ValidationSummaryRef(val findingCodes: List<String>)

data class SemanticDiffProposal(
    val proposalId: String,
    val baseRevisionId: String,
    val operations: List<SemanticDiffOperation>,
    val validationBefore: ValidationSummaryRef,
    val validationAfter: ValidationSummaryRef,
) {
    init {
        require(proposalId.isNotBlank()) { "SemanticDiffProposal.proposalId must be non-blank." }
        require(baseRevisionId.isNotBlank()) { "SemanticDiffProposal.baseRevisionId must be non-blank." }
    }

    /** §8's display requirement: authority-widening operations grouped apart from ordinary ones. */
    fun groupedForReview(): Pair<List<SemanticDiffOperation>, List<SemanticDiffOperation>> =
        operations.filter { it.isAuthorityWidening } to operations.filter { !it.isAuthorityWidening }
}

enum class ProposalDraftState { BASE_REVISION, PROPOSED, APPLIED, DISCARDED }

object SemanticDiffProposalMachine {

    sealed interface Event {
        data class Propose(val proposal: SemanticDiffProposal) : Event
        /** User edits the proposal (e.g. removes one operation) -- a self-loop, re-diffed before the next transition. */
        data class EditProposal(val revisedProposal: SemanticDiffProposal) : Event
        object Approve : Event
        object Reject : Event
    }

    sealed interface Result {
        data class Advanced(val state: ProposalDraftState, val proposal: SemanticDiffProposal?) : Result
        data class Rejected(val reason: String) : Result
    }

    fun start(): Pair<ProposalDraftState, SemanticDiffProposal?> = ProposalDraftState.BASE_REVISION to null

    fun apply(state: ProposalDraftState, proposal: SemanticDiffProposal?, event: Event): Result = when (state) {
        ProposalDraftState.BASE_REVISION -> when (event) {
            is Event.Propose -> Result.Advanced(ProposalDraftState.PROPOSED, event.proposal)
            else -> reject(state, event)
        }
        ProposalDraftState.PROPOSED -> when (event) {
            is Event.EditProposal -> Result.Advanced(ProposalDraftState.PROPOSED, event.revisedProposal)
            Event.Approve -> Result.Advanced(ProposalDraftState.APPLIED, proposal)
            Event.Reject -> Result.Advanced(ProposalDraftState.DISCARDED, null)
            else -> reject(state, event)
        }
        ProposalDraftState.APPLIED, ProposalDraftState.DISCARDED -> reject(state, event)
    }

    fun isValidTransition(from: ProposalDraftState, to: ProposalDraftState): Boolean = when (from) {
        ProposalDraftState.BASE_REVISION -> to == ProposalDraftState.PROPOSED
        ProposalDraftState.PROPOSED -> to == ProposalDraftState.PROPOSED || to == ProposalDraftState.APPLIED || to == ProposalDraftState.DISCARDED
        ProposalDraftState.APPLIED, ProposalDraftState.DISCARDED -> false
    }

    private fun reject(state: ProposalDraftState, event: Event): Result.Rejected =
        Result.Rejected("SemanticDiffProposalMachine: event ${event::class.simpleName} is not legal from state $state (LOOP_PHONE_AUTHORING_SPEC.md §8).")
}
