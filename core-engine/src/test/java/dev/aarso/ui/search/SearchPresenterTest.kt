package dev.aarso.ui.search

import dev.aarso.domain.search.LexicalSearch
import dev.aarso.domain.search.SearchDoc
import dev.aarso.domain.search.SearchKind
import dev.aarso.domain.search.query.QueryParser
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

    private fun doc(id: String, title: String) = SearchDoc(
        id = id, title = title, snippet = "s", body = title,
        lastActivityMillis = now, kind = SearchKind.TEXT,
    )

    private fun present(rawText: String, hits: List<dev.aarso.domain.search.SearchHit> = emptyList(), indexing: Boolean = false) =
        SearchPresenter.present(
            parsed = QueryParser.parse(rawText, now, zone),
            hits = hits,
            indexing = indexing,
            indexedCount = 12L,
            savedSearches = emptyList(),
            expandedResultId = null,
            nowMillis = now,
            zone = zone,
            locale = locale,
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

    @Test fun `facets in the query text are flagged as not yet applied to results`() {
        val state = present("is:starred gradle")
        assertTrue(state.hasUnappliedFacets)
        assertTrue(state.chips.isNotEmpty())
    }

    @Test fun `a query with no facets is not flagged`() {
        val state = present("gradle build")
        assertFalse(state.hasUnappliedFacets)
    }

    @Test fun `diagnostics from the parser pass through untouched`() {
        val state = present("\"unterminated")
        assertTrue(state.diagnostics.isNotEmpty())
    }
}
