package dev.fonebrew.domain.loop.authoring

import dev.fonebrew.contracts.loops.EdgeDefinition
import dev.fonebrew.contracts.loops.LoopDefinition

/**
 * §3.2, `LOOP_PHONE_AUTHORING_SPEC.md` -- Stage View must be "total by construction": every loop
 * graph renders, even the ones Kiepuszewski, Küster & Ouyang (CAiSE 2000) prove are not
 * expressible as properly nested sequence/choice/repetition. This object computes exactly the two
 * things §3.2 assigns to "the validator" (its point 1 of five) -- per-gateway structurability, and
 * back-edge cycles as bounded repeat groups -- as real, tested domain logic. Turning that
 * computation into the actual nested-outcome-card / unstructured-region-card RENDER TREE (§3.2
 * points 2-4) is Compose UI work belonging to `ui/loops/` (per `CLAUDE.md`'s repo map) and is
 * explicitly not attempted here -- this build container has no device to verify a render against,
 * and rendering logic with no verification loop is exactly what `CLAUDE.md`'s "Environment
 * honesty" rule warns against claiming.
 *
 * The structurability test here is a deliberately bounded approximation, not a full
 * Refined-Process-Structure-Tree (RPST) / SPQR-tree implementation of Polyvyanyy, García-Bañuelos
 * & Dumas ("Structuring Acyclic Process Models," BPM 2010): for each gateway it checks whether the
 * gateway's branches form a genuine single-entry-single-exit (SESE) region -- every branch reaches
 * a single nearest common rejoin node (or reaches none, terminating independently) without any
 * branch's interior overlapping a sibling branch's interior or reaching into a sibling branch's
 * own target. This catches the textbook non-structurable shape motivating §3.2 (one branch jumping
 * into a sibling branch's interior) without claiming the cited papers' full generality. Flagged,
 * not silently substituted, in `docs/WP8b_GATE_REPORT.md`.
 */
object StageLinearizer {

    data class EdgeClassification(val backEdgeIds: Set<String>, val cycleEntryNodeIds: Set<String>)

    /**
     * Classifies every edge as forward or a back edge (its target is already on the current DFS
     * path from [startNodeId]) via standard white/gray/black DFS coloring. A back edge is a
     * bounded cycle -- "repetition appears as bounded cycle cards" (§3.2) -- and is excluded from
     * [analyze]'s structurability check below, which only concerns the acyclic control flow.
     */
    fun classifyEdges(startNodeId: String, edges: List<EdgeDefinition>): EdgeClassification {
        val adjacency: Map<String, List<EdgeDefinition>> = edges.groupBy { it.fromNodeId }
        val color = HashMap<String, Int>() // 0=unvisited (default), 1=on current path, 2=finished
        val backEdgeIds = mutableSetOf<String>()
        val cycleEntryNodeIds = mutableSetOf<String>()

        fun visit(node: String) {
            color[node] = 1
            for (edge in adjacency[node].orEmpty()) {
                when (color[edge.toNodeId] ?: 0) {
                    0 -> visit(edge.toNodeId)
                    1 -> { backEdgeIds += edge.edgeId; cycleEntryNodeIds += edge.toNodeId }
                    else -> Unit
                }
            }
            color[node] = 2
        }

        visit(startNodeId)
        // Any node never reached from startNodeId (a disconnected component) is still visited, so
        // no edge is silently left unclassified.
        val allNodeIds = (edges.map { it.fromNodeId } + edges.map { it.toNodeId }).toSet()
        for (nodeId in allNodeIds) if ((color[nodeId] ?: 0) == 0) visit(nodeId)

        return EdgeClassification(backEdgeIds, cycleEntryNodeIds)
    }

    data class GatewayStructurability(
        val gatewayId: String,
        val branchTargetNodeIds: List<String>,
        val rejoinNodeId: String?,
        val structurable: Boolean,
        /** Non-empty only when [structurable] is false -- the node(s) reachable from more than one branch's interior, the seed of an §3.2 "unstructured region" card. */
        val crossingNodeIds: Set<String>,
    )

    /** §3.2 point 1: "The validator MUST compute structurability per region." One [GatewayStructurability] per gateway in [definition]. */
    fun analyze(definition: LoopDefinition): List<GatewayStructurability> {
        val edgeClass = classifyEdges(definition.startNodeId, definition.edges)
        val forwardEdges = definition.edges.filterNot { it.edgeId in edgeClass.backEdgeIds }
        val forwardAdjacency: Map<String, List<String>> = forwardEdges.groupBy({ it.fromNodeId }, { it.toNodeId })

        return definition.gateways.map { gateway ->
            val branchTargets = (
                gateway.outcomes.map { it.targetNodeId } +
                    listOfNotNull(gateway.defaultOrTerminalFailurePath?.targetNodeId)
                ).distinct()
            analyzeBranches(gateway.gatewayId, branchTargets, forwardAdjacency)
        }
    }

    private fun analyzeBranches(
        gatewayId: String,
        branchTargets: List<String>,
        forwardAdjacency: Map<String, List<String>>,
    ): GatewayStructurability {
        if (branchTargets.size < 2) {
            return GatewayStructurability(gatewayId, branchTargets, branchTargets.singleOrNull(), structurable = true, crossingNodeIds = emptySet())
        }

        val reach: Map<String, Set<String>> = branchTargets.associateWith { reachableFrom(it, forwardAdjacency) }
        val branchTargetSet = branchTargets.toSet()
        // A branch target itself can never be the rejoin -- if a sibling branch reaches another
        // branch's target, that is exactly the crossing violation this check exists to catch, not
        // a valid merge point.
        val commonToAll = reach.values.reduce { a, b -> a intersect b } - branchTargetSet
        val rejoin = commonToAll.minByOrNull { node -> branchTargets.maxOf { t -> distance(t, node, forwardAdjacency) } }
        val rejoinDownstream = rejoin?.let { reachableFrom(it, forwardAdjacency) } ?: emptySet()
        val interiors = branchTargets.associateWith { t -> reach.getValue(t) - rejoinDownstream }

        val crossing = mutableSetOf<String>()
        for (i in branchTargets.indices) {
            for (j in i + 1 until branchTargets.size) {
                crossing += interiors.getValue(branchTargets[i]) intersect interiors.getValue(branchTargets[j])
            }
        }
        return GatewayStructurability(gatewayId, branchTargets, rejoin, crossing.isEmpty(), crossing)
    }

    private fun reachableFrom(start: String, adjacency: Map<String, List<String>>): Set<String> {
        val visited = linkedSetOf<String>()
        val stack = ArrayDeque<String>()
        stack.addLast(start)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (!visited.add(node)) continue
            adjacency[node]?.forEach { if (it !in visited) stack.addLast(it) }
        }
        return visited
    }

    private fun distance(start: String, target: String, adjacency: Map<String, List<String>>): Int {
        if (start == target) return 0
        val visited = mutableSetOf(start)
        var frontier = listOf(start)
        var dist = 0
        while (frontier.isNotEmpty()) {
            dist++
            val next = mutableListOf<String>()
            for (node in frontier) {
                for (neighbor in adjacency[node].orEmpty()) {
                    if (neighbor == target) return dist
                    if (visited.add(neighbor)) next += neighbor
                }
            }
            frontier = next
        }
        return Int.MAX_VALUE
    }
}
