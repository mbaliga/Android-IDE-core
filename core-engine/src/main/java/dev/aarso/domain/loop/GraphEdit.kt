package dev.aarso.domain.loop

import dev.aarso.domain.bpmn.Bounds
import dev.aarso.domain.bpmn.BpmnEdge
import dev.aarso.domain.bpmn.BpmnGraph
import dev.aarso.domain.bpmn.BpmnNode
import dev.aarso.domain.bpmn.BpmnNodeKind

/**
 * The free-form editing model behind the visual editor's Bricks palette (docs/build-plan.md,
 * Sprint 4): add/remove nodes, connect/disconnect edges, on an arbitrary graph — the pure
 * transforms the canvas drives, plus validation so an un-runnable graph is caught *materially*
 * before a run (THE LAW), not after. Each transform returns a new [BpmnGraph] (immutable; the
 * editor keeps undo for free). JVM-tested.
 */
object GraphEdit {

    /** Add a node; ids must be unique. */
    fun addNode(
        graph: BpmnGraph,
        id: String,
        kind: BpmnNodeKind,
        name: String = "",
        bounds: Bounds = Bounds(0.0, 0.0),
        ext: Map<String, String> = emptyMap(),
    ): BpmnGraph {
        require(graph.node(id) == null) { "duplicate node id: $id" }
        return graph.copy(nodes = graph.nodes + BpmnNode(id, kind, name, bounds, ext))
    }

    /** Remove a node and every edge touching it. */
    fun removeNode(graph: BpmnGraph, id: String): BpmnGraph = graph.copy(
        nodes = graph.nodes.filterNot { it.id == id },
        edges = graph.edges.filterNot { it.sourceId == id || it.targetId == id },
    )

    /** Connect two existing nodes with a (optionally labelled/conditioned) edge. */
    fun connect(
        graph: BpmnGraph,
        edgeId: String,
        sourceId: String,
        targetId: String,
        name: String? = null,
        condition: String? = null,
    ): BpmnGraph {
        require(graph.node(sourceId) != null) { "no such source: $sourceId" }
        require(graph.node(targetId) != null) { "no such target: $targetId" }
        require(graph.edges.none { it.id == edgeId }) { "duplicate edge id: $edgeId" }
        return graph.copy(edges = graph.edges + BpmnEdge(edgeId, sourceId, targetId, name, condition))
    }

    /** Remove an edge by id. */
    fun disconnect(graph: BpmnGraph, edgeId: String): BpmnGraph =
        graph.copy(edges = graph.edges.filterNot { it.id == edgeId })

    /** Replace a node (e.g. after editing its name/prompt/model in a dialog). */
    fun updateNode(graph: BpmnGraph, node: BpmnNode): BpmnGraph {
        require(graph.node(node.id) != null) { "no such node: ${node.id}" }
        return graph.copy(nodes = graph.nodes.map { if (it.id == node.id) node else it })
    }

    /**
     * Validate a graph is runnable. Returns the problems (empty = runnable) so the editor can
     * show each one materially against the offending brick.
     */
    fun validate(graph: BpmnGraph): List<String> {
        val problems = ArrayList<String>()
        val starts = graph.nodes.count { it.kind == BpmnNodeKind.START_EVENT }
        val ends = graph.nodes.count { it.kind == BpmnNodeKind.END_EVENT }
        if (starts != 1) problems += "need exactly one start event (have $starts)"
        if (ends < 1) problems += "need at least one end event"

        // Dangling edges.
        for (e in graph.edges) {
            if (graph.node(e.sourceId) == null) problems += "edge ${e.id} has unknown source ${e.sourceId}"
            if (graph.node(e.targetId) == null) problems += "edge ${e.id} has unknown target ${e.targetId}"
        }

        // Tasks with no way in or out can never run / never complete.
        val taskKinds = setOf(
            BpmnNodeKind.TASK, BpmnNodeKind.USER_TASK, BpmnNodeKind.SERVICE_TASK,
            BpmnNodeKind.SCRIPT_TASK, BpmnNodeKind.BUSINESS_RULE_TASK, BpmnNodeKind.MANUAL_TASK,
            BpmnNodeKind.SEND_TASK, BpmnNodeKind.RECEIVE_TASK,
        )
        for (n in graph.nodes.filter { it.kind in taskKinds }) {
            if (graph.incoming(n.id).isEmpty()) problems += "task '${n.name.ifBlank { n.id }}' has no incoming flow"
            if (graph.outgoing(n.id).isEmpty()) problems += "task '${n.name.ifBlank { n.id }}' has no outgoing flow"
        }

        // The end must be reachable from the start.
        val start = graph.nodes.firstOrNull { it.kind == BpmnNodeKind.START_EVENT }
        if (start != null && ends >= 1 && !reachesEnd(graph, start.id)) {
            problems += "no end event is reachable from the start"
        }
        return problems
    }

    fun isRunnable(graph: BpmnGraph): Boolean = validate(graph).isEmpty()

    private fun reachesEnd(graph: BpmnGraph, startId: String): Boolean {
        val seen = HashSet<String>()
        val stack = ArrayDeque<String>().apply { add(startId) }
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (!seen.add(id)) continue
            val node = graph.node(id) ?: continue
            if (node.kind == BpmnNodeKind.END_EVENT) return true
            graph.outgoing(id).forEach { stack.add(it.targetId) }
        }
        return false
    }
}

/**
 * The Bricks palette — the BPMN kinds the editor offers, with plain labels. (Aeon styling is
 * design-gated; this is just the vocabulary + ordering.) Mirrors the owner's Bricks cheat-sheet.
 */
object Bricks {
    data class Brick(val kind: BpmnNodeKind, val label: String)

    val palette: List<Brick> = listOf(
        Brick(BpmnNodeKind.START_EVENT, "Start"),
        Brick(BpmnNodeKind.TASK, "Expert"),
        Brick(BpmnNodeKind.EXCLUSIVE_GATEWAY, "Choice"),
        Brick(BpmnNodeKind.PARALLEL_GATEWAY, "Fan-out"),
        Brick(BpmnNodeKind.USER_TASK, "Ask you"),
        Brick(BpmnNodeKind.SERVICE_TASK, "Run a tool"),
        Brick(BpmnNodeKind.END_EVENT, "End"),
    )

    fun labelFor(kind: BpmnNodeKind): String =
        palette.firstOrNull { it.kind == kind }?.label ?: kind.element
}
