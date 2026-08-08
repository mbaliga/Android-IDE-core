package dev.aarso.domain.curation

/**
 * Marks a branch as no longer the active path — the annotation half of rewind
 * (STUDIO_UX_SPEC.md §4.5/§5.1). The message tree is append-only and nothing is ever deleted
 * (see [dev.aarso.domain.tree.MessageTree]); "rewind" is just moving the session's active-leaf
 * pointer to an earlier node (already-existing [dev.aarso.domain.tree.MessageTree] mechanics —
 * "restore = make an earlier node the active leaf," per the architecture spine). A [GhostBranch]
 * records *that* a formerly-active branch became inactive, *why*, and *when* — it does not
 * itself move or hide anything; the branch stays fully navigable via the existing sibling/
 * alternative-branch UI, just labeled and dimmed (40% opacity per the spec) instead of
 * highlighted as the live path.
 *
 * @property branchTipMsgId the node id that was the active leaf immediately before it was
 *   superseded — the ghost branch's whole history is reachable from this id via
 *   [dev.aarso.domain.tree.MessageTree.pathToRoot], same as any other branch.
 */
data class GhostBranch(
    val branchTipMsgId: String,
    val rewoundAt: Long,
    val reason: GhostReason,
)

enum class GhostReason {
    REWIND,
    ROUNDTABLE_LOSER,
}

/**
 * The pure rewind computation: given the current active leaf and the node being rewound *to*,
 * decide whether a [GhostBranch] needs recording. Persistence (moving the actual active-leaf
 * pointer) stays the caller's job — this only computes the annotation.
 */
object Rewind {

    /**
     * @param currentLeafId the session's active leaf right now.
     * @param rewindToId the message the user long-pressed → "Rewind from here" on.
     * @param isDescendant true if [currentLeafId] is reachable from [rewindToId] by following
     *   children (i.e. there is an existing "future" below the rewind point to ghost). Callers
     *   compute this from [dev.aarso.domain.tree.MessageTree] since [Rewind] itself has no tree
     *   access (kept pure/dependency-free for testability).
     * @return a [GhostBranch] to record for [currentLeafId], or null if there is nothing to
     *   ghost (rewinding to the current leaf itself, or to a node with no existing future).
     */
    fun planGhost(
        currentLeafId: String,
        rewindToId: String,
        isDescendant: Boolean,
        now: Long,
        reason: GhostReason = GhostReason.REWIND,
    ): GhostBranch? {
        if (!isDescendant) return null
        if (currentLeafId == rewindToId) return null
        return GhostBranch(branchTipMsgId = currentLeafId, rewoundAt = now, reason = reason)
    }
}
