package dev.aarso.domain.instrument

/**
 * Maps a generated token's entropy to a 0..1 "confidence" for the logprob/entropy
 * coloring (handoff §5a): low entropy = the model is on rails (1.0), high entropy
 * = uncertain (0.0). Pure so it is unit-testable.
 *
 * IMPORTANT scope notes:
 *  - This is **local-models-only** (§5a): the Anthropic API returns no logprobs,
 *    so entropy is null on Claude turns and the coloring goes dark.
 *  - [maxEntropy] is a **presentation parameter**, not a scientific claim — it
 *    sets where the colour scale saturates (entropy in nats). It is exposed and
 *    adjustable rather than baked, and defining the "right" value is deferred to
 *    the owner (§0: no unilateral measurement choices).
 *  - Returns null when entropy is absent (e.g. the echo stand-in, or cloud turns),
 *    so the UI can fall back to a neutral colour instead of implying confidence.
 */
object Confidence {

    const val DEFAULT_MAX_ENTROPY = 4.0f

    fun fromEntropy(entropy: Float?, maxEntropy: Float = DEFAULT_MAX_ENTROPY): Float? {
        if (entropy == null) return null
        if (maxEntropy <= 0f) return null
        return (1f - entropy / maxEntropy).coerceIn(0f, 1f)
    }
}
