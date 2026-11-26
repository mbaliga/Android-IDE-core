package dev.fonebrew.domain.agent

import dev.fonebrew.domain.diff.ChangeSet

enum class CallStatus(val glyph: String, val label: String) {
    OK("✓", "ok"),
    ERROR("✗", "error"),
    /** Not run: consent withheld, over the per-step limit, or after cancel. */
    REFUSED("⊘", "refused"),
}

/** One tool call in a step: what was asked and what came of it. */
data class StepCall(val tool: String, val argsSummary: String, val status: CallStatus, val summary: String)

/** One model turn plus the calls it made. [usage] null = the engine reported nothing and no estimate was possible. */
data class AgentStep(
    val number: Int,
    val calls: List<StepCall>,
    val usage: TurnUsage?,
    val note: String? = null,
)

/** Running figures. Tokens only count steps whose usage is known; [unknownSteps] says how many were not. */
data class RunTotals(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val anyEstimated: Boolean = false,
    val unknownSteps: Int = 0,
    val costMinor: Long = 0,
    val costUnknownSteps: Int = 0,
) {
    fun plus(u: TurnUsage?): RunTotals =
        if (u == null) copy(unknownSteps = unknownSteps + 1, costUnknownSteps = costUnknownSteps + 1)
        else copy(
            inputTokens = inputTokens + u.inputTokens,
            outputTokens = outputTokens + u.outputTokens,
            anyEstimated = anyEstimated || u.estimated,
            costMinor = costMinor + (u.costMinor ?: 0),
            costUnknownSteps = costUnknownSteps + if (u.costMinor == null) 1 else 0,
        )

    val knownTokens: Long get() = inputTokens + outputTokens
}

/** Which engine ran the steps. A cloud engine is always shown as a watched object. */
data class EngineBadge(val name: String, val cloud: Boolean) {
    val glyph: String get() = if (cloud) "☁" else "⌂"
    val label: String get() = if (cloud) "cloud - watched object" else "on-device"
}

data class SessionOutcome(
    val stop: StopReason,
    val stopDetail: String,
    val steps: List<AgentStep>,
    val changeSet: ChangeSet,
    val summary: String?,
    val filesRead: List<String>,
    val totals: RunTotals,
)

/** The log kept with a proposal so the review screen can show how the agent got there. */
data class AgentRunLog(
    val engine: EngineBadge,
    val objective: String,
    val seedNotes: List<String>,
    val outcome: SessionOutcome,
)

/** Pure rendering of the step log. Glyph + label first, never colour alone. */
object StepLogPresenter {

    fun header(engine: EngineBadge): String = "${engine.glyph} ${engine.name} (${engine.label})"

    fun tokens(t: RunTotals): String {
        if (t.knownTokens == 0L && t.unknownSteps > 0) return "tokens: unknown"
        val approx = if (t.anyEstimated) "≈ " else ""
        val est = if (t.anyEstimated) " (estimated)" else ""
        val unk = if (t.unknownSteps > 0) "; ${t.unknownSteps} step(s) unknown" else ""
        return "tokens: ${approx}in ${t.inputTokens} / out ${t.outputTokens}$est$unk"
    }

    /** [format] renders minor units in the user's currency; this file never invents a currency. */
    fun cost(t: RunTotals, cloud: Boolean, format: (Long) -> String): String = when {
        !cloud -> "cost: ${format(0)} (on-device)"
        t.costUnknownSteps > 0 && t.costMinor == 0L -> "cost: unknown"
        t.costUnknownSteps > 0 -> "cost: ≥ ${format(t.costMinor)} (${t.costUnknownSteps} step(s) unpriced)"
        // A cloud price is always tokens x the user's own price book, never an invoice figure.
        else -> "cost: ≈ ${format(t.costMinor)} (estimated from your price book)"
    }

    fun call(c: StepCall): String = "${c.status.glyph} ${c.status.label} ${c.tool}(${c.argsSummary}) - ${c.summary}"

    fun step(s: AgentStep): List<String> = buildList {
        add("Step ${s.number}" + (s.note?.let { " - $it" } ?: ""))
        s.calls.forEach { add("  " + call(it)) }
        if (s.calls.isEmpty()) add("  (no tool call)")
    }

    fun stop(o: SessionOutcome): String = "${o.stop.glyph} ${o.stop.label}: ${o.stopDetail}"

    /** The whole log as plain lines (for the live view and the review dialog). */
    fun lines(log: AgentRunLog, format: (Long) -> String): List<String> = buildList {
        add(header(log.engine))
        log.seedNotes.forEach { add("Context: $it") }
        log.outcome.steps.forEach { addAll(step(it)) }
        add(stop(log.outcome))
        add(tokens(log.outcome.totals) + " | " + cost(log.outcome.totals, log.engine.cloud, format))
    }
}
