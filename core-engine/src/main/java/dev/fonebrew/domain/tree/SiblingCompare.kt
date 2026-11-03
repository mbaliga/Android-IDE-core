package dev.fonebrew.domain.tree

/**
 * THREAD_TOPOLOGY_PLAN.md WP2's "compare" presenter — the read model behind "Compare alternatives"
 * on the pager row ([PathView.Step.isBranchPoint]) and `ui/CompareSheet`'s stacked cards. Turns a
 * branch point's raw sibling children into one comparable summary per alternative: what it opens
 * with, how far it's been taken, which models produced it, and whether it's the one currently
 * active on the visible path — everything the sheet needs to let a human *pick*, never a computed
 * recommendation (this app's thesis is human-as-aggregator by default; see
 * [dev.fonebrew.domain.council.Council]'s own KDoc for the model-as-aggregator opt-in counterpart).
 *
 * Pure and tree-only, same shape as [PathView] and [Conversations] — no Android, JVM-tested.
 */
object SiblingCompares {

    /** Characters of an alternative's opening message kept before the ellipsis — mirrors
     *  [dev.fonebrew.domain.bridge.SummaryBridges]' own bullet-truncation convention, doubled (a
     *  compare card is a bigger surface than a one-line carry-forward bullet). */
    private const val PREVIEW_CHARS = 160
    private const val ELLIPSIS = "…"

    /**
     * @property branchNodeId the fork point being compared (the node whose children are the
     *   alternatives).
     * @property alternatives one entry per direct child of [branchNodeId], in the same oldest-
     *   first order [MessageTree.childrenOf] returns (so index position matches
     *   [PathView.Step.activeAlternative]'s 1-based indexing minus one).
     */
    data class Compare(
        val branchNodeId: String,
        val alternatives: List<Alternative>,
    )

    /**
     * @property childId the immediate child under [Compare.branchNodeId] — this alternative's
     *   first turn.
     * @property leafId [childId] descended to its branch's current tip ([MessageTree.descendToLeaf]) —
     *   what "Continue with this" ([dev.fonebrew.ui.ChatViewModel.branchFrom]) should target, so
     *   continuing lands on the alternative's own newest turn rather than its fork point.
     * @property isActive true when [childId] is the alternative the currently-visible path
     *   actually follows.
     * @property preview the opening child's content, truncated to [PREVIEW_CHARS] — the honest
     *   verbatim excerpt a compare card leads with (never a fabricated summary — same "excerpt,
     *   not invention" discipline [dev.fonebrew.domain.bridge.SummaryBridges.selectCarryForward] uses).
     * @property turnCount how many turns (nodes) run from [childId] to [leafId] inclusive.
     * @property lastUpdatedAt [leafId]'s `createdAt` — when this alternative was last added to.
     * @property modelIds distinct models that produced a turn along this alternative, in
     *   first-use order.
     */
    data class Alternative(
        val childId: String,
        val leafId: String,
        val isActive: Boolean,
        val preview: String,
        val turnCount: Int,
        val lastUpdatedAt: Long,
        val modelIds: List<String>,
    )

    /**
     * Builds the [Compare] for [branchNodeId]. [activeChildId] — the child the visible path
     * currently descends through, if any — marks [Alternative.isActive]; pass null when nothing is
     * active yet (e.g. the branch point itself is the leaf).
     *
     * @return a [Compare] with an empty [Compare.alternatives] list when [branchNodeId] is unknown
     *   or is not itself a fork (fewer than two children) — callers gate the "Compare alternatives"
     *   affordance on [PathView.Step.isBranchPoint] already, so this stays a defensive default
     *   rather than throwing.
     */
    fun build(
        tree: MessageTree,
        branchNodeId: String,
        activeChildId: String?,
    ): Compare {
        val children = tree.childrenOf(branchNodeId)
        val alternatives = children.map { child ->
            val leafId = tree.descendToLeaf(child.id) ?: child.id
            val segment = tree.pathToRoot(leafId).dropWhile { it.id != child.id }
            Alternative(
                childId = child.id,
                leafId = leafId,
                isActive = child.id == activeChildId,
                preview = truncate(child.content),
                turnCount = segment.size,
                lastUpdatedAt = segment.lastOrNull()?.createdAt ?: child.createdAt,
                modelIds = segment.mapNotNull { it.modelId }.distinct(),
            )
        }
        return Compare(branchNodeId = branchNodeId, alternatives = alternatives)
    }

    private fun truncate(text: String): String {
        val collapsed = text.replace(Regex("\\s+"), " ").trim()
        return if (collapsed.length > PREVIEW_CHARS) collapsed.substring(0, PREVIEW_CHARS) + ELLIPSIS else collapsed
    }
}
