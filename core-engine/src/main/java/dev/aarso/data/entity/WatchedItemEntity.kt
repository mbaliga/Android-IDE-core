package dev.aarso.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.aarso.domain.watch.WatchKind

/**
 * Room representation of a watched item (CORE_PHASES.md §"Data models", P2): renewals,
 * expiries, and status flags a user wants legibility on — never something the app acts on.
 * [amountText] is freeform and user-editable; never asserted as a current fact.
 */
@Entity(tableName = "watched_items")
data class WatchedItemEntity(
    @PrimaryKey val id: String,
    val label: String,
    val kind: WatchKind,
    val dueAt: Long? = null,
    val note: String = "",
    val amountText: String? = null,
    val snoozedUntil: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
