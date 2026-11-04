// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
package dev.fonebrew.domain.thread

/**
 * **Graph-wave lane B** — "explain the connection between these two nodes": the shortest recorded
 * path between two node ids in a [ThreadGraph], rendered as an ordered list of the real edges along
 * it, each carrying its own [ThreadGraphEdge.because]. Pure, deterministic, JVM-tested — same
 * "presenter over an already-built snapshot" shape [ThreadMapLayout]/[ThreadGraphAnalytics] use.
 *
 * ### Never routes through an INFERRED edge silently
 * [explain] searches [ThreadGraph.edges] treating them as an undirected graph (connectivity is
 * what "how are these two related" needs; each returned edge still carries its own real recorded
 * `from`/`to` — this function never flips one to make a path read more naturally) in two passes:
 *
 * 1. **Recorded-only** — every edge whose [ThreadGraphEdge.derivation] is
 *    [EdgeDerivation.EXTRACTED] or absent (`null`; a pre-1.2.0 edge that predates the field but is
 *    still a real recorded fact — see [ThreadGraphEdge]'s own KDoc). If a path exists here, it's
 *    [ExplainPathResult.extractedPath] and [ExplainPathResult.inferredAlternative] is always `null`
 *    — there's nothing to offer as a second-class alternative when the honest, recorded answer
 *    already exists.
 * 2. **Every edge, including [EdgeDerivation.INFERRED] ones** — tried ONLY when pass 1 finds
 *    nothing. A path found here necessarily uses at least one INFERRED edge (if it didn't, pass 1
 *    would already have found it), so it is offered as [ExplainPathResult.inferredAlternative],
 *    separately labelled, never silently substituted for a "recorded" answer that doesn't exist.
 *
 * When neither pass finds a path (the two ids live in genuinely disconnected parts of the graph,
 * or one/both ids aren't in [ThreadGraph.nodes] at all), [ExplainPathResult] carries `null` for
 * both — an honest "no connection recorded," never a fabricated one.
 */
object ThreadGraphExplainPath {

    /**
     * @property extractedPath the shortest path using only recorded (non-INFERRED) edges, oldest-
     *   endpoint-first ([fromId] to [toId]); `null` when none exists. Empty (not null) when
     *   [fromId] == [toId] — the trivial zero-edge path.
     * @property inferredAlternative only ever non-null when [extractedPath] is `null` AND a path
     *   exists once INFERRED edges are allowed too — see this object's own KDoc for the two-pass
     *   contract.
     */
    data class ExplainPathResult(
        val fromId: String,
        val toId: String,
        val extractedPath: List<ThreadGraphEdge>?,
        val inferredAlternative: List<ThreadGraphEdge>?,
    ) {
        /** True iff a recorded (non-INFERRED) path was found — the "honest, structural" answer. */
        val found: Boolean get() = extractedPath != null
    }

    fun explain(graph: ThreadGraph, fromId: String, toId: String): ExplainPathResult {
        if (fromId == toId) return ExplainPathResult(fromId, toId, emptyList(), null)
        val extracted = shortestPath(graph, fromId, toId) { it.derivation != EdgeDerivation.INFERRED }
        if (extracted != null) return ExplainPathResult(fromId, toId, extracted, null)
        val anyPath = shortestPath(graph, fromId, toId) { true }
        return ExplainPathResult(fromId, toId, null, anyPath)
    }

    /** Plain BFS over [graph.edges] filtered by [allow], treated as undirected for reachability —
     *  each node's adjacent edges are visited in [graph.edges]' own order (a fixed, deterministic
     *  order for any given [ThreadGraph] value), so ties between equally-short paths resolve the
     *  same way on every call, never by incidental map/set iteration order. Returns the edges in
     *  [fromId]-to-[toId] order, each exactly as recorded (`from`/`to` never flipped) — or `null`
     *  when [toId] isn't reachable from [fromId] through edges [allow] accepts (including when
     *  either id is absent from the graph entirely: the search simply never reaches it). */
    private fun shortestPath(
        graph: ThreadGraph,
        fromId: String,
        toId: String,
        allow: (ThreadGraphEdge) -> Boolean,
    ): List<ThreadGraphEdge>? {
        val adjacency = LinkedHashMap<String, MutableList<ThreadGraphEdge>>()
        for (edge in graph.edges) {
            if (!allow(edge) || edge.from == edge.to) continue
            adjacency.getOrPut(edge.from) { mutableListOf() } += edge
            adjacency.getOrPut(edge.to) { mutableListOf() } += edge
        }

        val cameFromEdge = HashMap<String, ThreadGraphEdge>()
        val visited = HashSet<String>()
        visited += fromId
        val queue = ArrayDeque<String>()
        queue += fromId

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current == toId) return reconstruct(fromId, toId, cameFromEdge)
            for (edge in adjacency[current].orEmpty()) {
                val neighbor = if (edge.from == current) edge.to else edge.from
                if (!visited.add(neighbor)) continue
                cameFromEdge[neighbor] = edge
                queue += neighbor
            }
        }
        return null
    }

    private fun reconstruct(fromId: String, toId: String, cameFromEdge: Map<String, ThreadGraphEdge>): List<ThreadGraphEdge> {
        val path = ArrayDeque<ThreadGraphEdge>()
        var node = toId
        while (node != fromId) {
            val edge = cameFromEdge.getValue(node)
            path.addFirst(edge)
            node = if (edge.from == node) edge.to else edge.from
        }
        return path.toList()
    }
}
