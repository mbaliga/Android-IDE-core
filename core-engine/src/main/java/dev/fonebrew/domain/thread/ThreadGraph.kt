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
    // ThreadGraphNode.outcome, ThreadGraphNode.confidence. 1.2.0 (graph-wave lane A):
    // ThreadGraphEdge.derivation/because (EXTRACTED|INFERRED provenance, adapted from the
    // Graphify codebase-knowledge-graph tool — see thread-graph.schema.json's own $comment),
    // the COMMIT node kind + COMMIT_ANCHOR edge kind (commit-anchor vocabulary, schema only —
    // no producer mints one yet). Still major 1 — every field the shape grew is additive and
    // optional, so a 1.0.0 reader that ignores unknown enum values it doesn't recognize (or
    // simply doesn't ask for the new optional fields) degrades, it doesn't break; see
    // thread-graph.schema.json's own changelog notes.
    val schemaVersion: String = "1.2.0",
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
    /** 1.2.0. COMMIT only: the git commit's own sha this node records — the node's real stable
     *  identity fact (FB-RAT-COM-002); [id] need not literally equal [sha] (a future minter may
     *  namespace it), but this is always the raw commit hash. Null for every other kind and
     *  never fabricated. Schema only in 1.2.0 — no producer mints a COMMIT node yet. */
    val sha: String? = null,
    /** 1.2.0. COMMIT only, optional: a short display label naming the repo/ref the commit
     *  belongs to (e.g. "owner/repo@main") — convenience only, [sha] is the identity field. */
    val repoRef: String? = null,
) {
    init {
        require(confidence == null || confidence in 0.0..1.0) {
            "ThreadGraphNode.confidence must be in [0,1] when present (got $confidence)."
        }
        require(kind != ThreadNodeKind.COMMIT || !sha.isNullOrBlank()) {
            "ThreadGraphNode: COMMIT requires a non-blank sha (got '$sha')."
        }
    }
}

/**
 * 1.2.0: [derivation]/[because] adapt the Graphify codebase-knowledge-graph tool's own
 * EXTRACTED-vs-INFERRED edge tagging (see `thread-graph.schema.json`'s top-level `$comment` for
 * the full rationale, stated honestly as an adaptation, not marketed as original) — every edge
 * is tagged as either restating a fact already recorded elsewhere, or a relationship a future
 * analysis layer derived, and every edge names *why* it exists. Both fields are optional/
 * nullable for the exact reason [ThreadGraphNode.outcome]/[ThreadGraphNode.confidence] were in
 * 1.1.0: an edge from a 1.0.x/1.1.x snapshot predates them and MUST keep decoding, never be
 * treated as malformed for lacking a field it was minted before. [ThreadGraphProjector] (the one
 * producer today) always populates both — see its own KDoc — but the wire contract itself does
 * not require it, so old data keeps working.
 */
data class ThreadGraphEdge(
    val from: String,
    val to: String,
    val kind: ThreadEdgeKind,
    val derivation: EdgeDerivation? = null,
    /** Short, human-readable, PURELY STRUCTURAL explanation of why this edge exists (e.g. "reply
     *  recorded in the message tree"). Non-blank when present; MUST NEVER interpret the user's
     *  phrasing, tone, or intent (binding constraint 3 / Issue #2) — see
     *  `fixtures/thread/adversarial` for a worked example of the line this crosses. */
    val because: String? = null,
) {
    init {
        require((derivation == null) == (because == null)) {
            "ThreadGraphEdge: derivation and because must be both present or both absent " +
                "(got derivation=$derivation, because=$because) — 1.2.0 ships them as a pair; " +
                "a half-populated edge is a malformed provenance claim, not a legitimately-old one."
        }
        require(because == null || because.isNotBlank()) {
            "ThreadGraphEdge.because must be non-blank when present (got '$because')."
        }
    }
}

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
    /** 1.2.0: a git commit a message/run node's agent action minted — see
     *  [ThreadGraphNode.sha]/[ThreadGraphNode.repoRef]. Schema only: no producer mints or
     *  projects one yet (a future work package's scope). */
    COMMIT,
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
    /** 1.2.0: a message/run node's edge to the [ThreadNodeKind.COMMIT] node its agent action
     *  minted — `from` is the message/run node, `to` is the COMMIT node, deliberately the
     *  REVERSE direction of the three `*_ANCHOR` kinds above (those point FROM the annotation TO
     *  the message they describe; a commit is the forward, causal OUTCOME of the message/run's
     *  own action, so the edge points the other way). Schema only: no producer emits one yet. */
    COMMIT_ANCHOR,
}

/** 1.2.0. See [ThreadGraphEdge]'s own KDoc for the full Graphify-adaptation rationale. */
enum class EdgeDerivation {
    /** This edge restates a fact already recorded in the tree/Room stores (a reply's
     *  parent-child pointer, a fork/spawn's lineage metadata, a marker/decision/delegation/
     *  commit's own anchor field). The only value [ThreadGraphProjector] ever emits. */
    EXTRACTED,
    /** A future analysis layer computed this relationship rather than reading it off an
     *  existing record. Nothing in this repo emits this value yet (binding constraint 3 /
     *  Issue #2 — structure, never interpretation, is the bar any future emitter must clear). */
    INFERRED,
}
