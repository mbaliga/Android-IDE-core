package dev.fonebrew.domain.agent

/**
 * Every cap the session loop enforces. The step cap is user-visible and clamped to
 * [HARD_MAX_STEPS] no matter what a caller passes. Defaults are conservative starting points, not
 * measurements; the owner tunes them against real models.
 */
data class AgentBudgets(
    val maxSteps: Int = DEFAULT_STEPS,
    /** Malformed/unknown/refused-by-validation calls tolerated across the whole run. */
    val maxMalformed: Int = 3,
    val maxStepOutputBytes: Int = 24_000,
    val maxStepOutputTokens: Int = 4_000,
    /** Sum of known input+output tokens over all steps (steps with unknown usage add nothing). */
    val maxTotalTokens: Long = 300_000,
    /** Total bytes of seed context plus tool results fed to the model. */
    val maxContextBytes: Int = 300_000,
    val maxResultBytes: Int = 16_000,
    val maxFileBytes: Int = 200_000,
    val maxProposedFiles: Int = 20,
    val maxProposedBytes: Int = 400_000,
) {
    /** The step cap actually used: at least 1, never above [HARD_MAX_STEPS]. */
    val effectiveSteps: Int get() = maxSteps.coerceIn(1, HARD_MAX_STEPS)

    companion object {
        const val DEFAULT_STEPS = 12
        const val HARD_MAX_STEPS = 30
        /** Tool calls executed per model turn; extra calls in one turn are refused, not run. */
        const val MAX_CALLS_PER_STEP = 4
    }
}

/** Why a run ended. Always stated in the log. */
enum class StopReason(val glyph: String, val label: String) {
    FINISHED("✓", "Agent finished"),
    STEP_LIMIT("■", "Step limit reached"),
    TOKEN_LIMIT("■", "Token budget reached"),
    BYTE_LIMIT("■", "Context byte budget reached"),
    MALFORMED_LIMIT("■", "Too many malformed tool calls"),
    CANCELLED("⊘", "Stopped by you"),
    ENGINE_ERROR("✗", "Model call failed"),
}

/** A cooperative cancel flag, checked between steps, between calls and while streaming. */
class CancelToken {
    @Volatile var isCancelled: Boolean = false
        private set
    fun cancel() { isCancelled = true }
}

data class AgentMessage(val role: dev.fonebrew.domain.Role, val text: String)

data class TurnLimits(val maxOutputBytes: Int, val maxOutputTokens: Int)

/** Token figures for one model call. [estimated] true = tokenizer counts, not provider-reported.
 *  [costMinor] null = unknown (never shown as zero); 0 = on-device. */
data class TurnUsage(val inputTokens: Long, val outputTokens: Long, val estimated: Boolean, val costMinor: Long?)

data class TurnReply(val text: String, val usage: TurnUsage? = null)

/** The only model seam: given the transcript so far, produce the model's next turn. */
fun interface AgentTurnSource {
    suspend fun next(messages: List<AgentMessage>, limits: TurnLimits): TurnReply
}
