package dev.aarso.domain.thread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ThreadMapLayoutTest {

    private val now = Instant.parse("2026-08-12T00:00:00Z")

    private fun msg(id: String, rootId: String, parentId: String?, atMillis: Long) =
        ThreadGraphNode(id = id, kind = ThreadNodeKind.MESSAGE, rootId = rootId, parentId = parentId, at = Instant.ofEpochMilli(atMillis))

    @Test fun `an empty graph produces an empty layout`() {
        val layout = ThreadMapLayout.compute(ThreadGraph(generatedAtUtc = now))
        assertTrue(layout.positions.isEmpty())
        assertEquals(0, layout.columnCount)
        assertEquals(0, layout.rowCount)
    }

    @Test fun `a single linear conversation lands in one column, one row per turn`() {
        val graph = ThreadGraph(
            generatedAtUtc = now,
            nodes = listOf(msg("root-1", "root-1", null, 100L), msg("a", "root-1", "root-1", 200L), msg("b", "root-1", "a", 300L)),
            edges = listOf(
                ThreadGraphEdge("root-1", "a", ThreadEdgeKind.REPLY),
                ThreadGraphEdge("a", "b", ThreadEdgeKind.REPLY),
            ),
        )
        val layout = ThreadMapLayout.compute(graph)
        assertEquals(1, layout.columnCount)
        assertEquals(3, layout.rowCount)
        assertEquals(ThreadMapLayout.NodePosition(0, 0), layout.positions.getValue("root-1"))
        assertEquals(ThreadMapLayout.NodePosition(0, 1), layout.positions.getValue("a"))
        assertEquals(ThreadMapLayout.NodePosition(0, 2), layout.positions.getValue("b"))
    }

    @Test fun `two sibling children of the same parent share a row (branching, not a straight line)`() {
        val graph = ThreadGraph(
            generatedAtUtc = now,
            nodes = listOf(msg("root-1", "root-1", null, 100L), msg("a", "root-1", "root-1", 200L), msg("b", "root-1", "root-1", 210L)),
            edges = listOf(
                ThreadGraphEdge("root-1", "a", ThreadEdgeKind.REPLY),
                ThreadGraphEdge("root-1", "b", ThreadEdgeKind.REPLY),
            ),
        )
        val layout = ThreadMapLayout.compute(graph)
        assertEquals(1, layout.positions.getValue("a").row)
        assertEquals(1, layout.positions.getValue("b").row)
        assertEquals(2, layout.rowCount)
    }

    @Test fun `a Fork root gets its own column placed right after its source conversation's column`() {
        val forkRoot = ThreadGraphNode(id = "root-2", kind = ThreadNodeKind.FORK_ROOT, rootId = "root-2", parentId = "msg-42", at = Instant.ofEpochMilli(300L))
        val otherOrigin = ThreadGraphNode(id = "root-9", kind = ThreadNodeKind.MESSAGE, rootId = "root-9", parentId = null, at = Instant.ofEpochMilli(50L))
        val graph = ThreadGraph(
            generatedAtUtc = now,
            nodes = listOf(msg("root-1", "root-1", null, 100L), msg("msg-42", "root-1", "root-1", 200L), forkRoot, otherOrigin),
            edges = listOf(
                ThreadGraphEdge("root-1", "msg-42", ThreadEdgeKind.REPLY),
                ThreadGraphEdge("msg-42", "root-2", ThreadEdgeKind.FORK),
            ),
        )
        val layout = ThreadMapLayout.compute(graph)
        // root-9 has no lineage parent, so it's a second top-level chain; root-1's fork child
        // (root-2) must be adjacent to root-1's own column, not scattered after every top root.
        val col1 = layout.positions.getValue("root-1").column
        val col2 = layout.positions.getValue("root-2").column
        assertEquals(col1 + 1, col2)
        assertEquals(3, layout.columnCount)
    }

    @Test fun `a FORK edge never contributes to row depth (columns, not rows, carry lineage)`() {
        val forkRoot = ThreadGraphNode(id = "root-2", kind = ThreadNodeKind.FORK_ROOT, rootId = "root-2", parentId = "msg-42", at = Instant.ofEpochMilli(300L))
        val graph = ThreadGraph(
            generatedAtUtc = now,
            nodes = listOf(msg("root-1", "root-1", null, 100L), msg("msg-42", "root-1", "root-1", 200L), forkRoot),
            edges = listOf(
                ThreadGraphEdge("root-1", "msg-42", ThreadEdgeKind.REPLY),
                ThreadGraphEdge("msg-42", "root-2", ThreadEdgeKind.FORK),
            ),
        )
        val layout = ThreadMapLayout.compute(graph)
        // The fork root starts its own column at row 0, regardless of how deep its source sits.
        assertEquals(0, layout.positions.getValue("root-2").row)
    }

    @Test fun `a marker takes its anchor's row, not a row of its own`() {
        val marker = ThreadGraphNode(id = "mk-1", kind = ThreadNodeKind.MARKER, rootId = "root-1", parentId = "a", at = Instant.ofEpochMilli(205L))
        val graph = ThreadGraph(
            generatedAtUtc = now,
            nodes = listOf(msg("root-1", "root-1", null, 100L), msg("a", "root-1", "root-1", 200L), marker),
            edges = listOf(ThreadGraphEdge("root-1", "a", ThreadEdgeKind.REPLY), ThreadGraphEdge("mk-1", "a", ThreadEdgeKind.MARKER_ANCHOR)),
        )
        val layout = ThreadMapLayout.compute(graph)
        assertEquals(layout.positions.getValue("a").row, layout.positions.getValue("mk-1").row)
        assertEquals(layout.positions.getValue("a").column, layout.positions.getValue("mk-1").column)
    }

    @Test fun `an unanchored marker takes row 0`() {
        val marker = ThreadGraphNode(id = "mk-1", kind = ThreadNodeKind.MARKER, rootId = "root-1", parentId = null, at = Instant.ofEpochMilli(105L))
        val graph = ThreadGraph(generatedAtUtc = now, nodes = listOf(msg("root-1", "root-1", null, 100L), marker))
        val layout = ThreadMapLayout.compute(graph)
        assertEquals(0, layout.positions.getValue("mk-1").row)
    }

    @Test fun `layout is deterministic across repeated calls on the same graph`() {
        val graph = ThreadGraph(
            generatedAtUtc = now,
            nodes = listOf(msg("root-1", "root-1", null, 100L), msg("a", "root-1", "root-1", 200L), msg("b", "root-1", "root-1", 200L)),
            edges = listOf(ThreadGraphEdge("root-1", "a", ThreadEdgeKind.REPLY), ThreadGraphEdge("root-1", "b", ThreadEdgeKind.REPLY)),
        )
        val first = ThreadMapLayout.compute(graph)
        val second = ThreadMapLayout.compute(graph)
        assertEquals(first, second)
    }

    @Test fun `every node in the graph gets a position, none dropped or duplicated`() {
        val forkRoot = ThreadGraphNode(id = "root-2", kind = ThreadNodeKind.FORK_ROOT, rootId = "root-2", parentId = "msg-42", at = Instant.ofEpochMilli(300L))
        val delegation = ThreadGraphNode(id = "dg-1", kind = ThreadNodeKind.DELEGATION, rootId = "root-1", parentId = "msg-42", at = Instant.ofEpochMilli(210L))
        val graph = ThreadGraph(
            generatedAtUtc = now,
            nodes = listOf(msg("root-1", "root-1", null, 100L), msg("msg-42", "root-1", "root-1", 200L), forkRoot, delegation),
            edges = listOf(ThreadGraphEdge("root-1", "msg-42", ThreadEdgeKind.REPLY), ThreadGraphEdge("msg-42", "root-2", ThreadEdgeKind.FORK)),
        )
        val layout = ThreadMapLayout.compute(graph)
        assertEquals(graph.nodes.map { it.id }.toSet(), layout.positions.keys)
    }
}
