package dev.fonebrew.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.fonebrew.domain.curation.GhostReason

/** Room representation of [dev.fonebrew.domain.curation.GhostBranch]. [branchTipMsgId] is the primary key — the (formerly active) leaf node id the ghost annotation is about. */
@Entity(tableName = "ghost_branches")
data class GhostBranchEntity(
    @PrimaryKey val branchTipMsgId: String,
    val rewoundAt: Long,
    val reason: GhostReason,
)
