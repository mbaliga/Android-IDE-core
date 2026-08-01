package dev.aarso.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Room representation of [dev.aarso.domain.curation.Version]. Indexed by [branchTipMsgId] for "is this tip already a version" lookups (curation sheet, L1 flags). */
@Entity(tableName = "versions", indices = [Index("branchTipMsgId")])
data class VersionEntity(
    @PrimaryKey val id: String,
    val branchTipMsgId: String,
    val name: String,
    val note: String?,
    val proofRef: String?,
    val at: Long,
    val suggestedBy: String?,
)
