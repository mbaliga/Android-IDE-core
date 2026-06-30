package dev.aarso.domain.loop

import dev.aarso.domain.Role
import dev.aarso.domain.council.Expert
import dev.aarso.domain.council.Iteration
import dev.aarso.domain.council.RunResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunLogTest {

    private val proposer = Expert("proposer", "draft", model = "Qwen2.5-7B")
    private val critic = Expert("critic", "critique", model = "Claude")
    private val result = RunResult(
        iterations = listOf(
            Iteration(1, "attempt 1", "needs work", approved = false),
            Iteration(2, "attempt 2", "APPROVE good", approved = true),
        ),
        finalProposal = "attempt 2",
        stoppedBecause = "critic approved at iteration 2",
    )

    private fun seqIds(): () -> String {
        var i = 0
        return { "n${i++}" }
    }

    private fun build() = RunLog.toNodes(
        objective = "ship it", proposer = proposer, critic = critic, result = result,
        loopRunId = "run1", loopId = "loop1", now = 100, idGen = seqIds(),
    )

    @Test fun `builds a detached sub-tree rooted at the objective`() {
        val nodes = build()
        assertEquals(5, nodes.size) // objective + 2×(proposal, critique)
        val root = nodes.first()
        assertNull(root.parentId) // detached root
        assertEquals(Role.USER, root.role)
        assertEquals("ship it", root.content)
        assertEquals("objective", root.metadata[RunLog.TAG_ROLE])
        assertEquals("critic approved at iteration 2", root.metadata[RunLog.TAG_STOPPED])
    }

    @Test fun `nodes form a linear chain parent-before-child`() {
        val nodes = build()
        // each node's parent appears earlier in the list (safe to insert in order)
        val seen = HashSet<String>()
        for (n in nodes) {
            if (n.parentId != null) assertTrue("parent of ${n.id} precedes it", n.parentId in seen)
            seen += n.id
        }
        // chain: root -> p1 -> c1 -> p2 -> c2
        assertEquals(nodes[0].id, nodes[1].parentId)
        assertEquals(nodes[1].id, nodes[2].parentId)
        assertEquals(nodes[2].id, nodes[3].parentId)
        assertEquals(nodes[3].id, nodes[4].parentId)
    }

    @Test fun `proposer and critic nodes carry their model and role`() {
        val nodes = build()
        val p1 = nodes[1]
        assertEquals("Qwen2.5-7B", p1.modelId)
        assertEquals("proposal", p1.metadata[RunLog.TAG_ROLE])
        assertEquals("1", p1.metadata[RunLog.TAG_ITER])
        val c2 = nodes[4]
        assertEquals("Claude", c2.modelId)
        assertEquals("critique", c2.metadata[RunLog.TAG_ROLE])
        assertEquals("true", c2.metadata[RunLog.TAG_APPROVED])
    }

    @Test fun `run nodes are identifiable and all tagged with the run id`() {
        val nodes = build()
        assertTrue(nodes.all { RunLog.isRunNode(it, "run1") })
        assertFalse(RunLog.isRunNode(nodes[0].copy(metadata = emptyMap()), "run1"))
    }

    @Test fun `ordered returns objective first then proposal-critique per iteration`() {
        val shuffled = build().reversed()
        val order = RunLog.ordered(shuffled).map { it.metadata[RunLog.TAG_ROLE] to it.metadata[RunLog.TAG_ITER] }
        assertEquals(
            listOf(
                "objective" to null,
                "proposal" to "1", "critique" to "1",
                "proposal" to "2", "critique" to "2",
            ),
            order,
        )
    }
}
