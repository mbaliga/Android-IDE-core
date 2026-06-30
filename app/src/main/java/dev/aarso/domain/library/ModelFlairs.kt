package dev.aarso.domain.library

import dev.aarso.domain.ledger.LedgerEntry
import dev.aarso.domain.ledger.Provenance

/**
 * **Per-row model flairs** (Doc 02): the small badges on a Conversations row that say
 * *which models touched this chat and where the work ran*. On-thesis legibility — you can
 * see at a glance that a chat was answered on-device (⌂) versus by a watched cloud object
 * (☁), without opening it.
 *
 * A flair is derived purely from the **usage ledger** ([LedgerEntry], one entry per turn,
 * several per Council fan-out). The list is the distinct set of `(model, provenance)` pairs
 * that appear in the chat's entries — so the same model used both on-device and in the
 * cloud yields **two** flairs, because the provenance is the point. Ordering is
 * most-recent-first (by [LedgerEntry.timestampMillis]) so the row leads with the model you
 * last used.
 *
 * Provenance maps to a glyph: [Provenance.LOCAL] → ⌂ (on-device), [Provenance.CLOUD] → ☁
 * (a watched cloud object). **Colour is never the sole encoder** — the glyph carries the
 * meaning, so the badge stays legible to colour-blind users and in monochrome. (A single
 * flair is only ever LOCAL or CLOUD; [Provenance.MIXED] is an aggregate value and does not
 * appear on a per-turn-derived flair.)
 *
 * The flair strip is bounded: at most `max` flairs render, with a `"+k more"` overflow
 * count for the rest. Pure domain, JVM-tested — no Android, no clock, no I/O.
 */
data class Flair(
    /** Model identifier as the engine saw it (e.g. `"qwen2.5-7b"`, `"claude-…"`). */
    val model: String,
    /** Where this model ran for this chat: [Provenance.LOCAL] (⌂) or [Provenance.CLOUD] (☁). */
    val provenance: Provenance,
)

/**
 * The bounded flair strip for one row: the visible [flairs] (≤ the requested `max`) plus
 * [moreCount], the number of further distinct flairs collapsed into `"+k more"`.
 */
data class FlairSet(
    /** Visible flairs, most-recent-first, length ≤ the `max` passed to [ModelFlairs.deriveFlairs]. */
    val flairs: List<Flair>,
    /** Count of additional distinct flairs not shown; render as `"+$moreCount more"`. 0 if none. */
    val moreCount: Int,
)

/** Pure flair derivation over a chat's usage-ledger entries. */
object ModelFlairs {

    /**
     * Derive the bounded [FlairSet] for a chat from its [entries].
     *
     * Each distinct `(model, provenance)` pair becomes one [Flair]; the first time a pair
     * is seen (scanning newest-first) fixes its position, so the strip is **most-recent
     * first** by [LedgerEntry.timestampMillis]. Ties on timestamp fall back to
     * `(model, provenance)` order so the result is fully deterministic. The first [max]
     * distinct flairs are kept; [FlairSet.moreCount] is the number of remaining distinct
     * flairs.
     *
     * `max` is coerced to ≥ 0; a non-positive `max` yields no visible flairs and a
     * `moreCount` equal to the total distinct count.
     */
    fun deriveFlairs(entries: List<LedgerEntry>, max: Int = 3): FlairSet {
        // Most-recent first, deterministic tie-break on model then provenance name.
        val ordered = entries.sortedWith(
            compareByDescending<LedgerEntry> { it.timestampMillis }
                .thenBy { it.model }
                .thenBy { it.provenance.name }
        )

        val distinct = LinkedHashSet<Flair>()
        for (e in ordered) {
            distinct.add(Flair(e.model, e.provenance))
        }

        val cap = max.coerceAtLeast(0)
        val all = distinct.toList()
        val visible = all.take(cap)
        return FlairSet(flairs = visible, moreCount = all.size - visible.size)
    }
}
