package dev.aarso.domain.tree

import dev.aarso.domain.MessageNode
import dev.aarso.domain.Role

/**
 * Groups the append-only tree by root into conversations for the map view. The
 * storage stays one tree (handoff §2) — a conversation is just the subtree under
 * one parentless node, which is what "new chat" creates. Pure, tested.
 */
object Conversations {

    data class Summary(
        val rootId: String,
        val title: String,
        /** Distinct models that produced turns in this conversation, in first-use order. */
        val modelIds: List<String>,
        val lastUpdatedAt: Long,
        val nodeCount: Int,
        /** The tip of the most recent branch — where "open" lands. */
        val latestLeafId: String,
        /** True if any turn in the conversation is a generated image (for the Text-only filter). */
        val hasImage: Boolean = false,
    )

    /** One summary per root, newest activity first. */
    fun summarize(tree: MessageTree): List<Summary> =
        tree.roots().map { root ->
            val nodes = subtree(tree, root.id)
            Summary(
                rootId = root.id,
                title = titleOf(nodes),
                modelIds = nodes.sortedBy { it.createdAt }.mapNotNull { it.modelId }.distinct(),
                lastUpdatedAt = nodes.maxOf { it.createdAt },
                nodeCount = nodes.size,
                latestLeafId = tree.descendToLeaf(root.id) ?: root.id,
                hasImage = nodes.any { it.metadata.containsKey(IMAGE_KEY) },
            )
        }.sortedByDescending { it.lastUpdatedAt }

    /** The root of the conversation containing [nodeId], or null if unknown. */
    fun rootOf(tree: MessageTree, nodeId: String): String? =
        tree.pathToRoot(nodeId).firstOrNull()?.id

    /** Metadata key marking a turn whose payload is a generated image file. */
    const val IMAGE_KEY = "image"

    /**
     * Every image turn in the tree, newest first — "browse images" is a filter
     * over nodes (redesign §6), not a separate store.
     */
    fun imageNodes(tree: MessageTree): List<MessageNode> =
        tree.roots()
            .flatMap { subtree(tree, it.id) }
            .filter { it.metadata.containsKey(IMAGE_KEY) }
            .sortedByDescending { it.createdAt }

    private fun subtree(tree: MessageTree, rootId: String): List<MessageNode> {
        val out = mutableListOf<MessageNode>()
        fun walk(node: MessageNode) {
            out += node
            tree.childrenOf(node.id).forEach(::walk)
        }
        tree.node(rootId)?.let(::walk)
        return out
    }

    private const val TITLE_WORDS = 8

    /** First words of the first user turn (a conversation is named by its opening ask). */
    private fun titleOf(nodes: List<MessageNode>): String {
        val opening = nodes.asSequence()
            .filter { it.role == Role.USER }
            .minByOrNull { it.createdAt }
            ?: nodes.minByOrNull { it.createdAt }
        val text = opening?.content.orEmpty().trim()
        if (text.isEmpty()) return "Untitled"
        val words = text.split(Regex("\\s+"))
        return words.take(TITLE_WORDS).joinToString(" ") +
            if (words.size > TITLE_WORDS) "…" else ""
    }
}
