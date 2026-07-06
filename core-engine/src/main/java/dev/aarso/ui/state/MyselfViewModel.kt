package dev.aarso.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.aarso.domain.ledger.Budget
import dev.aarso.domain.state.UiState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * **Thin ViewModel for the "Myself" usage view** (Doc 07).
 *
 * This is the compile-only `androidx.lifecycle` glue that wraps the pure, JVM-tested
 * [MyselfPresenter]: it observes the on-device usage ledger via a [LedgerSource] and folds each
 * emission through [MyselfPresenter.present], carrying the user's own [Budget] rings. All of
 * the real state derivation (totals, provenance split, provider/model rollups, budget rings,
 * estimated-share) lives in the presenter where it is unit-tested — this class adds nothing but
 * the lifecycle-scoped subscription, so there is no untested logic here.
 *
 * @param source the on-device ledger stream (the data-layer seam — see [LedgerSource]).
 * @param budgets the user's own informational ceilings; each becomes one budget ring. Defaults
 *   to none.
 */
class MyselfViewModel(
    private val source: LedgerSource,
    private val budgets: List<Budget> = emptyList(),
) : ViewModel() {

    /**
     * The "Myself" view state. Derived by mapping each ledger emission through
     * [MyselfPresenter.present] and sharing the latest [UiState] to collectors. Stays
     * [UiState.Loading] until the first emission; [SharingStarted.WhileSubscribed] (5s grace)
     * keeps the upstream alive across brief recompositions without leaking when nothing
     * observes.
     */
    val state: StateFlow<UiState<MyselfView>> =
        source.entries()
            .map { MyselfPresenter.present(it, budgets) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                UiState.Loading,
            )
}
