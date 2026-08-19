package dev.fonebrew.domain.thread

import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.Role
import dev.fonebrew.domain.tree.MessageTree
import dev.fonebrew.domain.tree.TreeFork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ThreadGraphProjectorTest {

    private fun node(
        id: String,
        parentId: String?,
        createdAt: Long,
        metadata: Map<String, String> = emptyMap(),
    ) = MessageNode(id = id, parentId = parentId, role = Role.USER, content = "x", createdAt = createdAt, metadata = metadata)

    // ---- plain reply chain ------------------------------------------------------------------

    @Test fun `a plain reply chain becomes MESSAGE nodes and REPLY edges`() {
        val tree = MessageTree(listOf(
            node("root-1", null, 100L),
            node("msg-42", "root-1", 200L),
        ))
        val graph = ThreadGraphProjector.project(tree, emptyList(), emptyList(), Instant.parse("2026-08-12T00:00:00Z"))

        assertEquals(2, graph.nodes.size)
        val root = graph.nodes.single { it.id == "root-1" }
        assertEquals(ThreadNodeKind.MESSAGE, root.kind)
        assertNull(root.parentId)
        assertEquals("root-1", root.rootId)

        val child = graph.nodes.single { it.id == "msg-42" }
        assertEquals(ThreadNodeKind.MESSAGE, child.kind)
        assertEquals("root-1", child.parentId)
        assertEquals("root-1", child.rootId)

        assertEquals(listOf(ThreadGraphEdge("root-1", "msg-42", ThreadEdgeKind.REPLY)), graph.edges)
    }

    @Test fun `rootId is computed correctly for a deep branch, not just the immediate parent`() {
        val tree = MessageTree(listOf(
            node("root-1", null, 100L),
            node("a", "root-1", 110L),
            node("b", "a", 120L),
            node("c", "b", 130L),
        ))
        val graph = ThreadGraphProjector.project(tree, emptyList(), emptyList(), Instant.parse("2026-08-12T00:00:00Z"))
        assertEquals("root-1", graph.nodes.single { it.id == "c" }.rootId)
    }

    // ---- fork/spawn roots (matches fixtures/thread/valid/thread-graph-valid.json's shape) -----

    @Test fun `a Fork root becomes FORK_ROOT with a lineage parentId and a FORK edge from the source node`() {
        val tree = MessageTree(listOf(
            node("root-1", null, 100L),
            node("msg-42", "root-1", 200L),
            node(
                "root-2", null, 300L,
                metadata = mapOf(
                    TreeFork.LINEAGE_KIND_KEY to TreeFork.LineageKind.FORK.name,
                    TreeFork.LINEAGE_SRC_ROOT_KEY to "root-1",
                    TreeFork.LINEAGE_SRC_NODE_KEY to "msg-42",
                    TreeFork.LINEAGE_AT_KEY to "300",
                ),
            ),
        ))
        val graph = ThreadGraphProjector.project(tree, emptyList(), emptyList(), Instant.parse("2026-08-12T00:00:00Z"))

        val forkRoot = graph.nodes.single { it.id == "root-2" }
        assertEquals(ThreadNodeKind.FORK_ROOT, forkRoot.kind)
        assertEquals("msg-42", forkRoot.parentId)
        assertEquals("root-2", forkRoot.rootId)

        assertTrue(ThreadGraphEdge("msg-42", "root-2", ThreadEdgeKind.FORK) in graph.edges)
        // A fork root has no tree parent, so it must never also emit a REPLY edge.
        assertTrue(graph.edges.none { it.to == "root-2" && it.kind == ThreadEdgeKind.REPLY })
    }

    @Test fun `a Spawn root becomes SPAWN_ROOT with a SPAWN edge`() {
        val tree = MessageTree(listOf(
            node("root-1", null, 100L),
            node(
                "root-2", null, 300L,
                metadata = mapOf(
                    TreeFork.LINEAGE_KIND_KEY to TreeFork.LineageKind.SPAWN.name,
                    TreeFork.LINEAGE_SRC_ROOT_KEY to "root-1",
                    TreeFork.LINEAGE_SRC_NODE_KEY to "root-1",
                ),
            ),
        ))
        val graph = ThreadGraphProjector.project(tree, emptyList(), emptyList(), Instant.parse("2026-08-12T00:00:00Z"))
        assertEquals(ThreadNodeKind.SPAWN_ROOT, graph.nodes.single { it.id == "root-2" }.kind)
        assertTrue(ThreadGraphEdge("root-1", "root-2", ThreadEdgeKind.SPAWN) in graph.edges)
    }

    @Test fun `an ordinary root with no lineage metadata stays a plain MESSAGE`() {
        val tree = MessageTree(listOf(node("root-1", null, 100L)))
        val graph = ThreadGraphProjector.project(tree, emptyList(), emptyList(), Instant.parse("2026-08-12T00:00:00Z"))
        assertEquals(ThreadNodeKind.MESSAGE, graph.nodes.single().kind)
    }

    // ---- markers -----------------------------------------------------------------------------

    @Test fun `a CHAPTER marker becomes a MARKER node with a MARKER_ANCHOR edge`() {
        val tree = MessageTree(listOf(node("root-1", null, 100L), node("msg-42", "root-1", 200L)))
        val marker = ThreadMarker(
            id = "mk-1", rootId = "root-1", anchorMsgId = "msg-42", kind = ThreadMarkerKind.CHAPTER,
            label = "Auth flow rewrite", at = 250L, source = ThreadMarkerSource.USER,
        )
        val graph = ThreadGraphProjector.project(tree, listOf(marker), emptyList(), Instant.parse("2026-08-12T00:00:00Z"))

        val markerNode = graph.nodes.single { it.id == "mk-1" }
        assertEquals(ThreadNodeKind.MARKER, markerNode.kind)
        assertEquals("msg-42", markerNode.parentId)
        assertEquals("Auth flow rewrite", markerNode.label)
        assertTrue(ThreadGraphEdge("mk-1", "msg-42", ThreadEdgeKind.MARKER_ANCHOR) in graph.edges)
    }

    @Test fun `a SESSION_START marker with no anchor becomes a node with no MARKER_ANCHOR edge`() {
        val tree = MessageTree(listOf(node("root-1", null, 100L)))
        val marker = ThreadMarker(
            id = "mk-1", rootId = "root-1", anchorMsgId = null, kind = ThreadMarkerKind.SESSION_START,
            at = 250L, source = ThreadMarkerSource.USER,
        )
        val graph = ThreadGraphProjector.project(tree, listOf(marker), emptyList(), Instant.parse("2026-08-12T00:00:00Z"))
        assertNull(graph.nodes.single { it.id == "mk-1" }.parentId)
        assertTrue(graph.edges.none { it.kind == ThreadEdgeKind.MARKER_ANCHOR })
    }

    @Test fun `a LINEAGE_SRC marker adds a LINEAGE edge to its decoded srcNodeId`() {
        val tree = MessageTree(listOf(node("root-1", null, 100L), node("root-2", null, 300L)))
        val marker = ThreadMarkerStoreLikeFixtures.lineageSrc(newRootId = "root-2", srcRootId = "root-1", srcNodeId = "root-1")
        val graph = ThreadGraphProjector.project(tree, listOf(marker), emptyList(), Instant.parse("2026-08-12T00:00:00Z"))
        assertTrue(ThreadGraphEdge(marker.id, "root-1", ThreadEdgeKind.LINEAGE) in graph.edges)
    }

    @Test fun `a marker anchored to an unknown message is kept as a node but drops the dangling edge`() {
        val tree = MessageTree(listOf(node("root-1", null, 100L)))
        val marker = ThreadMarker(
            id = "mk-1", rootId = "root-1", anchorMsgId = "does-not-exist", kind = ThreadMarkerKind.CHAPTER,
            label = "x", at = 250L, source = ThreadMarkerSource.USER,
        )
        val graph = ThreadGraphProjector.project(tree, listOf(marker), emptyList(), Instant.parse("2026-08-12T00:00:00Z"))
        assertTrue(graph.nodes.any { it.id == "mk-1" })
        assertTrue(graph.edges.none { it.kind == ThreadEdgeKind.MARKER_ANCHOR })
    }

    // ---- delegations ---------------------------------------------------------------------------

    @Test fun `a DelegationEvent with a rootId becomes a DELEGATION node with a DELEGATION_ANCHOR edge`() {
        val tree = MessageTree(listOf(node("root-1", null, 100L), node("msg-42", "root-1", 200L)))
        val delegation = DelegationEvent(
            id = "dg-1", at = 260L, kind = DelegationKind.MODEL_PICK_BRANCH,
            rootId = "root-1", anchorMsgId = "msg-42", chosenRef = "msg-51",
        )
        val graph = ThreadGraphProjector.project(tree, emptyList(), listOf(delegation), Instant.parse("2026-08-12T00:00:00Z"))

        val delegationNode = graph.nodes.single { it.id == "dg-1" }
        assertEquals(ThreadNodeKind.DELEGATION, delegationNode.kind)
        assertEquals("root-1", delegationNode.rootId)
        assertEquals("MODEL_PICK_BRANCH", delegationNode.label)
        assertTrue(ThreadGraphEdge("dg-1", "msg-42", ThreadEdgeKind.DELEGATION_ANCHOR) in graph.edges)
    }

    @Test fun `a DelegationEvent with no rootId is honestly omitted, not fabricated`() {
        val tree = MessageTree(listOf(node("root-1", null, 100L)))
        val delegation = DelegationEvent(id = "dg-1", at = 260L, kind = DelegationKind.AUTO_DEFAULT, rootId = null)
        val graph = ThreadGraphProjector.project(tree, emptyList(), listOf(delegation), Instant.parse("2026-08-12T00:00:00Z"))
        assertTrue(graph.nodes.none { it.id == "dg-1" })
    }

    // ---- structural invariants -----------------------------------------------------------------

    @Test fun `node ids are unique within a projected ThreadGraph`() {
        // Per schemas/thread/thread-graph.schema.json's own note: "a future ThreadGraphProjector
        // test MUST assert" node-id uniqueness, since the schema itself can't enforce it.
        val tree = MessageTree(listOf(
            node("root-1", null, 100L),
            node("msg-42", "root-1", 200L),
            node(
                "root-2", null, 300L,
                metadata = mapOf(
                    TreeFork.LINEAGE_KIND_KEY to TreeFork.LineageKind.FORK.name,
                    TreeFork.LINEAGE_SRC_ROOT_KEY to "root-1",
                    TreeFork.LINEAGE_SRC_NODE_KEY to "msg-42",
                ),
            ),
        ))
        val markers = listOf(
            ThreadMarker(id = "mk-1", rootId = "root-1", anchorMsgId = "msg-42", kind = ThreadMarkerKind.CHAPTER, label = "x", at = 1L, source = ThreadMarkerSource.USER),
        )
        val delegations = listOf(
            DelegationEvent(id = "dg-1", at = 1L, kind = DelegationKind.AUTO_DEFAULT, rootId = "root-1", anchorMsgId = "msg-42"),
        )
        val graph = ThreadGraphProjector.project(tree, markers, delegations, Instant.parse("2026-08-12T00:00:00Z"))
        assertEquals(graph.nodes.size, graph.nodes.map { it.id }.toSet().size)
    }

    @Test fun `an empty tree with no markers or delegations projects to an empty graph`() {
        val graph = ThreadGraphProjector.project(MessageTree(emptyList()), emptyList(), emptyList(), Instant.parse("2026-08-12T00:00:00Z"))
        assertTrue(graph.nodes.isEmpty())
        assertTrue(graph.edges.isEmpty())
    }

    @Test fun `the projected graph round-trips through ThreadCodec`() {
        val tree = MessageTree(listOf(node("root-1", null, 100L), node("msg-42", "root-1", 200L)))
        val graph = ThreadGraphProjector.project(tree, emptyList(), emptyList(), Instant.parse("2026-08-12T00:00:00Z"))
        val decoded = ThreadCodec.decodeThreadGraph(ThreadCodec.encodeThreadGraph(graph))
        assertEquals(graph, decoded)
    }
}

/** Builds the exact [ThreadMarker] shape [dev.fonebrew.data.ThreadMarkerStore.markLineageSource]
 *  writes, without depending on the `data` module's Room-backed store from this pure-domain test. */
private object ThreadMarkerStoreLikeFixtures {
    fun lineageSrc(newRootId: String, srcRootId: String, srcNodeId: String): ThreadMarker {
        val payload = org.json.JSONObject()
            .put("srcRootId", srcRootId)
            .put("srcNodeId", srcNodeId)
            .put("lineageKind", TreeFork.LineageKind.FORK.name)
            .toString()
        return ThreadMarker(
            id = "mk-lineage-1", rootId = newRootId, anchorMsgId = null,
            kind = ThreadMarkerKind.LINEAGE_SRC, at = 1L, source = ThreadMarkerSource.SYSTEM,
            payloadJson = payload,
        )
    }
}
