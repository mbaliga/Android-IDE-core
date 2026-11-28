package dev.fonebrew.domain.loop

import dev.fonebrew.domain.bpmn.BpmnNodeKind
import dev.fonebrew.domain.council.Generator

/**
 * The live run-event stream: everything a viewer needs to *watch* a loop run while it happens,
 * not just read the result afterwards. [GraphRunner.run] emits these through its optional
 * `onEvent`; a pure reducer ([RunViewReducer]) folds them into the state the run view renders
 * (which node is active, what it is saying right now, which branch a gateway took, the
 * agent/sub-agent tree). The same events serialise to one JSON line each ([RunProgressCodec]),
 * so a headless `fonebrew` CLI, an SSH session, a git-recorded log, or an external agent's
 * transcript can drive the very same view — the run view is a function of the event stream and
 * nothing else.
 *
 * Honesty rules, same as the rest of the loop engine: token counts are `null` when nothing
 * measured them (never a fabricated 0), and an event describes only what the runner actually
 * observed. Pure Kotlin, no Android, no I/O.
 *
 * **Agents.** Every executing step is an *agent* with an [agentId] and a [parentAgentId]; the run
 * itself is the root agent ([ROOT_AGENT]). [GraphRunner] makes each step a child of the root, but
 * the fields exist so a nested run (a sub-loop, a council member, a delegated sub-agent,
 * an imported agent transcript) can attach anywhere in the tree without a schema change.
 */
sealed interface RunProgress {
    /** Wall-clock millis when the runner observed this event (its injectable clock). */
    val t: Long

    /** The run began. [nodeCount] and [hardCap] let a viewer size its expectations. */
    data class RunStarted(
        override val t: Long,
        val objective: String,
        val nodeCount: Int,
        val hardCap: Int,
    ) : RunProgress

    /** The walk reached [nodeId] (any kind — events and gateways light up too, not just tasks). */
    data class NodeEntered(
        override val t: Long,
        val nodeId: String,
        val kind: BpmnNodeKind,
        val viaEdgeId: String? = null,
    ) : RunProgress

    /** An agent (task step) began. [stepIndex] is the run-wide step ordinal; a node revisited by a
     *  loop-back gets a new agent each visit, so retries are visible as separate rows. */
    data class StepStarted(
        override val t: Long,
        val stepIndex: Int,
        val nodeId: String,
        val role: String,
        val model: String?,
        val agentId: String = agentIdFor(stepIndex),
        val parentAgentId: String = ROOT_AGENT,
    ) : RunProgress

    /** A chunk of the agent's output as it streams. Only emitted when the [Generator] is a
     *  [StreamingGenerator]; a plain one-shot generator simply never produces deltas, and the
     *  full text arrives with [StepCompleted]. */
    data class StepDelta(
        override val t: Long,
        val stepIndex: Int,
        val nodeId: String,
        val text: String,
        val agentId: String = agentIdFor(stepIndex),
    ) : RunProgress

    /** The agent finished; [step] is the same record `onStep` delivers. */
    data class StepCompleted(
        override val t: Long,
        val step: GraphStep,
        val agentId: String = agentIdFor(step.index),
    ) : RunProgress

    /** The agent threw (not cancellation). The run is about to end with [RunFinished]. */
    data class StepFailed(
        override val t: Long,
        val stepIndex: Int,
        val nodeId: String,
        val message: String,
        val agentId: String = agentIdFor(stepIndex),
    ) : RunProgress

    /** A branching node chose an edge. [alternativeEdgeIds] are the ones not taken, so the
     *  decision is legible, not just its outcome. */
    data class GatewayDecided(
        override val t: Long,
        val nodeId: String,
        val chosenEdgeId: String,
        val chosenLabel: String?,
        val alternativeEdgeIds: List<String>,
    ) : RunProgress

    /** The walk crossed [edgeId]. Emitted for every traversal, so a loop-back shows as a repeat. */
    data class EdgeTaken(
        override val t: Long,
        val edgeId: String,
        val fromNodeId: String,
        val toNodeId: String,
    ) : RunProgress

    /** The run ended — always the last event, whatever the reason (end, budget, cap, cancel,
     *  dead end, error). */
    data class RunFinished(
        override val t: Long,
        val stoppedBecause: String,
        val reachedEnd: Boolean,
        val stepCount: Int,
        val elapsedMs: Long,
    ) : RunProgress

    companion object {
        const val ROOT_AGENT = "run"
        fun agentIdFor(stepIndex: Int) = "step-$stepIndex"
    }
}

/**
 * A [Generator] that can hand back its text as it is produced. [GraphRunner] uses it when
 * present so the run view can show a step *thinking out loud*; any plain [Generator] keeps
 * working exactly as before (one result, no deltas).
 */
interface StreamingGenerator : Generator {
    /** Complete the prompt, invoking [onDelta] with each new chunk; returns the full text. */
    suspend fun stream(system: String, user: String, onDelta: suspend (String) -> Unit): String

    override suspend fun complete(system: String, user: String): String = stream(system, user) { }
}
