package dev.aarso.domain

/**
 * A conversation is the path from the root to a chosen leaf (handoff §2). It is
 * a *view* over the tree, not a stored object: choosing a different leaf yields a
 * different conversation over the same nodes.
 *
 * [nodes] is ordered root-first.
 */
data class Conversation(
    val nodes: List<MessageNode>,
) {
    val leaf: MessageNode? get() = nodes.lastOrNull()
    val root: MessageNode? get() = nodes.firstOrNull()
    val isEmpty: Boolean get() = nodes.isEmpty()
}
