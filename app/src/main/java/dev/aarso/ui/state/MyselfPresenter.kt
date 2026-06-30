package dev.aarso.ui.state

import dev.aarso.domain.ledger.Budget
import dev.aarso.domain.ledger.BudgetRing
import dev.aarso.domain.ledger.LedgerAggregations
import dev.aarso.domain.ledger.LedgerEntry
import dev.aarso.domain.ledger.RingState
import dev.aarso.domain.state.UiState

/**
 * The **pure presentation state** for the "Myself" usage view (Doc 07).
 *
 * This is the verifiable half of the screen's MVVM wiring: all of the state *derivation*
 * lives here as a pure, deterministic fold over a `List<LedgerEntry>` (plus the user's own
 * [Budget] rings), with **no Android, no `androidx.lifecycle`, no clock, no I/O**. The thin
 * `androidx` ViewModel that observes a repository and calls [MyselfPresenter.present] is a
 * separate, compile-only step — keeping all the math here means the whole surface is
 * JVM-unit-tested rather than trusted.
 *
 * On-thesis: the ledger is an *on-device aggregation* (binding rule — no telemetry, no
 * server rollup), and [MyselfView] surfaces the sovereignty headline ([provenanceSplit])
 * and the estimated-vs-measured honesty signal ([estimatedCount]) so a cost figure is
 * never silently presented as measured truth.
 */
data class MyselfView(
    /** Grand totals across every entry — tokens, estimated cost, turn count. */
    val totals: LedgerAggregations.Totals,
    /** On-device vs cloud split, including the sovereignty ratio (the headline). */
    val provenanceSplit: LedgerAggregations.ProvenanceSplit,
    /** Provider → rollup, ordered by total tokens descending (heaviest first). */
    val byProvider: Map<String, LedgerAggregations.ProviderRollup>,
    /** Model → rollup, ordered by total tokens descending. */
    val byModel: Map<String, LedgerAggregations.ModelRollup>,
    /** One [RingState] per user-set [Budget], filled in the order the budgets were given. */
    val budgetRings: List<RingState>,
    /** How many entries carry an *estimated* (vs provider-measured) cost. */
    val estimatedCount: Int,
)

/**
 * Derives a [UiState] of [MyselfView] from raw ledger entries — pure and deterministic.
 *
 * Contract:
 *  - `entries.isEmpty()` → [UiState.Empty] (the *useful* empty: the surface should teach
 *    the user that there is no usage to reflect on yet, not render a misleading zero view).
 *  - otherwise → [UiState.Ready] wrapping a [MyselfView] computed by running each
 *    [LedgerAggregations] fold exactly once over [entries].
 */
object MyselfPresenter {

    /**
     * Compute the "Myself" presentation state.
     *
     * @param entries the raw usage ledger (one entry per turn, one per council member).
     * @param budgets the user's own informational ceilings; each becomes one [RingState].
     */
    fun present(entries: List<LedgerEntry>, budgets: List<Budget> = emptyList()): UiState<MyselfView> {
        if (entries.isEmpty()) return UiState.Empty

        val view = MyselfView(
            totals = LedgerAggregations.totals(entries),
            provenanceSplit = LedgerAggregations.provenanceSplit(entries),
            byProvider = LedgerAggregations.byProvider(entries),
            byModel = LedgerAggregations.byModel(entries),
            budgetRings = budgets.map { BudgetRing.fill(entries, it) },
            estimatedCount = LedgerAggregations.estimatedFlagged(entries).count,
        )
        return UiState.Ready(view)
    }
}
