package dev.aarso.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.aarso.domain.curation.Fidelity

/** Room representation of [dev.aarso.domain.curation.CompactionDirective]. [msgId] is the primary key — at most one directive per message; setting a new fidelity replaces the row. */
@Entity(tableName = "compaction_directives")
data class CompactionDirectiveEntity(
    @PrimaryKey val msgId: String,
    val mustInclude: Boolean,
    val fidelity: Fidelity,
)
