package dev.aarso.embedding

import dev.aarso.data.Converters
import dev.aarso.data.MessageTreeRepository
import dev.aarso.data.entity.MessageEmbeddingEntity
import dev.aarso.domain.MessageNode
import dev.aarso.domain.Role

/**
 * Cold-start logging (handoff §5c): embeds the user's own messages from day one
 * so a baseline exists when the self-observation / drift features ship. "Change"
 * is meaningless without a baseline, so this runs in Phase 0 even though no
 * metric consumes the vectors yet.
 *
 * Scope is deliberately the *user's* messages — the corpus whose drift toward
 * model register is the research subject. The retrospective-only constraint (§5c)
 * lives in the future review UI, not here: logging is silent and never surfaces a
 * live score.
 */
class EmbeddingLogger(
    private val embedder: Embedder,
    private val repository: MessageTreeRepository,
) {

    suspend fun onMessageInserted(node: MessageNode) {
        if (node.role != Role.USER) return
        val vector = embedder.embed(node.content)
        repository.putEmbedding(
            MessageEmbeddingEntity(
                nodeId = node.id,
                embedderId = embedder.id,
                dim = embedder.dim,
                vector = Converters.floatsToBytes(vector),
                createdAt = node.createdAt,
            ),
        )
    }
}
