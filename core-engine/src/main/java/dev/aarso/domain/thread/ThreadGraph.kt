package dev.aarso.domain.thread

import java.time.Instant

/**
 * A snapshot of the conversation topology as a graph (`schemas/thread/thread-graph.schema.json`)
 * — messages, fork/spawn roots, markers, and delegations as [ThreadGraphNode]s; replies/forks/
 * spawns/lineage/anchors as [ThreadGraphEdge]s.
 *
 * Forward pointer to THREAD_TOPOLOGY_PLAN.md WP9's `ThreadGraphProjector` — fixed here, WP0-
 * first, per that plan's own "contracts first" Definition of Ready. **No consumer wired in yet**
 * (WP9 is out of this session's scope); this type and [dev.aarso.domain.thread.ThreadCodec]'s
 * `encodeThreadGraph`/`decodeThreadGraph` exist so the shape is fixed before WP9's implementation
 * lands, same "no consumer wired in yet" pattern `dev.aarso.di.AppContainer`'s own comments use
 * throughout. Per binding constraint 3 (Issue #2, in advance): a future `ThreadGraphProjector`
 * MUST read Room stores, never `AarsoEventLog` — this is a plain structural snapshot, carrying no
 * drift/idiolect interpretation.
 */
data class ThreadGraph(
    val generatedAtUtc: Instant,
    val nodes: List<ThreadGraphNode> = emptyList(),
    val edges: List<ThreadGraphEdge> = emptyList(),
    val schemaVersion: String = "1.0.0",
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
)

data class ThreadGraphEdge(
    val from: String,
    val to: String,
    val kind: ThreadEdgeKind,
)

enum class ThreadNodeKind {
    MESSAGE,
    FORK_ROOT,
    SPAWN_ROOT,
    MARKER,
    DELEGATION,
}

enum class ThreadEdgeKind {
    REPLY,
    FORK,
    SPAWN,
    LINEAGE,
    MARKER_ANCHOR,
    DELEGATION_ANCHOR,
}
