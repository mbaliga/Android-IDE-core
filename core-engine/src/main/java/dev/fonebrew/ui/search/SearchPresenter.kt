package dev.fonebrew.ui.search

import dev.fonebrew.domain.search.SearchHit
import dev.fonebrew.domain.search.query.ChipKind
import dev.fonebrew.domain.search.query.Diagnostic
import dev.fonebrew.domain.search.query.FacetEvaluator
import dev.fonebrew.domain.search.query.QueryCompiler
import dev.fonebrew.domain.search.query.ParsedQuery
import dev.fonebrew.domain.search.query.QueryChip
import dev.fonebrew.domain.search.query.QuerySuggestions
import dev.fonebrew.domain.search.query.facetCaveat
import dev.fonebrew.domain.search.query.toQueryText
import java.time.ZoneId
import java.util.Locale

/**
 * Pure derivation of the search overlay's whole screen state from raw inputs — no Android,
 * no clock, no I/O, following this repo's presenter convention. [SearchViewModel] owns the
 * live inputs (query text, FTS hits, index status, saved searches, recent searches, the facet
 * values autocomplete draws on); this function does the actual UI-state derivation, so it's the
 * part of the overlay that's unit-testable without a device.
 *
 * Facets are fully applied to [hits] by [dev.fonebrew.data.search.SearchQuery] /
 * [dev.fonebrew.domain.search.query.FacetEvaluator]. Three honest caveats surface to the user:
 * an unbacked facet (`tool:`, `tag:`, `is:archived`, …) produces a [Diagnostic.UnindexedFacet],
 * a backed-but-coarser-than-its-name facet produces a [UiState.facetCaveats] line, and the one
 * lossy evaluation shape — a facet OR'd with text — is flagged via [UiState.hasLossyDisjunction]
 * rather than left to be discovered as mysteriously-missing rows.
 */
object SearchPresenter {

    data class SavedSearchRow(val id: String, val name: String, val query: String, val pinned: Boolean)

    data class UiState(
        val queryText: String,
        val chips: List<QueryChip>,
        val diagnostics: List<Diagnostic>,
        /** What a *working* facet actually measures, when its name promises more — see
         *  [dev.fonebrew.domain.search.query.facetCaveat]. Not errors: these filters do run. */
        val facetCaveats: List<String>,
        val hasLossyDisjunction: Boolean,
        /** The plain words to hand to a chat's find bar when a result is opened (S9
         *  continuity) — facet/regex syntax stripped. Blank for a facet-only query, which has
         *  no text to look for inside the conversation. */
        val findText: String,
        val rows: List<SearchResultsPresenter.ResultRow>,
        val indexing: Boolean,
        val indexedCount: Long,
        val savedSearches: List<SavedSearchRow>,
        /** Distinct past queries, newest first — the zero state's one genuinely personal thing.
         *  Sensitive by nature, hence the overlay's Clear affordance. */
        val recentSearches: List<String>,
        /** As-you-type completions for the token being typed; empty whenever nothing is being
         *  typed, which is most of the time. */
        val suggestions: List<QuerySuggestions.Suggestion>,
        val expandedResultId: String?,
        /** Whether the operator legend is open. Collapsed by default — the owner's actual
         *  complaint was that this help was permanent furniture on a screen he always arrives at
         *  blank. */
        val operatorsExpanded: Boolean,
    ) {
        val isZeroState: Boolean get() = queryText.isBlank()
        val isNoResults: Boolean get() = !isZeroState && !indexing && rows.isEmpty()

        /** Whether the query carries at least one top-level facet chip — decides whether the
         *  no-results state can honestly offer "search without filters". A facet buried inside a
         *  parenthesised group is deliberately not counted: [withoutFacets] can't strip it
         *  without rewriting the group. */
        val hasFacets: Boolean get() = chips.any { it.kind == ChipKind.FACET }

        /** Whether to show the index-status line at all: while indexing is genuinely running, or
         *  on a genuine first run (nothing indexed yet). A permanent "N conversations indexed"
         *  banner is exactly the always-on furniture this state exists to stop showing. */
        val showIndexStatus: Boolean get() = indexing || indexedCount == 0L

        /** This query with every top-level facet chip dropped, for the no-results escape hatch.
         *  Chips round-trip through the parser by construction (see [QueryChip]), so this is a
         *  reparseable query, not string surgery. */
        fun withoutFacets(): String = chips.filter { it.kind != ChipKind.FACET }.toQueryText()

        /** This query with the chip at [index] removed — what tapping a chip does. */
        fun withoutChip(index: Int): String =
            chips.filterIndexed { i, _ -> i != index }.toQueryText()
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
        recentSearches: List<String> = emptyList(),
        facetValues: QuerySuggestions.IndexedValues = QuerySuggestions.IndexedValues(),
        operatorsExpanded: Boolean = false,
    ): UiState {
        val findText = QueryCompiler.lexicalText(parsed.root)
        return UiState(
            queryText = parsed.rawText,
            chips = parsed.chips,
            diagnostics = parsed.diagnostics,
            facetCaveats = parsed.facets.mapNotNull { facetCaveat(it.field, it.value) }.distinct(),
            hasLossyDisjunction = FacetEvaluator.hasLossyDisjunction(parsed.root),
            findText = findText,
            // findText, not rawText: highlighting has to be told the plain words, or a query like
            // `gradle is:starred` would try to highlight the literal "is:starred".
            rows = SearchResultsPresenter.present(hits, nowMillis, zone, locale, findText),
            indexing = indexing,
            indexedCount = indexedCount,
            savedSearches = savedSearches,
            recentSearches = recentSearches,
            suggestions = QuerySuggestions.suggest(parsed.rawText, facetValues),
            expandedResultId = expandedResultId,
            operatorsExpanded = operatorsExpanded,
        )
    }
}
