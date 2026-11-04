package dev.fonebrew.domain.loop

import dev.fonebrew.contracts.loops.BaseRevisionRef
import dev.fonebrew.contracts.loops.DurableObjectRef
import dev.fonebrew.contracts.loops.NodeAttempt
import dev.fonebrew.contracts.loops.NodeAttemptOutcome
import dev.fonebrew.contracts.loops.RunEvent
import dev.fonebrew.contracts.loops.RunOutputs
import dev.fonebrew.contracts.loops.RunState
import dev.fonebrew.contracts.loops.TerminalReason
import dev.fonebrew.contracts.loops.TerminalReasonCategory
import dev.fonebrew.contracts.loops.LoopRun
import dev.fonebrew.domain.bpmn.BpmnGraph
import dev.fonebrew.domain.contracts.Digest
import dev.fonebrew.domain.contracts.IdGenerator
import java.time.Instant

/**
 * WP-8: the "loop engineering lifecycle around GraphRunner" -- drives the real
 * `LOOP_ENGINEERING_SPEC_V2.1.md` §9 17-state [RunState] machine AROUND the already-real
 * [GraphRunner] engine, exactly the same "adapter over existing code, not a rewrite" shape every
 * WP since WP-3 has used. [GraphRunner] itself is untouched -- this class translates its flat
 * [GraphRunResult] into the full [LoopRun] audit record §9 requires (event log, per-node
 * attempts, a correctly-categorized terminal reason), and walks the state machine through the
 * states a real run always passes through even when nothing there needs a live decision.
 *
 * **Deliberately bounded, not a full binding/authority integration:** [resolveBinding] and
 * [resolveAuthority] are seams a real caller fills with WP-10's Device Broker / WP-4's
 * [dev.fonebrew.domain.authority.AuthorityEngine] respectively -- this class defaults both to
 * "auto-resolve, nothing to wait for," which is honest for [GraphRunner]'s own scope (an abstract
 * [dev.fonebrew.domain.council.Generator] seam with no device binding or explicit authority grant of
 * its own) but is NOT a claim that a run needing a real device target or a real authority gate is
 * fully wired end to end yet -- see the WP-8 gate report for the flagged gap.
 */
class LoopRunDriver(
    private val graphRunner: GraphRunner,
    private val idGenerator: () -> String = { "run_" + IdGenerator.generate() },
    private val now: () -> Instant = Instant::now,
) {

    class ValidationFailedException(val missingParams: List<String>) : Exception("missing params: ${missingParams.joinToString(", ")}")

    suspend fun run(
        graph: BpmnGraph,
        objective: String,
        sourceRef: BaseRevisionRef,
        bindingProfileRef: DurableObjectRef,
        hardCap: Int = 24,
        params: Map<String, String> = emptyMap(),
        budget: LoopBudget? = null,
        resolveBinding: suspend () -> Boolean = { true },
        resolveAuthority: suspend () -> Boolean = { true },
        onStep: (suspend (GraphStep) -> Unit)? = null,
    ): LoopRun {
        val runId = idGenerator()
        val definitionDigest = "sha256:" + Digest.ofUtf8(graph.toString() + "|" + objective).digestHex
        val events = mutableListOf<RunEvent>()
        var state = RunState.CREATED

        fun emit(to: RunState, description: String, nodeId: String? = null) {
            events += RunEvent(
                eventId = "evt_" + IdGenerator.generate(), occurredAtUtc = now(), toState = to,
                description = description, fromState = state, nodeId = nodeId,
            )
            state = to
        }

        // CREATED -- the initiating event carries fromState=null, per §9's "(none — initiation)" row.
        events += RunEvent(
            eventId = "evt_" + IdGenerator.generate(), occurredAtUtc = now(), toState = RunState.CREATED,
            description = "Run initiated.", fromState = null,
        )

        emit(RunState.PREFLIGHT, "Preflight: validating graph parameters.")

        val missing = LoopParams.missing(graph, objective, params)
        if (missing.isNotEmpty()) {
            emit(RunState.FAILED_SAFE, "Preflight failed: missing params ${missing.joinToString(", ")}.")
            return buildLoopRun(
                runId, definitionDigest, sourceRef, bindingProfileRef, state, events, emptyList(),
                TerminalReason(TerminalReasonCategory.VALIDATION_FAILURE, "Missing params: ${missing.joinToString(", ")}"),
                params, null,
            )
        }

        emit(RunState.WAITING_BINDING, "Awaiting execution-target binding.")
        val boundOk = resolveBinding()
        if (!boundOk) {
            emit(RunState.CANCELLING, "Binding could not be resolved; cancelling.")
            emit(RunState.CANCELLED, "Run cancelled: no execution target bound.")
            return buildLoopRun(
                runId, definitionDigest, sourceRef, bindingProfileRef, state, events, emptyList(),
                TerminalReason(TerminalReasonCategory.CANCELLATION_REQUESTED, "Execution-target binding was not resolved."),
                params, null,
            )
        }

        emit(RunState.WAITING_AUTHORITY, "Awaiting authority grant.")
        val authorityOk = resolveAuthority()
        if (!authorityOk) {
            emit(RunState.CANCELLING, "Authority not granted; cancelling.")
            emit(RunState.CANCELLED, "Run cancelled: authority grant declined.")
            return buildLoopRun(
                runId, definitionDigest, sourceRef, bindingProfileRef, state, events, emptyList(),
                TerminalReason(TerminalReasonCategory.CANCELLATION_REQUESTED, "Authority grant was declined."),
                params, null,
            )
        }

        emit(RunState.READY, "Ready to run.")
        emit(RunState.RUNNING, "Execution started.")

        val result = graphRunner.run(graph, objective, hardCap, params, budget, onStep)

        val nodeAttempts = result.steps.map { step ->
            NodeAttempt(
                nodeId = step.nodeId, attemptNumber = 1, startedAtUtc = now().minusMillis(step.durationMs),
                outcome = NodeAttemptOutcome.SUCCEEDED, endedAtUtc = now(),
            )
        }

        val (terminalState, category) = classifyOutcome(result)
        if (terminalState == RunState.CANCELLED) {
            emit(RunState.CANCELLING, "Run cancelled mid-execution.")
        }
        emit(terminalState, "Run stopped: ${result.stoppedBecause}.")

        val outputs = if (result.reachedEnd || result.steps.isNotEmpty()) {
            RunOutputs(primaryResult = result.finalOutput, humanReadableSummary = result.finalOutput.take(500))
        } else null

        return buildLoopRun(
            runId, definitionDigest, sourceRef, bindingProfileRef, state, events, nodeAttempts,
            TerminalReason(category, result.stoppedBecause), params, outputs,
        )
    }

    /**
     * Maps [GraphRunner]'s flat stop reason onto the closed §9 (state, [TerminalReasonCategory])
     * pair. **Interpretation choice, flagged not hidden:** [GraphRunner] performs no result
     * verification of its own, so a graph reaching its end event always maps to
     * `SUCCEEDED_UNVERIFIED`, never `SUCCEEDED_VERIFIED` -- and since the closed
     * [TerminalReasonCategory] vocabulary has no explicit "no verifiers were configured at all"
     * value, `REQUIRED_VERIFIER_INCOMPLETE` is used as the closest fit (see the WP-8 gate report).
     */
    private fun classifyOutcome(result: GraphRunResult): Pair<RunState, TerminalReasonCategory> = when {
        result.reachedEnd -> RunState.SUCCEEDED_UNVERIFIED to TerminalReasonCategory.REQUIRED_VERIFIER_INCOMPLETE
        result.stoppedBecause == "cancelled" -> RunState.CANCELLED to TerminalReasonCategory.CANCELLATION_REQUESTED
        result.stoppedBecause == "budget:steps" || result.stoppedBecause.startsWith("hit step cap") ->
            RunState.STOPPED_BUDGET to TerminalReasonCategory.BUDGET_STEPS_EXHAUSTED
        result.stoppedBecause == "budget:wall" -> RunState.STOPPED_BUDGET to TerminalReasonCategory.BUDGET_WALL_CLOCK_EXHAUSTED
        result.stoppedBecause == "budget:tokens" -> RunState.STOPPED_BUDGET to TerminalReasonCategory.BUDGET_TOKENS_EXHAUSTED
        else -> RunState.FAILED_SAFE to TerminalReasonCategory.VALIDATION_FAILURE
    }

    private fun buildLoopRun(
        runId: String, definitionDigest: String, sourceRef: BaseRevisionRef, bindingProfileRef: DurableObjectRef,
        state: RunState, events: List<RunEvent>, nodeAttempts: List<NodeAttempt>, terminalReason: TerminalReason?,
        params: Map<String, String>, outputs: RunOutputs?,
    ): LoopRun = LoopRun(
        schemaVersion = "1.0.0", runId = runId, definitionDigest = definitionDigest, sourceRef = sourceRef,
        bindingProfileRef = bindingProfileRef, runState = state, createdAtUtc = events.first().occurredAtUtc,
        parameters = params, eventLog = events, nodeAttempts = nodeAttempts, outputs = outputs,
        terminalReason = terminalReason, updatedAtUtc = events.last().occurredAtUtc,
    )
}
