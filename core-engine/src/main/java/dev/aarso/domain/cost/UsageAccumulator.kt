package dev.aarso.domain.cost

/**
 * Accumulates provider-reported usage across a streaming response (Cost epic, P1). A stream
 * reports its token counts in pieces — Anthropic puts `input_tokens` on `message_start` and the
 * final `output_tokens` on `message_delta`; OpenAI sends a single `usage` near the end; Gemini
 * sends `usageMetadata` per chunk with cumulative counts. This folds those fragments into one
 * [UsageReport] so a finished turn carries its true cost (→ `UsageReport.toAdviceCost`).
 *
 * Merge rule: take the **maximum** seen for each field. That is correct whether a provider sends
 * a field once (others stay 0) or sends a growing cumulative count (the last is the largest); it
 * never double-counts. Pure Kotlin; JVM-tested. The live capture from the SSE engines is the
 * runtime plumbing (owner-verified — no cloud in CI).
 */
class UsageAccumulator {

    var current: UsageReport = UsageReport.ZERO
        private set

    /** Merge a parsed usage fragment, keeping the max per field. */
    fun merge(fragment: UsageReport?) {
        if (fragment == null) return
        current = UsageReport(
            inputTokens = maxOf(current.inputTokens, fragment.inputTokens),
            outputTokens = maxOf(current.outputTokens, fragment.outputTokens),
        )
    }

    /** Reset for a new turn. */
    fun reset() { current = UsageReport.ZERO }

    val hasUsage: Boolean get() = current.totalTokens > 0
}
