package dev.fonebrew.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.fonebrew.domain.curation.BookmarkKind

/** Room representation of [dev.fonebrew.domain.curation.MessageBookmark]. Indexed by [msgId] since every read is "bookmarks for this message" or "bookmarks for this conversation." */
@Entity(tableName = "message_bookmarks", indices = [Index("msgId")])
data class MessageBookmarkEntity(
    @PrimaryKey val id: String,
    val msgId: String,
    val blockIndex: Int?,
    val kind: BookmarkKind,
    val note: String?,
    val label: String?,
    val at: Long,
)
