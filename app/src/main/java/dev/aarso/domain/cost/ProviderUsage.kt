package dev.aarso.domain.cost

import org.json.JSONObject

/**
 * Provider-**reported** LLM usage → [CostVector], for the Cost epic (`docs/design/cost.md`).
 * Where the council `CostEstimator` *guesses* tokens pre-run (chars/4), this reads the
 * **actual** input/output token counts the provider returns, so a recommendation can
 * carry its true cost — "this advice cost N tokens / ₹X to produce."
 *
 * Pure parsers over the three provider response shapes (Anthropic / OpenAI-compatible /
 * Gemini), like `GitContentsApi`/`BuildsApi`: JVM-tested against sample JSON; the live
 * capture from the streaming engines is the runtime plumbing (owner-verified, no cloud
 * in CI).
 */
data class UsageReport(val inputTokens: Long, val outputTokens: Long) {
    val totalTokens: Long get() = inputTokens + outputTokens

    /** Price this usage into advice cost (money from the rates + the raw tokens). */
    fun toAdviceCost(pricing: UsagePricing, readingMinutes: Long = 0): CostVector =
        LlmAdvice.cost(
            tokensIn = inputTokens,
            tokensOut = outputTokens,
            moneyPer1kIn = pricing.centsPer1kInput,
            moneyPer1kOut = pricing.centsPer1kOutput,
            readingMinutes = readingMinutes,
        )

    companion object {
        val ZERO = UsageReport(0, 0)
    }
}

/**
 * Per-1k-token price, in the minor currency unit. **Not** a baked-in authoritative price
 * table — provider prices change and are the user's to set (sovereignty: don't invent the
 * numbers). [CONSERVATIVE_DEFAULT] is a clearly-labelled placeholder, not a quote.
 */
data class UsagePricing(val centsPer1kInput: Long, val centsPer1kOutput: Long) {
    companion object {
        /** A rough stand-in until the user sets real per-model prices. */
        val CONSERVATIVE_DEFAULT = UsagePricing(centsPer1kInput = 1, centsPer1kOutput = 3)
        /** On-device: no money changes hands (time/energy is the real cost, not money). */
        val ON_DEVICE = UsagePricing(0, 0)
    }
}

object ProviderUsage {

    /**
     * Anthropic Messages usage. Handles the non-stream body (`usage` at top level) and the
     * stream's `message_start` (`message.usage.input_tokens`) / `message_delta`
     * (`usage.output_tokens`) events. Returns null when no usage is present.
     */
    fun fromAnthropic(json: String): UsageReport? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val usage = root.optJSONObject("usage")
            ?: root.optJSONObject("message")?.optJSONObject("usage")
            ?: return null
        val input = usage.optLong("input_tokens", -1)
        val output = usage.optLong("output_tokens", -1)
        if (input < 0 && output < 0) return null
        return UsageReport(input.coerceAtLeast(0), output.coerceAtLeast(0))
    }

    /** OpenAI-compatible `usage` (`prompt_tokens` / `completion_tokens`). */
    fun fromOpenAi(json: String): UsageReport? {
        val usage = runCatching { JSONObject(json) }.getOrNull()?.optJSONObject("usage") ?: return null
        val input = usage.optLong("prompt_tokens", -1)
        val output = usage.optLong("completion_tokens", -1)
        if (input < 0 && output < 0) return null
        return UsageReport(input.coerceAtLeast(0), output.coerceAtLeast(0))
    }

    /** Gemini `usageMetadata` (`promptTokenCount` / `candidatesTokenCount`). */
    fun fromGemini(json: String): UsageReport? {
        val meta = runCatching { JSONObject(json) }.getOrNull()?.optJSONObject("usageMetadata") ?: return null
        val input = meta.optLong("promptTokenCount", -1)
        val output = meta.optLong("candidatesTokenCount", -1)
        if (input < 0 && output < 0) return null
        return UsageReport(input.coerceAtLeast(0), output.coerceAtLeast(0))
    }
}
