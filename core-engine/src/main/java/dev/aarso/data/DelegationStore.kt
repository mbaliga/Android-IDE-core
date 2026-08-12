package dev.aarso.data

import dev.aarso.data.dao.DelegationEventDao
import dev.aarso.data.entity.DelegationEventEntity
import dev.aarso.domain.thread.DelegationEvent
import dev.aarso.domain.thread.DelegationKind
import dev.aarso.domain.thread.DelegationOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * THREAD_TOPOLOGY_PLAN.md WP1's data gateway for [dev.aarso.domain.thread.DelegationEvent] —
 * the four "choose for me" surfaces (model-picks-at-branch-point, council auto-merge acceptance,
 * loop gateway auto-choice, accepted auto-defaults) unified under one concept (owner decision 2).
 * Fronts [DelegationEventDao] with one store, same shape [dev.aarso.data.CurationStore] uses.
 *
 * `outcome` is tracked **descriptively only** here — this store never interprets KEPT/REVERTED,
 * it only records whatever [dev.aarso.domain.thread.DelegationOutcomes.correlate] (a later work
 * package, WP8) decides. Per THREAD_TOPOLOGY_PLAN.md binding constraint 3 / CLAUDE.md rule 4.
 */
class DelegationStore(private val dao: DelegationEventDao) {

    val delegations: Flow<List<DelegationEvent>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun forRoot(rootId: String): List<DelegationEvent> = dao.forRoot(rootId).map { it.toDomain() }

    /** Records a new delegation at the moment a "choose for me" surface acts. */
    suspend fun record(
        kind: DelegationKind,
        rootId: String? = null,
        anchorMsgId: String? = null,
        chosenRef: String? = null,
        alternatives: List<String> = emptyList(),
        now: Long = System.currentTimeMillis(),
    ): DelegationEvent {
        val event = DelegationEvent(
            id = UUID.randomUUID().toString(),
            at = now,
            kind = kind,
            rootId = rootId,
            anchorMsgId = anchorMsgId,
            chosenRef = chosenRef,
            alternatives = alternatives,
            outcome = DelegationOutcome.PENDING,
        )
        dao.insert(event.toEntity())
        return event
    }

    /** Resolves a pending delegation's outcome — the store-level write side of `DelegationOutcomes.correlate` (WP8). */
    suspend fun resolveOutcome(event: DelegationEvent, outcome: DelegationOutcome, now: Long = System.currentTimeMillis()) {
        require(outcome != DelegationOutcome.PENDING) { "resolveOutcome must move a delegation OUT of PENDING, got $outcome." }
        dao.update(event.copy(outcome = outcome, outcomeAt = now).toEntity())
    }
}

private fun DelegationEventEntity.toDomain() = DelegationEvent(
    id = id,
    at = at,
    kind = kind,
    rootId = rootId,
    anchorMsgId = anchorMsgId,
    chosenRef = chosenRef,
    alternatives = alternatives,
    outcome = outcome,
    outcomeAt = outcomeAt,
)

private fun DelegationEvent.toEntity() = DelegationEventEntity(
    id = id,
    at = at,
    kind = kind,
    rootId = rootId,
    anchorMsgId = anchorMsgId,
    chosenRef = chosenRef,
    alternatives = alternatives,
    outcome = outcome,
    outcomeAt = outcomeAt,
)
