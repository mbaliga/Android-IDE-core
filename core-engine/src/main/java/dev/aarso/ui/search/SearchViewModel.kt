package dev.aarso.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dev.aarso.AarsoApp
import dev.aarso.data.search.SearchDatabase
import dev.aarso.data.search.SearchRepository
import dev.aarso.domain.search.SearchHit
import dev.aarso.domain.search.query.ParsedQuery
import dev.aarso.domain.search.query.QueryParser
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
 * [SearchRepository], and the saved-search table — and hands everything else to that pure
 * function. [SearchOverlay] only ever reads [uiState]; it never touches the repository or the
 * database directly.
 *
 * Indexing runs once, on creation (see [SearchRepository]'s KDoc for why: there is no
 * app-start backfill hook yet, so "the overlay was just opened" is the trigger this pass
 * relies on). A conversation added or edited after that stays unsearched until the overlay is
 * reopened — a known, deliberate M3-scope gap, not an oversight.
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

    private val parsed: StateFlow<ParsedQuery> = queryText
        .map { text -> QueryParser.parse(text, System.currentTimeMillis(), zone) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, QueryParser.parse(""))

    private val hits: StateFlow<List<SearchHit>> = queryText
        .debounce(120)
        .distinctUntilChanged()
        .flatMapLatest { text ->
            flow { emit(if (text.isBlank()) emptyList() else repository.search(text, System.currentTimeMillis())) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val indexState: StateFlow<IndexState> = combine(indexing, indexedCount) { i, c -> IndexState(i, c) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, IndexState(indexing = true, count = 0L))

    private val savedSearches: StateFlow<List<SearchPresenter.SavedSearchRow>> =
        database.searchQueries.selectSavedSearches().asFlow().mapToList(Dispatchers.IO)
            .map { rows -> rows.map { SearchPresenter.SavedSearchRow(it.id, it.name, it.query, it.pinned == 1L) } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val uiState: StateFlow<SearchPresenter.UiState> = combine(
        parsed, hits, indexState, savedSearches, expandedResultId,
    ) { p, h, idx, saved, expanded ->
        SearchPresenter.present(p, h, idx.indexing, idx.count, saved, expanded, System.currentTimeMillis(), zone, locale)
    }.stateIn(
        viewModelScope, SharingStarted.Eagerly,
        SearchPresenter.present(QueryParser.parse(""), emptyList(), true, 0L, emptyList(), null, 0L, zone, locale),
    )

    init {
        viewModelScope.launch {
            indexing.value = true
            repository.reindexAll(System.currentTimeMillis())
            indexedCount.value = repository.indexedCount()
            indexing.value = false
        }
    }

    fun onQueryChange(text: String) {
        queryText.value = text
    }

    fun toggleExplain(convId: String) {
        expandedResultId.value = if (expandedResultId.value == convId) null else convId
    }

    fun applySavedSearch(query: String) {
        queryText.value = query
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

    private data class IndexState(val indexing: Boolean, val count: Long)

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AarsoApp
                val c = app.container
                SearchViewModel(c.searchRepository, c.searchDatabaseHandle.database)
            }
        }
    }
}
