package dev.aarso.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room representation of [dev.aarso.domain.curation.Verdict]. [msgId] is the primary key —
 * "one live per message" (STUDIO_UX_SPEC.md §5.1): re-rating a message replaces this row rather
 * than appending a history.
 */
@Entity(tableName = "verdicts")
data class VerdictEntity(
    @PrimaryKey val msgId: String,
    val grade: Int,
    val at: Long,
)
