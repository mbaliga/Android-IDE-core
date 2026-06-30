package dev.aarso.domain.council

import dev.aarso.domain.bpmn.Bounds
import dev.aarso.domain.bpmn.BpmnEdge
import dev.aarso.domain.bpmn.BpmnGraph
import dev.aarso.domain.bpmn.BpmnNode
import dev.aarso.domain.bpmn.BpmnNodeKind

/**
 * The "Cost gate" macro ⇄ standard BPMN (docs/design/workflow-builder.md §3).
 *
 * The owner's point: the escalation matrix is not a new primitive. It is an
 * exclusive **gateway** (the budget test) feeding a chain of **approval tasks** —
 * an AGENT rung is a **service task** (automated yes/no), a teammate/user rung is
 * a **user task** (BPMN's native human-in-the-loop). [expand] turns an
 * [EscalationPolicy] into that standard sub-graph, so a Cost gate transports as
 * plain BPMN and can be inspected node-by-node ("explode"); [collapse] reads the
 * policy back from the gateway's Aarso extension metadata ("collapse"). The
 * extension metadata is the source of truth, the tasks are the legible expansion.
 *
 * Pure; JVM-tested. The runtime is still [WorkflowRunner] + [Escalation.decide].
 */
object EscalationBpmn {

    private const val MACRO = "costGate"

    /** Expand a policy into a gateway + one approval task per rung. */
    fun expand(policy: EscalationPolicy, idPrefix: String = "gate", at: Bounds = Bounds(0.0, 0.0)): BpmnGraph {
        val nodes = ArrayList<BpmnNode>()
        val edges = ArrayList<BpmnEdge>()

        val gwId = "${idPrefix}_budget"
        nodes += BpmnNode(
            id = gwId,
            kind = BpmnNodeKind.EXCLUSIVE_GATEWAY,
            name = "Within budget?",
            bounds = at,
            ext = encodePolicy(policy),
        )

        var prevId = gwId
        policy.gates.forEachIndexed { i, gate ->
            val taskKind =
                if (gate.approver.kind == ApproverKind.AGENT) BpmnNodeKind.SERVICE_TASK
                else BpmnNodeKind.USER_TASK
            val taskId = "${idPrefix}_approve_$i"
            nodes += BpmnNode(
                id = taskId,
                kind = taskKind,
                name = "${gate.approver.name} approves",
                bounds = Bounds(at.x + 170.0 * (i + 1), at.y),
                ext = linkedMapOf(
                    "approverKind" to gate.approver.kind.name,
                    "approver" to gate.approver.name,
                    "ceiling" to encodeBudget(gate.ceiling),
                ),
            )
            edges += BpmnEdge(
                id = "${idPrefix}_e$i",
                sourceId = prevId,
                targetId = taskId,
                name = if (i == 0) "over budget" else "rejected",
            )
            prevId = taskId
        }
        return BpmnGraph(id = idPrefix, name = "Cost gate", nodes = nodes, edges = edges)
    }

    /** Reconstruct the policy from a graph that carries a cost-gate macro. */
    fun collapse(graph: BpmnGraph): EscalationPolicy? {
        val lead = graph.nodes.firstOrNull { it.ext["macro"] == MACRO } ?: return null
        return decodePolicy(lead.ext)
    }

    // ---- compact, human-readable encoding into ext attributes ----

    private fun encodePolicy(p: EscalationPolicy): Map<String, String> = linkedMapOf<String, String>().apply {
        put("macro", MACRO)
        put("autoBudget", encodeBudget(p.autoBudget))
        p.autoBudgetWithTestsGreen?.let { put("autoBudgetGreen", encodeBudget(it)) }
        put("gates", p.gates.joinToString(";") {
            "${it.approver.kind.name}:${it.approver.name}:${encodeBudget(it.ceiling)}"
        })
    }

    private fun decodePolicy(ext: Map<String, String>): EscalationPolicy? {
        val auto = ext["autoBudget"]?.let(::decodeBudget) ?: return null
        val green = ext["autoBudgetGreen"]?.let(::decodeBudget)
        val gates = ext["gates"]?.split(";")?.filter { it.isNotBlank() }?.map { spec ->
            val p = spec.split(":")
            Gate(Approver(ApproverKind.valueOf(p[0]), p[1]), decodeBudget(p[2]))
        }.orEmpty()
        if (gates.isEmpty()) return null
        return EscalationPolicy(auto, gates, green)
    }

    // Budget as "tokens,seconds,moneyCents,calls"; '_' marks an unlimited dimension.
    private fun encodeBudget(b: Budget): String =
        listOf<Number?>(b.tokens, b.seconds, b.moneyCents, b.calls).joinToString(",") { it?.toString() ?: "_" }

    private fun decodeBudget(s: String): Budget {
        val p = s.split(",")
        fun l(i: Int): Long? = p.getOrNull(i)?.takeIf { it != "_" }?.toLongOrNull()
        fun n(i: Int): Int? = p.getOrNull(i)?.takeIf { it != "_" }?.toIntOrNull()
        return Budget(tokens = l(0), seconds = l(1), moneyCents = l(2), calls = n(3))
    }
}
