package dev.fonebrew.domain.thread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ThreadGraphExplainPathTest {

    private val at = Instant.parse("2026-08-12T00:00:00Z")

    private fun n(id: String) = ThreadGraphNode(id = id, kind = ThreadNodeKind.MESSAGE, rootId = "root-1", at = at)

    private fun extracted(from: String, to: String, kind: ThreadEdgeKind = ThreadEdgeKind.REPLY) =
        ThreadGraphEdge(from, to, kind, EdgeDerivation.EXTRACTED, "recorded fact")

    private fun inferred(from: String, to: String, kind: ThreadEdgeKind = ThreadEdgeKind.HOT_PATH) =
        ThreadGraphEdge(from, to, kind, EdgeDerivation.INFERRED, "shares a branch point (root-1) recorded with 2 continuations")

    /** No `derivation`/`because` at all — a legitimately-old pre-1.2.0 edge, still a real
     *  recorded fact (see [ThreadGraphEdge]'s own KDoc). */
    private fun undated(from: String, to: String) = ThreadGraphEdge(from, to, ThreadEdgeKind.REPLY)

    private fun graph(nodeIds: List<String>, edges: List<ThreadGraphEdge>) =
        ThreadGraph(generatedAtUtc = at, nodes = nodeIds.map(::n), edges = edges)

    @Test fun `the same node id both ways is the trivial empty path`() {
        val g = graph(listOf("a"), emptyList())
        val result = ThreadGraphExplainPath.explain(g, "a", "a")
        assertTrue(result.found)
        assertEquals(emptyList<ThreadGraphEdge>(), result.extractedPath)
        assertNull(result.inferredAlternative)
    }

    @Test fun `a direct recorded edge is the whole path`() {
        val g = graph(listOf("a", "b"), listOf(extracted("a", "b")))
        val result = ThreadGraphExplainPath.explain(g, "a", "b")
        assertEquals(listOf(extracted("a", "b")), result.extractedPath)
        assertNull(result.inferredAlternative)
    }

    @Test fun `a multi-hop recorded chain is returned in order from-to-to`() {
        val g = graph(listOf("a", "b", "c"), listOf(extracted("a", "b"), extracted("b", "c")))
        val result = ThreadGraphExplainPath.explain(g, "a", "c")
        assertEquals(listOf(extracted("a", "b"), extracted("b", "c")), result.extractedPath)
    }

    @Test fun `a path is found regardless of which direction the recorded edge points`() {
        // The edge is recorded a->b, but we ask for the path from b to a — connectivity is
        // undirected for search purposes, and the edge itself is returned exactly as recorded,
        // never flipped to read "b->a".
        val g = graph(listOf("a", "b"), listOf(extracted("a", "b")))
        val result = ThreadGraphExplainPath.explain(g, "b", "a")
        assertEquals(listOf(extracted("a", "b")), result.extractedPath)
    }

    @Test fun `the shortest recorded path is preferred over a longer one`() {
        val g = graph(
            listOf("a", "b", "c", "d"),
            listOf(extracted("a", "b"), extracted("a", "c"), extracted("c", "d"), extracted("d", "b")),
        )
        // a->b is a direct 1-hop path; a->c->d->b is a 3-hop alternative that must lose.
        val result = ThreadGraphExplainPath.explain(g, "a", "b")
        assertEquals(listOf(extracted("a", "b")), result.extractedPath)
    }

    @Test fun `a derivation-absent (pre-1_2_0) edge is still usable as a recorded path`() {
        val g = graph(listOf("a", "b"), listOf(undated("a", "b")))
        val result = ThreadGraphExplainPath.explain(g, "a", "b")
        assertEquals(listOf(undated("a", "b")), result.extractedPath)
        assertNull(result.inferredAlternative)
    }

    @Test fun `two disconnected nodes have no path and no fabricated alternative`() {
        val g = graph(listOf("a", "b"), emptyList())
        val result = ThreadGraphExplainPath.explain(g, "a", "b")
        assertFalse(result.found)
        assertNull(result.extractedPath)
        assertNull(result.inferredAlternative)
    }

    @Test fun `a node id absent from the graph entirely has no path`() {
        val g = graph(listOf("a"), emptyList())
        val result = ThreadGraphExplainPath.explain(g, "a", "does-not-exist")
        assertNull(result.extractedPath)
        assertNull(result.inferredAlternative)
    }

    @Test fun `an INFERRED-only connection is offered as a separately-labelled alternative, never silently returned as the recorded path`() {
        val g = graph(listOf("a", "b"), listOf(inferred("a", "b")))
        val result = ThreadGraphExplainPath.explain(g, "a", "b")
        assertFalse("an INFERRED-only connection must not count as a recorded path", result.found)
        assertNull(result.extractedPath)
        assertEquals(listOf(inferred("a", "b")), result.inferredAlternative)
    }

    @Test fun `never routes through an INFERRED shortcut even when it is shorter than the real recorded path`() {
        // a-b is a 1-hop INFERRED shortcut; a-x-b is a 2-hop but fully recorded path. The
        // recorded, longer path must win — the INFERRED shortcut is never silently preferred for
        // being shorter, and since a recorded path exists, no alternative is offered at all.
        val g = graph(
            listOf("a", "b", "x"),
            listOf(inferred("a", "b"), extracted("a", "x"), extracted("x", "b")),
        )
        val result = ThreadGraphExplainPath.explain(g, "a", "b")
        assertEquals(listOf(extracted("a", "x"), extracted("x", "b")), result.extractedPath)
        assertNull(result.inferredAlternative)
    }

    @Test fun `the inferred alternative path is also its own shortest path, not just any path`() {
        val g = graph(
            listOf("a", "b", "x", "y"),
            listOf(
                inferred("a", "b"), // 1-hop alternative
                inferred("a", "x"), inferred("x", "y"), inferred("y", "b"), // 3-hop alternative, must lose
            ),
        )
        val result = ThreadGraphExplainPath.explain(g, "a", "b")
        assertNull(result.extractedPath)
        assertEquals(listOf(inferred("a", "b")), result.inferredAlternative)
    }

    @Test fun `a HOT_PATH sibling edge alone connects two message nodes with no other recorded relationship`() {
        val g = graph(listOf("sibling-1", "sibling-2"), listOf(inferred("sibling-1", "sibling-2")))
        val result = ThreadGraphExplainPath.explain(g, "sibling-1", "sibling-2")
        assertFalse(result.found)
        assertEquals(listOf(inferred("sibling-1", "sibling-2")), result.inferredAlternative)
    }
}
