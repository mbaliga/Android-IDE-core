package dev.aarso.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room representation of one node in the append-only message tree (handoff §2).
 *
 * Append-only: rows are inserted, never updated in place. The self-referencing
 * [parentId] forms the tree; it is indexed because every path/branch query walks
 * it. Deleting a node cascades to its children (a subtree is removed atomically),
 * though Phase 0 never deletes — restore is "add a child to an earlier node".
 */
@Entity(
    tableName = "message_nodes",
    foreignKeys = [
        ForeignKey(
            entity = MessageNodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("parentId")],
)
data class MessageNodeEntity(
    @PrimaryKey val id: String,
    val parentId: String?,
    val role: String,
    val content: String,
    val modelId: String?,
    val createdAt: Long,
    /** Tags / outcome labels / council membership, serialized as a JSON object. */
    val metadataJson: String?,
)
