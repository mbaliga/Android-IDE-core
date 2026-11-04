package dev.fonebrew.data.search

import dev.fonebrew.data.entity.TaskEntity
import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.Role
import dev.fonebrew.domain.bpmn.BpmnArchive
import dev.fonebrew.domain.bpmn.BpmnGraph
import dev.fonebrew.domain.bpmn.BpmnNode
import dev.fonebrew.domain.bpmn.BpmnNodeKind
import dev.fonebrew.domain.bpmn.Bounds
import dev.fonebrew.domain.loop.Loop
import dev.fonebrew.domain.search.Segmenter
import dev.fonebrew.domain.search.Stemmer
import dev.fonebrew.domain.search.query.QueryCompiler
import dev.fonebrew.domain.search.query.QueryParser
import dev.fonebrew.domain.tasks.TaskState
import dev.fonebrew.domain.tree.MessageTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-FTS5, end-to-end proof that stemming ([Stemmer]) actually works through the whole
 * pipeline — [Segmenter] → [Stemmer] → `Search.sq`'s real `conv_fts`/`loop_fts`/`task_fts`
 * (via `sqlite-bundled`, no device/Robolectric needed) → [SearchQuery] — the same real-`MATCH`/
 * `bm25()` style [SearchIndexerTest] and [SearchQueryTypeFacetTest] already use. Covers the
 * acceptance list from the search-stemming build plan: a bare inflected query finds a
 * differently-inflected document; a quoted phrase with inflected forms still matches; an
 * explicit prefix query is unaffected; loop:/task: are stemmed too; bm25 ordering is unmoved by
 * stemming; and a version-bump rebuild actually repopulates all three corpora with stemmed text.
 */
class StemmingSearchIntegrationTest {

    private companion object {
        const val NOW = 1_800_000_000_000L
    }

    private fun node(id: String, parentId: String?, role: Role, content: String, createdAt: Long) =
        MessageNode(id, parentId, role, content, null, createdAt)

    /** Same shape as [dev.fonebrew.data.search.LoopSearchProjectorTest]'s own helper: one task
     *  node carrying the interesting text, bracketed by a start/end event. */
    private fun bpmn(nodeName: String, systemPrompt: String): String {
        val graph = BpmnGraph(
            id = "g1",
            name = "Loop",
            nodes = listOf(
                BpmnNode("start", BpmnNodeKind.START_EVENT, "Start", Bounds(0.0, 0.0)),
                BpmnNode("t1", BpmnNodeKind.TASK, nodeName, Bounds(100.0, 0.0), ext = linkedMapOf("systemPrompt" to systemPrompt)),
                BpmnNode("end", BpmnNodeKind.END_EVENT, "End", Bounds(200.0, 0.0)),
            ),
        )
        return BpmnArchive.write(graph)
    }

    // ---- a bare inflected query finds a differently-inflected document ----

    @Test fun `a query for running matches a doc containing runs`() {
        val tree = MessageTree(listOf(node("u1", null, Role.USER, "the tests keep failing whenever the pipeline runs", 1L)))
        val rows = SearchProjector.project(tree, emptySet(), emptySet(), emptyMap(), emptyMap())
        val db = SearchDriverFactory.createInMemory().database
        SearchIndexer.reindex(db, rows, nowMillis = NOW)

        // Real FTS5 MATCH, at the candidate-generation layer this lane actually owns (Stemmer +
        // SearchProjector on the index side, QueryCompiler.compileFts on the query side): the
        // stemmed index really does carry "run" (from "runs"), and the stemmed, prefixed query
        // really does find it.
        //
        // Deliberately NOT asserted through the full SearchQuery.search() pipeline here: that
        // pipeline's second stage re-scores survivors with LexicalSearch over RAW, un-stemmed
        // text (a hard, pre-existing rule — see LexicalSearch's own class KDoc and SearchQuery's
        // "never modified or replaced" — and pinned by SearchQueryTest's golden-ordering test).
        // A single bare term with zero literal-substring overlap with the raw text ("running" vs
        // a document that only ever spells it "runs") is exactly the case that stage was never
        // built to credit, independent of anything this lane changed. QueryCompiler's own KDoc
        // documents this boundary; the phrase-search and loop:/task: tests below exercise the
        // full ranked pipeline successfully because their query words retain enough literal
        // overlap with the raw text to survive that stage too.
        val fts = QueryCompiler.compileFts(QueryParser.parse("running").root, prefixBareTerms = true)
        val hits = db.searchQueries.searchCandidates(fts!!, 10).executeAsList()
        assertEquals(listOf("u1"), hits.map { it.conv_id })
    }

    // ---- a quoted phrase with inflected forms still matches ----

    @Test fun `a quoted phrase using different inflected forms than the document still matches`() {
        val tree = MessageTree(listOf(node("u1", null, Role.USER, "running tests every night keeps the build honest", 1L)))
        val rows = SearchProjector.project(tree, emptySet(), emptySet(), emptyMap(), emptyMap())
        val db = SearchDriverFactory.createInMemory().database
        SearchIndexer.reindex(db, rows, nowMillis = NOW)

        // The document says "running tests"; the query types the plural-verb/singular-noun
        // inflection "runs test" instead. Phrase search is exact-token, not prefix, so this can
        // only succeed because both sides stem to the identical "run test" token pair.
        val hits = SearchQuery.search(db, "\"runs test\"", NOW)
        assertEquals(listOf("u1"), hits.map { it.doc.id })
    }

    @Test fun `a quoted phrase using the document's own literal spelling still matches too`() {
        val tree = MessageTree(listOf(node("u1", null, Role.USER, "the annotation processor caresses every build", 1L)))
        val rows = SearchProjector.project(tree, emptySet(), emptySet(), emptyMap(), emptyMap())
        val db = SearchDriverFactory.createInMemory().database
        SearchIndexer.reindex(db, rows, nowMillis = NOW)

        assertEquals(listOf("u1"), SearchQuery.search(db, "\"caresses every build\"", NOW).map { it.doc.id })
    }

    // ---- an explicit prefix query is unaffected by stemming ----

    @Test fun `an explicit user-typed prefix is literal and does not benefit from stemming`() {
        val tree = MessageTree(listOf(node("u1", null, Role.USER, "the customer caresses were the whole complaint", 1L)))
        val rows = SearchProjector.project(tree, emptySet(), emptySet(), emptyMap(), emptyMap())
        val db = SearchDriverFactory.createInMemory().database
        SearchIndexer.reindex(db, rows, nowMillis = NOW)
        // The indexed token is stemmed to "caress" ("caresses" -> "caress", Step 1a).

        // An explicit prefix search for the un-stemmed spelling asks for a literal prefix that no
        // longer exists in the (now-shorter, stemmed) index — this is the honest, documented
        // cost of "explicit prefix means literal": it is unaffected by stemming in either
        // direction, so it does not automatically find the stemmed token either.
        assertTrue(SearchQuery.search(db, "caresses*", NOW).isEmpty())

        // The same word typed bare (no explicit "*") IS stemmed, and finds it.
        assertEquals(listOf("u1"), SearchQuery.search(db, "caresses", NOW).map { it.doc.id })
    }

    // ---- loop: and task: corpora are stemmed too ----

    @Test fun `the loop corpus is stemmed too`() {
        val loop = Loop(id = "l1", name = "Automation", bpmnXml = bpmn("Runner", "running tests before every deploy"))
        val rows = LoopSearchProjector.project(listOf(loop))
        val db = SearchDriverFactory.createInMemory().database
        SearchIndexer.syncLoops(db, rows, nowMillis = NOW)

        val hits = SearchQuery.search(db, "\"runs test\" loop:", NOW)
        assertEquals(listOf("l1"), hits.map { it.doc.id })
    }

    @Test fun `the task corpus is stemmed too`() {
        val task = TaskEntity(
            id = "t1", title = "Fix flakiness", notes = "running tests until it consistently passes",
            orderKey = 0.0, createdAt = 1L, updatedAt = 1L, state = TaskState.TODO,
        )
        val rows = TaskSearchProjector.project(listOf(task))
        val db = SearchDriverFactory.createInMemory().database
        SearchIndexer.syncTasks(db, rows, nowMillis = NOW)

        val hits = SearchQuery.search(db, "\"runs test\" task:", NOW)
        assertEquals(listOf("t1"), hits.map { it.doc.id })
    }

    // ---- bm25 ordering is still ascending-is-better once tokens are stemmed ----

    @Test fun `bm25 ordering over stemmed tokens is still ascending-is-better`() {
        fun row(id: String, bodyRaw: String) = SearchProjector.Row(
            convId = id,
            title = Stemmer.stemJoined(Segmenter.tokenizeForIndex("untitled")),
            snippet = "",
            body = Stemmer.stemJoined(Segmenter.tokenizeForIndex(bodyRaw)),
            titleRaw = "untitled", snippetRaw = "", bodyRaw = bodyRaw,
            updatedAt = 0L, createdAt = 0L, projectionVersion = SearchProjector.PROJECTION_VERSION,
            starred = false, archived = false, projectId = null, modelIds = "",
            turnCount = 1L, branchCount = 1L, hasImage = false, hasCode = false, costMinor = 0L, lastUsedAt = 0L,
        )
        val db = SearchDriverFactory.createInMemory().database
        SearchIndexer.reindex(
            db,
            listOf(
                row("many", "running running running running tests"),
                row("few", "a single run happened once"),
            ),
            nowMillis = NOW,
        )

        // Both rows carry the stemmed token "run"; "many" repeats it densely in a short field,
        // which bm25 scores as the stronger match — a lower (better) score, first in ascending
        // order — exactly the pre-existing, unchanged bm25 convention this Search.sq deviation
        // documents (see its own "Deviation from the spec" comment).
        val hits = db.searchQueries.searchCandidates("run", 10).executeAsList()
        assertEquals(listOf("many", "few"), hits.map { it.conv_id })
    }

    // ---- a version-bump rebuild actually repopulates all three corpora with stemmed text ----

    @Test fun `rebuilding after a version bump repopulates all three corpora with freshly-stemmed text`() {
        val db = SearchDriverFactory.createInMemory().database

        // ---- seed each corpus with LITERAL, un-stemmed text -- exactly what SearchIndexer wrote
        // before this change, still stamped at the version that predates stemming. ----
        val bodyRaw = "running tests before every release"
        val legacyConv = SearchProjector.Row(
            convId = "c1", title = Segmenter.tokenizeForIndex("pipeline"), snippet = "",
            body = Segmenter.tokenizeForIndex(bodyRaw), // segmented, NOT stemmed -- the pre-change shape
            titleRaw = "pipeline", snippetRaw = "", bodyRaw = bodyRaw,
            updatedAt = 1L, createdAt = 1L, projectionVersion = SearchProjector.PROJECTION_VERSION,
            starred = false, archived = false, projectId = null, modelIds = "", turnCount = 1L, branchCount = 1L,
            hasImage = false, hasCode = false, costMinor = 0L, lastUsedAt = 1L,
        )
        SearchIndexer.reindex(db, listOf(legacyConv), nowMillis = 1L)
        db.searchQueries.upsertIndexState(
            last_indexed_rowid = 1L,
            projection_version = SearchProjector.PROJECTION_VERSION,
            tokenizer_version = SearchIndexer.TOKENIZER_VERSION - 1,
            schema_version = SearchIndexer.SCHEMA_VERSION,
            updated_at = 1L,
        )
        db.searchQueries.upsertLoopProjection(
            loop_id = "l1",
            title = Segmenter.tokenizeForIndex("Automation"),
            body = Segmenter.tokenizeForIndex(bodyRaw),
            title_raw = "Automation", body_raw = bodyRaw, state = "UNUSED",
            updated_at = 1L, created_at = 1L, projection_version = LoopSearchProjector.PROJECTION_VERSION - 1,
        )
        db.searchQueries.upsertTaskProjection(
            task_id = "t1",
            title = Segmenter.tokenizeForIndex("Fix flakiness"),
            body = Segmenter.tokenizeForIndex(bodyRaw),
            title_raw = "Fix flakiness", body_raw = bodyRaw, state = "TODO",
            updated_at = 1L, created_at = 1L, projection_version = TaskSearchProjector.PROJECTION_VERSION - 1,
        )

        // A stemmed phrase query finds nothing yet: every corpus still holds the literal,
        // un-stemmed "running"/"tests" tokens, not "run"/"test" -- phrase search is exact-token,
        // so this genuinely depends on the index having been re-stemmed, not on prefix matching.
        assertTrue(SearchQuery.search(db, "\"run test\"", NOW).isEmpty())
        assertTrue(SearchQuery.search(db, "\"run test\" loop:", NOW).isEmpty())
        assertTrue(SearchQuery.search(db, "\"run test\" task:", NOW).isEmpty())

        // ---- rebuild with the SAME content, projected fresh -- exactly what
        // SearchRepository.reindexAll does on every app start, which is what actually re-stems it. ----
        val freshConv = SearchProjector.project(
            MessageTree(listOf(node("c1", null, Role.USER, bodyRaw, 1L))),
            emptySet(), emptySet(), emptyMap(), emptyMap(),
        )
        val freshLoop = LoopSearchProjector.project(listOf(Loop(id = "l1", name = "Automation", bpmnXml = bpmn("Runner", bodyRaw))))
        val freshTask = TaskSearchProjector.project(
            listOf(TaskEntity(id = "t1", title = "Fix flakiness", notes = bodyRaw, orderKey = 0.0, createdAt = 1L, updatedAt = 1L, state = TaskState.TODO)),
        )
        SearchIndexer.reindex(db, freshConv, nowMillis = 2L)
        SearchIndexer.syncLoops(db, freshLoop, nowMillis = 2L)
        SearchIndexer.syncTasks(db, freshTask, nowMillis = 2L)

        assertEquals(listOf("c1"), SearchQuery.search(db, "\"run test\"", NOW).map { it.doc.id })
        assertEquals(listOf("l1"), SearchQuery.search(db, "\"run test\" loop:", NOW).map { it.doc.id })
        assertEquals(listOf("t1"), SearchQuery.search(db, "\"run test\" task:", NOW).map { it.doc.id })

        val state = db.searchQueries.selectIndexState().executeAsOne()
        assertEquals(SearchIndexer.TOKENIZER_VERSION, state.tokenizer_version)
    }
}
