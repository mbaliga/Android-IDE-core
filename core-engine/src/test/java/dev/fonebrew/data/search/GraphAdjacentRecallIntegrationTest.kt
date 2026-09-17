package dev.fonebrew.data.search

import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.Role
import dev.fonebrew.domain.search.GraphAdjacentRecall
import dev.fonebrew.domain.thread.ThreadGraphProjector
import dev.fonebrew.domain.tree.MessageTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Real-FTS5, end-to-end proof of [GraphAdjacentRecall]'s whole shape — the same real `MATCH`/
 * `bm25()` [SearchDriverFactory]/[SearchIndexer]/[SearchQuery] pipeline [SearchIndexerTest] and
 * [dev.fonebrew.data.search.StemmingSearchIntegrationTest] already exercise, feeding into a real
 * [ThreadGraphProjector] snapshot of the *same* tree: a genuine FTS5 hit expands only into nodes a
 * recorded graph edge actually connects it to, each cited with that edge's own truthful `because`
 * and the query term that made the hit match — never into an unconnected conversation that merely
 * also lives in the index.
 */
class GraphAdjacentRecallIntegrationTest {

    private fun node(id: String, parentId: String?, content: String, createdAt: Long) =
        MessageNode(id, parentId, Role.USER, content, null, createdAt)

    @Test fun `a real FTS5 hit expands to its recorded REPLY neighbor, not an unconnected conversation`() {
        val tree = MessageTree(
            listOf(
                node("root-1", null, "the gradle build pipeline keeps failing", 100L),
                node("msg-42", "root-1", "let's add a retry step", 200L),
                node("root-2", null, "totally unrelated weather chat", 300L),
            ),
        )
        val rows = SearchProjector.project(tree, emptySet(), emptySet(), emptyMap(), emptyMap())
        val db = SearchDriverFactory.createInMemory().database
        SearchIndexer.reindex(db, rows, nowMillis = 1_000L)

        // Real FTS5 MATCH + LexicalSearch re-scoring — SearchQuery.search always ranks with
        // explain = true, so the hit already carries the term contributions seedsFrom reads.
        val hits = SearchQuery.search(db, "gradle", nowMillis = 1_000L)
        assertEquals(listOf("root-1"), hits.map { it.doc.id })

        val graph = ThreadGraphProjector.project(tree, emptyList(), emptyList(), Instant.ofEpochMilli(1_000L))
        val seeds = GraphAdjacentRecall.seedsFrom(hits)
        assertEquals(listOf("gradle"), seeds.single().matchedQueryTerms)

        val recall = GraphAdjacentRecall.expand(seeds, graph, cap = 10)

        assertEquals(listOf("msg-42"), recall.included.map { it.nodeId })
        val citation = recall.included.single().citations.single()
        assertEquals(GraphAdjacentRecall.Relation.CHILDREN, citation.relation)
        assertEquals("parent-child reply recorded in the message tree", citation.because)
        assertEquals("root-1", citation.fromNodeId)
        assertEquals(listOf("gradle"), citation.matchedQueryTerms)

        // root-2 is in the same FTS5 index but has no recorded edge to root-1 — it must never be
        // recalled just because it exists in the same corpus.
        assertTrue(recall.included.none { it.nodeId == "root-2" })
        assertTrue(recall.cut.none { it.nodeId == "root-2" })
    }

    @Test fun `a query matching two unrelated conversations expands each independently by hit rank`() {
        val tree = MessageTree(
            listOf(
                node("root-1", null, "gradle cache miss during release", 100L),
                node("child-1", "root-1", "retrying the build", 150L),
                node("root-2", null, "gradle cache also flaky here", 50L),
                node("child-2", "root-2", "different fix attempt", 60L),
            ),
        )
        val rows = SearchProjector.project(tree, emptySet(), emptySet(), emptyMap(), emptyMap())
        val db = SearchDriverFactory.createInMemory().database
        SearchIndexer.reindex(db, rows, nowMillis = 1_000L)

        val hits = SearchQuery.search(db, "gradle cache", nowMillis = 1_000L)
        assertEquals(setOf("root-1", "root-2"), hits.map { it.doc.id }.toSet())

        val graph = ThreadGraphProjector.project(tree, emptyList(), emptyList(), Instant.ofEpochMilli(1_000L))
        val recall = GraphAdjacentRecall.expand(GraphAdjacentRecall.seedsFrom(hits), graph, cap = 10)

        assertEquals(setOf("child-1", "child-2"), recall.included.map { it.nodeId }.toSet())
        // Each child is cited from its own parent conversation, never crossed over.
        recall.included.forEach { item ->
            val citation = item.citations.single()
            val expectedFrom = if (item.nodeId == "child-1") "root-1" else "root-2"
            assertEquals(expectedFrom, citation.fromNodeId)
        }
    }
}
