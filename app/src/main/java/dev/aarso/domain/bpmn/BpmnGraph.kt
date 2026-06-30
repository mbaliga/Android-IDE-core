package dev.aarso.domain.bpmn

/**
 * A loop's definition as a BPMN-shaped graph (docs/design/workflow-builder.md).
 *
 * This is the in-memory model. [BpmnArchive] serialises it to **standard BPMN 2.0
 * XML** so a loop is transportable to any BPMN tool and to the user's own Git host
 * (the sovereignty thesis: open, standard, portable — "discard the shell"). The
 * shapes mirror the owner's Bricks/cheat-sheet vocabulary. Aarso-specific data —
 * the model assigned to a node, council role, cost budget, watched-cloud — rides
 * in [BpmnNode.ext] and is written as BPMN **extension elements**, so the file
 * stays valid-standard while Aarso still round-trips its own semantics.
 *
 * Pure Kotlin; JVM-tested. Execution stays with the runner — BPMN is the notation.
 */
enum class BpmnNodeKind(val element: String) {
    START_EVENT("startEvent"),
    END_EVENT("endEvent"),
    TASK("task"),
    USER_TASK("userTask"),
    SERVICE_TASK("serviceTask"),
    SCRIPT_TASK("scriptTask"),
    BUSINESS_RULE_TASK("businessRuleTask"),
    MANUAL_TASK("manualTask"),
    SEND_TASK("sendTask"),
    RECEIVE_TASK("receiveTask"),
    CALL_ACTIVITY("callActivity"),
    SUBPROCESS("subProcess"),
    EXCLUSIVE_GATEWAY("exclusiveGateway"),
    PARALLEL_GATEWAY("parallelGateway"),
    INCLUSIVE_GATEWAY("inclusiveGateway"),
    ;

    companion object {
        fun fromElement(name: String): BpmnNodeKind? = entries.firstOrNull { it.element == name }
    }
}

/** Layout for BPMN-DI, so the canvas position travels with the file. */
data class Bounds(val x: Double, val y: Double, val width: Double = 130.0, val height: Double = 64.0)

data class BpmnNode(
    val id: String,
    val kind: BpmnNodeKind,
    val name: String = "",
    val bounds: Bounds = Bounds(0.0, 0.0),
    /** Aarso extension attributes (e.g. model, role, watched, budget, macro). */
    val ext: Map<String, String> = emptyMap(),
)

data class BpmnEdge(
    val id: String,
    val sourceId: String,
    val targetId: String,
    /** Branch label, e.g. "yes"/"no" on a gateway flow. */
    val name: String? = null,
    /** Optional sequence-flow condition (lives in a BPMN conditionExpression). */
    val condition: String? = null,
)

data class BpmnGraph(
    val id: String,
    val name: String = "",
    val nodes: List<BpmnNode> = emptyList(),
    val edges: List<BpmnEdge> = emptyList(),
) {
    fun node(id: String): BpmnNode? = nodes.firstOrNull { it.id == id }
    fun incoming(nodeId: String): List<BpmnEdge> = edges.filter { it.targetId == nodeId }
    fun outgoing(nodeId: String): List<BpmnEdge> = edges.filter { it.sourceId == nodeId }
}
