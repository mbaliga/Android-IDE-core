package dev.aarso.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Per-tokenizer token count for a node (handoff §2/§3).
 *
 * Token counts are **not** a single number on the node: the same text tokenizes
 * to different counts under different models' tokenizers, so each count is keyed
 * by the tokenizer that produced it. The composite primary key (nodeId,
 * tokenizerId) lets a node accumulate counts from every model that ever touched
 * the path.
 */
@Entity(
    tableName = "token_counts",
    primaryKeys = ["nodeId", "tokenizerId"],
    foreignKeys = [
        ForeignKey(
            entity = MessageNodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["nodeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("nodeId")],
)
data class TokenCountEntity(
    val nodeId: String,
    val tokenizerId: String,
    val count: Int,
)
