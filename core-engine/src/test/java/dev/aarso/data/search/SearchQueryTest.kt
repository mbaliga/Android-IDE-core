package dev.aarso.data.search

import dev.aarso.domain.search.LexicalSearch
import dev.aarso.domain.search.MatchedIn
import dev.aarso.domain.search.SearchDoc
import dev.aarso.domain.search.SearchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryTest {

    private companion object {
        const val NOW = 1_735_689_600_000L
        const val DAY = 24L * 60L * 60L * 1000L
    }

    private fun row(convId: String, titleRaw: String, bodyRaw: String, ageDays: Long) = SearchProjector.Row(
        convId = convId,
        title = titleRaw.lowercase(),
        snippet = "",
        body = bodyRaw.lowercase(),
        titleRaw = titleRaw,
        snippetRaw = "",
        bodyRaw = bodyRaw,
        updatedAt = NOW - ageDays * DAY,
        createdAt = NOW - ageDays * DAY,
        projectionVersion = SearchProjector.PROJECTION_VERSION,
        starred = false,
        archived = false,
        projectId = null,
        modelIds = "",
        turnCount = 2L,
        branchCount = 1L,
        hasImage = false,
        hasCode = false,
        costMinor = 0L,
        lastUsedAt = NOW - ageDays * DAY,
    )

    private fun index(database: SearchDatabase, rows: List<SearchProjector.Row>) {
        SearchIndexer.reindex(database, rows, nowMillis = NOW)
    }

    // ---- compileFtsQuery ----

    @Test fun `blank query compiles to null`() {
        assertEquals(null, SearchQuery.compileFtsQuery("   "))
    }

    @Test fun `each term becomes a quoted prefix match`() {
        assertEquals("\"gradle\"* \"cache\"*", SearchQuery.compileFtsQuery("Gradle CACHE"))
    }

    @Test fun `query syntax characters are neutralized by quoting`() {
        // Would otherwise be parsed as FTS5 operators/syntax if left unquoted.
        val compiled = SearchQuery.compileFtsQuery("OR -foo (bar")
        assertNotNull(compiled)
        assertTrue(compiled!!.contains("\"or\"*"))
        assertTrue(compiled.contains("\"-foo\"*") || compiled.contains("\"foo\"*")) // tokenizeQuery may drop the bare hyphen
    }

    // ---- search: no results / blank ----

    @Test fun `blank query returns no results`() {
        val db = SearchDriverFactory.createInMemory().database
        index(db, listOf(row("c1", "gradle build", "cache miss", 0)))
        assertTrue(SearchQuery.search(db, "   ", NOW).isEmpty())
    }

    @Test fun `no matching candidates returns empty`() {
        val db = SearchDriverFactory.createInMemory().database
        index(db, listOf(row("c1", "gradle build", "cache miss", 0)))
        assertTrue(SearchQuery.search(db, "zzz_nonexistent", NOW).isEmpty())
    }

    // ---- search: basic correctness ----

    @Test fun `finds a matching conversation and reports title match`() {
        val db = SearchDriverFactory.createInMemory().database
        index(
            db,
            listOf(
                row("hit", "kotlin coroutines", "misc", 0),
                row("miss", "weather today", "sunny", 0),
            ),
        )
        val hits = SearchQuery.search(db, "kotlin", NOW)
        assertEquals(1, hits.size)
        assertEquals("hit", hits[0].doc.id)
        assertEquals(MatchedIn.TITLE, hits[0].matchedIn)
        assertNotNull(hits[0].explanation)
    }

    @Test fun `prefix matching finds partial-word queries`() {
        val db = SearchDriverFactory.createInMemory().database
        index(db, listOf(row("c1", "gradle build cache", "invalidated", 0)))
        val hits = SearchQuery.search(db, "grad", NOW)
        assertEquals(1, hits.size)
        assertEquals("c1", hits[0].doc.id)
    }

    // ---- Doc §13 test 12: semantic-off reduces exactly to LexicalSearch's own ordering ----

    @Test fun `ordering matches LexicalSearch run directly over the same documents`() {
        val rows = listOf(
            row("titled", "gradle build cache", "notes", 0),
            row("content-only", "misc conversation", "gradle build cache discussion", 0),
            row("stale", "gradle build cache", "old notes", 400),
            row("unrelated", "weather forecast", "sunny and warm", 0),
        )
        val db = SearchDriverFactory.createInMemory().database
        index(db, rows)

        val query = "gradle build cache"
        val viaSearchQuery = SearchQuery.search(db, query, NOW)

        val docsDirect = rows.map { r ->
            SearchDoc(
                id = r.convId,
                title = r.titleRaw,
                snippet = r.snippetRaw,
                body = r.bodyRaw,
                lastActivityMillis = r.updatedAt,
                kind = SearchKind.TEXT,
            )
        }
        val viaLexicalSearchDirect = LexicalSearch.search(docsDirect, query, NOW)

        assertEquals(viaLexicalSearchDirect.map { it.doc.id }, viaSearchQuery.map { it.doc.id })
        assertEquals(viaLexicalSearchDirect.map { it.score }, viaSearchQuery.map { it.score })
        // "unrelated" never matches either path.
        assertTrue(viaSearchQuery.none { it.doc.id == "unrelated" })
    }

    @Test fun `recency ordering is preserved through the FTS candidate path`() {
        val db = SearchDriverFactory.createInMemory().database
        index(
            db,
            listOf(
                row("old", "alpha project notes", "x", 30),
                row("new", "alpha project notes", "x", 0),
            ),
        )
        val hits = SearchQuery.search(db, "alpha", NOW)
        assertEquals(listOf("new", "old"), hits.map { it.doc.id })
    }

    @Test fun `result limit is respected`() {
        val db = SearchDriverFactory.createInMemory().database
        index(db, (1..10).map { row("c$it", "gradle build $it", "cache", 0) })
        val hits = SearchQuery.search(db, "gradle", NOW, resultLimit = 3)
        assertEquals(3, hits.size)
    }

    @Test fun `multilingual query matches the segmented multilingual body`() {
        val db = SearchDriverFactory.createInMemory().database
        index(db, listOf(row("hi", "नमस्ते दुनिया", "बातचीत", 0)))
        val hits = SearchQuery.search(db, "नमस्ते", NOW)
        assertEquals(1, hits.size)
        assertEquals("hi", hits[0].doc.id)
    }
}
