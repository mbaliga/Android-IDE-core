package dev.aarso.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.aarso.domain.library.ConvFilter
import dev.aarso.domain.library.ConvSort
import dev.aarso.domain.state.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.Locale

/**
 * **Thin ViewModel for the Conversations room** (Doc 02 — the left-room list).
 *
 * This is the compile-only `androidx.lifecycle` glue over the pure, JVM-tested
 * [ConversationsPresenter]. It holds the room's *mutable* view controls — the active filter
 * tab, sort key, and grouped toggle — as plain [MutableStateFlow]s, then [combine]s them with
 * the data-layer's summary + ledger streams (via [ConversationsSource]) and folds every change
 * through [ConversationsPresenter.present]. The presenter does all the filter→sort→group work
 * and flair derivation; this class only wires the live inputs together, so there is no untested
 * logic here.
 *
 * The [Locale] is captured at construction (the room's collation locale for [ConvSort.TITLE])
 * and the [ConversationsSource.projectName] resolver is passed straight through as the
 * presenter's project-name lookup.
 *
 * @param source the on-device conversations + ledger streams (the data-layer seam).
 * @param locale the locale used to collate title sorts.
 */
class ConversationsViewModel(
    private val source: ConversationsSource,
    private val locale: Locale,
) : ViewModel() {

    private val filter = MutableStateFlow(ConvFilter.ALL)
    private val sort = MutableStateFlow(ConvSort.RECENT)
    private val grouped = MutableStateFlow(false)

    /** Switch the active filter tab. */
    fun setFilter(f: ConvFilter) {
        filter.value = f
    }

    /** Switch the active sort key. */
    fun setSort(s: ConvSort) {
        sort.value = s
    }

    /** Toggle whether rows are partitioned into project groups. */
    fun setGrouped(b: Boolean) {
        grouped.value = b
    }

    /**
     * The Conversations room view state. Re-derived whenever the source data *or* any of the
     * filter/sort/grouped controls change, by combining all five flows and folding them through
     * [ConversationsPresenter.present] with [ConversationsSource.projectName] as the resolver.
     * Stays [UiState.Loading] until the first emission; shared with a 5s
     * [SharingStarted.WhileSubscribed] grace so the upstream survives brief recompositions.
     */
    val state: StateFlow<UiState<ConversationsView>> =
        combine(
            source.summaries(),
            source.ledgerByConversation(),
            filter,
            sort,
            grouped,
        ) { summaries, ledgerByConversation, activeFilter, activeSort, isGrouped ->
            ConversationsPresenter.present(
                all = summaries,
                ledgerByConversation = ledgerByConversation,
                filter = activeFilter,
                sort = activeSort,
                grouped = isGrouped,
                locale = locale,
                projectName = source::projectName,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            UiState.Loading,
        )
}
