package dev.aarso.domain.instrument

import dev.aarso.domain.Role

/**
 * Token input/output ratio for a conversation path (handoff §5a — "trivial;
 * computed from stored counts"). Input = system + user turns; output = assistant
 * turns. A high output-per-input ratio means the model is producing far more than
 * it is given — one cheap signal of where the conversation's volume sits.
 */
data class TokenStats(
    val inputTokens: Int,
    val outputTokens: Int,
) {
    val total: Int get() = inputTokens + outputTokens

    /** Output tokens per input token; null when there is no input to divide by. */
    val outputPerInput: Float? get() = if (inputTokens == 0) null else outputTokens.toFloat() / inputTokens

    companion object {
        /** [counts] pairs each turn's role with its token count for the path. */
        fun of(counts: List<Pair<Role, Int>>): TokenStats {
            var input = 0
            var output = 0
            for ((role, n) in counts) {
                if (role == Role.ASSISTANT) output += n else input += n
            }
            return TokenStats(input, output)
        }
    }
}
