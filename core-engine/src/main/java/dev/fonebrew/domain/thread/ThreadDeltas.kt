// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
package dev.fonebrew.domain.thread

/**
 * **WP9** — a structural diff between two [ThreadGraph] snapshots (e.g. [ObserverScript]'s "what
 * changed since I last looked" remarks, or a future WP10 incremental redraw). Pure set/map
 * arithmetic over the two graphs' already-decoded nodes/edges — no tree walk, no store access, no
 * interpretation of *why* something changed (binding constraint 3 / CLAUDE.md rule 4).
 *
 * "Degree+direction" (THREAD_TOPOLOGY_PLAN.md's WP9 bullet): alongside the raw added/removed
 * node/edge lists, [nodeDegreeChanges] reports each touched node's in/out edge counts before and
 * after — "direction" meaning in-degree (edges pointing at a node — replies, forks *into* it,
 * anchors) is tracked separately from out-degree (edges pointing away from it), not merged into
 * one undirected count, since [ThreadGraphEdge] is itself directed.
 */
object ThreadDeltas {

    /** One node's in/out edge-count change between the two snapshots [ThreadDeltas.diff] compared. */
    data class DegreeChange(
        val nodeId: String,
        val inDegreeBefore: Int,
        val inDegreeAfter: Int,
        val outDegreeBefore: Int,
        val outDegreeAfter: Int,
    ) {
        val inDegreeDelta: Int get() = inDegreeAfter - inDegreeBefore
        val outDegreeDelta: Int get() = outDegreeAfter - outDegreeBefore
        val changed: Boolean get() = inDegreeDelta != 0 || outDegreeDelta != 0
    }

    data class Diff(
        val addedNodes: List<ThreadGraphNode>,
        val removedNodes: List<ThreadGraphNode>,
        val addedEdges: List<ThreadGraphEdge>,
        val removedEdges: List<ThreadGraphEdge>,
        /** Only nodes present in both snapshots whose degree actually moved (added/removed nodes
         *  aren't repeated here — their whole presence, not a degree count, is the delta). */
        val nodeDegreeChanges: List<DegreeChange>,
    ) {
        val isEmpty: Boolean get() =
            addedNodes.isEmpty() && removedNodes.isEmpty() && addedEdges.isEmpty() &&
                removedEdges.isEmpty() && nodeDegreeChanges.isEmpty()
    }

    /** Nodes compare by id (a [ThreadGraphNode] is a reference to an existing object per
     *  `thread-graph.schema.json` — "never mints a new identity" — so an id match IS the same
     *  underlying fact even if, e.g., its `label` changed; label edits aren't modelled by this
     *  diff, only presence/absence and degree). Edges compare by full value (from+to+kind) since
     *  an edge has no id of its own. */
    fun diff(before: ThreadGraph, after: ThreadGraph): Diff {
        val beforeNodesById = before.nodes.associateBy { it.id }
        val afterNodesById = after.nodes.associateBy { it.id }

        val addedNodes = after.nodes.filter { it.id !in beforeNodesById }
        val removedNodes = before.nodes.filter { it.id !in afterNodesById }

        val beforeEdgeSet = before.edges.toSet()
        val afterEdgeSet = after.edges.toSet()
        val addedEdges = after.edges.filter { it !in beforeEdgeSet }
        val removedEdges = before.edges.filter { it !in afterEdgeSet }

        val commonIds = beforeNodesById.keys intersect afterNodesById.keys
        val degreeChanges = commonIds.mapNotNull { id ->
            val inBefore = before.edges.count { it.to == id }
            val outBefore = before.edges.count { it.from == id }
            val inAfter = after.edges.count { it.to == id }
            val outAfter = after.edges.count { it.from == id }
            val change = DegreeChange(id, inBefore, inAfter, outBefore, outAfter)
            change.takeIf { it.changed }
        }.sortedBy { it.nodeId }

        return Diff(
            addedNodes = addedNodes,
            removedNodes = removedNodes,
            addedEdges = addedEdges,
            removedEdges = removedEdges,
            nodeDegreeChanges = degreeChanges,
        )
    }
}
