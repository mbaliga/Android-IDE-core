package dev.aarso.data

import dev.aarso.data.dao.EmbeddingDao
import dev.aarso.data.dao.MessageNodeDao
import dev.aarso.data.dao.TokenCountDao
import dev.aarso.data.entity.MessageEmbeddingEntity
import dev.aarso.data.entity.TokenCountEntity
import dev.aarso.domain.Conversation
import dev.aarso.domain.MessageNode
import dev.aarso.domain.tree.MessageTree
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The single gateway to the message-tree spine (handoff §2). It speaks domain
 * types to the rest of the app and reuses the tested in-memory
 * [MessageTree] for all path/branch logic, so there is one implementation of
 * "what is the conversation ending here" rather than DB queries drifting from
 * the pure algorithms.
 */
class MessageTreeRepository(
    private val nodes: MessageNodeDao,
    private val tokenCounts: TokenCountDao,
    private val embeddings: EmbeddingDao,
) {

    /** Append a node. Append-only: never updates an existing one. */
    suspend fun insert(node: MessageNode) = nodes.insert(node.toEntity())

    suspend fun node(id: String): MessageNode? = nodes.getById(id)?.toDomain()

    /** The whole tree as a reactive in-memory structure. */
    fun observeTree(): Flow<MessageTree> =
        nodes.observeAll().map { rows -> MessageTree(rows.map { it.toDomain() }) }

    /** A one-shot snapshot of the tree, for navigation actions. */
    suspend fun tree(): MessageTree = observeTree().first()

    /** The conversation ending at [leafId], reactively. */
    fun observePath(leafId: String): Flow<Conversation> =
        observeTree().map { it.conversation(leafId) }

    /**
     * One-shot root→leaf path, used to assemble the prompt for a generation. Uses
     * the recursive-CTE query (leaf-first) and reverses to root-first order.
     */
    suspend fun path(leafId: String): List<MessageNode> =
        nodes.pathToRoot(leafId).map { it.toDomain() }.reversed()

    /** Direct children of [parentId] — the alternative routes at a branch. */
    fun observeChildren(parentId: String): Flow<List<MessageNode>> =
        nodes.observeChildren(parentId).map { rows -> rows.map { it.toDomain() } }

    suspend fun setTokenCount(nodeId: String, tokenizerId: String, count: Int) =
        tokenCounts.upsert(TokenCountEntity(nodeId, tokenizerId, count))

    suspend fun tokenCounts(nodeId: String): Map<String, Int> =
        tokenCounts.forNode(nodeId).associate { it.tokenizerId to it.count }

    /** Total tokens recorded for a node across tokenizers (usually one entry). */
    suspend fun totalTokens(nodeId: String): Int =
        tokenCounts.forNode(nodeId).sumOf { it.count }

    suspend fun putEmbedding(embedding: MessageEmbeddingEntity) = embeddings.upsert(embedding)

    suspend fun embeddingCount(): Int = embeddings.count()
}
