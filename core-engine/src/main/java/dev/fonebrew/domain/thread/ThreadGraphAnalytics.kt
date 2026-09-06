// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
package dev.fonebrew.domain.thread

/**
 * **Graph-wave lane B** — descriptive, structural analytics over an already-projected [ThreadGraph]
 * (or, for [chapterSummaries], the same raw [ThreadMarker] list [ThreadGraphProjector] itself takes
 * — see that function's own KDoc for why). Same "extraction-ready plain-snapshot inputs" shape
 * [ThreadGraphProjector]/[ThreadMapLayout] already use: pure, deterministic, JVM-tested,
 * Room/Android-independent. Every number here is a count or a structural fact already recorded in
 * the graph — never a judgment, a "why," or an idiolect/drift signal (CLAUDE.md binding rule 4 /
 * THREAD_TOPOLOGY_PLAN.md binding constraint 3, Issue #2). This file never touches
 * `dev.fonebrew.domain.mirror.AarsoEventLog` (write-only) and never reads anything but the [ThreadGraph]/
 * [ThreadMarker] values a caller already has in hand.
 *
 * ### The one derived relationship this file materializes as an edge: [hotPathEdges]
 * Everything else below returns plain data classes. [hotPathEdges] is the exception — it is the
 * first real producer of [EdgeDerivation.INFERRED] in this repo (1.2.0/graph-wave lane A shipped
 * the vocabulary; `fixtures/thread/adversarial/thread-graph-inferred-edge-interpretive-because`
 * anticipated exactly this kind of future producer and named the obligation it must satisfy).
 * [hotPathEdges] emits ONLY [ThreadEdgeKind.HOT_PATH] edges, ONLY with `derivation =`
 * [EdgeDerivation.INFERRED], and their `because` names the recorded branch point + its recorded
 * continuation count ONLY — never a claim about why the user branched (see
 * `fixtures/thread/adversarial/thread-graph-hot-path-extracted-derivation` for the two-defect
 * fixture this contract exists to reject, and [ThreadGraphAnalyticsTest] for the mechanical check).
 * A caller renders these by merging them onto a `graph.copy(edges = graph.edges + hotPathEdges)` —
 * this file never mutates or re-projects a [ThreadGraph] itself.
 */
object ThreadGraphAnalytics {

    /** Edge kinds that encode real structural succession (a reply, or a fork/spawn root's edge
     *  back to its lineage source) — the same three [ThreadMapLayout] already treats specially for
     *  layout, reused here for degree/branch/ancestry purposes. `*_ANCHOR`/`LINEAGE`/`COMMIT_ANCHOR`/
     *  `HOT_PATH` edges connect an annotation to what it describes, or a derived sibling link — never
     *  "this is the next turn," so they're excluded from every computation below that means
     *  "conversation branching," on purpose. */
    private val STRUCTURAL_EDGE_KINDS = setOf(ThreadEdgeKind.REPLY, ThreadEdgeKind.FORK, ThreadEdgeKind.SPAWN)

    /** Node kinds a "leaf"/"branch point" computation cares about — a conversation turn or a
     *  root that could itself have continuations. Markers/decisions/delegations/commits are
     *  annotations *of* a turn, never a turn with continuations of their own. */
    private val STRUCTURAL_NODE_KINDS = setOf(
        ThreadNodeKind.MESSAGE, ThreadNodeKind.FORK_ROOT, ThreadNodeKind.SPAWN_ROOT, ThreadNodeKind.RUN_ROOT,
    )

    // ---------------------------------------------------------------------------------------
    // 1. Degree / branchiness
    // ---------------------------------------------------------------------------------------

    /** One node's plain edge counts. [branchiness] is extra structural continuations beyond the
     *  first (0 for a node with 0 or 1 structural children — a straight line so far — rising by
     *  one per additional sibling continuation the recorded tree shows); never negative. */
    data class NodeStats(
        val nodeId: String,
        val inDegree: Int,
        val outDegree: Int,
        val branchiness: Int,
    )

    /** [NodeStats] for every node in [graph], sorted by [NodeStats.nodeId] for a deterministic
     *  render order. A node with no edges at all still gets an all-zero entry — never omitted. */
    fun nodeStats(graph: ThreadGraph): List<NodeStats> {
        val inDegree = HashMap<String, Int>()
        val outDegree = HashMap<String, Int>()
        val structuralOutDegree = HashMap<String, Int>()
        for (edge in graph.edges) {
            outDegree[edge.from] = (outDegree[edge.from] ?: 0) + 1
            inDegree[edge.to] = (inDegree[edge.to] ?: 0) + 1
            if (edge.kind in STRUCTURAL_EDGE_KINDS) {
                structuralOutDegree[edge.from] = (structuralOutDegree[edge.from] ?: 0) + 1
            }
        }
        return graph.nodes
            .map { node ->
                NodeStats(
                    nodeId = node.id,
                    inDegree = inDegree[node.id] ?: 0,
                    outDegree = outDegree[node.id] ?: 0,
                    branchiness = ((structuralOutDegree[node.id] ?: 0) - 1).coerceAtLeast(0),
                )
            }
            .sortedBy { it.nodeId }
    }

    // ---------------------------------------------------------------------------------------
    // 2. Decision-outcome rollup — counts only, no judgment language (mirrors DelegationCounts,
    //    but derived from the graph snapshot itself so a caller that only has a ThreadGraph in
    //    hand — GraphRoom, the G6 deep room — never needs a second DelegationEvent query).
    // ---------------------------------------------------------------------------------------

    data class DecisionOutcomeRollup(val decided: Int, val kept: Int, val reverted: Int, val pending: Int)

    val EMPTY_ROLLUP = DecisionOutcomeRollup(decided = 0, kept = 0, reverted = 0, pending = 0)

    /** Every node carrying a non-null [ThreadGraphNode.outcome] (today, only [ThreadNodeKind.DELEGATION]
     *  nodes — see that field's own KDoc), tallied by value. [DecisionOutcomeRollup.decided] is the
     *  total; kept + reverted + pending always sums to it. */
    fun decisionOutcomeRollup(graph: ThreadGraph): DecisionOutcomeRollup {
        val outcomes = graph.nodes.mapNotNull { it.outcome }
        if (outcomes.isEmpty()) return EMPTY_ROLLUP
        return DecisionOutcomeRollup(
            decided = outcomes.size,
            kept = outcomes.count { it == DelegationOutcome.KEPT },
            reverted = outcomes.count { it == DelegationOutcome.REVERTED },
            pending = outcomes.count { it == DelegationOutcome.PENDING },
        )
    }

    // ---------------------------------------------------------------------------------------
    // 3. Chapter summaries — nodes per chapter, sessions crossed
    // ---------------------------------------------------------------------------------------

    /** One [ThreadMarkerKind.CHAPTER] marker's span within its own conversation: [nodeCount] is
     *  how many [ThreadNodeKind.MESSAGE] nodes recorded in [rootId] fall on-or-after this chapter's
     *  own timestamp and before the NEXT chapter marker in the same conversation (or "forever" for
     *  the last chapter); [sessionsCrossed] is how many [ThreadMarkerKind.SESSION_START] markers
     *  fall in that same span. Complementary to [ThreadChains] (which rolls chapter/session/
     *  compaction *counts* up per conversation, across a whole Fork/Spawn chain, from
     *  `Conversations.Summary` + markers) — this is the finer-grained *per-chapter* breakdown,
     *  computed straight from a graph snapshot + the raw marker list a caller already has (same
     *  two-input shape [ThreadGraphProjector.project] itself takes), never a Room/store read. */
    data class ChapterSummary(
        val markerId: String,
        val rootId: String,
        val label: String,
        val nodeCount: Int,
        val sessionsCrossed: Int,
    )

    /** Empty when [markers] has no [ThreadMarkerKind.CHAPTER] entries — never a fabricated
     *  single "whole conversation" chapter. [markers] is the same raw list
     *  [dev.fonebrew.data.ThreadMarkerStore.markers] already returns (a [ThreadGraphNode] of kind
     *  [ThreadNodeKind.MARKER] doesn't retain which [ThreadMarkerKind] it came from — only [label]
     *  survives projection — so this function reads the marker facts directly rather than trying
     *  to reconstruct kind from a projected node, an honest scope choice named here rather than a
     *  silent guess at "any MARKER node with a label is a chapter," which nothing in the domain
     *  model actually guarantees). */
    fun chapterSummaries(graph: ThreadGraph, markers: List<ThreadMarker>): List<ChapterSummary> {
        val chapters = markers.filter { it.kind == ThreadMarkerKind.CHAPTER }
        if (chapters.isEmpty()) return emptyList()
        val sessionStartsByRoot = markers.filter { it.kind == ThreadMarkerKind.SESSION_START }.groupBy { it.rootId }
        val messageTimesByRoot: Map<String, List<Long>> = graph.nodes
            .filter { it.kind == ThreadNodeKind.MESSAGE }
            .groupBy({ it.rootId }) { it.at.toEpochMilli() }

        return chapters
            .groupBy { it.rootId }
            .flatMap { (rootId, rootChapters) ->
                val sorted = rootChapters.sortedWith(compareBy({ it.at }, { it.id }))
                val messageTimes = messageTimesByRoot[rootId].orEmpty()
                val sessionTimes = sessionStartsByRoot[rootId].orEmpty().map { it.at }
                sorted.mapIndexed { index, marker ->
                    val spanStart = marker.at
                    val spanEndExclusive = sorted.getOrNull(index + 1)?.at ?: Long.MAX_VALUE
                    ChapterSummary(
                        markerId = marker.id,
                        rootId = rootId,
                        label = marker.label.orEmpty(),
                        nodeCount = messageTimes.count { it in spanStart until spanEndExclusive },
                        sessionsCrossed = sessionTimes.count { it in spanStart until spanEndExclusive },
                    )
                }
            }
            .sortedWith(compareBy({ it.rootId }, { it.markerId }))
    }

    // ---------------------------------------------------------------------------------------
    // 4. Hot paths — most-revisited ancestry prefixes, by recorded branch counts
    // ---------------------------------------------------------------------------------------

    /** A branch point ([headNodeId]) the recorded tree shows was branched [branchCount] times
     *  (2 or more — a single continuation is a straight line, not a "hot" path), plus
     *  [ancestryPath] — the recorded structural chain from that conversation's own root down to
     *  [headNodeId] (root first, [headNodeId] last), for a caller that wants to render or describe
     *  the whole revisited prefix, not just its tip. "Recorded branch counts," honestly — this
     *  repo records no per-node navigation/visit history (which turn a user actually looked at,
     *  how many times), only which continuations exist in the tree; "most-revisited" here means
     *  "the tree records the most alternate continuations from here," never a claim about how many
     *  times a person actually viewed it. */
    data class HotPath(val headNodeId: String, val ancestryPath: List<String>, val branchCount: Int)

    private fun structuralParentOf(graph: ThreadGraph): Map<String, String> {
        val parent = HashMap<String, String>()
        for (edge in graph.edges) {
            if (edge.kind in STRUCTURAL_EDGE_KINDS && edge.from != edge.to) parent[edge.to] = edge.from
        }
        return parent
    }

    private fun structuralChildrenOf(graph: ThreadGraph): Map<String, List<String>> {
        val children = LinkedHashMap<String, MutableList<String>>()
        for (edge in graph.edges) {
            if (edge.kind in STRUCTURAL_EDGE_KINDS && edge.from != edge.to) {
                children.getOrPut(edge.from) { mutableListOf() } += edge.to
            }
        }
        return children
    }

    /** Root-to-[nodeId] chain along recorded structural edges only. A cycle (never produced by the
     *  real append-only tree, but this function never trusts that from the outside) simply stops
     *  the walk where it started repeating, same honest-degrade posture [ThreadMapLayout.compute]
     *  documents for its own guarded cycle case — never an infinite loop. */
    private fun ancestryPathOf(nodeId: String, parentOf: Map<String, String>): List<String> {
        val path = ArrayList<String>()
        val seen = HashSet<String>()
        var current: String? = nodeId
        while (current != null && seen.add(current)) {
            path += current
            current = parentOf[current]
        }
        return path.asReversed()
    }

    /** Every node with [minBranchCount] or more recorded structural continuations (default 2 — the
     *  minimum that actually constitutes a branch), sorted by [HotPath.branchCount] descending
     *  then [HotPath.headNodeId] ascending for a deterministic render order. */
    fun hotPaths(graph: ThreadGraph, minBranchCount: Int = 2): List<HotPath> {
        require(minBranchCount >= 1) { "minBranchCount must be >= 1 (got $minBranchCount)" }
        val parentOf = structuralParentOf(graph)
        val childrenOf = structuralChildrenOf(graph)
        return graph.nodes
            .mapNotNull { node ->
                val branchCount = childrenOf[node.id]?.size ?: 0
                if (branchCount >= minBranchCount) HotPath(node.id, ancestryPathOf(node.id, parentOf), branchCount) else null
            }
            .sortedWith(compareByDescending<HotPath> { it.branchCount }.thenBy { it.headNodeId })
    }

    /**
     * Materializes [hotPaths] (2+ recorded continuations by default) as [ThreadEdgeKind.HOT_PATH]
     * edges linking every pair of sibling continuations at that branch point — the one relationship
     * this file renders as a graph edge, always [EdgeDerivation.INFERRED] with a purely structural
     * [ThreadGraphEdge.because] naming the shared branch point + its recorded continuation count
     * (e.g. `"shares a branch point (msg-42) recorded with 3 continuations"`) — never a claim about
     * why the user branched (see this object's own KDoc + `fixtures/thread/adversarial/
     * thread-graph-hot-path-extracted-derivation` for the contract this exists to satisfy).
     *
     * For a branch point with `n` siblings this emits `n - 1` edges (each later sibling, in sorted-
     * id order, linked back to the first) rather than every pair (`n choose 2`) — enough to make
     * every sibling reachable from the group in an [ThreadGraphExplainPath] search without an edge
     * count that grows quadratically in a heavily-revisited branch point. `from`/`to` order is the
     * deterministic sorted-id pick named in [ThreadEdgeKind.HOT_PATH]'s own KDoc, not a causal
     * direction claim — these are two undirected siblings, not a reply.
     */
    fun hotPathEdges(graph: ThreadGraph, minBranchCount: Int = 2): List<ThreadGraphEdge> {
        require(minBranchCount >= 1) { "minBranchCount must be >= 1 (got $minBranchCount)" }
        val childrenOf = structuralChildrenOf(graph)
        val edges = ArrayList<ThreadGraphEdge>()
        for ((branchPoint, children) in childrenOf) {
            if (children.size < minBranchCount) continue
            val sortedChildren = children.sorted()
            val anchor = sortedChildren.first()
            for (sibling in sortedChildren.drop(1)) {
                edges += ThreadGraphEdge(
                    from = anchor,
                    to = sibling,
                    kind = ThreadEdgeKind.HOT_PATH,
                    derivation = EdgeDerivation.INFERRED,
                    because = "shares a branch point ($branchPoint) recorded with ${children.size} continuations",
                )
            }
        }
        return edges.sortedWith(compareBy({ it.from }, { it.to }))
    }

    // ---------------------------------------------------------------------------------------
    // 5. Orphaned branches — leaves with no descendants and no bookmark
    // ---------------------------------------------------------------------------------------

    /** A structural leaf (no recorded continuation) that also carries no [ThreadNodeKind.DECISION]
     *  bookmark anchored to it. Honest scope limit, named rather than silently assumed: a
     *  [ThreadGraph] only ever projects [dev.fonebrew.domain.curation.BookmarkKind.DECISION]
     *  bookmarks (see [ThreadGraphProjector]'s own KDoc) — a message pinned as REFERENCE/SNIPPET/
     *  REVISIT is invisible to this graph entirely, so it can't be checked here; a caller that
     *  needs "no bookmark of ANY kind" must additionally consult
     *  [dev.fonebrew.domain.curation.MessageBookmarks] directly. */
    data class OrphanedBranch(val nodeId: String, val rootId: String)

    /** Every [STRUCTURAL_NODE_KINDS] node with zero recorded structural continuations and no
     *  DECISION bookmark anchored to it, sorted by [OrphanedBranch.nodeId]. */
    fun orphanedBranches(graph: ThreadGraph): List<OrphanedBranch> {
        val hasStructuralChild = HashSet<String>()
        for (edge in graph.edges) if (edge.kind in STRUCTURAL_EDGE_KINDS) hasStructuralChild += edge.from
        val bookmarkedNodeIds = graph.nodes
            .filter { it.kind == ThreadNodeKind.DECISION }
            .mapNotNullTo(HashSet()) { it.parentId }

        return graph.nodes
            .filter { it.kind in STRUCTURAL_NODE_KINDS && it.id !in hasStructuralChild && it.id !in bookmarkedNodeIds }
            .map { OrphanedBranch(it.id, it.rootId) }
            .sortedBy { it.nodeId }
    }
}
