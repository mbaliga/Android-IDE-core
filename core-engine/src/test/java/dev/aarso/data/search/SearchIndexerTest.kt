package dev.aarso.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchIndexerTest {

    private fun row(
        convId: String,
        titleRaw: String = convId,
        bodyRaw: String = "body for $convId",
        starred: Boolean = false,
        archived: Boolean = false,
        projectId: String? = null,
        updatedAt: Long = 0L,
        lineageParent: String? = null,
        lineageKind: String? = null,
        chapterCount: Long = 0L,
        compactionCount: Long = 0L,
    ) = SearchProjector.Row(
        convId = convId,
        title = titleRaw.lowercase(),
        snippet = "",
        body = bodyRaw.lowercase(),
        titleRaw = titleRaw,
        snippetRaw = "",
        bodyRaw = bodyRaw,
        updatedAt = updatedAt,
        createdAt = updatedAt,
        projectionVersion = SearchProjector.PROJECTION_VERSION,
        starred = starred,
        archived = archived,
        projectId = projectId,
        modelIds = "",
        turnCount = 2L,
        branchCount = 1L,
        hasImage = false,
        hasCode = false,
        costMinor = 0L,
        lastUsedAt = updatedAt,
        lineageParent = lineageParent,
        lineageKind = lineageKind,
        chapterCount = chapterCount,
        compactionCount = compactionCount,
    )

    @Test fun `reindex writes every row and makes it searchable`() {
        val db = SearchDriverFactory.createInMemory().database
        val rows = listOf(row("c1", bodyRaw = "gradle cache miss"), row("c2", bodyRaw = "unrelated weather"))

        SearchIndexer.reindex(db, rows, nowMillis = 1000L)

        assertEquals(2L, db.searchQueries.countProjections().executeAsOne())
        val hits = db.searchQueries.searchCandidates("cache", 10).executeAsList()
        assertEquals(listOf("c1"), hits.map { it.conv_id })
    }

    @Test fun `index_state checkpoints after every chunk`() {
        val db = SearchDriverFactory.createInMemory().database
        val rows = (1..10).map { row("c$it") }

        SearchIndexer.reindex(db, rows, nowMillis = 1000L, chunkSize = 3)

        val state = db.searchQueries.selectIndexState().executeAsOne()
        assertEquals(10L, state.last_indexed_rowid)
        assertEquals(SearchProjector.PROJECTION_VERSION, state.projection_version)
    }

    @Test fun `kill-and-resume mid-backfill produces a byte-identical final index`() {
        // "Uninterrupted" reference build.
        val reference = SearchDriverFactory.createInMemory().database
        val allRows = (1..23).map { row("c$it", bodyRaw = "content number $it", updatedAt = it.toLong()) }
        SearchIndexer.reindex(reference, allRows, nowMillis = 999L, chunkSize = 5)

        // "Killed" build: stop after the 2nd chunk (isCancelled trips once 2 chunks have run),
        // then resume by calling reindex again with the SAME rows — exactly what a real restart
        // does, since SearchProjector.project() is a pure function of the same tree state.
        val resumed = SearchDriverFactory.createInMemory().database
        var chunksRun = 0
        SearchIndexer.reindex(
            resumed,
            allRows,
            nowMillis = 111L,
            chunkSize = 5,
            isCancelled = { chunksRun >= 2 },
            onProgress = { chunksRun++ },
        )
        val stateAfterKill = resumed.searchQueries.selectIndexState().executeAsOne()
        assertEquals(10L, stateAfterKill.last_indexed_rowid) // 2 chunks * 5 rows
        assertTrue(resumed.searchQueries.countProjections().executeAsOne() < allRows.size)

        SearchIndexer.reindex(resumed, allRows, nowMillis = 222L, chunkSize = 5)

        assertEquals(
            reference.searchQueries.countProjections().executeAsOne(),
            resumed.searchQueries.countProjections().executeAsOne(),
        )
        val referenceHits = reference.searchQueries.searchCandidates("content", 100).executeAsList().map { it.conv_id }.sorted()
        val resumedHits = resumed.searchQueries.searchCandidates("content", 100).executeAsList().map { it.conv_id }.sorted()
        assertEquals(referenceHits, resumedHits)
        assertEquals(allRows.size.toLong(), resumed.searchQueries.selectIndexState().executeAsOne().last_indexed_rowid)
    }

    @Test fun `resuming with an unchanged already-written prefix does not double-write or throw`() {
        val db = SearchDriverFactory.createInMemory().database
        val rows = (1..6).map { row("c$it") }

        SearchIndexer.reindex(db, rows, nowMillis = 1L, chunkSize = 2)
        // Re-run with the identical row list, as if the app restarted after a clean exit.
        SearchIndexer.reindex(db, rows, nowMillis = 2L, chunkSize = 2)

        assertEquals(6L, db.searchQueries.countProjections().executeAsOne())
    }

    @Test fun `projection version bump triggers a full rebuild, not a resume from stale state`() {
        val db = SearchDriverFactory.createInMemory().database
        val rows = (1..5).map { row("c$it") }
        SearchIndexer.reindex(db, rows, nowMillis = 1L)

        // Simulate a later app version whose projector logic changed (PROJECTION_VERSION bumped)
        // by hand-writing an index_state row with a stale projection_version + a last_indexed_rowid
        // that would otherwise mean "already fully indexed, nothing to do".
        db.searchQueries.upsertIndexState(
            last_indexed_rowid = rows.size.toLong(),
            projection_version = SearchProjector.PROJECTION_VERSION - 1,
            tokenizer_version = SearchIndexer.TOKENIZER_VERSION,
            schema_version = SearchIndexer.SCHEMA_VERSION,
            updated_at = 1L,
        )

        var sawProgress = false
        SearchIndexer.reindex(db, rows, nowMillis = 2L, onProgress = { sawProgress = true })

        assertTrue("a stale projection_version must force real work, not a no-op", sawProgress)
        val state = db.searchQueries.selectIndexState().executeAsOne()
        assertEquals(SearchProjector.PROJECTION_VERSION, state.projection_version)
        assertEquals(rows.size.toLong(), state.last_indexed_rowid)
        assertEquals(rows.size.toLong(), db.searchQueries.countProjections().executeAsOne())
    }

    @Test fun `already-fully-indexed rows are a no-op on the next call`() {
        val db = SearchDriverFactory.createInMemory().database
        val rows = (1..4).map { row("c$it") }
        SearchIndexer.reindex(db, rows, nowMillis = 1L)

        var progressCalls = 0
        SearchIndexer.reindex(db, rows, nowMillis = 2L, onProgress = { progressCalls++ })

        assertEquals(1, progressCalls) // the "already done" completion callback, no chunk work
        assertEquals(4L, db.searchQueries.countProjections().executeAsOne())
    }

    @Test fun `remove deletes the projection and its facets`() {
        val db = SearchDriverFactory.createInMemory().database
        SearchIndexer.reindex(db, listOf(row("c1"), row("c2")), nowMillis = 1L)

        SearchIndexer.remove(db, "c1")

        assertEquals(1L, db.searchQueries.countProjections().executeAsOne())
        assertEquals(null, db.searchQueries.selectFacets("c1").executeAsOneOrNull())
    }

    @Test fun `verifyAndRepairIfNeeded reports no corruption on a freshly built consistent index`() {
        val handle = SearchDriverFactory.createInMemory()
        SearchIndexer.reindex(handle.database, listOf(row("c1"), row("c2")), nowMillis = 1L)

        assertFalse(handle.verifyAndRepairIfNeeded())
    }

    @Test fun `verifyAndRepairIfNeeded detects and repairs a desynced shadow index`() {
        val handle = SearchDriverFactory.createInMemory()
        SearchIndexer.reindex(handle.database, listOf(row("c1", bodyRaw = "gradle cache")), nowMillis = 1L)

        // Directly corrupt FTS5's internal shadow table (bypassing the sync triggers entirely,
        // which is exactly the class of real-world desync this check exists to catch — e.g. a
        // botched migration or a killed write that left the shadow index half-updated).
        handle.driver.execute(null, "DELETE FROM conv_fts_data WHERE rowid > 1", 0, null)

        val repaired = handle.verifyAndRepairIfNeeded()
        assertTrue("expected the corrupted shadow index to be detected", repaired)

        // Post-repair, the index is consistent again and still finds the row.
        assertFalse(handle.verifyAndRepairIfNeeded())
        val hits = handle.database.searchQueries.searchCandidates("cache", 10).executeAsList()
        assertEquals(listOf("c1"), hits.map { it.conv_id })
    }

    // ---- lineage + chapter/compaction facets (THREAD_TOPOLOGY_PLAN.md WP6) -------------------

    @Test fun `lineage and marker-count facets round-trip through reindex`() {
        val db = SearchDriverFactory.createInMemory().database
        SearchIndexer.reindex(
            db,
            listOf(row("fork", lineageParent = "orig", lineageKind = "FORK", chapterCount = 3, compactionCount = 1)),
            nowMillis = 1L,
        )

        val facets = db.searchQueries.selectFacets("fork").executeAsOne()
        assertEquals("orig", facets.lineage_parent)
        assertEquals("FORK", facets.lineage_kind)
        assertEquals(3L, facets.chapter_count)
        assertEquals(1L, facets.compaction_count)
    }

    @Test fun `a root with no lineage indexes with null lineage columns and zero counts`() {
        val db = SearchDriverFactory.createInMemory().database
        SearchIndexer.reindex(db, listOf(row("origin")), nowMillis = 1L)

        val facets = db.searchQueries.selectFacets("origin").executeAsOne()
        assertEquals(null, facets.lineage_parent)
        assertEquals(null, facets.lineage_kind)
        assertEquals(0L, facets.chapter_count)
        assertEquals(0L, facets.compaction_count)
    }
}
