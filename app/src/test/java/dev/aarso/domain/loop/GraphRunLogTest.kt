package dev.aarso.domain.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphRunLogTest {

    private fun result() = GraphRunResult(
        steps = listOf(
            GraphStep(0, "prop", "proposer", "qwen", "draft"),
            GraphStep(1, "crit", "critic", "claude", "APPROVE"),
        ),
        stoppedBecause = "reached end",
        reachedEnd = true,
    )

    @Test fun `a run maps to an objective root plus one node per step, chained`() {
        var i = 0
        val nodes = GraphRunLog.toNodes(
            objective = "make it airtight",
            result = result(),
            loopRunId = "run-1",
            loopId = "loop-7",
            now = 1000L,
            idGen = { "n${i++}" },
        )
        assertEquals(3, nodes.size)
        // root is parentless and tagged objective
        assertNull(nodes[0].parentId)
        assertEquals("objective", nodes[0].metadata[GraphRunLog.TAG_ROLE])
        assertEquals("reached end", nodes[0].metadata[GraphRunLog.TAG_STOPPED])
        // each step chains under the previous (a sub-tree)
        assertEquals(nodes[0].id, nodes[1].parentId)
        assertEquals(nodes[1].id, nodes[2].parentId)
        // step metadata is preserved
        assertEquals("proposer", nodes[1].metadata[GraphRunLog.TAG_ROLE])
        assertEquals("prop", nodes[1].metadata[GraphRunLog.TAG_BPMN_NODE])
        assertEquals("0", nodes[1].metadata[GraphRunLog.TAG_STEP])
        assertEquals("qwen", nodes[1].modelId)
        // all carry the run id for Tree clustering
        assertTrue(nodes.all { it.metadata[GraphRunLog.TAG_RUN] == "run-1" })
        assertTrue(nodes.drop(1).all { it.metadata[GraphRunLog.TAG_LOOP] == "loop-7" })
    }
}
