package dev.fonebrew.domain.thread

import dev.fonebrew.domain.instrument.Confidence

/**
 * **2026-08-29 audit, gap 4 ("influence not edge-wise")** — aggregates one turn's per-token
 * entropy ([dev.fonebrew.domain.GeneratedToken.entropy], plumbed from
 * [dev.fonebrew.inference.InferenceEngine] from token one) into a single 0..1 message-level
 * confidence, and names the `MessageNode.metadata` key it is persisted under.
 *
 * **Mean entropy, not mean logprob** (the audit's "pick one, justify in a comment"): entropy is
 * already bounded and mapped onto 0..1 by [Confidence.fromEntropy] — the exact instrument the
 * per-token §5a colouring already uses (`ChatScreen`'s live stream colouring) — so a message-level
 * aggregate over that *same* scale needs no second, ad-hoc normalization the way a raw mean
 * logprob (unbounded, and not comparable across models with different vocab sizes) would. Using
 * the same instrument at both granularities also means the number this file produces reads on the
 * same 0..1 "confident..uncertain" scale a user has already seen per-token, not a second scale
 * they'd have to learn.
 *
 * **Computed once, at persist time**, from the in-memory token stream the caller
 * ([dev.fonebrew.ui.ChatViewModel]'s send-turn flow) already holds for that turn — never
 * recomputed or backfilled for an older message that never captured it (a cloud turn, the Echo
 * engine, or any message persisted before this field existed all correctly read back `null`,
 * never a fabricated guess). Pure, JVM-tested.
 */
object MessageConfidence {

    /** The `MessageNode.metadata` key an assistant node's aggregate confidence is stored under,
     *  as a plain decimal string (`java.lang.Double.toString` round-trips exactly via
     *  [String.toDoubleOrNull]) — never present in metadata when [fromEntropies] returns null. */
    const val METADATA_KEY = "confidence"

    /**
     * Mean of every token's [Confidence.fromEntropy] this turn reported; `null` if none of
     * [entropies] carried one (never averages a partial/empty set into a fake number — the same
     * "materially absent, not faked" rule this codebase applies everywhere else optional
     * per-token data is missing).
     */
    fun fromEntropies(entropies: List<Float?>): Double? {
        val scored = entropies.mapNotNull { Confidence.fromEntropy(it) }
        if (scored.isEmpty()) return null
        return scored.map { it.toDouble() }.average()
    }
}
