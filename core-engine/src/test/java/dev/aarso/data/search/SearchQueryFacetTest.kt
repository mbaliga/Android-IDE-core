package dev.aarso.data.search

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end facet filtering (WP16) against a **real** FTS5 database — the bundled-SQLite driver
 * runs on the JVM host, so these are genuine `MATCH`/`bm25()` queries, not a stub (see
 * [SearchDriverFactory]'s KDoc). Proves the thing M2 left open: a typed `is:starred` actually
 * narrows results, instead of only round-tripping as a chip.
 */
class SearchQueryFacetTest {

    private companion object {
        /** 2026-07-30T00:00:00Z. */
        const val NOW = 1_785_369_600_000L
        const val DAY = 24L * 60L * 60L * 1000L
    }

    private val zone = ZoneId.of("UTC")

    private fun row(
        convId: String,
        titleRaw: String = "conversation $convId",
        bodyRaw: String = "gradle build output",
        ageDays: Long = 0,
        starred: Boolean = false,
        archived: Boolean = false,
        projectId: String? = null,
        modelIds: String = "",
        turnCount: Long = 2,
        branchCount: Long = 0,
        hasImage: Boolean = false,
        hasCode: Boolean = false,
        costMinor: Long = 0,
    ) = SearchProjector.Row(
        convId = convId,
        title = titleRaw.lowercase(), snippet = "", body = bodyRaw.lowercase(),
        titleRaw = titleRaw, snippetRaw = "", bodyRaw = bodyRaw,
        updatedAt = NOW - ageDays * DAY, createdAt = NOW - ageDays * DAY,
        projectionVersion = SearchProjector.PROJECTION_VERSION,
        starred = starred, archived = archived, projectId = projectId, modelIds = modelIds,
        turnCount = turnCount, branchCount = branchCount,
        hasImage = hasImage, hasCode = hasCode, costMinor = costMinor,
        lastUsedAt = NOW - ageDays * DAY,
    )

    private fun search(rows: List<SearchProjector.Row>, query: String): List<String> {
        val handle = SearchDriverFactory.createInMemory()
        SearchIndexer.reindex(handle.database, rows, nowMillis = NOW)
        return SearchQuery.search(handle.database, query, NOW, zone = zone).map { it.doc.id }
    }

    @Test fun `text plus a facet returns only the conversations matching both`() {
        val rows = listOf(
            row("a", starred = true),
            row("b", starred = false),
            row("c", starred = true, bodyRaw = "completely unrelated prose"),
        )
        assertEquals(listOf("a"), search(rows, "gradle is:starred"))
    }

    @Test fun `a facet-only query still returns results — no lexical text needed`() {
        val rows = listOf(row("a", starred = true), row("b", starred = false))
        assertEquals(listOf("a"), search(rows, "is:starred"))
    }

    @Test fun `an entirely empty query returns nothing rather than everything`() {
        assertEquals(emptyList<String>(), search(listOf(row("a"), row("b")), "   "))
    }

    @Test fun `a negated facet excludes`() {
        val rows = listOf(row("a", archived = true), row("b", archived = false))
        assertEquals(listOf("b"), search(rows, "gradle -is:archived"))
    }

    @Test fun `a negated term still returns the rows that do not contain it`() {
        // Regression: neither the facet stage nor the regex stage may treat a negated *term*
        // as a rejection — FTS5's own NOT already handled it.
        val rows = listOf(
            row("a", bodyRaw = "gradle build output"),
            row("b", bodyRaw = "gradle maven interop"),
        )
        assertEquals(listOf("a"), search(rows, "gradle -maven"))
    }

    @Test fun `a negated term composes with a facet`() {
        val rows = listOf(
            row("a", bodyRaw = "gradle build output", starred = true),
            row("b", bodyRaw = "gradle build output", starred = false),
            row("c", bodyRaw = "gradle maven interop", starred = true),
        )
        assertEquals(listOf("a"), search(rows, "gradle -maven is:starred"))
    }

    @Test fun `a negated regex excludes only matching rows`() {
        val rows = listOf(
            row("a", bodyRaw = "gradle build output"),
            row("b", bodyRaw = "gradle maven interop"),
        )
        assertEquals(listOf("a"), search(rows, "gradle -/mav\\w+/"))
    }

    @Test fun `project facet filters to one project`() {
        val rows = listOf(row("a", projectId = "Aarso"), row("b", projectId = "Hyle"), row("c"))
        assertEquals(listOf("a"), search(rows, "gradle project:aarso"))
    }

    @Test fun `model facet matches a substring of a namespaced model id`() {
        val rows = listOf(
            row("a", modelIds = "cloud:anthropic/claude-opus"),
            row("b", modelIds = "local:qwen-7b"),
        )
        assertEquals(listOf("a"), search(rows, "gradle model:claude"))
    }

    @Test fun `date facets filter on last activity`() {
        val rows = listOf(row("recent", ageDays = 1), row("old", ageDays = 60))
        assertEquals(listOf("recent"), search(rows, "gradle after:-7d"))
    }

    @Test fun `numeric facets filter with comparison operators`() {
        val rows = listOf(row("big", turnCount = 40), row("small", turnCount = 2))
        assertEquals(listOf("big"), search(rows, "gradle turns:>10"))
    }

    @Test fun `composed facets narrow cumulatively`() {
        val rows = listOf(
            row("a", starred = true, hasCode = true),
            row("b", starred = true, hasCode = false),
            row("c", starred = false, hasCode = true),
        )
        assertEquals(listOf("a"), search(rows, "gradle is:starred has:code"))
    }

    @Test fun `an unbacked facet returns an honest zero, not everything`() {
        val rows = listOf(row("a"), row("b"))
        assertEquals(emptyList<String>(), search(rows, "gradle tool:bash"))
    }

    @Test fun `a regex-only query scans the candidate set`() {
        val rows = listOf(row("a", bodyRaw = "gradle build output"), row("b", bodyRaw = "maven output"))
        assertEquals(listOf("a"), search(rows, "/grad\\w+/"))
    }

    @Test fun `regex composes with a facet`() {
        val rows = listOf(
            row("a", bodyRaw = "gradle build", starred = true),
            row("b", bodyRaw = "gradle build", starred = false),
        )
        assertEquals(listOf("a"), search(rows, "/grad\\w+/ is:starred"))
    }

    @Test fun `an invalid regex matches nothing rather than throwing`() {
        assertEquals(emptyList<String>(), search(listOf(row("a")), "/grad(/"))
    }

    @Test fun `a bare term prefix-matches so as-you-type finds results mid-word`() {
        val rows = listOf(row("a", bodyRaw = "gradle build output"))
        assertEquals(listOf("a"), search(rows, "gradl"))
    }

    @Test fun `facet syntax never leaks into the relevance ranking`() {
        // "is:starred" must not be scored as the literal words "is" and "starred": the
        // conversation whose body actually says "gradle" should win, not the decoy.
        val rows = listOf(
            row("real", titleRaw = "gradle notes", bodyRaw = "gradle gradle gradle", starred = true),
            row("decoy", titleRaw = "starred is", bodyRaw = "is starred is starred gradle", starred = true),
        )
        assertEquals("real", search(rows, "gradle is:starred").first())
    }

    @Test fun `facet-only results come back in recency order`() {
        val rows = listOf(
            row("older", ageDays = 5, starred = true),
            row("newest", ageDays = 0, starred = true),
            row("middle", ageDays = 2, starred = true),
        )
        assertEquals(listOf("newest", "middle", "older"), search(rows, "is:starred"))
    }

    @Test fun `the lossy disjunction under-returns rather than returning wrong rows`() {
        // Pinned behavior, documented in FacetEvaluator: `gradle OR is:starred` evaluates to
        // FTS(gradle) — the starred-but-not-gradle conversation is missed. Never a wrong row.
        val rows = listOf(
            row("gradle-only", bodyRaw = "gradle build", starred = false),
            row("starred-only", bodyRaw = "totally different prose", starred = true),
        )
        val results = search(rows, "(gradle OR is:starred)")
        assertTrue("no wrong rows", results.all { it == "gradle-only" })
        assertEquals(listOf("gradle-only"), results)
    }
}
