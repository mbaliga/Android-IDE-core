package dev.fonebrew.ui.search

import dev.fonebrew.domain.search.ExplainField
import dev.fonebrew.domain.search.GraphAdjacentRecall
import dev.fonebrew.domain.search.LexicalSearch
import dev.fonebrew.domain.search.MatchExplanation
import dev.fonebrew.domain.search.MatchedIn
import dev.fonebrew.domain.search.SearchDoc
import dev.fonebrew.domain.search.SearchKind
import dev.fonebrew.domain.search.TermContribution
import dev.fonebrew.domain.thread.EdgeDerivation
import dev.fonebrew.domain.thread.ThreadEdgeKind
import dev.fonebrew.domain.thread.ThreadGraph
import dev.fonebrew.domain.thread.ThreadGraphEdge
import dev.fonebrew.domain.thread.ThreadGraphNode
import dev.fonebrew.domain.thread.ThreadNodeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

class GraphAdjacentRecallPresenterTest {

    private val at: Instant = Instant.parse("2026-09-15T00:00:00Z")

    private fun row(
        convId: String,
        explanation: MatchExplanation? = null,
    ) = SearchResultsPresenter.ResultRow(
        convId = convId,
        title = "title-$convId",
        snippet = "snippet-$convId",
        relativeTime = "now",
        matchedIn = MatchedIn.CONTENT,
        titleHighlights = emptyList(),
        snippetHighlights = emptyList(),
        explanation = explanation,
    )

    private fun explanation(vararg terms: String) = MatchExplanation(
        termContributions = terms.map { TermContribution(it, ExplainField.CONTENT, rawCount = 1, weight = 1.0, subtotal = 1.0) },
        titleWeight = 0.0,
        contentWeight = 1.0,
        recencyFactor = 0.0,
        lexicalScore = 1.0,
    )

    // ---- seedsFor ---------------------------------------------------------------------------

    @Test fun `seedsFor builds one seed from the tapped row's own id and matched terms`() {
        val seeds = GraphAdjacentRecallPresenter.seedsFor(row("root-1", explanation("gradle", "cache")))
        assertEquals(listOf(GraphAdjacentRecall.Seed("root-1", listOf("gradle", "cache"))), seeds)
    }

    @Test fun `seedsFor honestly reports no matched terms when the row carries no explanation`() {
        val seeds = GraphAdjacentRecallPresenter.seedsFor(row("root-1", explanation = null))
        assertEquals(listOf(GraphAdjacentRecall.Seed("root-1", emptyList())), seeds)
    }

    @Test fun `seedsFor never seeds from any row but the tapped one`() {
        // Only the tapped row is ever passed in — there is no "current result set" parameter at
        // all, by construction (see this fun's own KDoc for why).
        val seeds = GraphAdjacentRecallPresenter.seedsFor(row("root-1", explanation("gradle")))
        assertEquals(1, seeds.size)
    }

    @Test fun `seedsFor matches GraphAdjacentRecall seedsFrom run on the same underlying hit`() {
        val doc = SearchDoc(id = "root-1", title = "Gradle cache miss", snippet = "", body = "the build cache went cold", lastActivityMillis = 0L, kind = SearchKind.TEXT)
        val hits = LexicalSearch.search(listOf(doc), query = "gradle cache", nowMillis = 0L, explain = true)
        val viaRow = GraphAdjacentRecallPresenter.seedsFor(
            SearchResultsPresenter.present(hits, nowMillis = 0L, zone = ZoneOffset.UTC, locale = Locale.US).single(),
        )
        val viaDomain = GraphAdjacentRecall.seedsFrom(hits)
        assertEquals(viaDomain, viaRow)
    }

    // ---- present ------------------------------------------------------------------------------

    private fun graph(nodes: List<ThreadGraphNode>, edges: List<ThreadGraphEdge> = emptyList()) =
        ThreadGraph(generatedAtUtc = at, nodes = nodes, edges = edges)

    private fun msgNode(id: String, rootId: String, label: String? = null) =
        ThreadGraphNode(id = id, kind = ThreadNodeKind.MESSAGE, rootId = rootId, at = at, label = label)

    private fun citation(relation: GraphAdjacentRecall.Relation = GraphAdjacentRecall.Relation.PARENT) =
        GraphAdjacentRecall.Citation(relation, "because text, verbatim", "seed-1", listOf("gradle"))

    @Test fun `present maps each included item to a row with the graph's own label and rootId`() {
        val g = graph(listOf(msgNode("a", rootId = "root-1", label = "A label")))
        val result = GraphAdjacentRecall.Result(
            included = listOf(GraphAdjacentRecall.RecalledItem("a", listOf(citation()))),
            cut = emptyList(),
            cap = 10,
        )
        val sheet = GraphAdjacentRecallPresenter.present(result, g)
        assertEquals(1, sheet.rows.size)
        val row = sheet.rows.single()
        assertEquals("a", row.nodeId)
        assertEquals("A label", row.label)
        assertEquals("root-1", row.jumpRootId)
    }

    @Test fun `present leaves label and jumpRootId honestly null when the graph minted no label`() {
        val g = graph(listOf(msgNode("a", rootId = "root-1", label = null)))
        val result = GraphAdjacentRecall.Result(
            included = listOf(GraphAdjacentRecall.RecalledItem("a", listOf(citation()))),
            cut = emptyList(),
            cap = 10,
        )
        val row = GraphAdjacentRecallPresenter.present(result, g).rows.single()
        assertNull(row.label)
        assertEquals("root-1", row.jumpRootId) // rootId is unconditional on ThreadGraphNode, unlike label
    }

    @Test fun `present never fabricates a jump target for a recalled id missing from the graph`() {
        // Defensive: GraphAdjacentRecall.expand only ever recalls ids reachable by a real edge in
        // the same graph it was given, so this should not happen in practice — but present() must
        // never guess a target rather than fail honestly if it somehow does.
        val g = graph(emptyList())
        val result = GraphAdjacentRecall.Result(
            included = listOf(GraphAdjacentRecall.RecalledItem("ghost", listOf(citation()))),
            cut = emptyList(),
            cap = 10,
        )
        val row = GraphAdjacentRecallPresenter.present(result, g).rows.single()
        assertNull(row.label)
        assertNull(row.jumpRootId)
    }

    @Test fun `present passes citations through verbatim, never synthesizing or reordering them`() {
        val g = graph(listOf(msgNode("a", rootId = "root-1")))
        val c1 = citation(GraphAdjacentRecall.Relation.PARENT)
        val c2 = GraphAdjacentRecall.Citation(GraphAdjacentRecall.Relation.CHILDREN, "second because", "seed-2", listOf("cache"))
        val result = GraphAdjacentRecall.Result(
            included = listOf(GraphAdjacentRecall.RecalledItem("a", listOf(c1, c2))),
            cut = emptyList(),
            cap = 10,
        )
        val row = GraphAdjacentRecallPresenter.present(result, g).rows.single()
        assertEquals(listOf(c1, c2), row.citations)
        // Not copies with equal fields — the exact same citation objects, never rebuilt.
        assertSame(c1, row.citations[0])
        assertSame(c2, row.citations[1])
    }

    @Test fun `present preserves GraphAdjacentRecall's own included order`() {
        val g = graph(listOf(msgNode("b", "root-1"), msgNode("a", "root-1")))
        val result = GraphAdjacentRecall.Result(
            included = listOf(
                GraphAdjacentRecall.RecalledItem("b", listOf(citation())),
                GraphAdjacentRecall.RecalledItem("a", listOf(citation())),
            ),
            cut = emptyList(),
            cap = 10,
        )
        assertEquals(listOf("b", "a"), GraphAdjacentRecallPresenter.present(result, g).rows.map { it.nodeId })
    }

    @Test fun `present surfaces the cap, cut count and truncation flag honestly, never silently`() {
        val g = graph((1..5).map { msgNode("n$it", "root-1") })
        val included = (1..2).map { GraphAdjacentRecall.RecalledItem("n$it", listOf(citation())) }
        val cut = (3..5).map { GraphAdjacentRecall.RecalledItem("n$it", listOf(citation())) }
        val result = GraphAdjacentRecall.Result(included = included, cut = cut, cap = 2)

        val sheet = GraphAdjacentRecallPresenter.present(result, g)
        assertEquals(2, sheet.rows.size)
        assertEquals(2, sheet.cap)
        assertEquals(3, sheet.cutCount)
        assertEquals(5, sheet.consideredCount)
        assertTrue(sheet.truncated)
    }

    @Test fun `present reports no truncation when the cap covered every candidate`() {
        val g = graph(listOf(msgNode("a", "root-1")))
        val result = GraphAdjacentRecall.Result(
            included = listOf(GraphAdjacentRecall.RecalledItem("a", listOf(citation()))),
            cut = emptyList(),
            cap = 10,
        )
        val sheet = GraphAdjacentRecallPresenter.present(result, g)
        assertEquals(0, sheet.cutCount)
        assertTrue(!sheet.truncated)
    }

    @Test fun `present of an empty result is an empty sheet, not an error`() {
        val g = graph(emptyList())
        val result = GraphAdjacentRecall.Result(included = emptyList(), cut = emptyList(), cap = 10)
        val sheet = GraphAdjacentRecallPresenter.present(result, g)
        assertTrue(sheet.rows.isEmpty())
        assertEquals(0, sheet.consideredCount)
        assertTrue(!sheet.truncated)
    }

    // ---- end-to-end over a real seedsFor -> expand -> present chain -------------------------

    @Test fun `seedsFor into expand into present round-trips a real one-hop recall`() {
        val g = graph(
            listOf(
                msgNode("root-1", "root-1", label = null),
                msgNode("msg-42", "root-1", label = "the reply"),
            ),
            listOf(ThreadGraphEdge("root-1", "msg-42", ThreadEdgeKind.REPLY, EdgeDerivation.EXTRACTED, "parent-child reply recorded in the message tree")),
        )
        val tappedRow = row("root-1", explanation("gradle"))
        val seeds = GraphAdjacentRecallPresenter.seedsFor(tappedRow)
        val result = GraphAdjacentRecall.expand(seeds, g, cap = GraphAdjacentRecallPresenter.DEFAULT_CAP)
        val sheet = GraphAdjacentRecallPresenter.present(result, g)

        val recalled = sheet.rows.single()
        assertEquals("msg-42", recalled.nodeId)
        assertEquals("the reply", recalled.label)
        assertEquals("root-1", recalled.jumpRootId)
        val citation = recalled.citations.single()
        assertEquals(GraphAdjacentRecall.Relation.CHILDREN, citation.relation)
        assertEquals("parent-child reply recorded in the message tree", citation.because)
        assertEquals(listOf("gradle"), citation.matchedQueryTerms)
    }
}
