package dev.fonebrew.domain.agent

import dev.fonebrew.domain.Role
import kotlinx.coroutines.CancellationException

/** Live hooks for the UI. Called on the caller's coroutine. */
interface AgentObserver {
    fun onStepStart(number: Int) {}
    fun onStep(step: AgentStep, totals: RunTotals) {}
}

/**
 * The multi-step loop. Pure over [AgentTurnSource], a [ToolRegistry] and the workspace behind it,
 * so a scripted fake engine drives every path in a JVM test.
 *
 * Invariants this loop enforces itself (never trusting the engine or the tools):
 *  - tool calls are parsed ONLY from the model's own reply, never from tool-result text;
 *  - a malformed/unknown call is fed back as an error, counted, and never run or guessed;
 *  - every tool runs through the per-class [ConsentPolicy]; PER_CALL asks [gate] for that call;
 *  - the step cap, token, byte and malformed budgets stop the run with a stated [StopReason];
 *  - cancel is honoured between steps, between calls, and a cancelled reply is never executed.
 */
class AgentSession(
    private val registry: ToolRegistry,
    private val turns: AgentTurnSource,
    private val budgets: AgentBudgets = AgentBudgets(),
    private val policy: ConsentPolicy = ConsentPolicy(),
    private val gate: ConsentGate = ConsentGate.DENY_ALL,
    private val cancel: CancelToken = CancelToken(),
    private val observer: AgentObserver = object : AgentObserver {},
) {
    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size

    /** [workspace] only supplies the final change set / summary / files read; the tools in [registry] close over it. */
    suspend fun run(objective: String, seed: Map<String, String>, workspace: AgentWorkspace): SessionOutcome {
        val messages = mutableListOf(
            AgentMessage(Role.SYSTEM, AgentPrompt.system(registry, budgets)),
            AgentMessage(Role.USER, AgentPrompt.user(objective, seed)),
        )
        var contextBytes = messages.sumOf { bytes(it.text) }
        val steps = mutableListOf<AgentStep>()
        var totals = RunTotals()
        var malformed = 0
        var stop: StopReason? = null
        var detail = ""

        fun end(r: StopReason, d: String) { if (stop == null) { stop = r; detail = d } }

        while (stop == null) {
            val n = steps.size + 1
            when {
                cancel.isCancelled -> { end(StopReason.CANCELLED, "stopped before step $n"); break }
                steps.size >= budgets.effectiveSteps -> { end(StopReason.STEP_LIMIT, "used all ${budgets.effectiveSteps} steps without finishing"); break }
                totals.knownTokens >= budgets.maxTotalTokens -> { end(StopReason.TOKEN_LIMIT, "${totals.knownTokens} tokens used, budget ${budgets.maxTotalTokens}"); break }
                contextBytes >= budgets.maxContextBytes -> { end(StopReason.BYTE_LIMIT, "$contextBytes bytes of context, budget ${budgets.maxContextBytes}"); break }
            }
            observer.onStepStart(n)

            val reply = try {
                turns.next(messages.toList(), TurnLimits(budgets.maxStepOutputBytes, budgets.maxStepOutputTokens))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val s = AgentStep(n, emptyList(), null, "model call failed")
                steps += s; totals = totals.plus(null)
                observer.onStep(s, totals)
                end(StopReason.ENGINE_ERROR, (e.message ?: e::class.java.simpleName).take(200))
                break
            }
            totals = totals.plus(reply.usage)

            if (cancel.isCancelled) {
                val s = AgentStep(n, emptyList(), reply.usage, "cancelled mid-step; reply discarded, nothing run")
                steps += s; observer.onStep(s, totals)
                end(StopReason.CANCELLED, "stopped during step $n")
                break
            }

            var text = reply.text
            var note: String? = null
            if (bytes(text) > budgets.maxStepOutputBytes) {
                text = text.toByteArray(Charsets.UTF_8).copyOf(budgets.maxStepOutputBytes).toString(Charsets.UTF_8)
                note = "reply cut at ${budgets.maxStepOutputBytes} bytes"
            }
            messages += AgentMessage(Role.ASSISTANT, text)

            val parsed = ToolCallParser.parse(text)
            val calls = mutableListOf<StepCall>()
            val feedback = StringBuilder()
            var byteLimitHit = false

            fun feed(tool: String, subject: String, ok: Boolean, data: String) {
                var d = data
                if (bytes(d) > budgets.maxResultBytes) {
                    d = d.toByteArray(Charsets.UTF_8).copyOf(budgets.maxResultBytes).toString(Charsets.UTF_8) +
                        "\n[truncated at ${budgets.maxResultBytes} bytes]"
                }
                var block = ToolResultFormat.wrap(tool, subject, ok, d)
                if (contextBytes + bytes(feedback.toString()) + bytes(block) > budgets.maxContextBytes) {
                    byteLimitHit = true
                    block = ToolResultFormat.wrap(tool, subject, false, "context byte budget exhausted; this result was omitted")
                }
                feedback.append(block).append('\n')
            }

            if (parsed.isEmpty) {
                malformed++
                calls += StepCall("(none)", "", CallStatus.ERROR, "no tool block in the reply")
                feed("agent", "no tool call", false, "Your reply contained no tool block. Reply with a <<<TOOL name>>> ... <<<END>>> block.")
            }
            for (e in parsed.errors) {
                malformed++
                calls += StepCall(e.tool ?: "(unknown)", "", CallStatus.ERROR, e.message)
                feed(e.tool ?: "agent", "malformed call", false, e.message)
            }
            var executed = 0
            var finished = false
            for (raw in parsed.calls) {
                val argsSummary = summarizeArgs(raw)
                if (finished) {
                    calls += StepCall(raw.name, argsSummary, CallStatus.REFUSED, "not run: the agent already finished")
                    continue
                }
                if (cancel.isCancelled) {
                    calls += StepCall(raw.name, argsSummary, CallStatus.REFUSED, "not run: stopped by you")
                    continue
                }
                if (executed >= AgentBudgets.MAX_CALLS_PER_STEP) {
                    calls += StepCall(raw.name, argsSummary, CallStatus.REFUSED, "not run: more than ${AgentBudgets.MAX_CALLS_PER_STEP} calls in one step")
                    feed(raw.name, "not run", false, "Too many calls in one step; this one was not run. Send fewer calls per reply.")
                    continue
                }
                when (val v = registry.validate(raw)) {
                    is ToolRegistry.Validated.Bad -> {
                        malformed++
                        calls += StepCall(raw.name, argsSummary, CallStatus.ERROR, v.message)
                        feed(raw.name, "invalid call", false, v.message)
                    }
                    is ToolRegistry.Validated.Ok -> {
                        val consent = policy.forEffect(v.spec.effect)
                        val allowed = when (consent) {
                            Consent.AUTO -> true
                            Consent.DENY -> false
                            Consent.PER_CALL -> gate.approve(ConsentRequest(v.spec.name, v.spec.effect, argsSummary))
                        }
                        if (!allowed) {
                            val why = if (consent == Consent.DENY) "this kind of tool (${v.spec.effect}) is denied" else "you did not approve this call"
                            calls += StepCall(raw.name, argsSummary, CallStatus.REFUSED, "not run: $why")
                            feed(raw.name, "refused", false, "Not run: $why.")
                        } else {
                            executed++
                            val r = try {
                                v.spec.execute(v.args)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                ToolResult.error("tool failed: ${(e.message ?: e::class.java.simpleName).take(120)}")
                            }
                            calls += StepCall(raw.name, argsSummary, if (r.ok) CallStatus.OK else CallStatus.ERROR, r.summary)
                            feed(raw.name, r.summary, r.ok, r.data.ifEmpty { r.summary })
                            if (r.ok && v.spec.terminal) finished = true
                        }
                    }
                }
            }
            contextBytes += bytes(feedback.toString())
            val step = AgentStep(n, calls, reply.usage, note)
            steps += step
            observer.onStep(step, totals)
            if (finished) end(StopReason.FINISHED, workspace.finishSummary?.takeIf { it.isNotBlank() } ?: "finished")
            else if (cancel.isCancelled) end(StopReason.CANCELLED, "stopped during step $n")
            else if (malformed > budgets.maxMalformed) end(StopReason.MALFORMED_LIMIT, "$malformed malformed calls, allowed ${budgets.maxMalformed}")
            else if (byteLimitHit) end(StopReason.BYTE_LIMIT, "$contextBytes bytes of context, budget ${budgets.maxContextBytes}")
            else messages += AgentMessage(Role.USER, feedback.toString())
        }
        return SessionOutcome(
            stop = stop!!, stopDetail = detail, steps = steps,
            changeSet = workspace.changeSet(), summary = workspace.finishSummary,
            filesRead = workspace.filesRead, totals = totals,
        )
    }

    private fun summarizeArgs(c: RawCall): String =
        c.args.entries.joinToString(", ") { "${it.key}=${it.value.take(60)}" } + if (c.body != null) ", body ${bytes(c.body)} bytes" else ""
}
