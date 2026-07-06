package dev.aarso.domain.loop

import dev.aarso.domain.bpmn.Bounds
import dev.aarso.domain.bpmn.BpmnEdge
import dev.aarso.domain.bpmn.BpmnGraph
import dev.aarso.domain.bpmn.BpmnNode
import dev.aarso.domain.bpmn.BpmnNodeKind
import dev.aarso.domain.council.Generator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphRunnerTest {

    // A refine loop with a gateway: start → proposer → critic → choice →(approve) end / (refine) proposer.
    private fun refineGraph() = BpmnGraph(
        id = "g", name = "refine",
        nodes = listOf(
            BpmnNode("start", BpmnNodeKind.START_EVENT),
            BpmnNode("prop", BpmnNodeKind.TASK, "Proposer", Bounds(0.0, 0.0), mapOf("role" to "proposer", "model" to "qwen")),
            BpmnNode("crit", BpmnNodeKind.TASK, "Critic", Bounds(0.0, 0.0), mapOf("role" to "critic", "model" to "claude")),
            BpmnNode("gate", BpmnNodeKind.EXCLUSIVE_GATEWAY, "Choice"),
            BpmnNode("end", BpmnNodeKind.END_EVENT),
        ),
        edges = listOf(
            BpmnEdge("e1", "start", "prop"),
            BpmnEdge("e2", "prop", "crit"),
            BpmnEdge("e3", "crit", "gate"),
            BpmnEdge("e4", "gate", "end", name = "approve", condition = "approved"),
            BpmnEdge("e5", "gate", "prop", name = "refine", condition = "!approved"),
        ),
    )

    /** Critic approves on the 2nd pass; proposer always proposes. */
    private fun generators(): (BpmnNode) -> Generator {
        var criticCalls = 0
        return { node ->
            when (node.id) {
                "crit" -> Generator { _, _ -> if (++criticCalls >= 2) "APPROVE looks good" else "needs work" }
                else -> Generator { _, _ -> "proposal v${node.id}" }
            }
        }
    }

    @Test fun `a non-linear graph runs the refine loop and reaches the end`() = runTest {
        val result = GraphRunner(generators()).run(refineGraph(), objective = "tighten it")
        assertTrue(result.reachedEnd)
        assertEquals("reached end", result.stoppedBecause)
        // proposer, critic(needs work), proposer, critic(APPROVE) = 4 task steps
        assertEquals(4, result.steps.size)
        assertEquals(listOf("proposer", "critic", "proposer", "critic"), result.steps.map { it.role })
        assertTrue(result.finalOutput.startsWith("APPROVE"))
    }

    @Test fun `the step cap bounds a loop that never approves`() = runTest {
        val never: (BpmnNode) -> Generator = { node ->
            if (node.id == "crit") Generator { _, _ -> "still not good" } else Generator { _, _ -> "again" }
        }
        val result = GraphRunner(never).run(refineGraph(), objective = "x", hardCap = 5)
        assertFalse(result.reachedEnd)
        assertTrue(result.stoppedBecause.contains("step cap"))
        assertEquals(5, result.steps.size)
    }

    @Test fun `a missing start event is reported, not crashed`() = runTest {
        val g = BpmnGraph("g", nodes = listOf(BpmnNode("end", BpmnNodeKind.END_EVENT)))
        val result = GraphRunner({ Generator { _, _ -> "" } }).run(g, "x")
        assertFalse(result.reachedEnd)
        assertEquals("no start event", result.stoppedBecause)
    }

    @Test fun `the model behind each step is captured`() = runTest {
        val result = GraphRunner(generators()).run(refineGraph(), "x")
        assertEquals("qwen", result.steps.first { it.role == "proposer" }.model)
        assertEquals("claude", result.steps.first { it.role == "critic" }.model)
    }
}
