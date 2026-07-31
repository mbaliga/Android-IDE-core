package dev.aarso.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Incremental index sync (WP17) against a real FTS5 database. This is what makes an edit made
 * while the app is running visible to search, instead of only after a cold rebuild.
 */
class SearchIndexerSyncTest {

    private companion object {
        const val NOW = 1_785_369_600_000L
    }

    private fun row(
        convId: String,
        bodyRaw: String = "gradle build output",
        updatedAt: Long = NOW,
        starred: Boolean = false,
        archived: Boolean = false,
        projectId: String? = null,
        turnCount: Long = 2,
    ) = SearchProjector.Row(
        convId = convId,
        title = "conversation $convId", snippet = "", body = bodyRaw.lowercase(),
        titleRaw = "conversation $convId", snippetRaw = "", bodyRaw = bodyRaw,
        updatedAt = updatedAt, createdAt = NOW,
        projectionVersion = SearchProjector.PROJECTION_VERSION,
        starred = starred, archived = archived, projectId = projectId, modelIds = "",
        turnCount = turnCount, branchCount = 0,
        hasImage = false, hasCode = false, costMinor = 0, lastUsedAt = NOW,
    )

    private fun freshIndex(rows: List<SearchProjector.Row>): SearchDatabaseHandle =
        SearchDriverFactory.createInMemory().also { SearchIndexer.reindex(it.database, rows, NOW) }

    private fun ids(handle: SearchDatabaseHandle, query: String) =
        SearchQuery.search(handle.database, query, NOW).map { it.doc.id }

    @Test fun `syncing an unchanged corpus writes nothing`() {
        val rows = listOf(row("a"), row("b"))
        val handle = freshIndex(rows)
        val result = SearchIndexer.sync(handle.database, rows, NOW)
        assertTrue(result.isNoOp)
        assertEquals(0, result.updated)
        assertEquals(0, result.removed)
    }

    @Test fun `a new conversation becomes searchable`() {
        val handle = freshIndex(listOf(row("a")))
        assertEquals(listOf("a"), ids(handle, "gradle"))

        val result = SearchIndexer.sync(handle.database, listOf(row("a"), row("b")), NOW)
        assertEquals(1, result.updated)
        assertEquals(setOf("a", "b"), ids(handle, "gradle").toSet())
    }

    @Test fun `edited text is re-indexed and the old text stops matching`() {
        val handle = freshIndex(listOf(row("a", bodyRaw = "gradle build output")))
        assertEquals(listOf("a"), ids(handle, "gradle"))

        SearchIndexer.sync(handle.database, listOf(row("a", bodyRaw = "maven build output", updatedAt = NOW + 1)), NOW)
        assertEquals(emptyList<String>(), ids(handle, "gradle"))
        assertEquals(listOf("a"), ids(handle, "maven"))
    }

    @Test fun `a deleted conversation is dropped from the index`() {
        val handle = freshIndex(listOf(row("a"), row("b")))
        val result = SearchIndexer.sync(handle.database, listOf(row("a")), NOW)
        assertEquals(1, result.removed)
        assertEquals(listOf("a"), ids(handle, "gradle"))
    }

    // ---- facet-only edits: the case an updated_at comparison alone would miss ----

    @Test fun `starring a conversation is picked up even though its timestamp did not change`() {
        val handle = freshIndex(listOf(row("a", starred = false)))
        assertEquals(emptyList<String>(), ids(handle, "gradle is:starred"))

        val result = SearchIndexer.sync(handle.database, listOf(row("a", starred = true)), NOW)
        assertEquals(1, result.updated)
        assertEquals(listOf("a"), ids(handle, "gradle is:starred"))
    }

    @Test fun `archiving is picked up`() {
        val handle = freshIndex(listOf(row("a", archived = false)))
        SearchIndexer.sync(handle.database, listOf(row("a", archived = true)), NOW)
        assertEquals(listOf("a"), ids(handle, "gradle is:archived"))
        assertEquals(emptyList<String>(), ids(handle, "gradle -is:archived"))
    }

    @Test fun `a project assignment is picked up`() {
        val handle = freshIndex(listOf(row("a", projectId = null)))
        assertEquals(listOf("a"), ids(handle, "gradle is:orphan"))

        SearchIndexer.sync(handle.database, listOf(row("a", projectId = "Aarso")), NOW)
        assertEquals(emptyList<String>(), ids(handle, "gradle is:orphan"))
        assertEquals(listOf("a"), ids(handle, "gradle project:aarso"))
    }

    @Test fun `a turn-count change is picked up`() {
        val handle = freshIndex(listOf(row("a", turnCount = 2)))
        SearchIndexer.sync(handle.database, listOf(row("a", turnCount = 40)), NOW)
        assertEquals(listOf("a"), ids(handle, "gradle turns:>10"))
    }

    @Test fun `sync leaves the resume checkpoint consistent, so a later reindex is a no-op`() {
        val rows = listOf(row("a"), row("b"))
        val handle = freshIndex(listOf(row("a")))
        SearchIndexer.sync(handle.database, rows, NOW)

        // reindex() short-circuits on a fully-processed corpus; if sync had left a stale
        // checkpoint this would re-walk (or worse, skip) rows.
        SearchIndexer.reindex(handle.database, rows, NOW)
        assertEquals(2L, handle.database.searchQueries.countProjections().executeAsOne())
        assertEquals(setOf("a", "b"), ids(handle, "gradle").toSet())
    }

    @Test fun `sync also backfills an entirely empty index`() {
        val handle = SearchDriverFactory.createInMemory()
        val result = SearchIndexer.sync(handle.database, listOf(row("a"), row("b")), NOW)
        assertEquals(2, result.updated)
        assertEquals(setOf("a", "b"), ids(handle, "gradle").toSet())
    }

    @Test fun `repeated syncs converge — the second is always a no-op`() {
        val handle = freshIndex(listOf(row("a")))
        val updated = listOf(row("a", starred = true), row("b"))
        SearchIndexer.sync(handle.database, updated, NOW)
        assertTrue(SearchIndexer.sync(handle.database, updated, NOW).isNoOp)
    }
}
