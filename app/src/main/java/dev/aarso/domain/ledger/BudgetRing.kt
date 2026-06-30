package dev.aarso.domain.ledger

/**
 * A **budget ring** — the user's own informational ceiling on usage, surfaced in the
 * "Myself" views (Doc 07). On-thesis and explicitly *non-manipulative*: this is not a
 * paywall, a throttle, or a dark-pattern nudge. It is a ring you set for *yourself* so a
 * cloud-cost or cloud-token figure has a denominator you chose. Crossing it changes
 * nothing the app does — it just lets you *see* that you crossed it.
 *
 * Pure domain, JVM-tested: [fill] folds a `List<LedgerEntry>` against a [Budget] and
 * reports the fraction used, clamped to `[0,1]`, plus whether the ceiling was crossed.
 */
data class Budget(
    val kind: BudgetKind,
    /** The ceiling, in the unit implied by [kind] (minor currency for cost, tokens otherwise). */
    val ceilingMinorOrTokens: Long,
    /**
     * For [BudgetKind.PROVIDER_COST] only: which provider this ceiling applies to. Ignored
     * by the other kinds. `null` with `PROVIDER_COST` means "no provider selected" → 0 used.
     */
    val provider: String? = null,
)

/**
 * What a [Budget] meters:
 *  - [COST_MINOR] — total estimated cost across every entry (minor currency unit).
 *  - [CLOUD_TOKENS] — tokens that left the device (non-[Provenance.LOCAL] entries).
 *  - [PROVIDER_COST] — estimated cost for one named provider only.
 */
enum class BudgetKind { COST_MINOR, CLOUD_TOKENS, PROVIDER_COST }

/**
 * The state of a budget ring. [fraction] is `used / ceiling` clamped to `[0,1]`; [crossed]
 * is true once `used` reaches or exceeds the ceiling (it can be true while [fraction] reads
 * 1.0). A non-positive ceiling is treated as "unbounded": fraction 0, never crossed.
 */
data class RingState(
    val used: Long,
    val ceiling: Long,
    val fraction: Double,
    val crossed: Boolean,
)

object BudgetRing {

    /** Compute the [RingState] for [budget] over [entries]. Pure; no clock, no I/O. */
    fun fill(entries: List<LedgerEntry>, budget: Budget): RingState {
        val used = when (budget.kind) {
            BudgetKind.COST_MINOR ->
                entries.sumOf { it.estCostMinor }
            BudgetKind.CLOUD_TOKENS ->
                entries.filter { it.provenance != Provenance.LOCAL }.sumOf { it.totalTokens }
            BudgetKind.PROVIDER_COST ->
                if (budget.provider == null) 0L
                else entries.filter { it.provider == budget.provider }.sumOf { it.estCostMinor }
        }
        val ceiling = budget.ceilingMinorOrTokens
        if (ceiling <= 0L) {
            return RingState(used = used, ceiling = ceiling, fraction = 0.0, crossed = false)
        }
        val fraction = (used.toDouble() / ceiling.toDouble()).coerceIn(0.0, 1.0)
        return RingState(used = used, ceiling = ceiling, fraction = fraction, crossed = used >= ceiling)
    }
}
