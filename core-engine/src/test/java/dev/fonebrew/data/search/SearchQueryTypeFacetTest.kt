package dev.fonebrew.data.search

import dev.fonebrew.domain.search.LexicalSearch
import dev.fonebrew.domain.search.SearchDoc
import dev.fonebrew.domain.search.SearchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end `loop:`/`task:` retrieval against a real FTS5 database (same pattern as
 * [SearchQueryFacetTest]) — proves the two facets actually switch retrieval to their own corpus
 * and results are correctly attributed with the [SearchKind] the overlay needs to navigate them
 * (Field.kt's loop:/task: honesty flip).
 */
class SearchQueryTypeFacetTest {

    private companion object {
        const val NOW = 1_785_369_600_000L
        const val DAY = 24L * 60L * 60L * 1000L
    }

    private fun convRow(convId: String, titleRaw: String, bodyRaw: String, ageDays: Long = 0) = SearchProjector.Row(
        convId = convId, title = titleRaw.lowercase(), snippet = "", body = bodyRaw.lowercase(),
        titleRaw = titleRaw, snippetRaw = "", bodyRaw = bodyRaw,
        updatedAt = NOW - ageDays * DAY, createdAt = NOW - ageDays * DAY,
        projectionVersion = SearchProjector.PROJECTION_VERSION,
        starred = false, archived = false, projectId = null, modelIds = "",
        turnCount = 2L, branchCount = 0L, hasImage = false, hasCode = false, costMinor = 0L,
        lastUsedAt = NOW - ageDays * DAY,
    )

    private fun loopRow(loopId: String, titleRaw: String, bodyRaw: String, state: String = "UNUSED", ageDays: Long = 0) =
        LoopSearchProjector.Row(
            loopId = loopId, title = titleRaw.lowercase(), body = bodyRaw.lowercase(),
            titleRaw = titleRaw, bodyRaw = bodyRaw, state = state,
            updatedAt = NOW - ageDays * DAY, createdAt = NOW - ageDays * DAY,
        )

    private fun taskRow(taskId: String, titleRaw: String, bodyRaw: String, state: String = "TODO", ageDays: Long = 0) =
        TaskSearchProjector.Row(
            taskId = taskId, title = titleRaw.lowercase(), body = bodyRaw.lowercase(),
            titleRaw = titleRaw, bodyRaw = bodyRaw, state = state,
            updatedAt = NOW - ageDays * DAY, createdAt = NOW - ageDays * DAY,
        )

    private fun freshIndex(
        convs: List<SearchProjector.Row> = emptyList(),
        loops: List<LoopSearchProjector.Row> = emptyList(),
        tasks: List<TaskSearchProjector.Row> = emptyList(),
    ): SearchDatabase {
        val db = SearchDriverFactory.createInMemory().database
        SearchIndexer.reindex(db, convs, NOW)
        SearchIndexer.syncLoops(db, loops, NOW)
        SearchIndexer.syncTasks(db, tasks, NOW)
        return db
    }

    // ---- loop: ----

    @Test fun `loop scopes retrieval to loop rows, by text`() {
        val db = freshIndex(
            convs = listOf(convRow("c1", "deploy pipeline", "conversation about deploy")),
            loops = listOf(
                loopRow("l1", "Deploy loop", "runs the deploy pipeline"),
                loopRow("l2", "Unrelated loop", "does something else entirely"),
            ),
        )
        val hits = SearchQuery.search(db, "deploy loop:", NOW)
        assertEquals(listOf("l1"), hits.map { it.doc.id })
        assertEquals(SearchKind.LOOP, hits.single().doc.kind)
    }

    @Test fun `bare loop with no text returns every loop, most recent first`() {
        val db = freshIndex(loops = listOf(loopRow("old", "A", "", ageDays = 10), loopRow("new", "B", "", ageDays = 0)))
        val hits = SearchQuery.search(db, "loop:", NOW)
        assertEquals(listOf("new", "old"), hits.map { it.doc.id })
    }

    @Test fun `loop with a state value narrows by LoopState`() {
        val db = freshIndex(
            loops = listOf(
                loopRow("unused-one", "Draft A", "", state = "UNUSED"),
                loopRow("retired-one", "Draft B", "", state = "RETIRED"),
            ),
        )
        assertEquals(listOf("unused-one"), SearchQuery.search(db, "loop:unused", NOW).map { it.doc.id })
        assertEquals(listOf("retired-one"), SearchQuery.search(db, "loop:retired", NOW).map { it.doc.id })
    }

    @Test fun `loop never returns a conversation, even one that matches the same text`() {
        val db = freshIndex(
            convs = listOf(convRow("c1", "gradle build", "gradle build details")),
            loops = listOf(loopRow("l1", "Gradle loop", "gradle build automation")),
        )
        val hits = SearchQuery.search(db, "gradle loop:", NOW)
        assertEquals(listOf("l1"), hits.map { it.doc.id })
    }

    // ---- task: ----

    @Test fun `task scopes retrieval to task rows, by text`() {
        val db = freshIndex(
            convs = listOf(convRow("c1", "changelog", "write the changelog")),
            tasks = listOf(taskRow("t1", "Write changelog", "before the release")),
        )
        val hits = SearchQuery.search(db, "changelog task:", NOW)
        assertEquals(listOf("t1"), hits.map { it.doc.id })
        assertEquals(SearchKind.TASK, hits.single().doc.kind)
    }

    @Test fun `task with a state value narrows by TaskState`() {
        val db = freshIndex(
            tasks = listOf(
                taskRow("done-one", "Ship it", "", state = "DONE"),
                taskRow("todo-one", "Ship it too", "", state = "TODO"),
            ),
        )
        assertEquals(listOf("done-one"), SearchQuery.search(db, "task:done", NOW).map { it.doc.id })
    }

    @Test fun `an unrecognized state value is an honest zero, not a thrown error`() {
        val db = freshIndex(loops = listOf(loopRow("l1", "A loop", "", state = "UNUSED")))
        assertEquals(emptyList<String>(), SearchQuery.search(db, "loop:garbage", NOW).map { it.doc.id })
    }

    // ---- indexing loops/tasks must never perturb conversation search ----

    @Test fun `conversation bm25 ordering is unaffected by an indexed loop and task corpus`() {
        // Regression for the separate-FTS5-tables design: mixing rows into conv_fts would shift
        // conv_fts's own bm25 IDF statistics the moment anything else was indexed. Same fixture
        // as SearchQueryTest's golden-ordering test, with a sizeable loop/task corpus alongside.
        val convs = listOf(
            convRow("titled", "gradle build cache", "notes"),
            convRow("content-only", "misc conversation", "gradle build cache discussion"),
            convRow("stale", "gradle build cache", "old notes", ageDays = 400),
            convRow("unrelated", "weather forecast", "sunny and warm"),
        )
        val loops = (1..5).map { loopRow("loop$it", "gradle loop $it", "gradle build cache automation $it") }
        val tasks = (1..5).map { taskRow("task$it", "gradle task $it", "gradle build cache chore $it") }

        val withoutOthers = freshIndex(convs = convs)
        val withOthers = freshIndex(convs = convs, loops = loops, tasks = tasks)

        val query = "gradle build cache"
        val hitsWithout = SearchQuery.search(withoutOthers, query, NOW)
        val hitsWith = SearchQuery.search(withOthers, query, NOW)

        assertEquals(hitsWithout.map { it.doc.id }, hitsWith.map { it.doc.id })
        assertEquals(hitsWithout.map { it.score }, hitsWith.map { it.score })

        // And the direct LexicalSearch comparison SearchQueryTest itself pins, restated here so
        // this file stands on its own as evidence the guarantee holds with the corpus expanded.
        val docsDirect = convs.map { r -> SearchDoc(r.convId, r.titleRaw, r.snippetRaw, r.bodyRaw, r.updatedAt, SearchKind.TEXT) }
        val direct = LexicalSearch.search(docsDirect, query, NOW)
        assertEquals(direct.map { it.doc.id }, hitsWith.map { it.doc.id })
    }

    @Test fun `a bare-text query never surfaces a loop or task result`() {
        val db = freshIndex(
            convs = listOf(convRow("c1", "gradle", "gradle build")),
            loops = listOf(loopRow("l1", "gradle loop", "gradle build")),
            tasks = listOf(taskRow("t1", "gradle task", "gradle build")),
        )
        val hits = SearchQuery.search(db, "gradle", NOW)
        assertTrue(hits.all { it.doc.kind == SearchKind.TEXT })
        assertEquals(listOf("c1"), hits.map { it.doc.id })
    }
}
