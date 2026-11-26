package dev.fonebrew.domain.loop

/**
 * What the budget editor and the run summary SAY about enforcement and spend — pure text, so a
 * test pins the honesty (docs/design/loop-tools.md, "Money budget"). Glyph-led, never colour-only;
 * estimated figures are flagged "est.", and an unknown stays "unknown".
 */
object BudgetEnforcementPresenter {

    /** The editor's "what is enforced and what is not" lines for [budget] (null = no limits). */
    fun lines(budget: LoopBudget?): List<String> = buildList {
        add("✓ Step cap: always on (engine safety), checked before each step.")
        if (budget?.maxSteps != null) add("✓ Max steps ${budget.maxSteps}: enforced between steps (tool steps count).")
        if (budget?.maxWallMs != null) add("✓ Max time ${budget.maxWallMs / 1000} s: enforced between steps; a step in flight is not interrupted.")
        if (budget?.maxTokensTotal != null) {
            add("◐ Max tokens ${budget.maxTokensTotal}: enforced between steps. On-device counts are est. (local tokenizer); cloud counts come from the provider.")
        }
        if (budget?.maxSpendMinor != null) {
            add("✓ Money cap: enforced between steps. A single step can overshoot it.")
            add("✓ On-device steps cost zero. Cloud steps are priced from provider-reported usage × the price you set for that model.")
            add("⚠ If a cloud step's price is unknown (no price set, or no usage reported) the run STOPS rather than run unenforced.")
        } else {
            add("○ Money cap: not set — spend is recorded but not limited.")
        }
        add("○ Network steps: zero tokens, zero money, but counted as steps and listed in the summary.")
    }
}

object RunSummaryPresenter {

    /** Lines for the run summary. [formatMoney] renders minor units in the user's currency label. */
    fun lines(result: GraphRunResult, budget: LoopBudget?, formatMoney: (Long) -> String): List<String> = buildList {
        val spend = result.totalSpendMinor
        val unpriced = result.unpricedSteps
        when {
            spend == null && unpriced > 0 -> add("Money: not measured for this run ($unpriced step${if (unpriced == 1) "" else "s"} unpriced).")
            spend == null -> Unit
            unpriced > 0 -> add("Money: at least ${formatMoney(spend)} from provider usage × your prices; $unpriced step${if (unpriced == 1) "" else "s"} unknown or unmeasured.")
            else -> add("Money: ${formatMoney(spend)} from provider usage × your prices (on-device and tool steps are zero).")
        }
        if (budget?.maxSpendMinor != null) {
            add("Money cap ${formatMoney(budget.maxSpendMinor)} — checked between steps; one step can overshoot.")
        }
        val hosts = result.hostsContacted
        if (hosts.isNotEmpty()) {
            add("${ToolSideEffect.NETWORK.glyph} Network: contacted ${hosts.joinToString(", ")}")
        } else if (result.steps.any { it.isTool }) {
            add("${ToolSideEffect.NETWORK.glyph} Network: no host contacted.")
        }
        val tools = result.steps.filter { it.isTool }
        if (tools.isNotEmpty()) add("Tools: ${tools.groupingBy { it.toolId!! }.eachCount().entries.joinToString(", ") { "${it.key}×${it.value}" }}")
        result.gatewayDecisions.forEach { add("◇ ${it.nodeId}: ${it.reason}") }
    }

    /** One run-log row for a tool/HTTP step: id, args summary, size, duration (and failure). */
    fun toolStepLine(step: GraphStep): String? {
        val id = step.toolId ?: return null
        val g = step.sideEffect?.glyph ?: "▤"
        return buildString {
            append(g).append(' ').append(id)
            step.argsSummary?.let { append(" · ").append(it) }
            append(" · ").append(step.resultChars ?: 0).append(" chars")
            if (step.truncated) append(" (truncated)")
            append(" · ").append(step.durationMs).append("ms")
            if (step.failed) append(" · ⚠ failed")
        }
    }

    /** The label for a stop reason, glyph first (never colour). Null when it is not a tool/budget reason. */
    fun stopLabel(reason: String): Pair<String, String>? = when {
        reason == "budget:spend" -> "⏸" to "Money budget reached"
        reason == StepPricing.UNKNOWN_STOP_REASON -> "⚠" to "Stopped: $reason"
        reason.startsWith("unsupported in this version") -> "⛔" to "Unsupported in this version — ${reason.removePrefix("unsupported in this version: ")}"
        reason.startsWith("egress not confirmed") -> "⛔" to "Not run: ${reason}"
        reason.startsWith("tool error") -> "⚠" to reason
        reason.startsWith("tool node") -> "⚠" to reason
        else -> null
    }
}

/** The budget editor's money field: major units typed by the user -> the ledger's minor unit. */
object BudgetInput {
    /** `null` for blank/invalid/negative/over-precise input (never rounds silently). */
    fun majorToMinor(text: String, currencyCode: String): Long? {
        val t = text.trim().replace(',', '.')
        if (t.isEmpty() || !t.all { it.isDigit() || it == '.' } || t.count { it == '.' } > 1) return null
        val digits = runCatching { java.util.Currency.getInstance(currencyCode.uppercase()).defaultFractionDigits.coerceAtLeast(0) }.getOrDefault(2)
        val bd = runCatching { java.math.BigDecimal(t) }.getOrNull() ?: return null
        if (bd.scale() > digits) return null
        val minor = bd.movePointRight(digits)
        return runCatching { minor.longValueExact() }.getOrNull()
    }
}
