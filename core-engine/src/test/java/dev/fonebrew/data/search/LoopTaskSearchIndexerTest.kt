package dev.fonebrew.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Incremental sync for the loop:/task: corpora — same real-FTS5 pattern as
 *  [SearchIndexerSyncTest], for [SearchIndexer.syncLoops]/[SearchIndexer.syncTasks]. */
class LoopTaskSearchIndexerTest {

    private companion object {
        const val NOW = 1_785_369_600_000L
    }

    private fun loopRow(id: String, bodyRaw: String = "gradle build automation", state: String = "UNUSED", updatedAt: Long = NOW) =
        LoopSearchProjector.Row(
            loopId = id, title = "loop $id", body = bodyRaw.lowercase(),
            titleRaw = "loop $id", bodyRaw = bodyRaw, state = state, updatedAt = updatedAt, createdAt = NOW,
        )

    private fun taskRow(id: String, bodyRaw: String = "gradle build chore", state: String = "TODO", updatedAt: Long = NOW) =
        TaskSearchProjector.Row(
            taskId = id, title = "task $id", body = bodyRaw.lowercase(),
            titleRaw = "task $id", bodyRaw = bodyRaw, state = state, updatedAt = updatedAt, createdAt = NOW,
        )

    private fun ids(db: SearchDatabase, query: String) = SearchQuery.search(db, query, NOW).map { it.doc.id }

    // ---- loops ----

    @Test fun `a new loop becomes searchable`() {
        val db = SearchDriverFactory.createInMemory().database
        SearchIndexer.syncLoops(db, listOf(loopRow("l1")), NOW)
        assertEquals(listOf("l1"), ids(db, "gradle loop:"))
    }

    @Test fun `editing a loop re-indexes it and the old text stops matching`() {
        val db = SearchDriverFactory.createInMemory().database
        SearchIndexer.syncLoops(db, listOf(loopRow("l1", bodyRaw = "gradle automation")), NOW)
        assertEquals(listOf("l1"), ids(db, "gradle loop:"))

        SearchIndexer.syncLoops(db, listOf(loopRow("l1", bodyRaw = "maven automation", updatedAt = NOW + 1)), NOW)
        assertEquals(emptyList<String>(), ids(db, "gradle loop:"))
        assertEquals(listOf("l1"), ids(db, "maven loop:"))
    }

    @Test fun `deleting a loop drops it from the index`() {
        val db = SearchDriverFactory.createInMemory().database
        SearchIndexer.syncLoops(db, listOf(loopRow("l1"), loopRow("l2")), NOW)
        val result = SearchIndexer.syncLoops(db, listOf(loopRow("l1")), NOW)
        assertEquals(1, result.removed)
        assertEquals(listOf("l1"), ids(db, "gradle loop:"))
    }

    @Test fun `a state-only edit (no text or timestamp change) is still picked up`() {
        // Same class of gap conv_facets' starring/archiving had before WP17 — a retire/trigger
        // transition doesn't necessarily bump updated_at, so an updated_at-only comparison would
        // miss it.
        val db = SearchDriverFactory.createInMemory().database
        SearchIndexer.syncLoops(db, listOf(loopRow("l1", state = "UNUSED")), NOW)
        assertEquals(listOf("l1"), ids(db, "loop:unused"))

        val result = SearchIndexer.syncLoops(db, listOf(loopRow("l1", state = "RETIRED")), NOW)
        assertEquals(1, result.updated)
        assertEquals(emptyList<String>(), ids(db, "loop:unused"))
        assertEquals(listOf("l1"), ids(db, "loop:retired"))
    }

    @Test fun `syncing an unchanged loop corpus writes nothing`() {
        val db = SearchDriverFactory.createInMemory().database
        val rows = listOf(loopRow("l1"), loopRow("l2"))
        SearchIndexer.syncLoops(db, rows, NOW)
        val result = SearchIndexer.syncLoops(db, rows, NOW)
        assertTrue(result.isNoOp)
    }

    // ---- tasks ----

    @Test fun `a new task becomes searchable`() {
        val db = SearchDriverFactory.createInMemory().database
        SearchIndexer.syncTasks(db, listOf(taskRow("t1")), NOW)
        assertEquals(listOf("t1"), ids(db, "gradle task:"))
    }

    @Test fun `completing a task (state edit) is picked up`() {
        val db = SearchDriverFactory.createInMemory().database
        SearchIndexer.syncTasks(db, listOf(taskRow("t1", state = "TODO")), NOW)
        assertEquals(listOf("t1"), ids(db, "task:todo"))

        val result = SearchIndexer.syncTasks(db, listOf(taskRow("t1", state = "DONE")), NOW)
        assertEquals(1, result.updated)
        assertEquals(emptyList<String>(), ids(db, "task:todo"))
        assertEquals(listOf("t1"), ids(db, "task:done"))
    }

    @Test fun `deleting a task drops it from the index`() {
        val db = SearchDriverFactory.createInMemory().database
        SearchIndexer.syncTasks(db, listOf(taskRow("t1"), taskRow("t2")), NOW)
        val result = SearchIndexer.syncTasks(db, listOf(taskRow("t2")), NOW)
        assertEquals(1, result.removed)
        assertEquals(listOf("t2"), ids(db, "gradle task:"))
    }

    @Test fun `syncing loops and tasks never cross-contaminates the other's results`() {
        val db = SearchDriverFactory.createInMemory().database
        SearchIndexer.syncLoops(db, listOf(loopRow("l1", bodyRaw = "shared keyword here")), NOW)
        SearchIndexer.syncTasks(db, listOf(taskRow("t1", bodyRaw = "shared keyword here")), NOW)
        assertEquals(listOf("l1"), ids(db, "keyword loop:"))
        assertEquals(listOf("t1"), ids(db, "keyword task:"))
    }
}
