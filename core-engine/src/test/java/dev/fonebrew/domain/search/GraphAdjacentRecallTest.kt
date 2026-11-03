package dev.fonebrew.domain.search

import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.Role
import dev.fonebrew.domain.curation.BookmarkKind
import dev.fonebrew.domain.curation.MessageBookmark
import dev.fonebrew.domain.curation.MessageRef
import dev.fonebrew.domain.thread.EdgeDerivation
import dev.fonebrew.domain.thread.ThreadEdgeKind
import dev.fonebrew.domain.thread.ThreadGraph
import dev.fonebrew.domain.thread.ThreadGraphEdge
import dev.fonebrew.domain.thread.ThreadGraphNode
import dev.fonebrew.domain.thread.ThreadGraphProjector
import dev.fonebrew.domain.thread.ThreadNodeKind
import dev.fonebrew.domain.tree.MessageTree
import dev.fonebrew.domain.tree.TreeFork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * [GraphAdjacentRecall] — the graph-traversal-instead-of-embeddings retrieval shape. Every test
 * either hand-builds a small [ThreadGraph] to pin one relation in isolation, or runs a real
 * [ThreadGraphProjector] snapshot to prove the citations it returns are the projector's own
 * truthful `because` text, not paraphrased.
 */
class GraphAdjacentRecallTest {

    private val at: Instant = Instant.parse("2026-09-06T00:00:00Z")

    private fun graph(nodes: List<ThreadGraphNode>, edges: List<ThreadGraphEdge>) =
        ThreadGraph(generatedAtUtc = at, nodes = nodes, edges = edges)

    private fun msgNode(id: String, rootId: String, parentId: String? = null) =
        ThreadGraphNode(id = id, kind = ThreadNodeKind.MESSAGE, rootId = rootId, parentId = parentId, at = at)

    private fun reply(from: String, to: String, because: String = "parent-child reply recorded in the message tree") =
        ThreadGraphEdge(from, to, ThreadEdgeKind.REPLY, EdgeDerivation.EXTRACTED, because)

    private fun seed(id: String, terms: List<String> = listOf("gradle")) = GraphAdjacentRecall.Seed(id, terms)

    // ---- PARENT / CHILDREN ------------------------------------------------------------------

    @Test fun `a hit's parent is recalled via the backward REPLY edge, cited with the edge's own because`() {
        val g = graph(
            listOf(msgNode("root-1", "root-1"), msgNode("msg-42", "root-1", "root-1")),
            listOf(reply("root-1", "msg-42")),
        )
        val result = GraphAdjacentRecall.expand(listOf(seed("msg-42")), g, cap = 10)

        assertEquals(listOf("root-1"), result.included.map { it.nodeId })
        val citation = result.included.single().citations.single()
        assertEquals(GraphAdjacentRecall.Relation.PARENT, citation.relation)
        assertEquals("parent-child reply recorded in the message tree", citation.because)
        assertEquals("msg-42", citation.fromNodeId)
        assertEquals(listOf("gradle"), citation.matchedQueryTerms)
    }

    @Test fun `a hit's children are recalled via forward REPLY edges`() {
        val g = graph(
            listOf(msgNode("root-1", "root-1"), msgNode("a", "root-1", "root-1"), msgNode("b", "root-1", "root-1")),
            listOf(reply("root-1", "a"), reply("root-1", "b")),
        )
        val result = GraphAdjacentRecall.expand(listOf(seed("root-1")), g, cap = 10)

        assertEquals(listOf("a", "b"), result.included.map { it.nodeId })
        result.included.forEach { assertEquals(GraphAdjacentRecall.Relation.CHILDREN, it.citations.single().relation) }
    }

    // ---- ALTERNATIVES -------------------------------------------------------------------------

    @Test fun `sibling branches under the same parent are recalled as ALTERNATIVES, each with its own REPLY citation`() {
        val g = graph(
            listOf(msgNode("root-1", "root-1"), msgNode("a", "root-1", "root-1"), msgNode("b", "root-1", "root-1")),
            listOf(
                reply("root-1", "a"),
                reply("root-1", "b", because = "parent-child reply recorded in the message tree (regenerated branch)"),
            ),
        )
        val result = GraphAdjacentRecall.expand(listOf(seed("a")), g, cap = 10)

        val byId = result.included.associateBy { it.nodeId }
        assertEquals(GraphAdjacentRecall.Relation.PARENT, byId.getValue("root-1").citations.single().relation)
        val altCitation = byId.getValue("b").citations.single()
        assertEquals(GraphAdjacentRecall.Relation.ALTERNATIVES, altCitation.relation)
        // Truthful: cited by b's OWN real REPLY edge, not a's.
        assertEquals("parent-child reply recorded in the message tree (regenerated branch)", altCitation.because)
    }

    @Test fun `a root hit with no parent has no ALTERNATIVES`() {
        val g = graph(
            listOf(msgNode("root-1", "root-1"), msgNode("a", "root-1", "root-1")),
            listOf(reply("root-1", "a")),
        )
        val result = GraphAdjacentRecall.expand(listOf(seed("root-1")), g, cap = 10)
        assertTrue(result.included.none { it.citations.any { c -> c.relation == GraphAdjacentRecall.Relation.ALTERNATIVES } })
    }

    // ---- LINEAGE_SOURCE (FORK/LINEAGE) ---------------------------------------------------------

    @Test fun `a FORK edge recalls the lineage source, either direction, as LINEAGE_SOURCE`() {
        val g = graph(
            listOf(
                msgNode("root-1", "root-1"), msgNode("msg-42", "root-1", "root-1"),
                ThreadGraphNode(id = "root-2", kind = ThreadNodeKind.FORK_ROOT, rootId = "root-2", parentId = "msg-42", at = at),
            ),
            listOf(
                reply("root-1", "msg-42"),
                ThreadGraphEdge(
                    "msg-42", "root-2", ThreadEdgeKind.FORK, EdgeDerivation.EXTRACTED,
                    "fork/spawn lineage recorded at insert (TreeFork.LINEAGE_SRC_NODE_KEY metadata on the new root's own node)",
                ),
            ),
        )

        val fromForkRoot = GraphAdjacentRecall.expand(listOf(seed("root-2")), g, cap = 10)
        assertEquals(listOf("msg-42"), fromForkRoot.included.map { it.nodeId })
        assertEquals(GraphAdjacentRecall.Relation.LINEAGE_SOURCE, fromForkRoot.included.single().citations.single().relation)

        val fromSourceNode = GraphAdjacentRecall.expand(listOf(seed("msg-42")), g, cap = 10)
        // msg-42's own PARENT (root-1) plus the FORK-side LINEAGE_SOURCE neighbor (root-2).
        assertEquals(setOf("root-1", "root-2"), fromSourceNode.included.map { it.nodeId }.toSet())
        assertEquals(
            GraphAdjacentRecall.Relation.LINEAGE_SOURCE,
            fromSourceNode.included.single { it.nodeId == "root-2" }.citations.single().relation,
        )
    }

    // ---- BRIDGE (SPAWN) -------------------------------------------------------------------------

    @Test fun `a SPAWN edge recalls the source as BRIDGE, not LINEAGE_SOURCE`() {
        val g = graph(
            listOf(
                msgNode("root-1", "root-1"),
                ThreadGraphNode(id = "root-2", kind = ThreadNodeKind.SPAWN_ROOT, rootId = "root-2", parentId = "root-1", at = at),
            ),
            listOf(
                ThreadGraphEdge(
                    "root-1", "root-2", ThreadEdgeKind.SPAWN, EdgeDerivation.EXTRACTED,
                    "fork/spawn lineage recorded at insert (TreeFork.LINEAGE_SRC_NODE_KEY metadata on the new root's own node)",
                ),
            ),
        )
        val result = GraphAdjacentRecall.expand(listOf(seed("root-2")), g, cap = 10)
        assertEquals(listOf("root-1"), result.included.map { it.nodeId })
        assertEquals(GraphAdjacentRecall.Relation.BRIDGE, result.included.single().citations.single().relation)
    }

    // ---- DECISION_ANCHOR ------------------------------------------------------------------------

    @Test fun `a DECISION_ANCHOR edge recalls in either direction`() {
        val g = graph(
            listOf(
                msgNode("root-1", "root-1"), msgNode("msg-42", "root-1", "root-1"),
                ThreadGraphNode(id = "bm-1", kind = ThreadNodeKind.DECISION, rootId = "root-1", parentId = "msg-42", at = at, label = "Use SQLDelight"),
            ),
            listOf(
                reply("root-1", "msg-42"),
                ThreadGraphEdge(
                    "bm-1", "msg-42", ThreadEdgeKind.DECISION_ANCHOR, EdgeDerivation.EXTRACTED,
                    "decision anchor recorded on the MessageBookmark itself (ref.msgId)",
                ),
            ),
        )

        val fromMessage = GraphAdjacentRecall.expand(listOf(seed("msg-42")), g, cap = 10)
        val decisionCitation = fromMessage.included.single { it.nodeId == "bm-1" }.citations.single()
        assertEquals(GraphAdjacentRecall.Relation.DECISION_ANCHOR, decisionCitation.relation)

        val fromDecision = GraphAdjacentRecall.expand(listOf(seed("bm-1")), g, cap = 10)
        assertEquals(listOf("msg-42"), fromDecision.included.map { it.nodeId })
        assertEquals(GraphAdjacentRecall.Relation.DECISION_ANCHOR, fromDecision.included.single().citations.single().relation)
    }

    // ---- out-of-scope edge kinds (named follow-up, not silently walked) ------------------------

    @Test fun `MARKER_ANCHOR, DELEGATION_ANCHOR and COMMIT_ANCHOR edges are never traversed`() {
        val g = graph(
            listOf(
                msgNode("msg-42", "root-1", null),
                ThreadGraphNode(id = "mk-1", kind = ThreadNodeKind.MARKER, rootId = "root-1", parentId = "msg-42", at = at),
                ThreadGraphNode(id = "dg-1", kind = ThreadNodeKind.DELEGATION, rootId = "root-1", parentId = "msg-42", at = at),
                ThreadGraphNode(id = "commit-1", kind = ThreadNodeKind.COMMIT, rootId = "root-1", at = at, sha = "abc123"),
            ),
            listOf(
                ThreadGraphEdge("mk-1", "msg-42", ThreadEdgeKind.MARKER_ANCHOR, EdgeDerivation.EXTRACTED, "marker anchor recorded on the ThreadMarker itself (anchorMsgId)"),
                ThreadGraphEdge("dg-1", "msg-42", ThreadEdgeKind.DELEGATION_ANCHOR, EdgeDerivation.EXTRACTED, "delegation anchor recorded on the DelegationEvent itself (anchorMsgId)"),
                ThreadGraphEdge("msg-42", "commit-1", ThreadEdgeKind.COMMIT_ANCHOR, EdgeDerivation.EXTRACTED, "commit recorded against this message's agent action"),
            ),
        )
        val result = GraphAdjacentRecall.expand(listOf(seed("msg-42")), g, cap = 10)
        assertTrue("expected no recall through out-of-scope edge kinds", result.included.isEmpty())
    }

    // ---- Issue #2 boundary: never through INFERRED --------------------------------------------

    @Test fun `an INFERRED edge is never traversed, even though the same nodes are otherwise connected`() {
        val g = graph(
            listOf(msgNode("root-1", "root-1"), msgNode("msg-42", "root-1", "root-1")),
            listOf(ThreadGraphEdge("root-1", "msg-42", ThreadEdgeKind.REPLY, EdgeDerivation.INFERRED, "a future analysis layer's derived relationship")),
        )
        val result = GraphAdjacentRecall.expand(listOf(seed("msg-42")), g, cap = 10)
        assertTrue(result.included.isEmpty())
        assertTrue(result.cut.isEmpty())
    }

    // ---- dedup: a neighbor that's already a direct hit is not "recalled" -----------------------

    @Test fun `a neighbor that is itself one of the seeds is not recalled`() {
        val g = graph(
            listOf(msgNode("root-1", "root-1"), msgNode("msg-42", "root-1", "root-1")),
            listOf(reply("root-1", "msg-42")),
        )
        val result = GraphAdjacentRecall.expand(listOf(seed("root-1"), seed("msg-42")), g, cap = 10)
        assertTrue(result.included.isEmpty())
    }

    // ---- multiple citations merge onto one item -------------------------------------------------

    @Test fun `a node reachable from two different seeds carries both citations, not two items`() {
        val g = graph(
            listOf(msgNode("root-1", "root-1"), msgNode("a", "root-1", "root-1"), msgNode("b", "root-1", "root-1")),
            listOf(reply("root-1", "a"), reply("root-1", "b")),
        )
        val result = GraphAdjacentRecall.expand(listOf(seed("a", listOf("gradle")), seed("b", listOf("cache"))), g, cap = 10)
        val rootItem = result.included.single { it.nodeId == "root-1" }
        assertEquals(2, rootItem.citations.size)
        assertEquals(setOf("a", "b"), rootItem.citations.map { it.fromNodeId }.toSet())
    }

    // ---- ordering: hit rank, then relation priority, then node id -------------------------------

    @Test fun `ordering is hit rank first, then relation priority, then neighbor id`() {
        // seed "x" recalls its parent (PARENT) and a sibling (ALTERNATIVES); seed "y" recalls
        // its own parent "p2". "x" is ranked first (position 0), so everything it recalls must
        // precede anything only "y" recalls, regardless of node-id alphabetical order.
        val g = graph(
            listOf(
                msgNode("p1", "root-1"), msgNode("x", "root-1", "p1"), msgNode("sib", "root-1", "p1"),
                msgNode("p2", "root-1"), msgNode("y", "root-1", "p2"),
            ),
            listOf(reply("p1", "x"), reply("p1", "sib"), reply("p2", "y")),
        )
        val result = GraphAdjacentRecall.expand(listOf(seed("x"), seed("y")), g, cap = 10)
        // "x"'s PARENT (p1) before its ALTERNATIVES (sib), both before "y"'s PARENT (p2).
        assertEquals(listOf("p1", "sib", "p2"), result.included.map { it.nodeId })
    }

    @Test fun `ordering and citations are deterministic across repeated runs`() {
        val g = graph(
            listOf(msgNode("root-1", "root-1"), msgNode("a", "root-1", "root-1"), msgNode("b", "root-1", "root-1")),
            listOf(reply("root-1", "a"), reply("root-1", "b")),
        )
        val seeds = listOf(seed("a"), seed("b"))
        val r1 = GraphAdjacentRecall.expand(seeds, g, cap = 10)
        val r2 = GraphAdjacentRecall.expand(seeds, g, cap = 10)
        assertEquals(r1, r2)
    }

    // ---- cap: surfaced, never silent -------------------------------------------------------------

    @Test fun `the cap is honored and every candidate beyond it is surfaced in cut, not silently dropped`() {
        val nodes = mutableListOf(msgNode("root-1", "root-1"))
        val edges = mutableListOf<ThreadGraphEdge>()
        for (i in 1..5) {
            nodes += msgNode("c$i", "root-1", "root-1")
            edges += reply("root-1", "c$i")
        }
        val g = graph(nodes, edges)

        val result = GraphAdjacentRecall.expand(listOf(seed("root-1")), g, cap = 2)
        assertEquals(2, result.included.size)
        assertEquals(3, result.cut.size)
        assertEquals(5, result.consideredCount)
        assertTrue(result.truncated)
        assertEquals(listOf("c1", "c2"), result.included.map { it.nodeId })
        assertEquals(listOf("c3", "c4", "c5"), result.cut.map { it.nodeId })
    }

    @Test fun `a cap covering every candidate reports no truncation`() {
        val g = graph(
            listOf(msgNode("root-1", "root-1"), msgNode("a", "root-1", "root-1")),
            listOf(reply("root-1", "a")),
        )
        val result = GraphAdjacentRecall.expand(listOf(seed("a")), g, cap = 10)
        assertFalse(result.truncated)
        assertTrue(result.cut.isEmpty())
        assertEquals(1, result.consideredCount)
    }

    @Test fun `cap of zero includes nothing but still reports what was considered`() {
        val g = graph(
            listOf(msgNode("root-1", "root-1"), msgNode("a", "root-1", "root-1")),
            listOf(reply("root-1", "a")),
        )
        val result = GraphAdjacentRecall.expand(listOf(seed("a")), g, cap = 0)
        assertTrue(result.included.isEmpty())
        assertEquals(1, result.cut.size)
        assertTrue(result.truncated)
    }

    @Test fun `a negative cap is rejected`() {
        val g = graph(emptyList(), emptyList())
        try {
            GraphAdjacentRecall.expand(emptyList(), g, cap = -1)
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    // ---- empty inputs -----------------------------------------------------------------------------

    @Test fun `no seeds recalls nothing`() {
        val g = graph(
            listOf(msgNode("root-1", "root-1"), msgNode("a", "root-1", "root-1")),
            listOf(reply("root-1", "a")),
        )
        val result = GraphAdjacentRecall.expand(emptyList(), g, cap = 10)
        assertTrue(result.included.isEmpty())
        assertTrue(result.cut.isEmpty())
        assertEquals(0, result.consideredCount)
    }

    @Test fun `a seed with no edges at all recalls nothing`() {
        val g = graph(listOf(msgNode("lonely", "lonely")), emptyList())
        val result = GraphAdjacentRecall.expand(listOf(seed("lonely")), g, cap = 10)
        assertTrue(result.included.isEmpty())
    }

    // ---- seedsFrom: real SearchHit -> Seed, honest term extraction ------------------------------

    @Test fun `seedsFrom reads matched terms from a real SearchHit's explanation`() {
        val hits = LexicalSearch.search(
            listOf(SearchDoc(id = "root-1", title = "Gradle cache miss", snippet = "", body = "the build cache went cold", lastActivityMillis = 0L, kind = SearchKind.TEXT)),
            query = "gradle cache",
            nowMillis = 0L,
            explain = true,
        )
        val seeds = GraphAdjacentRecall.seedsFrom(hits)
        assertEquals(listOf("root-1"), seeds.map { it.nodeId })
        assertEquals(setOf("gradle", "cache"), seeds.single().matchedQueryTerms.toSet())
    }

    @Test fun `seedsFrom honestly reports no matched terms when the hit carries no explanation`() {
        val hits = LexicalSearch.search(
            listOf(SearchDoc(id = "root-1", title = "Gradle cache miss", snippet = "", body = "", lastActivityMillis = 0L, kind = SearchKind.TEXT)),
            query = "gradle",
            nowMillis = 0L,
            explain = false,
        )
        val seeds = GraphAdjacentRecall.seedsFrom(hits)
        assertTrue(seeds.single().matchedQueryTerms.isEmpty())
    }

    // ---- a real ThreadGraphProjector snapshot: truthful citations end to end --------------------

    @Test fun `over a real projected graph, expansion cites the projector's own because text verbatim`() {
        val tree = MessageTree(
            listOf(
                MessageNode(id = "root-1", parentId = null, role = Role.USER, content = "x", createdAt = 100L),
                MessageNode(id = "msg-42", parentId = "root-1", role = Role.USER, content = "x", createdAt = 200L),
                MessageNode(
                    id = "root-2", parentId = null, role = Role.USER, content = "x", createdAt = 300L,
                    metadata = mapOf(
                        TreeFork.LINEAGE_KIND_KEY to TreeFork.LineageKind.FORK.name,
                        TreeFork.LINEAGE_SRC_ROOT_KEY to "root-1",
                        TreeFork.LINEAGE_SRC_NODE_KEY to "msg-42",
                    ),
                ),
            ),
        )
        val graph = ThreadGraphProjector.project(tree, emptyList(), emptyList(), at)

        // Hit on the fork root itself: recalls its real lineage source, msg-42.
        val result = GraphAdjacentRecall.expand(listOf(seed("root-2", listOf("auth"))), graph, cap = 10)
        assertEquals(listOf("msg-42"), result.included.map { it.nodeId })
        val citation = result.included.single().citations.single()
        assertEquals(GraphAdjacentRecall.Relation.LINEAGE_SOURCE, citation.relation)
        assertEquals(
            "fork/spawn lineage recorded at insert (TreeFork.LINEAGE_SRC_NODE_KEY metadata on the new root's own node)",
            citation.because,
        )
        assertEquals(listOf("auth"), citation.matchedQueryTerms)
    }

    @Test fun `over a real projected graph, a hit on a message recalls its parent and children with the projector's REPLY because`() {
        val tree = MessageTree(
            listOf(
                MessageNode(id = "root-1", parentId = null, role = Role.USER, content = "x", createdAt = 100L),
                MessageNode(id = "msg-42", parentId = "root-1", role = Role.USER, content = "x", createdAt = 200L),
                MessageNode(id = "msg-43", parentId = "msg-42", role = Role.USER, content = "x", createdAt = 300L),
            ),
        )
        val graph = ThreadGraphProjector.project(tree, emptyList(), emptyList(), at)

        val result = GraphAdjacentRecall.expand(listOf(seed("msg-42")), graph, cap = 10)
        val byId = result.included.associateBy { it.nodeId }
        assertEquals(GraphAdjacentRecall.Relation.PARENT, byId.getValue("root-1").citations.single().relation)
        assertEquals(GraphAdjacentRecall.Relation.CHILDREN, byId.getValue("msg-43").citations.single().relation)
        byId.values.forEach {
            assertEquals("parent-child reply recorded in the message tree", it.citations.single().because)
        }
    }

    @Test fun `over a real projected graph, a decision bookmark and its anchor recall each other`() {
        val tree = MessageTree(
            listOf(
                MessageNode(id = "root-1", parentId = null, role = Role.USER, content = "x", createdAt = 100L),
                MessageNode(id = "msg-42", parentId = "root-1", role = Role.USER, content = "x", createdAt = 200L),
            ),
        )
        val bookmark = MessageBookmark(
            id = "bm-1", ref = MessageRef("msg-42"),
            kind = BookmarkKind.DECISION, label = "Use SQLDelight", at = 250L,
        )
        val graph = ThreadGraphProjector.project(tree, emptyList(), emptyList(), at, bookmarks = listOf(bookmark))

        val result = GraphAdjacentRecall.expand(listOf(seed("bm-1")), graph, cap = 10)
        assertEquals(listOf("msg-42"), result.included.map { it.nodeId })
        assertEquals(GraphAdjacentRecall.Relation.DECISION_ANCHOR, result.included.single().citations.single().relation)
    }

}
