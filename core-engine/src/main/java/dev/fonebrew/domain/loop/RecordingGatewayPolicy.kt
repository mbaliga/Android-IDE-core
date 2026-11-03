package dev.fonebrew.domain.loop

import dev.fonebrew.domain.bpmn.BpmnEdge
import dev.fonebrew.domain.bpmn.BpmnNode

/**
 * THREAD_TOPOLOGY_PLAN.md WP8's `GATEWAY_AUTO` capture — wraps a [GatewayPolicy] to additionally
 * record every genuine "choose for me" gateway decision: a gateway with more than one outgoing
 * edge, where the policy silently picked one on the run's behalf (owner decision 2's loop-gateway
 * auto-choice surface). A gateway with a single outgoing edge isn't a *choice* — there was nothing
 * else it could have done — so it's never recorded, the same "no false delegation" discipline
 * [dev.fonebrew.domain.thread.DelegationPrompts.parseChoice] applies on the model-pick surface.
 *
 * Deliberately synchronous and store-free: [GatewayPolicy.choose] isn't suspending, and this type
 * stays pure/JVM-testable like the rest of `domain/loop`. [recorded] accumulates for the caller to
 * read once a run finishes; [dev.fonebrew.ui.loops.LoopRoom] persists each entry via
 * [dev.fonebrew.data.DelegationRecorder] only after the run completes — the same "log to the tree
 * only when the run is done" discipline it already applies to [GraphRunResult] via `GraphRunLog`.
 *
 * A `DELEGATION` recorded this way has no [dev.fonebrew.domain.thread.DelegationEvent.rootId] /
 * `anchorMsgId` — a loop-gateway choice isn't tied to a specific conversation node at the moment
 * it's made (`schemas/thread/thread-event.schema.json`'s `rootId` doc names this exact case) —
 * so [dev.fonebrew.ui.ChatViewModel]'s per-conversation outcome correlation never resolves these;
 * they stay `PENDING` (a documented gap, not a silent one — a per-loop correlation signal is a
 * later work package's job, not invented here).
 */
class RecordingGatewayPolicy(
    private val inner: GatewayPolicy = ConditionGatewayPolicy,
) : GatewayPolicy {

    /** One gateway auto-choice, in the order the run made them. [chosenEdgeRef]/[alternativeRefs]
     *  are [BpmnEdge.id] values — stable within the graph, unlike [BpmnEdge.name] which is often
     *  blank on an unlabeled edge. */
    data class Recorded(val nodeId: String, val chosenEdgeRef: String, val alternativeRefs: List<String>)

    private val _recorded = mutableListOf<Recorded>()

    /** Snapshot of every auto-choice recorded so far this run. */
    val recorded: List<Recorded> get() = _recorded.toList()

    override fun choose(node: BpmnNode, lastOutput: String, outgoing: List<BpmnEdge>): BpmnEdge? {
        val edge = inner.choose(node, lastOutput, outgoing) ?: return null
        if (outgoing.size > 1) {
            _recorded += Recorded(
                nodeId = node.id,
                chosenEdgeRef = edge.id,
                alternativeRefs = outgoing.filter { it.id != edge.id }.map { it.id },
            )
        }
        return edge
    }
}
