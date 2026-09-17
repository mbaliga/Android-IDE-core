package dev.fonebrew.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.fonebrew.domain.thread.ThreadMarkerKind
import dev.fonebrew.domain.thread.ThreadMarkerSource

/**
 * Room representation of [dev.fonebrew.domain.thread.ThreadMarker] (THREAD_TOPOLOGY_PLAN.md's
 * `thread_markers` table). Indexed by [rootId] (every read is "markers for this conversation")
 * and [anchorMsgId] (the curation-sheet-style "is this message already a chapter boundary"
 * lookup, same shape [dev.fonebrew.data.entity.VersionEntity]'s `branchTipMsgId` index serves).
 */
@Entity(tableName = "thread_markers", indices = [Index("rootId"), Index("anchorMsgId")])
data class ThreadMarkerEntity(
    @PrimaryKey val id: String,
    val rootId: String,
    val anchorMsgId: String?,
    val kind: ThreadMarkerKind,
    val label: String?,
    val note: String?,
    val at: Long,
    val source: ThreadMarkerSource,
    val payloadJson: String?,
)
