package dev.fonebrew.domain.thread

import java.time.Instant

/**
 * Wire shape for the five new [dev.fonebrew.domain.mirror.AarsoEventKind] values
 * (`schemas/thread/thread-event.schema.json`) — `FORK_CREATED`, `SPAWN_CREATED`, `CHAPTER_MARK`,
 * `SESSION_START`, `DELEGATION`. `java.time.Instant`-based (not `Long`, unlike [ThreadMarker]/
 * [DelegationEvent]) because this is a pure contracts-layer payload meant to be carried inside a
 * `dev.fonebrew.contracts.common.ContractEnvelope<T>` the same way `ErrorEnvelope` is — mirroring
 * `dev.fonebrew.domain.contracts.EnvelopeCodec`'s own convention, not `CurationStore`'s Room-facing
 * one. Per binding constraint 3 (Issue #2): this documents the log's APPEND shape only — nothing
 * here computes, interprets, or reads `AarsoEventLog` back.
 *
 * Which kind-specific field group is populated (and required) is fixed by [kind] — mirrors
 * `schemas/workspace/buffer-journal-entry.schema.json`'s `opType`-conditional field pattern.
 * The `DELEGATION` variant's `outcome`/`outcomeAt` double as the wire shape for [DelegationEvent]
 * (one JSON shape, two consumers: the inert append-only log, and the queryable Room store).
 */
data class ThreadEvent(
    val eventId: String,
    val kind: ThreadEventKind,
    val occurredAtUtc: Instant,
    val rootId: String? = null,
    val anchorMsgId: String? = null,
    // FORK_CREATED / SPAWN_CREATED lineage fields.
    val srcRootId: String? = null,
    val srcNodeId: String? = null,
    val newRootId: String? = null,
    // CHAPTER_MARK / SESSION_START marker fields.
    val markerId: String? = null,
    val label: String? = null,
    // DELEGATION fields.
    val delegationId: String? = null,
    val delegationKind: DelegationKind? = null,
    val chosenRef: String? = null,
    val alternatives: List<String> = emptyList(),
    val outcome: DelegationOutcome? = null,
    val outcomeAt: Instant? = null,
    val schemaVersion: String = "1.0.0",
    val unknownFields: Map<String, Any?> = emptyMap(),
) {
    init {
        require(schemaVersion.matches(Regex("^1\\.\\d+\\.\\d+$"))) {
            "ThreadEvent.schemaVersion must be major version 1 (got '$schemaVersion') — " +
                "a reader MUST reject an unsupported major before construction (FB-RAT-COM-003)."
        }
        require(eventId.isNotBlank()) { "ThreadEvent.eventId must be non-blank (FB-RAT-COM-002)." }
        when (kind) {
            ThreadEventKind.FORK_CREATED, ThreadEventKind.SPAWN_CREATED -> {
                require(!srcRootId.isNullOrBlank() && !srcNodeId.isNullOrBlank() && !newRootId.isNullOrBlank()) {
                    "ThreadEvent: $kind requires non-blank srcRootId/srcNodeId/newRootId."
                }
            }
            ThreadEventKind.CHAPTER_MARK -> {
                require(!markerId.isNullOrBlank() && !label.isNullOrBlank() && !anchorMsgId.isNullOrBlank()) {
                    "ThreadEvent: CHAPTER_MARK requires non-blank markerId/label/anchorMsgId."
                }
            }
            ThreadEventKind.SESSION_START -> {
                require(!markerId.isNullOrBlank()) { "ThreadEvent: SESSION_START requires a non-blank markerId." }
            }
            ThreadEventKind.DELEGATION -> {
                require(!delegationId.isNullOrBlank() && delegationKind != null && outcome != null) {
                    "ThreadEvent: DELEGATION requires delegationId/delegationKind/outcome."
                }
            }
        }
        if (outcome == DelegationOutcome.KEPT || outcome == DelegationOutcome.REVERTED) {
            require(outcomeAt != null) { "ThreadEvent: a resolved outcome ($outcome) requires a non-null outcomeAt." }
        }
    }
}

/** THREAD_TOPOLOGY_PLAN.md's five new AarsoEventKind values, verbatim. */
enum class ThreadEventKind {
    FORK_CREATED,
    SPAWN_CREATED,
    CHAPTER_MARK,
    SESSION_START,
    DELEGATION,
}
