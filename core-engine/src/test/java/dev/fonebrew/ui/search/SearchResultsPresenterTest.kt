package dev.fonebrew.ui.search

import dev.fonebrew.domain.search.LexicalSearch
import dev.fonebrew.domain.search.MatchedIn
import dev.fonebrew.domain.search.SearchDoc
import dev.fonebrew.domain.search.SearchKind
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchResultsPresenterTest {

    private val zone = ZoneId.of("UTC")
    private val locale = Locale.US
    private val now = 1_735_689_600_000L

    private fun doc(id: String, title: String, snippet: String, body: String, ageDays: Long = 0) = SearchDoc(
        id = id, title = title, snippet = snippet, body = body,
        lastActivityMillis = now - ageDays * 86_400_000L, kind = SearchKind.TEXT,
    )

    @Test fun `one row per hit, in the same order`() {
        val docs = listOf(doc("a", "gradle build", "s", "b"), doc("b", "unrelated", "s", "gradle"))
        val hits = LexicalSearch.search(docs, "gradle", now, explain = true)
        val rows = SearchResultsPresenter.present(hits, now, zone, locale)
        assertEquals(hits.map { it.doc.id }, rows.map { it.convId })
    }

    @Test fun `title match carries highlights on the title, not the snippet`() {
        val hits = LexicalSearch.search(listOf(doc("a", "gradle build", "misc", "misc")), "gradle", now, explain = true)
        val row = SearchResultsPresenter.present(hits, now, zone, locale).single()
        assertEquals(MatchedIn.TITLE, row.matchedIn)
        assertTrue(row.titleHighlights.isNotEmpty())
        assertTrue(row.snippetHighlights.isEmpty())
    }

    @Test fun `content match carries highlights on the snippet, not the title`() {
        val hits = LexicalSearch.search(listOf(doc("a", "misc", "gradle cache miss", "gradle cache miss")), "gradle", now, explain = true)
        val row = SearchResultsPresenter.present(hits, now, zone, locale).single()
        assertEquals(MatchedIn.CONTENT, row.matchedIn)
        assertTrue(row.snippetHighlights.isNotEmpty())
        assertTrue(row.titleHighlights.isEmpty())
    }

    @Test fun `snippet falls back to the body when the doc snippet is blank`() {
        val hits = LexicalSearch.search(listOf(doc("a", "gradle", "", "the full body text about gradle")), "gradle", now, explain = true)
        val row = SearchResultsPresenter.present(hits, now, zone, locale).single()
        assertTrue(row.snippet.contains("full body text"))
    }

    @Test fun `relative time reflects the doc's last activity, not the query time`() {
        val hits = LexicalSearch.search(listOf(doc("a", "gradle", "s", "s", ageDays = 3)), "gradle", now, explain = true)
        val row = SearchResultsPresenter.present(hits, now, zone, locale).single()
        assertEquals("3 days ago", row.relativeTime)
    }

    @Test fun `explanation passes through when requested upstream`() {
        val hits = LexicalSearch.search(listOf(doc("a", "gradle", "s", "s")), "gradle", now, explain = true)
        assertNotNull(SearchResultsPresenter.present(hits, now, zone, locale).single().explanation)
    }

    @Test fun `explanation is null when the caller didn't request it`() {
        val hits = LexicalSearch.search(listOf(doc("a", "gradle", "s", "s")), "gradle", now, explain = false)
        assertEquals(null, SearchResultsPresenter.present(hits, now, zone, locale).single().explanation)
    }

    @Test fun `empty hits present to an empty list`() {
        assertTrue(SearchResultsPresenter.present(emptyList(), now, zone, locale).isEmpty())
    }
}
