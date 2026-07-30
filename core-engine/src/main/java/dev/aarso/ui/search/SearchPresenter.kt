package dev.aarso.ui.search

import dev.aarso.domain.search.SearchHit
import dev.aarso.domain.search.query.Diagnostic
import dev.aarso.domain.search.query.FacetEvaluator
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
 * Facets are fully applied to [hits] by [dev.aarso.data.search.SearchQuery] /
 * [dev.aarso.domain.search.query.FacetEvaluator]. Two honest caveats still surface to the user:
 * an unbacked facet (`tool:`, `tag:`, …) produces a [Diagnostic.UnindexedFacet], and the one
 * lossy evaluation shape — a facet OR'd with text — is flagged via [UiState.hasLossyDisjunction]
 * rather than left to be discovered as mysteriously-missing rows.
 */
object SearchPresenter {

    data class SavedSearchRow(val id: String, val name: String, val query: String, val pinned: Boolean)

    data class UiState(
        val queryText: String,
        val chips: List<QueryChip>,
        val diagnostics: List<Diagnostic>,
        val hasLossyDisjunction: Boolean,
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
        hasLossyDisjunction = FacetEvaluator.hasLossyDisjunction(parsed.root),
        rows = SearchResultsPresenter.present(hits, nowMillis, zone, locale),
        indexing = indexing,
        indexedCount = indexedCount,
        savedSearches = savedSearches,
        expandedResultId = expandedResultId,
    )
}
