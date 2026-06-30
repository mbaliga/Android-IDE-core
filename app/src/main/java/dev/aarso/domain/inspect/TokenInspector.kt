package dev.aarso.domain.inspect

/**
 * The logprob / entropy inspector model: the legibility surface that makes a model's
 * *per-token uncertainty* visible. The architecture spine plumbs per-token logprobs and
 * entropy from token one (why llama.cpp over Ollama); this is the pure-domain model that
 * turns those numbers into a heatmap, a "highest uncertainty" pick, and an honest summary
 * line. No Android, no clock, no randomness — JVM-tested and deterministic.
 *
 * Two binding commitments encoded here:
 *  - **Honesty about availability** ([Availability]). On-device generation gives FULL
 *    per-token logprobs; some cloud providers give only top-k; some give nothing. The
 *    inspector never *fabricates* numbers it doesn't have — UNAVAILABLE is shown plainly.
 *  - **Non-colour-alone encoding** (Doc 00 §3.5). A [Heatcell] carries both a continuous
 *    [Heatcell.intensity] and a discrete [Heatcell.bucket] so meaning survives without
 *    colour — a screen reader can speak the bucket, a pattern can encode it. Colour is one
 *    redundant channel, never the only one.
 */

/**
 * One token's inspection scores. [logprob] is the log-probability the model assigned the
 * token it emitted; [entropy] is the uncertainty of the full next-token distribution at
 * that step (higher = more uncertain). [topK] is the optional ranked alternatives
 * `(token, probability)` — empty when the provider gives only the chosen token's logprob.
 */
data class TokenScore(
    val token: String,
    val logprob: Double,
    val entropy: Double,
    val topK: List<Pair<String, Double>> = emptyList(),
)

/**
 * How much logprob/entropy detail the active engine actually provides — shown plainly so
 * the inspector is never mistaken for fabricating numbers it doesn't have.
 *  - [FULL]        — on-device (llama.cpp): full per-token logprobs + entropy.
 *  - [TOPK_ONLY]   — some cloud providers: only the top-k alternatives, no full entropy.
 *  - [UNAVAILABLE] — a cloud provider that returns no logprobs at all.
 */
enum class Availability { FULL, TOPK_ONLY, UNAVAILABLE }

/**
 * One cell of the uncertainty heatmap for a token. [intensity] is normalized to `0..1`
 * (higher = more uncertain across this sequence); [bucket] is the same signal discretized
 * to `0..(buckets-1)`. Both are present on purpose: the discrete bucket lets the meaning be
 * conveyed without relying on colour alone (Doc 00 §3.5) — a screen reader speaks the
 * bucket, an accessible pattern maps to it.
 */
data class Heatcell(
    val token: String,
    /** 0..1, higher = more uncertain. */
    val intensity: Double,
    /** 0..(buckets-1), the discretized [intensity] for non-colour-alone encoding. */
    val bucket: Int,
)

/** Pure operations over a sequence of [TokenScore]s. Deterministic; no clock, no random. */
object TokenInspector {

    /**
     * Map each token's **entropy** (its uncertainty) to a [Heatcell]. Intensity is
     * min-max normalized across the whole sequence: the max-entropy token → `1.0`, the
     * min → `0.0`. If every token shares the same entropy (no spread, including a
     * single-token sequence) all intensities are `0.0` — there is nothing to highlight.
     *
     * The intensity is then discretized into one of [buckets] discrete levels
     * (`0..buckets-1`) so the signal is also available without colour. Bucketing is the
     * even split `floor(intensity * buckets)`, clamped so intensity `1.0` lands in the
     * top bucket. Deterministic and order-preserving.
     */
    fun heatmap(tokens: List<TokenScore>, buckets: Int = 5): List<Heatcell> {
        require(buckets >= 1) { "buckets must be >= 1" }
        if (tokens.isEmpty()) return emptyList()

        val entropies = tokens.map { it.entropy }
        val min = entropies.min()
        val max = entropies.max()
        val span = max - min

        return tokens.map { t ->
            val intensity = if (span == 0.0) 0.0 else (t.entropy - min) / span
            val bucket = (intensity * buckets).toInt().coerceIn(0, buckets - 1)
            Heatcell(token = t.token, intensity = intensity, bucket = bucket)
        }
    }

    /**
     * The single highest-entropy (most uncertain) token — the one the spoken summary
     * calls out as "highest uncertainty at token N". On a tie the first such token wins
     * (order-preserving). Null when there are no tokens.
     */
    fun highestUncertainty(tokens: List<TokenScore>): TokenScore? =
        tokens.maxByOrNull { it.entropy }

    /**
     * A short, legible, accessibility-friendly summary line. Honest about [availability]:
     *  - [Availability.UNAVAILABLE] → "Logprobs not provided by this provider."
     *  - empty sequence             → "No tokens to inspect."
     *  - [Availability.TOPK_ONLY]   → top-k note + the most-uncertain token.
     *  - [Availability.FULL]        → token count + the most-uncertain token (by entropy).
     */
    fun summary(tokens: List<TokenScore>, availability: Availability): String {
        if (availability == Availability.UNAVAILABLE) {
            return "Logprobs not provided by this provider."
        }
        if (tokens.isEmpty()) return "No tokens to inspect."

        val peak = highestUncertainty(tokens)!!
        val n = tokens.size
        val prefix = if (availability == Availability.TOPK_ONLY) {
            "Top-k only ($n tokens)"
        } else {
            "$n tokens"
        }
        return "$prefix; highest uncertainty at \"${peak.token}\" (entropy ${peak.entropy})."
    }
}
