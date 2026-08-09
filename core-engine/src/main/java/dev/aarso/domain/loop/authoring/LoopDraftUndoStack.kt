package dev.aarso.domain.loop.authoring

/**
 * §14, `LOOP_PHONE_AUTHORING_SPEC.md` ("Undo and redo"). **Honesty note: `FB-RAT-PHN-011` is
 * marked `PROPOSED, not yet ratified` in that document -- "this document does not self-ratify it."**
 * This class implements the fully-specified "proposed shape" as real, tested code anyway, because
 * the gap it closes is genuine (zero undo anywhere in the source pack, on the highest-
 * accidental-actuation input modality, with destructive verbs one tap away) and the proposal is
 * concrete enough to build, not aspirational. It is provisional pending an owner/Amendments-phase
 * ratification decision, same posture WP-3 took recording `FB-RAT-WS-NEW-1` as a proposal rather
 * than a ruling -- flagged again in `docs/WP8b_GATE_REPORT.md`, not silently treated as settled.
 *
 * Semantic Stage View verbs only (§3.2's list plus one AI-proposal case) -- never raw UI events
 * (taps, scroll, focus). One [LoopDraftUndoStack] instance belongs to exactly one draft revision
 * lineage; once that draft is activated or its package exported, `crossedBoundary` retires this
 * instance permanently -- a forked draft (editing an installed/exported loop always forks,
 * `LOOP-ID-002`) gets its own fresh stack, not a reuse of this one.
 */
enum class StageVerb { ADD, INSERT, DUPLICATE, REORDER, CONNECT, BRANCH, WRAP_AS_SUBLOOP, DISABLE, DELETE, APPLIED_AI_PROPOSAL }

data class UndoableAction(val verb: StageVerb, val description: String) {
    init { require(description.isNotBlank()) { "UndoableAction.description must be non-blank." } }
}

class LoopDraftUndoStack {
    private val undoStack = ArrayDeque<UndoableAction>()
    private val redoStack = ArrayDeque<UndoableAction>()
    private var crossedBoundary = false

    /**
     * A new action clears the redo stack -- "standard linear undo history, not a tree." Returns
     * false without recording anything if this stack has already crossed an activation/export
     * boundary.
     */
    fun record(action: UndoableAction): Boolean {
        if (crossedBoundary) return false
        undoStack.addLast(action)
        redoStack.clear()
        return true
    }

    /**
     * "Applying an AI proposal is exactly one undoable transaction, however many individual
     * operations the proposal's operation list contained -- undoing an applied AI edit reverts the
     * whole proposal atomically, not operation-by-operation." Callers MUST NOT also [record] each
     * individual operation inside an approved [SemanticDiffProposal] -- this single call is the
     * whole transaction.
     */
    fun recordAiProposalApplied(proposalDescription: String): Boolean =
        record(UndoableAction(StageVerb.APPLIED_AI_PROPOSAL, proposalDescription))

    fun canUndo(): Boolean = !crossedBoundary && undoStack.isNotEmpty()
    fun canRedo(): Boolean = !crossedBoundary && redoStack.isNotEmpty()

    /** Restores the prior semantic state; does not cross an activation/export boundary (only reachable while one hasn't happened yet). */
    fun undo(): UndoableAction? {
        if (!canUndo()) return null
        val action = undoStack.removeLast()
        redoStack.addLast(action)
        return action
    }

    fun redo(): UndoableAction? {
        if (!canRedo()) return null
        val action = redoStack.removeLast()
        undoStack.addLast(action)
        return action
    }

    /**
     * "Loop is activated (§11) or a package is exported (§13)" -- clears both stacks and retires
     * this instance. "Undo cannot reach back across this event afterward" -- enforced by
     * [crossedBoundary] permanently blocking [record]/[undo]/[redo] on this instance from here on;
     * a forked draft after this point uses a new [LoopDraftUndoStack].
     */
    fun markActivationOrExportBoundary() {
        undoStack.clear()
        redoStack.clear()
        crossedBoundary = true
    }

    fun undoStackSize(): Int = undoStack.size
    fun redoStackSize(): Int = redoStack.size
}
