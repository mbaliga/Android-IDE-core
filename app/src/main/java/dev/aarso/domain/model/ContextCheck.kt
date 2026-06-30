package dev.aarso.domain.model

/**
 * Whether a conversation path fits a target model's context window (handoff §3:
 * "validate the path fits the target model's window"). Computed against the
 * templated prompt's token count under the target model's tokenizer.
 */
data class ContextCheck(
    val usedTokens: Int,
    val window: Int,
) {
    val fits: Boolean get() = usedTokens <= window
    val overflowBy: Int get() = (usedTokens - window).coerceAtLeast(0)
    /** Fraction of the window consumed, clamped to [0,1] for a meter. */
    val fraction: Float get() = if (window <= 0) 1f else (usedTokens.toFloat() / window).coerceIn(0f, 1f)
}

fun checkContext(usedTokens: Int, window: Int): ContextCheck =
    ContextCheck(usedTokens, window)
