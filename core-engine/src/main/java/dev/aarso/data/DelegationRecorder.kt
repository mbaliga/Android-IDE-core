package dev.aarso.data

import dev.aarso.domain.mirror.AarsoEventKind
import dev.aarso.domain.mirror.AarsoEventLog
import dev.aarso.domain.thread.DelegationEvent
import dev.aarso.domain.thread.DelegationKind
import dev.aarso.domain.thread.DelegationOutcome
import dev.aarso.domain.thread.ThreadCodec
import dev.aarso.domain.thread.ThreadEvent
import dev.aarso.domain.thread.ThreadEventKind
import java.time.Instant
import java.util.UUID

/**
 * THREAD_TOPOLOGY_PLAN.md WP8's shared "record twice" writer for a delegation ("choose for me")
 * action — the same split every other thread-topology writer uses (see e.g.
 * [dev.aarso.ui.ChatViewModel.forkFrom]'s KDoc): a queryable [DelegationStore] row, plus an inert,
 * write-only [AarsoEventLog] line (Issue #2 — never read back). Pulled out to its own small class
 * rather than a private `ChatViewModel` method because [dev.aarso.ui.loops.LoopRoom]'s
 * `GATEWAY_AUTO` surface needs the exact same two writes and isn't a `ChatViewModel` caller.
 */
class DelegationRecorder(
    private val store: DelegationStore,
    private val log: AarsoEventLog,
) {

    /** Records a new delegation (PENDING) in both the queryable store and the write-only log. */
    suspend fun record(
        kind: DelegationKind,
        rootId: String? = null,
        anchorMsgId: String? = null,
        chosenRef: String? = null,
        alternatives: List<String> = emptyList(),
        now: Long = System.currentTimeMillis(),
    ): DelegationEvent {
        val event = store.record(kind, rootId, anchorMsgId, chosenRef, alternatives, now)
        log.record(AarsoEventKind.DELEGATION, ThreadCodec.encodeThreadEvent(wireEvent(event, now)).toString(), now = now)
        return event
    }

    /** Moves a PENDING delegation to KEPT/REVERTED (see [DelegationStore.resolveOutcome]) and
     *  logs the resolution the same way [record] logs the creation. */
    suspend fun resolveOutcome(event: DelegationEvent, outcome: DelegationOutcome, now: Long = System.currentTimeMillis()) {
        store.resolveOutcome(event, outcome, now)
        val resolved = event.copy(outcome = outcome, outcomeAt = now)
        log.record(AarsoEventKind.DELEGATION, ThreadCodec.encodeThreadEvent(wireEvent(resolved, now)).toString(), now = now)
    }

    private fun wireEvent(event: DelegationEvent, now: Long): ThreadEvent = ThreadEvent(
        eventId = UUID.randomUUID().toString(),
        kind = ThreadEventKind.DELEGATION,
        occurredAtUtc = Instant.ofEpochMilli(now),
        rootId = event.rootId,
        anchorMsgId = event.anchorMsgId,
        delegationId = event.id,
        delegationKind = event.kind,
        chosenRef = event.chosenRef,
        alternatives = event.alternatives,
        outcome = event.outcome,
        outcomeAt = event.outcomeAt?.let { Instant.ofEpochMilli(it) },
    )
}
