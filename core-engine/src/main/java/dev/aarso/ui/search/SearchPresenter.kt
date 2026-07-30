package dev.aarso.ui.search

import dev.aarso.domain.search.SearchHit
import dev.aarso.domain.search.query.Diagnostic
import dev.aarso.domain.search.query.ParsedQuery
import dev.aarso.domain.search.query.QueryChip
import java.time.ZoneId
import java.util.Locale

/**
 * Pure derivation of the search overlay's whole screen state from raw inputs — no Android,
 * no clock, no I/O, following this repo's presenter convention. [SearchViewModel] owns the
 * live inputs (query text, FTS hits, index status, saved searches); this function does the
 * actual UI-state derivation, so it's the part of the overlay that's unit-testable without a
 * device.
 *
 * One deliberate, documented gap: [ParsedQuery.facets] (the `is:`/`has:`/etc. chips a user
 * typed) are shown and round-trip in the query box, but are **not yet applied to [hits]** —
 * [dev.aarso.data.search.SearchQuery] (WP7) only executes the lexical/phrase portion of a
 * query against the FTS5 index; wiring facet predicates into that SQL path is a follow-up, not
 * part of this pass. [UiState.hasUnappliedFacets] lets the UI say so honestly rather than
 * silently pretending a typed `is:starred` filtered anything.
 */
object SearchPresenter {

    data class SavedSearchRow(val id: String, val name: String, val query: String, val pinned: Boolean)

    data class UiState(
        val queryText: String,
        val chips: List<QueryChip>,
        val diagnostics: List<Diagnostic>,
        val hasUnappliedFacets: Boolean,
        val rows: List<SearchResultsPresenter.ResultRow>,
        val indexing: Boolean,
        val indexedCount: Long,
        val savedSearches: List<SavedSearchRow>,
        val expandedResultId: String?,
    ) {
        val isZeroState: Boolean get() = queryText.isBlank()
        val isNoResults: Boolean get() = !isZeroState && !indexing && rows.isEmpty()
    }

    fun present(
        parsed: ParsedQuery,
        hits: List<SearchHit>,
        indexing: Boolean,
        indexedCount: Long,
        savedSearches: List<SavedSearchRow>,
        expandedResultId: String?,
        nowMillis: Long,
        zone: ZoneId,
        locale: Locale,
    ): UiState = UiState(
        queryText = parsed.rawText,
        chips = parsed.chips,
        diagnostics = parsed.diagnostics,
        hasUnappliedFacets = parsed.facets.isNotEmpty(),
        rows = SearchResultsPresenter.present(hits, nowMillis, zone, locale),
        indexing = indexing,
        indexedCount = indexedCount,
        savedSearches = savedSearches,
        expandedResultId = expandedResultId,
    )
}
