package dev.fonebrew.ui.search

import dev.fonebrew.domain.search.LexicalSearch
import dev.fonebrew.domain.search.SearchDoc
import dev.fonebrew.domain.search.SearchKind
import dev.fonebrew.domain.search.query.QueryParser
import dev.fonebrew.domain.search.query.QuerySuggestions
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPresenterTest {

    private val zone = ZoneId.of("UTC")
    private val locale = Locale.US
    private val now = 1_735_689_600_000L

    private fun doc(id: String, title: String, snippet: String = "s", body: String = title) = SearchDoc(
        id = id, title = title, snippet = snippet, body = body,
        lastActivityMillis = now, kind = SearchKind.TEXT,
    )

    private fun present(
        rawText: String,
        hits: List<dev.fonebrew.domain.search.SearchHit> = emptyList(),
        indexing: Boolean = false,
        indexedCount: Long = 12L,
        recentSearches: List<String> = emptyList(),
        facetValues: QuerySuggestions.IndexedValues = QuerySuggestions.IndexedValues(),
        operatorsExpanded: Boolean = false,
    ) =
        SearchPresenter.present(
            parsed = QueryParser.parse(rawText, now, zone),
            hits = hits,
            indexing = indexing,
            indexedCount = indexedCount,
            savedSearches = emptyList(),
            expandedResultId = null,
            nowMillis = now,
            zone = zone,
            locale = locale,
            recentSearches = recentSearches,
            facetValues = facetValues,
            operatorsExpanded = operatorsExpanded,
        )

    @Test fun `blank query is the zero state, not the no-results state`() {
        val state = present("")
        assertTrue(state.isZeroState)
        assertFalse(state.isNoResults)
    }

    @Test fun `a query with no hits is the no-results state once indexing has finished`() {
        val state = present("gradle", hits = emptyList(), indexing = false)
        assertFalse(state.isZeroState)
        assertTrue(state.isNoResults)
    }

    @Test fun `no-results never fires while still indexing`() {
        val state = present("gradle", hits = emptyList(), indexing = true)
        assertFalse(state.isNoResults)
    }

    @Test fun `a query with hits is neither zero state nor no-results`() {
        val hits = LexicalSearch.search(listOf(doc("a", "gradle build")), "gradle", now)
        val state = present("gradle", hits = hits)
        assertFalse(state.isZeroState)
        assertFalse(state.isNoResults)
        assertEquals(1, state.rows.size)
    }

    @Test fun `a facet AND'd with text is applied exactly, so nothing is flagged`() {
        val state = present("is:starred gradle")
        assertFalse(state.hasLossyDisjunction)
        assertTrue(state.chips.isNotEmpty())
    }

    @Test fun `a facet OR'd with text is flagged as the one lossy shape`() {
        assertTrue(present("(gradle OR is:starred)").hasLossyDisjunction)
    }

    @Test fun `a query with no facets is not flagged`() {
        assertFalse(present("gradle build").hasLossyDisjunction)
    }

    @Test fun `diagnostics from the parser pass through untouched`() {
        val state = present("\"unterminated")
        assertTrue(state.diagnostics.isNotEmpty())
    }

    // ---- the index-status line stops being permanent furniture ----

    @Test fun `index status shows while indexing is actually running`() {
        assertTrue(present("", indexing = true).showIndexStatus)
    }

    @Test fun `index status shows on a genuine first run`() {
        assertTrue(present("", indexing = false, indexedCount = 0L).showIndexStatus)
    }

    @Test fun `index status is hidden once there is an index`() {
        // The owner's complaint in one assertion: "N conversations indexed" was standing furniture
        // on a screen you always arrive at blank.
        assertFalse(present("", indexing = false, indexedCount = 12L).showIndexStatus)
    }

    // ---- chips are real ----

    @Test fun `removing a chip rebuilds a query the parser reproduces`() {
        val state = present("gradle is:starred \"exact phrase\"")
        assertEquals(3, state.chips.size)
        val rebuilt = state.withoutChip(0)
        assertEquals("is:starred \"exact phrase\"", rebuilt)
        assertEquals(2, present(rebuilt).chips.size)
    }

    @Test fun `removing an out-of-range chip is not possible to express`() {
        // withoutChip is total: the ViewModel range-checks, and a bad index here would silently
        // return the whole query rather than throwing.
        val state = present("gradle")
        assertEquals("gradle", state.withoutChip(5))
    }

    // ---- the no-results escape hatch ----

    @Test fun `a query with facets can offer to drop them`() {
        val state = present("gradle is:starred -has:image")
        assertTrue(state.hasFacets)
        assertEquals("gradle", state.withoutFacets())
    }

    @Test fun `a query without facets has nothing to drop`() {
        assertFalse(present("gradle build").hasFacets)
    }

    @Test fun `a facet nested inside a group is not counted as droppable`() {
        // withoutFacets works chip-by-chip, and a group chip is one opaque node — claiming we
        // could strip a facet out of it would be a promise this can't keep.
        assertFalse(present("(gradle OR is:starred)").hasFacets)
    }

    // ---- honesty about what a facet measures ----

    @Test fun `an overselling facet name carries its caveat`() {
        assertTrue(present("turns:>5").facetCaveats.single().startsWith("turns:"))
        assertTrue(present("has:code").facetCaveats.isNotEmpty())
    }

    @Test fun `a facet whose name matches its data carries no caveat`() {
        assertTrue(present("has:image is:starred").facetCaveats.isEmpty())
    }

    @Test fun `a caveat is stated once however many times the facet appears`() {
        assertEquals(1, present("turns:>5 turns:<20").facetCaveats.size)
    }

    // ---- autocomplete ----

    @Test fun `a blank query offers no suggestions`() {
        assertTrue(present("").suggestions.isEmpty())
    }

    @Test fun `a field being typed offers completions`() {
        assertTrue(present("pro").suggestions.any { it.insertText == "project:" })
    }

    @Test fun `facet values come from what is actually in the index`() {
        val values = QuerySuggestions.IndexedValues(models = listOf("cloud:anthropic/claude-x"))
        assertEquals(
            listOf("model:cloud:anthropic/claude-x"),
            present("model:cl", facetValues = values).suggestions.map { it.insertText },
        )
    }

    // ---- pass-through state ----

    @Test fun `recent searches reach the state`() {
        assertEquals(listOf("gradle", "is:starred"), present("", recentSearches = listOf("gradle", "is:starred")).recentSearches)
    }

    @Test fun `the operator legend is collapsed unless it was opened`() {
        assertFalse(present("").operatorsExpanded)
        assertTrue(present("", operatorsExpanded = true).operatorsExpanded)
    }

    // ---- highlighting uses the lexical text, not the raw query ----

    @Test fun `result highlights ignore facet syntax`() {
        val hits = LexicalSearch.search(listOf(doc("a", "notes", "the gradle cache")), "gradle", now, explain = true)
        val state = present("gradle is:starred", hits = hits)
        val row = state.rows.single()
        val range = row.snippetHighlights.single()
        assertEquals("gradle", row.snippet.substring(range.first, range.last + 1))
    }
}
