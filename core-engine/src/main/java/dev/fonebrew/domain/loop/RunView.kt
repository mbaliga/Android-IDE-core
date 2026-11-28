package dev.fonebrew.domain.loop

/**
 * Pure state for a live run view: a fold over the [RunProgress] event stream. Nothing here knows
 * about Compose, a clock, or a transport — feed it events from a running [GraphRunner], a decoded
 * JSONL log ([RunProgressCodec]), or a remote `fonebrew` session, and the same view results.
 * `RunView.IDLE` + `reduce` is the whole API; JVM-tested.
 */

enum class NodeRunStatus { IDLE, ACTIVE, DONE, FAILED }

enum class AgentStatus { RUNNING, DONE, FAILED, CANCELLED }

/** One executing agent — a step of the run, or any nested agent attached via [parentId]. */
data class AgentRow(
    val id: String,
    val parentId: String?,
    val nodeId: String?,
    val label: String,
    val model: String?,
    val status: AgentStatus,
    val startedAt: Long,
    val endedAt: Long? = null,
    /** What the agent has produced so far (streamed), or its final output once done. Capped to
     *  [RunViewReducer.TEXT_CAP] chars from the *end* — the live tail is what a viewer wants. */
    val text: String = "",
    val tokensIn: Long? = null,
    val tokensOut: Long? = null,
    /** True when [text] was cut to the cap. */
    val truncated: Boolean = false,
    val streamed: Boolean = false,
    val error: String? = null,
    val stepIndex: Int? = null,
) {
    /** Elapsed millis, ticking against [nowMs] while the agent is still running. */
    fun elapsedMs(nowMs: Long): Long = (endedAt ?: nowMs) - startedAt
}

data class GatewayDecision(
    val nodeId: String,
    val chosenEdgeId: String,
    val chosenLabel: String?,
    val alternativeEdgeIds: List<String>,
    val at: Long,
)

enum class RunPhase { IDLE, RUNNING, FINISHED }

/** One row of the flattened agent tree: [depth] 0 is the run itself. */
data class TreeRow(val depth: Int, val agent: AgentRow)

data class RunView(
    val phase: RunPhase = RunPhase.IDLE,
    val objective: String = "",
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val stoppedBecause: String? = null,
    val reachedEnd: Boolean = false,
    val nodeCount: Int = 0,
    val hardCap: Int = 0,
    val nodeStatus: Map<String, NodeRunStatus> = emptyMap(),
    /** How many times each node has been entered — a loop-back shows as > 1. */
    val nodeVisits: Map<String, Int> = emptyMap(),
    /** Traversal count per edge id. */
    val edgeTraversals: Map<String, Int> = emptyMap(),
    val lastEdgeId: String? = null,
    val gateways: List<GatewayDecision> = emptyList(),
    /** Insertion-ordered: agent id → row. */
    val agents: Map<String, AgentRow> = emptyMap(),
    val steps: List<GraphStep> = emptyList(),
) {
    val running: Boolean get() = phase == RunPhase.RUNNING

    /** Nodes whose agent is running right now (more than one only if a runner ever runs agents
     *  concurrently — nothing here assumes it does not). */
    val activeNodeIds: Set<String>
        get() = nodeStatus.filterValues { it == NodeRunStatus.ACTIVE }.keys

    val runningAgents: List<AgentRow> get() = agents.values.filter { it.status == AgentStatus.RUNNING }

    /** Summed over steps that reported a count; `null` when none did — never a fake 0. */
    val tokensIn: Long? get() = steps.mapNotNull { it.tokensIn }.takeIf { it.isNotEmpty() }?.sum()
    val tokensOut: Long? get() = steps.mapNotNull { it.tokensOut }.takeIf { it.isNotEmpty() }?.sum()

    fun elapsedMs(nowMs: Long): Long = (endedAt ?: nowMs) - (startedAt ?: nowMs)

    /** The agent tree, depth-first, children in start order. The synthetic root is the run. */
    fun tree(): List<TreeRow> {
        val byParent = agents.values.groupBy { it.parentId }
        val out = ArrayList<TreeRow>()
        fun walk(parent: String?, depth: Int) {
            byParent[parent].orEmpty().forEach { a ->
                out += TreeRow(depth, a)
                walk(a.id, depth + 1)
            }
        }
        // Roots: agents with no parent, or whose parent was never announced (an orphan attaches at
        // the top rather than vanishing).
        val roots = agents.values.filter { it.parentId == null || it.parentId !in agents }
        roots.forEach { r ->
            out += TreeRow(0, r)
            walk(r.id, 1)
        }
        return out
    }

    companion object {
        val IDLE = RunView()
    }
}

object RunViewReducer {
    /** Live text kept per agent. A step can run long; the viewer wants the tail, the full output
     *  arrives intact in [GraphStep.output]. */
    const val TEXT_CAP = 4_000

    fun reduce(view: RunView, e: RunProgress): RunView = when (e) {
        is RunProgress.RunStarted -> RunView(
            phase = RunPhase.RUNNING, objective = e.objective, startedAt = e.t,
            nodeCount = e.nodeCount, hardCap = e.hardCap,
            agents = linkedMapOf(
                RunProgress.ROOT_AGENT to AgentRow(
                    id = RunProgress.ROOT_AGENT, parentId = null, nodeId = null,
                    label = "Run", model = null, status = AgentStatus.RUNNING, startedAt = e.t,
                ),
            ),
        )

        is RunProgress.NodeEntered -> view.copy(
            nodeVisits = view.nodeVisits + (e.nodeId to ((view.nodeVisits[e.nodeId] ?: 0) + 1)),
            // Entering a non-task node (event/gateway) marks it DONE-in-passing: it has no work
            // to wait on. Tasks become ACTIVE at StepStarted, so they never flash DONE early.
            nodeStatus = if (isInstant(e.kind)) view.nodeStatus + (e.nodeId to NodeRunStatus.DONE) else view.nodeStatus,
        )

        is RunProgress.StepStarted -> view.copy(
            nodeStatus = view.nodeStatus + (e.nodeId to NodeRunStatus.ACTIVE),
            agents = view.agents + (
                e.agentId to AgentRow(
                    id = e.agentId, parentId = e.parentAgentId, nodeId = e.nodeId,
                    label = e.role, model = e.model, status = AgentStatus.RUNNING,
                    startedAt = e.t, stepIndex = e.stepIndex,
                )
                ),
        )

        is RunProgress.StepDelta -> view.updateAgent(e.agentId) { a ->
            val joined = a.text + e.text
            val cut = joined.length > TEXT_CAP
            a.copy(text = if (cut) joined.takeLast(TEXT_CAP) else joined, truncated = a.truncated || cut, streamed = true)
        }

        is RunProgress.StepCompleted -> {
            val s = e.step
            view.copy(
                nodeStatus = view.nodeStatus + (s.nodeId to NodeRunStatus.DONE),
                steps = view.steps + s,
            ).updateAgent(e.agentId) { a ->
                a.copy(
                    status = AgentStatus.DONE, endedAt = e.t,
                    text = s.output.takeLast(TEXT_CAP), truncated = s.output.length > TEXT_CAP,
                    tokensIn = s.tokensIn, tokensOut = s.tokensOut, model = s.model ?: a.model,
                )
            }
        }

        is RunProgress.StepFailed -> view.copy(
            nodeStatus = view.nodeStatus + (e.nodeId to NodeRunStatus.FAILED),
        ).updateAgent(e.agentId) { it.copy(status = AgentStatus.FAILED, endedAt = e.t, error = e.message) }

        is RunProgress.GatewayDecided -> view.copy(
            gateways = view.gateways + GatewayDecision(e.nodeId, e.chosenEdgeId, e.chosenLabel, e.alternativeEdgeIds, e.t),
        )

        is RunProgress.EdgeTaken -> view.copy(
            edgeTraversals = view.edgeTraversals + (e.edgeId to ((view.edgeTraversals[e.edgeId] ?: 0) + 1)),
            lastEdgeId = e.edgeId,
        )

        is RunProgress.RunFinished -> view.copy(
            phase = RunPhase.FINISHED, endedAt = e.t, stoppedBecause = e.stoppedBecause, reachedEnd = e.reachedEnd,
            // Anything still marked running when the run ends (cancel, error) is closed, not left spinning.
            nodeStatus = view.nodeStatus.mapValues { (_, v) -> if (v == NodeRunStatus.ACTIVE) NodeRunStatus.IDLE else v },
            agents = view.agents.mapValues { (_, a) ->
                when {
                    a.status != AgentStatus.RUNNING -> a
                    // The run itself: how it ended. Any other agent still running was cut off.
                    a.id == RunProgress.ROOT_AGENT -> a.copy(
                        endedAt = e.t,
                        status = when {
                            e.stoppedBecause.startsWith("error") -> AgentStatus.FAILED
                            e.stoppedBecause == "cancelled" -> AgentStatus.CANCELLED
                            else -> AgentStatus.DONE
                        },
                    )
                    else -> a.copy(status = AgentStatus.CANCELLED, endedAt = e.t)
                }
            },
        )
    }

    fun fold(events: Iterable<RunProgress>, from: RunView = RunView.IDLE): RunView = events.fold(from, ::reduce)

    private fun isInstant(kind: dev.fonebrew.domain.bpmn.BpmnNodeKind) = when (kind) {
        dev.fonebrew.domain.bpmn.BpmnNodeKind.START_EVENT,
        dev.fonebrew.domain.bpmn.BpmnNodeKind.END_EVENT,
        dev.fonebrew.domain.bpmn.BpmnNodeKind.EXCLUSIVE_GATEWAY,
        dev.fonebrew.domain.bpmn.BpmnNodeKind.INCLUSIVE_GATEWAY,
        dev.fonebrew.domain.bpmn.BpmnNodeKind.PARALLEL_GATEWAY -> true
        else -> false
    }

    private inline fun RunView.updateAgent(id: String, f: (AgentRow) -> AgentRow): RunView {
        val a = agents[id] ?: return this
        return copy(agents = agents + (id to f(a)))
    }
}
