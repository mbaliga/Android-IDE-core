package dev.aarso.domain.thread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ThreadDeltasTest {

    private val at = Instant.parse("2026-08-12T00:00:00Z")

    private fun n(id: String, kind: ThreadNodeKind = ThreadNodeKind.MESSAGE, parentId: String? = null) =
        ThreadGraphNode(id = id, kind = kind, rootId = "root-1", parentId = parentId, at = at)

    private fun graph(nodes: List<ThreadGraphNode>, edges: List<ThreadGraphEdge>) =
        ThreadGraph(generatedAtUtc = at, nodes = nodes, edges = edges)

    @Test fun `identical snapshots diff to an empty Diff`() {
        val g = graph(listOf(n("a"), n("b")), listOf(ThreadGraphEdge("a", "b", ThreadEdgeKind.REPLY)))
        val diff = ThreadDeltas.diff(g, g)
        assertTrue(diff.isEmpty)
        assertTrue(diff.addedNodes.isEmpty())
        assertTrue(diff.removedNodes.isEmpty())
        assertTrue(diff.addedEdges.isEmpty())
        assertTrue(diff.removedEdges.isEmpty())
        assertTrue(diff.nodeDegreeChanges.isEmpty())
    }

    @Test fun `a new leaf message is an added node plus an added REPLY edge and a degree change on its parent`() {
        val before = graph(listOf(n("a")), emptyList())
        val after = graph(listOf(n("a"), n("b", parentId = "a")), listOf(ThreadGraphEdge("a", "b", ThreadEdgeKind.REPLY)))
        val diff = ThreadDeltas.diff(before, after)

        assertEquals(listOf("b"), diff.addedNodes.map { it.id })
        assertTrue(diff.removedNodes.isEmpty())
        assertEquals(listOf(ThreadGraphEdge("a", "b", ThreadEdgeKind.REPLY)), diff.addedEdges)

        // "b" is a newly added node, so it must NOT also appear in nodeDegreeChanges (that list is
        // only for nodes present in BOTH snapshots).
        assertTrue(diff.nodeDegreeChanges.none { it.nodeId == "b" })

        val aChange = diff.nodeDegreeChanges.single { it.nodeId == "a" }
        assertEquals(0, aChange.outDegreeBefore)
        assertEquals(1, aChange.outDegreeAfter)
        assertEquals(0, aChange.inDegreeBefore)
        assertEquals(0, aChange.inDegreeAfter)
        assertEquals(1, aChange.outDegreeDelta)
        assertTrue(aChange.changed)
    }

    @Test fun `a removed node and edge show up as removedNodes and removedEdges`() {
        val before = graph(listOf(n("a"), n("b", parentId = "a")), listOf(ThreadGraphEdge("a", "b", ThreadEdgeKind.REPLY)))
        val after = graph(listOf(n("a")), emptyList())
        val diff = ThreadDeltas.diff(before, after)
        assertEquals(listOf("b"), diff.removedNodes.map { it.id })
        assertEquals(listOf(ThreadGraphEdge("a", "b", ThreadEdgeKind.REPLY)), diff.removedEdges)
    }

    @Test fun `direction is tracked separately — an in-degree-only change never reports as an out-degree change`() {
        val before = graph(listOf(n("a"), n("b")), emptyList())
        val after = graph(listOf(n("a"), n("b")), listOf(ThreadGraphEdge("a", "b", ThreadEdgeKind.REPLY)))
        val diff = ThreadDeltas.diff(before, after)

        val bChange = diff.nodeDegreeChanges.single { it.nodeId == "b" }
        assertEquals(1, bChange.inDegreeDelta)
        assertEquals(0, bChange.outDegreeDelta)

        val aChange = diff.nodeDegreeChanges.single { it.nodeId == "a" }
        assertEquals(0, aChange.inDegreeDelta)
        assertEquals(1, aChange.outDegreeDelta)
    }

    @Test fun `a node with no degree change is absent from nodeDegreeChanges even though it exists in both`() {
        val before = graph(listOf(n("a"), n("untouched")), emptyList())
        val after = graph(listOf(n("a"), n("untouched"), n("b", parentId = "a")), listOf(ThreadGraphEdge("a", "b", ThreadEdgeKind.REPLY)))
        val diff = ThreadDeltas.diff(before, after)
        assertTrue(diff.nodeDegreeChanges.none { it.nodeId == "untouched" })
    }
}
