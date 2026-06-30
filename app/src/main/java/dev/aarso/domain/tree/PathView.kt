package dev.aarso.domain.tree

import dev.aarso.domain.MessageNode

/**
 * Annotates a root→leaf path with the branching choices made along it, so the UI
 * can make forks legible (handoff §2, branching axis): at each step the user
 * *chose one* continuation among the node's children, and that choice can be
 * switched.
 *
 * Pure and tree-only so it is unit-testable without Android.
 */
object PathView {

    /**
     * @property node the message at this step of the path.
     * @property alternativeCount how many children [node] has — i.e. how many
     *   alternative continuations exist at this point (1 = no fork).
     * @property activeAlternative 1-based index of the child that the current
     *   path actually follows, within [node]'s children ordered oldest-first; 0
     *   when [node] is the leaf (no continuation chosen yet).
     */
    data class Step(
        val node: MessageNode,
        val alternativeCount: Int,
        val activeAlternative: Int,
    ) {
        val isBranchPoint: Boolean get() = alternativeCount > 1
    }

    fun annotate(tree: MessageTree, leafId: String): List<Step> {
        val path = tree.pathToRoot(leafId)
        return path.mapIndexed { index, node ->
            val children = tree.childrenOf(node.id)
            val next = path.getOrNull(index + 1)
            val active = if (next == null) 0 else children.indexOfFirst { it.id == next.id } + 1
            Step(
                node = node,
                alternativeCount = children.size,
                activeAlternative = active,
            )
        }
    }
}
