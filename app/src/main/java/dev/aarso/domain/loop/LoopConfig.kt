package dev.aarso.domain.loop

import dev.aarso.domain.bpmn.Bounds
import dev.aarso.domain.bpmn.BpmnEdge
import dev.aarso.domain.bpmn.BpmnGraph
import dev.aarso.domain.bpmn.BpmnNode
import dev.aarso.domain.bpmn.BpmnNodeKind

/**
 * The editable contents of the canonical refine loop, as the visual editor holds it:
 * an objective, a proposer and a critic (each a role + name + system prompt + assigned
 * model + canvas position), and an iteration count. This is what the Loop room edits and
 * runs; [LoopConfigBpmn] maps it to/from a [BpmnGraph] so it serialises to **standard
 * BPMN 2.0** (via `BpmnArchive`) for persistence + Git sync. Pure; JVM-tested.
 */
data class ExpertConfig(
    /** "proposer" / "critic" — the runner uses this to route the model. */
    val role: String,
    val name: String,
    val systemPrompt: String,
    val modelId: String? = null,
    val x: Double = 0.0,
    val y: Double = 0.0,
)

data class LoopConfig(
    val id: String,
    val name: String,
    val objective: String,
    val proposer: ExpertConfig,
    val critic: ExpertConfig,
    val iterations: Int = 2,
)

/** Maps [LoopConfig] ⇄ [BpmnGraph]; fixed node ids so the graph round-trips cleanly. */
object LoopConfigBpmn {

    fun toGraph(c: LoopConfig): BpmnGraph {
        val start = BpmnNode(
            "start", BpmnNodeKind.START_EVENT, "Start",
            Bounds(40.0, 140.0, 48.0, 48.0), mapOf("objective" to c.objective),
        )
        val proposer = BpmnNode(
            "proposer", BpmnNodeKind.TASK, c.proposer.name,
            Bounds(c.proposer.x, c.proposer.y), ext(c.proposer),
        )
        val critic = BpmnNode(
            "critic", BpmnNodeKind.TASK, c.critic.name,
            Bounds(c.critic.x, c.critic.y), ext(c.critic) + ("iterations" to c.iterations.toString()),
        )
        val end = BpmnNode("end", BpmnNodeKind.END_EVENT, "End", Bounds(560.0, 240.0, 48.0, 48.0))
        val edges = listOf(
            BpmnEdge("e1", "start", "proposer"),
            BpmnEdge("e2", "proposer", "critic"),
            BpmnEdge("e3", "critic", "proposer", name = "refine"),
            BpmnEdge("e4", "critic", "end", name = "approve"),
        )
        return BpmnGraph(c.id, c.name, listOf(start, proposer, critic, end), edges)
    }

    fun fromGraph(g: BpmnGraph): LoopConfig {
        val proposer = requireNotNull(g.node("proposer")) { "graph has no proposer node" }
        val critic = requireNotNull(g.node("critic")) { "graph has no critic node" }
        return LoopConfig(
            id = g.id,
            name = g.name,
            objective = g.node("start")?.ext?.get("objective").orEmpty(),
            proposer = expert(proposer),
            critic = expert(critic),
            iterations = critic.ext["iterations"]?.toIntOrNull() ?: 2,
        )
    }

    private fun ext(e: ExpertConfig): Map<String, String> = buildMap {
        put("role", e.role)
        put("prompt", e.systemPrompt)
        e.modelId?.let { put("model", it) }
    }

    private fun expert(n: BpmnNode) = ExpertConfig(
        role = n.ext["role"].orEmpty(),
        name = n.name,
        systemPrompt = n.ext["prompt"].orEmpty(),
        modelId = n.ext["model"],
        x = n.bounds.x,
        y = n.bounds.y,
    )
}
