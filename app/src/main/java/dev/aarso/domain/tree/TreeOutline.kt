package dev.aarso.domain.tree

import dev.aarso.domain.MessageNode

/**
 * Flattens the whole message tree into an indented outline for a native tree view
 * (handoff §8.5). A depth-first walk from the root(s) yields rows tagged with
 * depth, whether they sit on the active path, and whether they are a branch point
 * — enough to render and navigate the three axes without a WebView. Pure, tested.
 */
object TreeOutline {

    data class Row(
        val node: MessageNode,
        val depth: Int,
        val onActivePath: Boolean,
        val isLeaf: Boolean,
        val childCount: Int,
    ) {
        val isBranchPoint: Boolean get() = childCount > 1
    }

    fun build(tree: MessageTree, activeLeafId: String?): List<Row> {
        val onPath: Set<String> =
            activeLeafId?.let { tree.pathToRoot(it).mapTo(HashSet()) { n -> n.id } } ?: emptySet()
        val out = mutableListOf<Row>()
        fun walk(node: MessageNode, depth: Int) {
            val children = tree.childrenOf(node.id)
            out += Row(node, depth, node.id in onPath, children.isEmpty(), children.size)
            for (c in children) walk(c, depth + 1)
        }
        tree.roots().forEach { walk(it, 0) }
        return out
    }
}
