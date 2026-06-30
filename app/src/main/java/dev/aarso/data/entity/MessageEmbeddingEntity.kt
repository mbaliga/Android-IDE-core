package dev.aarso.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * An embedding vector for a message node.
 *
 * Cold-start (handoff §5c): the app logs embeddings of the user's own messages
 * *from day one* — before any drift/self-observation metric is built — so that a
 * baseline exists when those features ship. "Change" is meaningless without a
 * baseline, hence this table is populated in Phase 0.
 *
 * Provenance-tagged: [embedderId] and [dim] record which embedder produced the
 * vector, so a later embedder swap is legible rather than silently mixing
 * incomparable vector spaces. The vector itself is a little-endian float32 BLOB.
 */
@Entity(
    tableName = "message_embeddings",
    foreignKeys = [
        ForeignKey(
            entity = MessageNodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["nodeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class MessageEmbeddingEntity(
    @PrimaryKey val nodeId: String,
    val embedderId: String,
    val dim: Int,
    val vector: ByteArray,
    val createdAt: Long,
) {
    // ByteArray needs structural equals/hashCode for data-class correctness.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageEmbeddingEntity) return false
        return nodeId == other.nodeId &&
            embedderId == other.embedderId &&
            dim == other.dim &&
            createdAt == other.createdAt &&
            vector.contentEquals(other.vector)
    }

    override fun hashCode(): Int {
        var result = nodeId.hashCode()
        result = 31 * result + embedderId.hashCode()
        result = 31 * result + dim
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + vector.contentHashCode()
        return result
    }
}
