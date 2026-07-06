package dev.aarso.domain.loop

import dev.aarso.domain.bpmn.BpmnEdge
import dev.aarso.domain.bpmn.BpmnGraph
import dev.aarso.domain.bpmn.BpmnNode
import dev.aarso.domain.bpmn.BpmnNodeKind
import dev.aarso.domain.council.Generator

/**
 * Executes an **arbitrary** [BpmnGraph] — not just the canonical proposer↔critic refine
 * loop (docs/build-plan.md, Sprint 4). This is what makes the visual editor a real loop
 * engine: any graph the user draws (extra experts, branches, loop-backs) actually runs.
 *
 * The interpreter walks the graph from its start event, executing each task by calling the
 * [Generator] resolved for that node (its model/role/prompt ride in [BpmnNode.ext]), threading
 * a running transcript as context, and resolving gateways with a pluggable [GatewayPolicy].
 * A hard step cap bounds loops so a refine cycle always terminates (cost legibility, rule B).
 *
 * Pure orchestration over the [Generator] seam — no UI, no model wiring, no I/O. JVM-tested.
 */

/** One executed step: which node ran, the role/model behind it, and its output. */
data class GraphStep(
    val index: Int,
    val nodeId: String,
    val role: String,
    val model: String?,
    val output: String,
)

/** The result of running a graph: the ordered steps, why it stopped, and whether it reached an end. */
data class GraphRunResult(
    val steps: List<GraphStep>,
    val stoppedBecause: String,
    val reachedEnd: Boolean,
) {
    val finalOutput: String get() = steps.lastOrNull()?.output ?: ""
}

/**
 * Decides which outgoing edge to take at a gateway (or a task with multiple exits). Default:
 * [ConditionGatewayPolicy], which reads edge conditions/labels against the last output.
 */
fun interface GatewayPolicy {
    fun choose(node: BpmnNode, lastOutput: String, outgoing: List<BpmnEdge>): BpmnEdge?
}

/**
 * The default gateway logic, kept deliberately small and legible:
 * - An edge whose `condition` is `approved` is taken when the last output begins with
 *   "APPROVE" (the critic's approval convention); `!approved` is its negation.
 * - Otherwise an edge **named** "approve" is taken on approval, one named "refine"/"reject"
 *   on non-approval.
 * - A conditionless / unmatched edge is the default ("else") branch.
 * Anything more elaborate is a custom [GatewayPolicy].
 */
object ConditionGatewayPolicy : GatewayPolicy {
    private fun approved(lastOutput: String) =
        lastOutput.trimStart().startsWith("APPROVE", ignoreCase = true)

    override fun choose(node: BpmnNode, lastOutput: String, outgoing: List<BpmnEdge>): BpmnEdge? {
        if (outgoing.isEmpty()) return null
        if (outgoing.size == 1) return outgoing.first()
        val ok = approved(lastOutput)

        outgoing.firstOrNull { e ->
            when (e.condition?.trim()?.lowercase()) {
                "approved" -> ok
                "!approved", "not approved" -> !ok
                else -> false
            }
        }?.let { return it }

        outgoing.firstOrNull { e ->
            when (e.name?.trim()?.lowercase()) {
                "approve", "approved", "yes" -> ok
                "refine", "reject", "no" -> !ok
                else -> false
            }
        }?.let { return it }

        // Default branch: the first edge with no condition, else the first edge.
        return outgoing.firstOrNull { it.condition == null } ?: outgoing.first()
    }
}

class GraphRunner(
    private val generatorFor: (BpmnNode) -> Generator,
    private val gatewayPolicy: GatewayPolicy = ConditionGatewayPolicy,
) {
    /**
     * Run [graph] toward [objective]. Task nodes execute; gateways branch; the walk ends at an
     * end event or when [hardCap] steps are spent (bounding loops). Each task's prompt is its
     * system instruction (`ext["systemPrompt"]` or the node name) over a transcript of the
     * objective + prior outputs.
     */
    suspend fun run(
        graph: BpmnGraph,
        objective: String,
        hardCap: Int = 24,
    ): GraphRunResult {
        val start = graph.nodes.firstOrNull { it.kind == BpmnNodeKind.START_EVENT }
            ?: return GraphRunResult(emptyList(), "no start event", reachedEnd = false)

        val steps = ArrayList<GraphStep>()
        val transcript = StringBuilder("Objective:\n").append(objective).append('\n')

        var current: BpmnNode? = nextNode(graph, start, lastOutput = "", steps)
        var stepIndex = 0

        while (current != null) {
            when (current.kind) {
                BpmnNodeKind.END_EVENT ->
                    return GraphRunResult(steps, "reached end", reachedEnd = true)

                BpmnNodeKind.START_EVENT ->
                    current = nextNode(graph, current, steps.lastOrNull()?.output ?: "", steps)

                BpmnNodeKind.EXCLUSIVE_GATEWAY,
                BpmnNodeKind.INCLUSIVE_GATEWAY,
                BpmnNodeKind.PARALLEL_GATEWAY ->
                    current = nextNode(graph, current, steps.lastOrNull()?.output ?: "", steps)

                else -> {
                    if (stepIndex >= hardCap)
                        return GraphRunResult(steps, "hit step cap ($hardCap)", reachedEnd = false)
                    val role = current.ext["role"]?.ifBlank { null } ?: current.name.ifBlank { current.id }
                    val system = current.ext["systemPrompt"]?.ifBlank { null } ?: current.name
                    val user = transcript.toString() + "\nProduce your contribution as $role."
                    val output = generatorFor(current).complete(system, user).trim()
                    steps += GraphStep(stepIndex++, current.id, role, current.ext["model"]?.ifBlank { null }, output)
                    transcript.append('\n').append(role).append(":\n").append(output).append('\n')
                    current = nextNode(graph, current, output, steps)
                }
            }
        }
        return GraphRunResult(steps, "no outgoing edge", reachedEnd = false)
    }

    /** Follow the chosen outgoing edge from [from]; null when there is none (dead end). */
    private fun nextNode(graph: BpmnGraph, from: BpmnNode, lastOutput: String, steps: List<GraphStep>): BpmnNode? {
        val out = graph.outgoing(from.id)
        val edge = gatewayPolicy.choose(from, lastOutput, out) ?: return null
        return graph.node(edge.targetId)
    }
}
