package dev.aarso.domain.loop

import dev.aarso.domain.bpmn.BpmnArchive
import dev.aarso.domain.bpmn.BpmnGraph
import dev.aarso.domain.bpmn.BpmnNodeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphEditTest {

    private fun built(): BpmnGraph {
        var g = BpmnGraph(id = "g", name = "built")
        g = GraphEdit.addNode(g, "start", BpmnNodeKind.START_EVENT)
        g = GraphEdit.addNode(g, "a", BpmnNodeKind.TASK, "Expert A")
        g = GraphEdit.addNode(g, "end", BpmnNodeKind.END_EVENT)
        g = GraphEdit.connect(g, "e1", "start", "a")
        g = GraphEdit.connect(g, "e2", "a", "end")
        return g
    }

    @Test fun `add, connect, and validate produce a runnable graph`() {
        val g = built()
        assertTrue(GraphEdit.isRunnable(g))
        assertEquals(3, g.nodes.size)
        assertEquals(2, g.edges.size)
    }

    @Test fun `removing a node drops its edges`() {
        val g = GraphEdit.removeNode(built(), "a")
        assertTrue(g.edges.isEmpty())
        assertFalse(GraphEdit.isRunnable(g)) // end no longer reachable
    }

    @Test fun `validation flags missing start, dangling tasks, and unreachable end`() {
        val noStart = BpmnGraph("g", nodes = listOf(), edges = listOf())
        assertTrue(GraphEdit.validate(noStart).any { it.contains("start event") })

        var g = BpmnGraph("g")
        g = GraphEdit.addNode(g, "start", BpmnNodeKind.START_EVENT)
        g = GraphEdit.addNode(g, "a", BpmnNodeKind.TASK, "lonely")
        g = GraphEdit.addNode(g, "end", BpmnNodeKind.END_EVENT)
        // 'a' has no edges; end unreachable
        val problems = GraphEdit.validate(g)
        assertTrue(problems.any { it.contains("no incoming") })
        assertTrue(problems.any { it.contains("no outgoing") })
        assertTrue(problems.any { it.contains("reachable") })
    }

    @Test fun `duplicate ids are rejected`() {
        val g = built()
        var threw = false
        try { GraphEdit.addNode(g, "a", BpmnNodeKind.TASK) } catch (e: IllegalArgumentException) { threw = true }
        assertTrue(threw)
    }

    @Test fun `a built graph survives BPMN round-trip`() {
        val g = built()
        val restored = BpmnArchive.read(BpmnArchive.write(g))
        assertEquals(g.nodes.map { it.id }.toSet(), restored.nodes.map { it.id }.toSet())
        assertEquals(g.edges.size, restored.edges.size)
        assertTrue(GraphEdit.isRunnable(restored))
    }

    @Test fun `the Bricks palette covers the core kinds`() {
        assertEquals("Expert", Bricks.labelFor(BpmnNodeKind.TASK))
        assertEquals("Start", Bricks.labelFor(BpmnNodeKind.START_EVENT))
        assertTrue(Bricks.palette.any { it.kind == BpmnNodeKind.END_EVENT })
    }
}
