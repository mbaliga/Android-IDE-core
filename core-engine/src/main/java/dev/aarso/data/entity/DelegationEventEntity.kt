package dev.aarso.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.aarso.domain.thread.DelegationKind
import dev.aarso.domain.thread.DelegationOutcome

/**
 * Room representation of [dev.aarso.domain.thread.DelegationEvent] (THREAD_TOPOLOGY_PLAN.md's
 * `delegation_events` table). Indexed by [rootId] (per-conversation delegation lists, the
 * descriptive "delegated N · kept N · reverted N" card a later work package mounts) and
 * [outcome] (the PENDING-only query `DelegationOutcomes.correlate`, WP8, needs to find
 * unresolved delegations to check).
 */
@Entity(tableName = "delegation_events", indices = [Index("rootId"), Index("outcome")])
data class DelegationEventEntity(
    @PrimaryKey val id: String,
    val at: Long,
    val kind: DelegationKind,
    val rootId: String?,
    val anchorMsgId: String?,
    val chosenRef: String?,
    val alternatives: List<String>,
    val outcome: DelegationOutcome,
    val outcomeAt: Long?,
)
