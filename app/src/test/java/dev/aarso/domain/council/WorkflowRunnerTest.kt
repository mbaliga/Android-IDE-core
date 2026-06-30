package dev.aarso.domain.council

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowRunnerTest {

    private val proposer = Expert("proposer", "You draft.")
    private val critic = Expert("critic", "You critique.")

    @Test fun `stops when the critic approves`() = runTest {
        // Critic approves on the 3rd look; proposer echoes the iteration number.
        var look = 0
        val gen = Generator { system, _ ->
            if (system.contains("draft")) "attempt ${look + 1}"
            else { look++; if (look >= 3) "APPROVE good" else "needs work" }
        }
        val r = WorkflowRunner { gen }.run("ship it", proposer, critic, Stop.CriticApproves)
        assertEquals(3, r.iterations.size)
        assertTrue(r.iterations.last().approved)
        assertEquals("attempt 3", r.finalProposal)
        assertTrue(r.stoppedBecause.contains("approved"))
    }

    @Test fun `stops at max iterations even without approval`() = runTest {
        val gen = Generator { system, _ -> if (system.contains("draft")) "x" else "never approves" }
        val r = WorkflowRunner { gen }.run("obj", proposer, critic, Stop.MaxIterations(2))
        assertEquals(2, r.iterations.size)
        assertTrue(r.iterations.none { it.approved })
    }

    @Test fun `hard cap bounds an over-large request`() = runTest {
        val gen = Generator { _, _ -> "noop" }
        val r = WorkflowRunner { gen }.run("obj", proposer, critic, Stop.MaxIterations(99), hardCap = 4)
        assertEquals(4, r.iterations.size)
    }

    @Test fun `feeds the prior critique back into the next proposal`() = runTest {
        val seenByProposer = mutableListOf<String>()
        val gen = Generator { system, user ->
            if (system.contains("draft")) { seenByProposer += user; "draft" }
            else "needs work: be bolder"
        }
        WorkflowRunner { gen }.run("obj", proposer, critic, Stop.MaxIterations(2))
        // Second proposal prompt must carry the first critique.
        assertTrue(seenByProposer[1].contains("be bolder"))
    }
}
