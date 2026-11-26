package dev.fonebrew.domain.benchmark

/** One fixed prompt. [targetTokens] is the size the text was *built toward*, not a measurement. */
data class BenchmarkPrompt(
    val id: String,
    val label: String,
    val text: String,
    val targetTokens: Int,
)

/**
 * The fixed, deterministic work list. Nothing here is random or time-dependent, so two runs on the
 * same model differ only by the device's behaviour.
 *
 * Prompt sizes are approximate: the text is built from repeated neutral sentences at a rough
 * [CHARS_PER_TOKEN] ratio, because the real token count depends on the model's tokenizer. The
 * runner asks the engine for the true count and records that; [BenchmarkPrompt.targetTokens] is
 * only the label.
 */
data class BenchmarkPlan(
    val prompts: List<BenchmarkPrompt>,
    /** Max tokens requested per generation. The engine may stop earlier (end-of-text). */
    val decodeTokens: Int,
    val repeats: Int,
    val contextSize: Int,
) {
    init {
        require(prompts.isNotEmpty()) { "plan needs at least one prompt" }
        require(prompts.map { it.id }.toSet().size == prompts.size) { "prompt ids must be unique" }
        require(decodeTokens >= 2) { "decodeTokens must be >= 2 (decode rate needs two tokens)" }
        require(repeats >= 1) { "repeats must be >= 1" }
        require(contextSize > 0) { "contextSize must be positive" }
    }

    val generationCount: Int get() = prompts.size * repeats

    companion object {
        const val CHARS_PER_TOKEN = 4

        // Plain, neutral, one sentence at a time so repeating it stays grammatical-ish filler.
        private const val FILLER =
            "The river runs past the old mill and the stone bridge before it reaches the sea. "

        const val SHORT_PROMPT = "Write a short paragraph about a quiet harbour town at dawn."

        /** Text of about [targetTokens] tokens (by the rough char ratio), ending in a fixed instruction. */
        fun prefillText(targetTokens: Int): String {
            val chars = targetTokens * CHARS_PER_TOKEN
            val body = StringBuilder()
            while (body.length < chars) body.append(FILLER)
            return body.toString().trimEnd() + "\n\nSummarise the text above in two sentences."
        }

        fun default(): BenchmarkPlan = BenchmarkPlan(
            prompts = listOf(
                BenchmarkPrompt("short", "Short prompt", SHORT_PROMPT, 16),
            ) + listOf(512, 1024, 2048).map { n ->
                BenchmarkPrompt("prefill-$n", "Prefill ~$n tokens", prefillText(n), n)
            },
            decodeTokens = 128,
            repeats = 3,
            contextSize = 4096,
        )
    }
}
