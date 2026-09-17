package dev.fonebrew.domain.loop

import dev.fonebrew.domain.bpmn.BpmnEdge
import dev.fonebrew.domain.bpmn.BpmnGraph
import dev.fonebrew.domain.bpmn.BpmnNode
import dev.fonebrew.domain.bpmn.BpmnNodeKind
import dev.fonebrew.domain.council.Generator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext

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
 * CORE_PHASES.md P3 additive extension: `${key}` **params**, a per-run [LoopBudget], and a live
 * [onStep] callback — all optional, all defaulted, so every existing caller compiles and
 * behaves unchanged.
 *
 * Pure orchestration over the [Generator] seam — no UI, no model wiring, no I/O. JVM-tested.
 */

/** One executed step: which node ran, the role/model behind it, and its output.
 *  [tokensIn]/[tokensOut] are `null` when no [GraphRunner.tokenCounter] is wired — never a
 *  fabricated guess. [estimated] mirrors ledger semantics: `true` unless a counter reports a
 *  provider-authoritative count. [durationMs] is always measurable directly (wall-clock around
 *  the step's [Generator] call), independent of token counting. */
data class GraphStep(
    val index: Int,
    val nodeId: String,
    val role: String,
    val model: String?,
    val output: String,
    val tokensIn: Long? = null,
    val tokensOut: Long? = null,
    val durationMs: Long = 0L,
    val estimated: Boolean = false,
)

/** The result of running a graph: the ordered steps, why it stopped, and whether it reached an
 *  end. [totalTokensIn]/[totalTokensOut] sum only the steps that reported a count — `null` when
 *  none did, never a fake 0 standing in for "unknown." */
data class GraphRunResult(
    val steps: List<GraphStep>,
    val stoppedBecause: String,
    val reachedEnd: Boolean,
    val totalTokensIn: Long? = null,
    val totalTokensOut: Long? = null,
    val elapsedMs: Long = 0L,
) {
    val finalOutput: String get() = steps.lastOrNull()?.output ?: ""

    companion object {
        /** [reason] alongside [steps]/[elapsedMs] already spent — the shared tail every early
         *  return in [GraphRunner.run] builds. */
        internal fun stop(steps: List<GraphStep>, reason: String, reachedEnd: Boolean, elapsedMs: Long): GraphRunResult {
            val ins = steps.mapNotNull { it.tokensIn }
            val outs = steps.mapNotNull { it.tokensOut }
            return GraphRunResult(
                steps = steps,
                stoppedBecause = reason,
                reachedEnd = reachedEnd,
                totalTokensIn = ins.takeIf { it.isNotEmpty() }?.sum(),
                totalTokensOut = outs.takeIf { it.isNotEmpty() }?.sum(),
                elapsedMs = elapsedMs,
            )
        }
    }
}

/** One step's token usage, reported by an optional [GraphRunner.tokenCounter]. [estimated]
 *  mirrors ledger semantics: `false` only for a provider-authoritative count (cloud), `true`
 *  for a locally-approximated one (on-device). */
data class StepTokens(val inputTokens: Long, val outputTokens: Long, val estimated: Boolean)

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
    /** Optional per-step token accounting. `null` (default) means every [GraphStep.tokensIn]/
     *  [GraphStep.tokensOut] stays `null` — this engine never invents a token estimate of its
     *  own; the app layer wires a real counter (on-device tokenizer or provider usage) when it
     *  has one. */
    private val tokenCounter: (suspend (system: String, user: String, output: String) -> StepTokens?)? = null,
    /** Injectable clock so [LoopBudget.maxWallMs] and [GraphStep.durationMs] are deterministic
     *  under test; defaults to the real wall clock. Not a "no I/O" violation — a time source,
     *  not network/disk — kept out of [run]'s signature so it matches CORE_PHASES.md's spec
     *  exactly. */
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * Run [graph] toward [objective]. Task nodes execute; gateways branch; the walk ends at an
     * end event, when [hardCap] steps are spent (engine safety, always applies), when [budget]
     * is exceeded (user intent, checked between steps — the step that would exceed it is never
     * started), or when the caller's coroutine is cancelled. Each task's prompt is its system
     * instruction (`ext["systemPrompt"]` or the node name) over a transcript of the objective +
     * prior outputs; both may reference `${key}` [params], substituted via [LoopParams]. A
     * `${key}` with no matching [params] entry anywhere in the graph is a **refusal to start**
     * (never a silent empty substitution) — [GraphRunResult.stoppedBecause] names every missing
     * key. [onStep] fires once per completed step, in order.
     *
     * Cancellation is deliberately **graceful, not thrown**: like every other stop reason, it
     * comes back as a normal [GraphRunResult] (`stoppedBecause = "cancelled"`) carrying whatever
     * steps completed, so a partial run still tree-logs via [GraphRunLog] and ledger-logs via
     * [GraphRunLedger]. This asks a caller's coroutine [Job] to be cancelled (structured
     * concurrency) rather than taking its own cancel-flag parameter — [run] notices between
     * steps via a non-throwing [Job.isActive] check, and around the in-flight [Generator] call
     * via [CancellationException] — but a step already mid-flight can only be interrupted if
     * that call itself is cancellation-cooperative (calls a real suspend point). A synchronous,
     * non-suspending [Generator] cannot be interrupted mid-step; this is an inherent limit of
     * cooperative cancellation, not something this engine can paper over.
     */
    suspend fun run(
        graph: BpmnGraph,
        objective: String,
        hardCap: Int = 24,
        params: Map<String, String> = emptyMap(),
        budget: LoopBudget? = null,
        onStep: (suspend (GraphStep) -> Unit)? = null,
    ): GraphRunResult {
        val missing = LoopParams.missing(graph, objective, params)
        if (missing.isNotEmpty()) {
            return GraphRunResult.stop(emptyList(), "missing params: ${missing.joinToString(", ")}", reachedEnd = false, elapsedMs = 0L)
        }
        val resolvedObjective = LoopParams.substitute(objective, params)

        val runStart = now()
        fun elapsed() = now() - runStart

        val start = graph.nodes.firstOrNull { it.kind == BpmnNodeKind.START_EVENT }
            ?: return GraphRunResult.stop(emptyList(), "no start event", reachedEnd = false, elapsedMs = elapsed())

        val steps = ArrayList<GraphStep>()
        val transcript = StringBuilder("Objective:\n").append(resolvedObjective).append('\n')
        var totalIn = 0L
        var totalOut = 0L

        fun budgetStop(): String? {
            if (budget == null) return null
            if (budget.maxSteps != null && steps.size >= budget.maxSteps) return "budget:steps"
            if (budget.maxWallMs != null && elapsed() >= budget.maxWallMs) return "budget:wall"
            if (budget.maxTokensTotal != null && (totalIn + totalOut) >= budget.maxTokensTotal) return "budget:tokens"
            return null
        }

        suspend fun isActive(): Boolean = coroutineContext[Job]?.isActive ?: true

        var current: BpmnNode? = nextNode(graph, start, lastOutput = "", steps)

        while (current != null) {
            if (!isActive()) return GraphRunResult.stop(steps, "cancelled", reachedEnd = false, elapsedMs = elapsed())

            when (current.kind) {
                BpmnNodeKind.END_EVENT ->
                    return GraphRunResult.stop(steps, "reached end", reachedEnd = true, elapsedMs = elapsed())

                BpmnNodeKind.START_EVENT ->
                    current = nextNode(graph, current, steps.lastOrNull()?.output ?: "", steps)

                BpmnNodeKind.EXCLUSIVE_GATEWAY,
                BpmnNodeKind.INCLUSIVE_GATEWAY,
                BpmnNodeKind.PARALLEL_GATEWAY ->
                    current = nextNode(graph, current, steps.lastOrNull()?.output ?: "", steps)

                else -> {
                    if (steps.size >= hardCap)
                        return GraphRunResult.stop(steps, "hit step cap ($hardCap)", reachedEnd = false, elapsedMs = elapsed())
                    budgetStop()?.let { return GraphRunResult.stop(steps, it, reachedEnd = false, elapsedMs = elapsed()) }

                    val role = current.ext["role"]?.ifBlank { null } ?: current.name.ifBlank { current.id }
                    val systemRaw = current.ext["systemPrompt"]?.ifBlank { null } ?: current.name
                    val system = LoopParams.substitute(systemRaw, params)
                    val user = transcript.toString() + "\nProduce your contribution as $role."

                    val stepStart = now()
                    val output = try {
                        generatorFor(current).complete(system, user).trim()
                    } catch (c: CancellationException) {
                        return GraphRunResult.stop(steps, "cancelled", reachedEnd = false, elapsedMs = elapsed())
                    }
                    val duration = now() - stepStart

                    val tokens = tokenCounter?.invoke(system, user, output)
                    if (tokens != null) {
                        totalIn += tokens.inputTokens
                        totalOut += tokens.outputTokens
                    }

                    val step = GraphStep(
                        index = steps.size,
                        nodeId = current.id,
                        role = role,
                        model = current.ext["model"]?.ifBlank { null },
                        output = output,
                        tokensIn = tokens?.inputTokens,
                        tokensOut = tokens?.outputTokens,
                        durationMs = duration,
                        estimated = tokens?.estimated ?: true,
                    )
                    steps += step
                    onStep?.invoke(step)
                    transcript.append('\n').append(role).append(":\n").append(output).append('\n')
                    current = nextNode(graph, current, output, steps)
                }
            }
        }
        return GraphRunResult.stop(steps, "no outgoing edge", reachedEnd = false, elapsedMs = elapsed())
    }

    /** Follow the chosen outgoing edge from [from]; null when there is none (dead end). */
    private fun nextNode(graph: BpmnGraph, from: BpmnNode, lastOutput: String, steps: List<GraphStep>): BpmnNode? {
        val out = graph.outgoing(from.id)
        val edge = gatewayPolicy.choose(from, lastOutput, out) ?: return null
        return graph.node(edge.targetId)
    }
}
