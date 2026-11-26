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
    /** Money for this step; [StepCost.NotMeasured] unless a [StepCostResolver] is wired. */
    val cost: StepCost = StepCost.NotMeasured,
    /** Set only for a tool/HTTP step ([GraphRunner] tool nodes). */
    val toolId: String? = null,
    /** Log-safe argument summary (no query strings). */
    val argsSummary: String? = null,
    /** Hosts this step contacted (network tools only) — the run's auditable egress. */
    val hosts: List<String> = emptyList(),
    val sideEffect: ToolSideEffect? = null,
    /** Result length in characters, for tool steps. */
    val resultChars: Int? = null,
    val truncated: Boolean = false,
    /** A tool step that failed: [output] then holds the reason, and the run stopped there. */
    val failed: Boolean = false,
) {
    val isTool: Boolean get() = toolId != null
}

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
    /** Every multi-exit decision with its reason (an unmatched gateway logs why it defaulted). */
    val gatewayDecisions: List<GatewayDecision> = emptyList(),
) {
    val finalOutput: String get() = steps.lastOrNull()?.output ?: ""

    /** Sum of [StepCost.Priced] steps; `null` when no step was priced or free-measured at all. */
    val totalSpendMinor: Long?
        get() = steps.map { it.cost }.takeIf { c -> c.any { it is StepCost.Priced || it is StepCost.Free } }
            ?.sumOf { (it as? StepCost.Priced)?.minor ?: 0L }

    /** Steps whose money cost was unknown or never measured. */
    val unpricedSteps: Int get() = steps.count { it.cost is StepCost.Unknown || it.cost is StepCost.NotMeasured }

    /** Distinct hosts contacted by network steps, in first-contact order — the egress audit. */
    val hostsContacted: List<String> get() = steps.flatMap { it.hosts }.distinct()

    companion object {
        /** [reason] alongside [steps]/[elapsedMs] already spent — the shared tail every early
         *  return in [GraphRunner.run] builds. */
        internal fun stop(
            steps: List<GraphStep>, reason: String, reachedEnd: Boolean, elapsedMs: Long,
            decisions: List<GatewayDecision> = emptyList(),
        ): GraphRunResult {
            val ins = steps.mapNotNull { it.tokensIn }
            val outs = steps.mapNotNull { it.tokensOut }
            return GraphRunResult(
                steps = steps,
                stoppedBecause = reason,
                reachedEnd = reachedEnd,
                totalTokensIn = ins.takeIf { it.isNotEmpty() }?.sum(),
                totalTokensOut = outs.takeIf { it.isNotEmpty() }?.sum(),
                elapsedMs = elapsedMs,
                gatewayDecisions = decisions,
            )
        }
    }
}

/** One step's token usage, reported by an optional [GraphRunner.tokenCounter]. [estimated]
 *  mirrors ledger semantics: `false` only for a provider-authoritative count (cloud), `true`
 *  for a locally-approximated one (on-device). */
data class StepTokens(val inputTokens: Long, val outputTokens: Long, val estimated: Boolean)

/** A gateway's pick plus the legible reason for it — what the run log shows (never a silent
 *  fall-through to the default branch). */
data class GatewayChoice(val edge: BpmnEdge?, val reason: String)

/** One recorded multi-exit decision of a run: which edge, and why. */
data class GatewayDecision(val nodeId: String, val edgeId: String?, val reason: String)

/**
 * Decides which outgoing edge to take at a gateway (or a task with multiple exits). Default:
 * [ConditionGatewayPolicy], which reads edge conditions/labels against the last output.
 * [decide] is the reason-carrying form the runner records; a policy that only implements
 * [choose] gets a generic reason.
 */
fun interface GatewayPolicy {
    fun choose(node: BpmnNode, lastOutput: String, outgoing: List<BpmnEdge>): BpmnEdge?

    fun decide(node: BpmnNode, lastOutput: String, outgoing: List<BpmnEdge>): GatewayChoice {
        val e = choose(node, lastOutput, outgoing)
        return GatewayChoice(e, if (e == null) "no outgoing edge" else "custom policy chose this branch")
    }
}

/**
 * The default gateway logic, kept deliberately small and legible:
 * - An edge whose `condition` is one of the CLOSED set ([ConditionSyntax]: `approved`,
 *   `!approved`, `contains(text)`, `equals(text)`, `startsWith(text)`, `length>N`, `is-empty`, ...)
 *   is taken, in edge order, when [ConditionEvaluator] says it holds for the last output.
 * - Otherwise an edge **named** "approve" is taken on approval, one named "refine"/"reject"
 *   on non-approval.
 * - A conditionless / unmatched edge is the default ("else") branch, and the reason says so.
 * Anything more elaborate is a custom [GatewayPolicy]. No regular expressions anywhere.
 */
object ConditionGatewayPolicy : GatewayPolicy {
    private fun approved(lastOutput: String) =
        lastOutput.trimStart().startsWith("APPROVE", ignoreCase = true)

    override fun choose(node: BpmnNode, lastOutput: String, outgoing: List<BpmnEdge>): BpmnEdge? =
        decide(node, lastOutput, outgoing).edge

    override fun decide(node: BpmnNode, lastOutput: String, outgoing: List<BpmnEdge>): GatewayChoice {
        if (outgoing.isEmpty()) return GatewayChoice(null, "no outgoing edge")
        if (outgoing.size == 1) return GatewayChoice(outgoing.first(), "only one outgoing edge")
        val ok = approved(lastOutput)

        for (e in outgoing) {
            val c = ConditionSyntax.parse(e.condition) ?: continue
            if (ConditionEvaluator.evaluate(c, lastOutput)) {
                return GatewayChoice(e, "condition matched: ${ConditionSyntax.describe(c)}")
            }
        }

        outgoing.firstOrNull { e ->
            when (e.name?.trim()?.lowercase()) {
                "approve", "approved", "yes" -> ok
                "refine", "reject", "no" -> !ok
                else -> false
            }
        }?.let { return GatewayChoice(it, "branch label \"${it.name}\" matched") }

        // Default branch: the first edge with no condition, else the first edge.
        val def = outgoing.firstOrNull { it.condition == null } ?: outgoing.first()
        return GatewayChoice(def, "no condition matched - default branch")
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
    /** Tools a `serviceTask` with a `tool` extension may call. Default: none — every tool node
     *  then stops the run as "unsupported in this version". */
    private val tools: LoopToolRegistry = LoopToolRegistry.EMPTY,
    /** Prices a finished model step for [LoopBudget.maxSpendMinor]. `null` = money not measured,
     *  which a run with a money budget treats as unknown (it stops, never runs unenforced). */
    private val stepCost: StepCostResolver? = null,
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
        /** What the user confirmed on the run-start sheet. **Default none**: a loop with any tool
         *  node refuses to start without it, whatever UI path (import, run-again, a gateway
         *  loop-back) led here — see docs/design/loop-tools.md, "Run-time gate". */
        confirmation: RunConfirmation? = null,
    ): GraphRunResult {
        val missing = LoopParams.missing(graph, objective, params)
        if (missing.isNotEmpty()) {
            return GraphRunResult.stop(emptyList(), "missing params: ${missing.joinToString(", ")}", reachedEnd = false, elapsedMs = 0L)
        }
        val resolvedObjective = LoopParams.substitute(objective, params)

        // Preflight for tool nodes: unknown tool, bad args, or unconfirmed egress stop the run
        // BEFORE any step executes, naming exactly why. Never a silent skip.
        val plan = RunPlan.of(graph, tools, params)
        plan.unsupported.firstOrNull()?.let { (nodeId, toolId) ->
            return GraphRunResult.stop(emptyList(), "unsupported in this version: node \"$nodeId\" uses tool \"$toolId\"", reachedEnd = false, elapsedMs = 0L)
        }
        plan.invalid.firstOrNull()?.let { (nodeId, msg) ->
            return GraphRunResult.stop(emptyList(), "tool node \"$nodeId\" is invalid: $msg", reachedEnd = false, elapsedMs = 0L)
        }
        plan.uncovered(confirmation).takeIf { it.isNotEmpty() }?.let { gaps ->
            return GraphRunResult.stop(emptyList(), "egress not confirmed: ${gaps.joinToString(", ")}", reachedEnd = false, elapsedMs = 0L)
        }
        val confirmed = confirmation ?: RunConfirmation.NONE

        val runStart = now()
        fun elapsed() = now() - runStart

        val start = graph.nodes.firstOrNull { it.kind == BpmnNodeKind.START_EVENT }
            ?: return GraphRunResult.stop(emptyList(), "no start event", reachedEnd = false, elapsedMs = elapsed())

        val steps = ArrayList<GraphStep>()
        val decisions = ArrayList<GatewayDecision>()
        val transcript = StringBuilder("Objective:\n").append(resolvedObjective).append('\n')
        var totalIn = 0L
        var totalOut = 0L
        var spendMinor = 0L
        var unknownCostReason: String? = null

        fun finish(reason: String, reachedEnd: Boolean) =
            GraphRunResult.stop(steps, reason, reachedEnd, elapsed(), decisions)

        fun budgetStop(): String? {
            if (budget == null) return null
            if (budget.maxSteps != null && steps.size >= budget.maxSteps) return "budget:steps"
            if (budget.maxWallMs != null && elapsed() >= budget.maxWallMs) return "budget:wall"
            if (budget.maxTokensTotal != null && (totalIn + totalOut) >= budget.maxTokensTotal) return "budget:tokens"
            if (budget.maxSpendMinor != null) {
                // Between-steps, like every other limit: one step can overshoot. A known overspend
                // is reported before an unknown price; an unknown price stops the run outright.
                if (spendMinor > 0 && spendMinor >= budget.maxSpendMinor) return "budget:spend"
                if (unknownCostReason != null) return StepPricing.UNKNOWN_STOP_REASON
            }
            return null
        }

        suspend fun isActive(): Boolean = coroutineContext[Job]?.isActive ?: true

        fun advance(from: BpmnNode, lastOutput: String): BpmnNode? {
            val out = graph.outgoing(from.id)
            val choice = gatewayPolicy.decide(from, lastOutput, out)
            if (out.size > 1) decisions += GatewayDecision(from.id, choice.edge?.id, choice.reason)
            val edge = choice.edge ?: return null
            return graph.node(edge.targetId)
        }

        var current: BpmnNode? = advance(start, "")

        while (current != null) {
            if (!isActive()) return finish("cancelled", reachedEnd = false)

            when (current.kind) {
                BpmnNodeKind.END_EVENT -> return finish("reached end", reachedEnd = true)

                BpmnNodeKind.START_EVENT,
                BpmnNodeKind.EXCLUSIVE_GATEWAY,
                BpmnNodeKind.INCLUSIVE_GATEWAY,
                BpmnNodeKind.PARALLEL_GATEWAY ->
                    current = advance(current, steps.lastOrNull()?.output ?: "")

                else -> {
                    if (steps.size >= hardCap) return finish("hit step cap ($hardCap)", reachedEnd = false)
                    budgetStop()?.let { return finish(it, reachedEnd = false) }

                    if (ToolNodes.isToolNode(current)) {
                        val toolId = ToolNodes.toolId(current)!!
                        val tool = tools.find(toolId)
                            ?: return finish("unsupported in this version: node \"${current.id}\" uses tool \"$toolId\"", reachedEnd = false)
                        val args = ToolNodes.args(current, params)
                        // Call-time re-check (defence in depth): the host actually about to be
                        // contacted must be one the user confirmed, whatever path got us here.
                        val gaps = tool.hosts(args).filterNot { it in confirmed.hosts } +
                            (if (toolId in confirmed.toolIds) emptyList() else listOf(toolId))
                        if (gaps.isNotEmpty()) return finish("egress not confirmed: ${gaps.joinToString(", ")}", reachedEnd = false)

                        val role = "tool:$toolId"
                        val stepStart = now()
                        val outcome = try {
                            tool.invoke(args)
                        } catch (c: CancellationException) {
                            return finish("cancelled", reachedEnd = false)
                        } catch (e: Exception) {
                            // Class name only: an exception message can echo a URL.
                            ToolOutcome.Failed("tool crashed (${e.javaClass.simpleName})")
                        }
                        val duration = now() - stepStart
                        val hosts = tool.hosts(args)
                        val summary = tool.summarize(args)
                        val step = when (outcome) {
                            is ToolOutcome.Ok -> {
                                val cut = outcome.text.length > tool.maxResultChars
                                val text = if (cut) outcome.text.substring(0, tool.maxResultChars) + "\n[result truncated at ${tool.maxResultChars} characters]" else outcome.text
                                GraphStep(
                                    index = steps.size, nodeId = current.id, role = role, model = null, output = text,
                                    tokensIn = 0L, tokensOut = 0L, durationMs = duration, estimated = false,
                                    cost = StepCost.Free, toolId = toolId, argsSummary = summary, hosts = hosts,
                                    sideEffect = tool.sideEffect, resultChars = text.length,
                                    truncated = cut || text.contains("[response truncated"),
                                )
                            }
                            is ToolOutcome.Failed -> GraphStep(
                                index = steps.size, nodeId = current.id, role = role, model = null, output = outcome.reason,
                                tokensIn = 0L, tokensOut = 0L, durationMs = duration, estimated = false,
                                cost = StepCost.Free, toolId = toolId, argsSummary = summary, hosts = hosts,
                                sideEffect = tool.sideEffect, resultChars = outcome.reason.length, failed = true,
                            )
                        }
                        steps += step
                        onStep?.invoke(step)
                        if (step.failed) return finish("tool error: $toolId: ${step.output}", reachedEnd = false)
                        transcript.append('\n').append(role).append(":\n")
                            .append(ToolTranscript.wrap(tool, args, step.output)).append('\n')
                        current = advance(current, step.output)
                        continue
                    }

                    val role = current.ext["role"]?.ifBlank { null } ?: current.name.ifBlank { current.id }
                    val systemRaw = current.ext["systemPrompt"]?.ifBlank { null } ?: current.name
                    val system = LoopParams.substitute(systemRaw, params)
                    val user = transcript.toString() + "\nProduce your contribution as $role."

                    val stepStart = now()
                    val output = try {
                        generatorFor(current).complete(system, user).trim()
                    } catch (c: CancellationException) {
                        return finish("cancelled", reachedEnd = false)
                    }
                    val duration = now() - stepStart

                    val tokens = tokenCounter?.invoke(system, user, output)
                    if (tokens != null) {
                        totalIn += tokens.inputTokens
                        totalOut += tokens.outputTokens
                    }

                    val cost = stepCost?.costOf(current, tokens) ?: StepCost.NotMeasured
                    when (cost) {
                        is StepCost.Priced -> spendMinor += cost.minor
                        is StepCost.Unknown -> if (unknownCostReason == null) unknownCostReason = cost.reason
                        StepCost.NotMeasured -> if (unknownCostReason == null) unknownCostReason = "money was not measured for this run"
                        StepCost.Free -> Unit
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
                        cost = cost,
                    )
                    steps += step
                    onStep?.invoke(step)
                    transcript.append('\n').append(role).append(":\n").append(output).append('\n')
                    current = advance(current, output)
                }
            }
        }
        return finish("no outgoing edge", reachedEnd = false)
    }
}
