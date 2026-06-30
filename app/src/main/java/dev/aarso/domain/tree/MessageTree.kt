package dev.aarso.domain.tree

import dev.aarso.domain.Conversation
import dev.aarso.domain.MessageNode

/**
 * Pure, in-memory view over a set of message nodes implementing the three axes
 * of the spine (handoff §2):
 *
 *  1. Vertical  — turns within a path ([pathToRoot] / [conversation]).
 *  2. Branching — alternative paths from a node ([childrenOf], [branchPoints]);
 *                 you *choose one* to continue.
 *  3. Lateral   — the council/panel (§5b) is modelled as sibling children of a
 *                 panel node; this class exposes the sibling sets that the
 *                 lateral UI will later hold simultaneously rather than choose
 *                 between.
 *
 * Deliberately free of Android/Room types so the algorithms are unit-testable on
 * the JVM. The data layer constructs one of these from a query snapshot.
 */
class MessageTree(nodes: Collection<MessageNode>) {

    private val byId: Map<String, MessageNode> = nodes.associateBy { it.id }
    private val childrenByParent: Map<String?, List<MessageNode>> =
        nodes.sortedBy { it.createdAt }.groupBy { it.parentId }

    /** Every node in the tree, for whole-tree views (e.g. the graph map). */
    fun allNodes(): List<MessageNode> = byId.values.toList()

    /** All nodes with no parent. A well-formed tree has exactly one. */
    fun roots(): List<MessageNode> = childrenByParent[null].orEmpty()

    fun node(id: String): MessageNode? = byId[id]

    /** Direct children of [id], oldest first. */
    fun childrenOf(id: String): List<MessageNode> = childrenByParent[id].orEmpty()

    /**
     * The path from the root down to [leafId], ordered root-first.
     *
     * Returns an empty list if [leafId] is unknown. Throws if the ancestry is
     * cyclic or references a missing parent — both are corruption, not a normal
     * state, and silently truncating would hide it.
     */
    fun pathToRoot(leafId: String): List<MessageNode> {
        val start = byId[leafId] ?: return emptyList()
        val reversed = ArrayList<MessageNode>()
        val seen = HashSet<String>()
        var current: MessageNode? = start
        while (current != null) {
            check(seen.add(current.id)) { "Cycle detected in message tree at ${current!!.id}" }
            reversed.add(current)
            val parentId = current.parentId ?: break
            current = byId[parentId]
                ?: error("Dangling parent: ${parentId} referenced by ${reversed.last().id}")
        }
        reversed.reverse()
        return reversed
    }

    /** The conversation (handoff §2) ending at [leafId]. */
    fun conversation(leafId: String): Conversation = Conversation(pathToRoot(leafId))

    /**
     * Branch points: any node with more than one child (§2). These are exactly
     * the places where the conversation forked into alternative routes.
     */
    fun branchPoints(): List<MessageNode> =
        childrenByParent.entries
            .filter { it.key != null && it.value.size > 1 }
            .mapNotNull { it.key?.let(byId::get) }

    /**
     * The sibling set a given node belongs to — the alternatives at that branch,
     * including the node itself. For a council/panel node's children this is the
     * lateral set held simultaneously (§5b). Singleton if there is no fork.
     */
    fun siblingsOf(id: String): List<MessageNode> {
        val parentId = byId[id]?.parentId
        return childrenByParent[parentId].orEmpty()
    }

    /**
     * Descend from [nodeId] to a leaf, following the most-recently-created child
     * at each fork. Used when switching to an alternative branch so the view
     * lands on that branch's current tip rather than its fork point. Returns
     * [nodeId] itself if it is already a leaf, or null if unknown.
     */
    fun descendToLeaf(nodeId: String): String? {
        if (!byId.containsKey(nodeId)) return null
        var current = nodeId
        while (true) {
            val children = childrenByParent[current].orEmpty()
            if (children.isEmpty()) return current
            current = children.maxBy { it.createdAt }.id
        }
    }
}
