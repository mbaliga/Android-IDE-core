package dev.aarso.domain.tree

import dev.aarso.domain.MessageNode
import dev.aarso.domain.Role

/**
 * Factory for new tree nodes. Kept pure (id and clock injected) so node creation
 * is deterministic under test. Appending is the only mutation the spine allows:
 * branching and "restore to a touchpoint" are both just [child] calls against an
 * earlier node (handoff §2).
 */
object Nodes {

    /**
     * Create a child of [parent] (or a root when [parent] is null).
     *
     * @param idGen supplies the new node id (defaults to a random UUID).
     * @param now wall-clock millis for [MessageNode.createdAt].
     */
    fun child(
        parent: MessageNode?,
        role: Role,
        content: String,
        now: Long,
        modelId: String? = null,
        tokenCounts: Map<String, Int> = emptyMap(),
        metadata: Map<String, String> = emptyMap(),
        idGen: () -> String = { java.util.UUID.randomUUID().toString() },
    ): MessageNode = MessageNode(
        id = idGen(),
        parentId = parent?.id,
        role = role,
        content = content,
        modelId = modelId,
        createdAt = now,
        tokenCounts = tokenCounts,
        metadata = metadata,
    )
}
