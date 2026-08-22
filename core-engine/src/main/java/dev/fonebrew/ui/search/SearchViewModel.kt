package dev.fonebrew.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dev.fonebrew.FonebrewApp
import dev.fonebrew.data.search.SearchDatabase
import dev.fonebrew.data.search.SearchRepository
import dev.fonebrew.domain.search.SearchHit
import dev.fonebrew.domain.search.query.ParsedQuery
import dev.fonebrew.domain.search.query.QueryParser
import dev.fonebrew.domain.search.query.QuerySuggestions
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The app-wide search overlay's live state (S1-S5/S10/S14). Owns exactly what
 * [SearchPresenter] can't — the clock, the debounced query→hit pipeline against
 * [SearchRepository], the saved-search table, the recent-search table, and the facet values
 * autocomplete completes to — and hands everything else to that pure function. [SearchOverlay]
 * only ever reads [uiState]; it never touches the repository or the database directly.
 *
 * Indexing starts on creation and then *keeps running* for this ViewModel's lifetime via
 * [SearchRepository.keepIndexFresh], so a conversation edited while the app is open becomes
 * searchable without reopening anything. Indexing while the app is *not* running (a
 * `WorkManager` job, the spec's battery/thermal scheduling ladder) remains M6 territory.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val repository: SearchRepository,
    private val database: SearchDatabase,
) : ViewModel() {

    private val zone = ZoneId.systemDefault()
    private val locale = Locale.getDefault()

    private val queryText = MutableStateFlow("")
    private val indexing = MutableStateFlow(true)
    private val indexedCount = MutableStateFlow(0L)
    private val expandedResultId = MutableStateFlow<String?>(null)

    /**
     * Whether the operator legend is open. Lives here, not in the overlay's own composition, so
     * the choice survives closing and reopening search within a session — this ViewModel is
     * hoisted at `SpatialRoot` level while the overlay is a short-lived `Dialog`.
     *
     * It is deliberately **not** persisted across process death: the durable home for a UI
     * preference in this app is `SessionStore`, which this cluster doesn't own. Default-collapsed
     * on a cold start is also the safer default for the owner's actual complaint.
     */
    private val operatorsExpanded = MutableStateFlow(false)

    /** Guards the close-path history write so leaving and re-entering the overlay on the same
     *  query doesn't keep rewriting the row (and, worse, overwrite an `opened_conv_id` with
     *  null). Reset whenever the query text actually changes. */
    private var lastRecordedQuery: String? = null

    private val parsed: StateFlow<ParsedQuery> = queryText
        .map { text -> QueryParser.parse(text, System.currentTimeMillis(), zone) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, QueryParser.parse(""))

    private val hits: StateFlow<List<SearchHit>> = queryText
        .debounce(120)
        .distinctUntilChanged()
        .flatMapLatest { text ->
            flow {
                val now = System.currentTimeMillis()
                // Parsed here (not inside the repository) so the exact tree the UI is painting
                // chips and diagnostics from is the one that gets executed.
                emit(if (text.isBlank()) emptyList() else repository.search(QueryParser.parse(text, now, zone), now, zone = zone))
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val indexState: StateFlow<IndexState> = combine(indexing, indexedCount) { i, c -> IndexState(i, c) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, IndexState(indexing = true, count = 0L))

    private val savedSearches: StateFlow<List<SearchPresenter.SavedSearchRow>> =
        database.searchQueries.selectSavedSearches().asFlow().mapToList(Dispatchers.IO)
            .map { rows -> rows.map { SearchPresenter.SavedSearchRow(it.id, it.name, it.query, it.pinned == 1L) } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Distinct past queries, newest first. Written only on a *committed* search — see
     *  [recordSearch] — so this is a list of searches that went somewhere, not a keystroke log. */
    private val recentSearches: StateFlow<List<String>> =
        database.searchQueries.selectRecentSearches(RECENT_LIMIT).asFlow().mapToList(Dispatchers.IO)
            .map { rows -> rows.map { it.query }.distinct() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** What `project:` and `model:` can actually complete to, read live from the index so a
     *  completion never offers a value that matches nothing. */
    private val facetValues: StateFlow<QuerySuggestions.IndexedValues> = combine(
        database.searchQueries.selectDistinctProjects().asFlow().mapToList(Dispatchers.IO),
        database.searchQueries.selectDistinctModelIds().asFlow().mapToList(Dispatchers.IO),
    ) { projects, modelRows ->
        QuerySuggestions.IndexedValues(
            projects = projects.filterNotNull().filter { it.isNotBlank() },
            // conv_facets.model_ids is the comma-joined column (SearchProjector) — split back
            // out here rather than in SQL; SQLite has no string-split worth writing a CTE for.
            models = modelRows.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted(),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, QuerySuggestions.IndexedValues())

    /** kotlinx.coroutines' typed [combine] tops out at 5 flows, and the overlay now needs 8.
     *  Nested rather than switching to the untyped vararg form (same call this repo's
     *  [SearchRepository] already makes for the same reason) — the four inputs the *empty state*
     *  and autocomplete need, folded into one. */
    private val auxiliary: StateFlow<Auxiliary> = combine(
        savedSearches, recentSearches, facetValues, operatorsExpanded,
    ) { saved, recent, values, expanded -> Auxiliary(saved, recent, values, expanded) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Auxiliary())

    val uiState: StateFlow<SearchPresenter.UiState> = combine(
        parsed, hits, indexState, auxiliary, expandedResultId,
    ) { p, h, idx, aux, expanded ->
        SearchPresenter.present(
            parsed = p,
            hits = h,
            indexing = idx.indexing,
            indexedCount = idx.count,
            savedSearches = aux.savedSearches,
            expandedResultId = expanded,
            nowMillis = System.currentTimeMillis(),
            zone = zone,
            locale = locale,
            recentSearches = aux.recentSearches,
            facetValues = aux.facetValues,
            operatorsExpanded = aux.operatorsExpanded,
        )
    }.stateIn(
        viewModelScope, SharingStarted.Eagerly,
        SearchPresenter.present(QueryParser.parse(""), emptyList(), true, 0L, emptyList(), null, 0L, zone, locale),
    )

    init {
        viewModelScope.launch {
            indexing.value = true
            // Backfills, then keeps indexing for this ViewModel's lifetime — a conversation
            // edited while the overlay is open becomes searchable without reopening it. Never
            // returns; cancelled with viewModelScope.
            repository.keepIndexFresh(
                onIndexed = { count ->
                    indexedCount.value = count
                    indexing.value = false
                },
            )
        }
    }

    fun onQueryChange(text: String) {
        if (text != queryText.value) lastRecordedQuery = null
        queryText.value = text
    }

    fun toggleExplain(convId: String) {
        expandedResultId.value = if (expandedResultId.value == convId) null else convId
    }

    fun toggleOperators() {
        operatorsExpanded.value = !operatorsExpanded.value
    }

    fun applySavedSearch(query: String) {
        onQueryChange(query)
    }

    /** Accepts one autocomplete row: the in-progress token is replaced, the rest of the query
     *  left alone. */
    fun applySuggestion(suggestion: QuerySuggestions.Suggestion) {
        onQueryChange(QuerySuggestions.apply(queryText.value, suggestion))
    }

    /**
     * Removes the chip at [index] — what makes the chip row above the results real. It rendered
     * as a row of removable filter chips from the day it shipped but its click handler was
     * literally `{}`, so tapping one did nothing at all.
     *
     * The rebuild goes through the chips, not through string surgery on the query text: chips
     * round-trip by construction (`ChipBuilder` → `renderCanonical` → reparse), so the result is
     * a query the parser produces the same tree from, minus one node.
     */
    fun removeChip(index: Int) {
        val state = uiState.value
        if (index !in state.chips.indices) return
        onQueryChange(state.withoutChip(index))
    }

    /** The no-results escape hatch: same words, no facets. */
    fun dropFacets() {
        onQueryChange(uiState.value.withoutFacets())
    }

    fun saveCurrentQuery(name: String) {
        val text = queryText.value
        if (text.isBlank() || name.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            database.searchQueries.insertSavedSearch(
                id = UUID.randomUUID().toString(),
                name = name,
                query = text,
                pinned = 0L,
                created_at = System.currentTimeMillis(),
                sort_key = null,
            )
        }
    }

    fun deleteSavedSearch(id: String) {
        viewModelScope.launch(Dispatchers.IO) { database.searchQueries.deleteSavedSearch(id) }
    }

    /** A result was opened — the strongest signal that this search was the one the user meant. */
    fun onResultOpened(convId: String) = recordSearch(openedConvId = convId)

    /** The overlay was dismissed. Records the query only if it actually found something and
     *  hasn't already been recorded, so an abandoned dead-end never becomes history. */
    fun onOverlayClosed() = recordSearch(openedConvId = null)

    fun clearRecentSearches() {
        // Local-first app, sensitive data: this deletes the rows, it doesn't hide them.
        viewModelScope.launch(Dispatchers.IO) { database.searchQueries.clearSearchHistory() }
    }

    /**
     * Writes one row of `search_history`, which had a schema and no queries at all until now.
     *
     * Deliberately **not** driven off the debounced hit pipeline: that fires on every settled
     * pause in typing, so "gradle" would deposit `g`, `gr`, `gra`… — a keystroke log wearing a
     * history label. It fires on the two moments a search is actually *committed*: a result
     * opened, or the overlay dismissed on a query that found something.
     */
    private fun recordSearch(openedConvId: String?) {
        val text = queryText.value.trim()
        if (text.isBlank()) return
        val count = hits.value.size
        if (openedConvId == null) {
            if (count == 0) return
            if (text == lastRecordedQuery) return
        }
        lastRecordedQuery = text
        viewModelScope.launch(Dispatchers.IO) {
            database.transaction {
                // Dedupe by exact query text: one row per distinct query, newest write wins.
                database.searchQueries.deleteSearchHistoryFor(text)
                database.searchQueries.insertSearchHistory(
                    at = System.currentTimeMillis(),
                    query = text,
                    result_count = count.toLong(),
                    opened_conv_id = openedConvId,
                )
                database.searchQueries.trimSearchHistory(HISTORY_KEEP)
            }
        }
    }

    private data class IndexState(val indexing: Boolean, val count: Long)

    /** The four inputs that don't come from the query itself, folded into one flow — see
     *  [auxiliary]. */
    private data class Auxiliary(
        val savedSearches: List<SearchPresenter.SavedSearchRow> = emptyList(),
        val recentSearches: List<String> = emptyList(),
        val facetValues: QuerySuggestions.IndexedValues = QuerySuggestions.IndexedValues(),
        val operatorsExpanded: Boolean = false,
    )

    companion object {
        /** Recent searches offered in the empty state. Small on purpose: the empty state is meant
         *  to be quiet. */
        private const val RECENT_LIMIT = 6L

        /** Rows of history kept on disk. Bounded because nothing else prunes this table. */
        private const val HISTORY_KEEP = 50L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as FonebrewApp
                val c = app.container
                SearchViewModel(c.searchRepository, c.searchDatabaseHandle.database)
            }
        }
    }
}
