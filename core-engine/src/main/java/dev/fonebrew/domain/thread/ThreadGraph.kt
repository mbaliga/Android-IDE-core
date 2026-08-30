package dev.fonebrew.domain.thread

import java.time.Instant

/**
 * A snapshot of the conversation topology as a graph (`schemas/thread/thread-graph.schema.json`)
 * — messages, fork/spawn roots, markers, and delegations as [ThreadGraphNode]s; replies/forks/
 * spawns/lineage/anchors as [ThreadGraphEdge]s.
 *
 * Forward pointer to THREAD_TOPOLOGY_PLAN.md WP9's `ThreadGraphProjector` — fixed here, WP0-
 * first, per that plan's own "contracts first" Definition of Ready. **No consumer wired in yet**
 * (WP9 is out of this session's scope); this type and [dev.fonebrew.domain.thread.ThreadCodec]'s
 * `encodeThreadGraph`/`decodeThreadGraph` exist so the shape is fixed before WP9's implementation
 * lands, same "no consumer wired in yet" pattern `dev.fonebrew.di.AppContainer`'s own comments use
 * throughout. Per binding constraint 3 (Issue #2, in advance): a future `ThreadGraphProjector`
 * MUST read Room stores, never `AarsoEventLog` — this is a plain structural snapshot, carrying no
 * drift/idiolect interpretation.
 */
data class ThreadGraph(
    val generatedAtUtc: Instant,
    val nodes: List<ThreadGraphNode> = emptyList(),
    val edges: List<ThreadGraphEdge> = emptyList(),
    // 1.1.0 (2026-08-29 audit): DECISION/RUN_ROOT node kinds, DECISION_ANCHOR edge kind,
    // ThreadGraphNode.outcome, ThreadGraphNode.confidence. Still major 1 — every field the
    // shape grew is additive and optional, so a 1.0.0 reader that ignores unknown enum values
    // it doesn't recognize (or simply doesn't ask for the new optional fields) degrades, it
    // doesn't break; see thread-graph.schema.json's own 1.1.0 changelog note.
    val schemaVersion: String = "1.1.0",
    val unknownFields: Map<String, Any?> = emptyMap(),
) {
    init {
        require(schemaVersion.matches(Regex("^1\\.\\d+\\.\\d+$"))) {
            "ThreadGraph.schemaVersion must be major version 1 (got '$schemaVersion') — " +
                "a reader MUST reject an unsupported major before construction (FB-RAT-COM-003)."
        }
    }
}

data class ThreadGraphNode(
    val id: String,
    val kind: ThreadNodeKind,
    val rootId: String,
    val parentId: String? = null,
    val at: Instant,
    val label: String? = null,
    /** DELEGATION only (1.1.0): the delegation's current [DelegationOutcomes.correlate] verdict,
     *  carried onto the node so the graph itself shows kept/reverted/pending, not just the
     *  underlying store. Null for every other kind — never fabricated. */
    val outcome: DelegationOutcome? = null,
    /** MESSAGE only (1.1.0), and only when captured at generation time (see
     *  [dev.fonebrew.domain.thread.MessageConfidence]): a 0..1 aggregate over this turn's
     *  per-token entropy. Null when the engine reported no entropy (a cloud turn, the Echo
     *  engine) or the message predates this field — never backfilled, never guessed. */
    val confidence: Double? = null,
) {
    init {
        require(confidence == null || confidence in 0.0..1.0) {
            "ThreadGraphNode.confidence must be in [0,1] when present (got $confidence)."
        }
    }
}

data class ThreadGraphEdge(
    val from: String,
    val to: String,
    val kind: ThreadEdgeKind,
)

enum class ThreadNodeKind {
    MESSAGE,
    FORK_ROOT,
    SPAWN_ROOT,
    /** 1.1.0: a loop run's detached-subtree root (RunLog/GraphRunLog write it with `loopRunId`
     *  tree metadata already — this kind only makes that existing fact visible in the graph,
     *  it mints no new storage). */
    RUN_ROOT,
    MARKER,
    /** 1.1.0: a [dev.fonebrew.domain.curation.MessageBookmark] with
     *  [dev.fonebrew.domain.curation.BookmarkKind.DECISION] — a curation-domain fact, distinct
     *  from [MARKER] ([dev.fonebrew.domain.thread.ThreadMarker] has no DECISION kind at all). */
    DECISION,
    DELEGATION,
}

enum class ThreadEdgeKind {
    REPLY,
    FORK,
    SPAWN,
    LINEAGE,
    MARKER_ANCHOR,
    /** 1.1.0: a [ThreadNodeKind.DECISION] node's edge to the message it anchors to. */
    DECISION_ANCHOR,
    DELEGATION_ANCHOR,
}
