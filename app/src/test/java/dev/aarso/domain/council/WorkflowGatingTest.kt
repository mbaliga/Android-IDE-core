package dev.aarso.domain.council

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the optional escalation [Gating] on [WorkflowRunner.run]. */
class WorkflowGatingTest {

    private val proposer = Expert("proposer", "You draft.")
    private val critic = Expert("critic", "You critique.")

    // Never approves, so the loop runs to its cap unless a gate halts it first.
    private val gen = Generator { system, _ -> if (system.contains("draft")) "draft" else "needs work" }

    private val user = Gate(Approver(ApproverKind.USER, "you"), Budget.UNLIMITED)
    private val agent = Gate(Approver(ApproverKind.AGENT, "Skeptic"), Budget.UNLIMITED)

    // Each build step is sized as one cloud call (non-zero ⇒ trips a zero budget).
    private val estimate: suspend (Expert, String) -> Cost =
        { _, text -> CostEstimator.estimate(text, ModelCostProfile.CLOUD_DEFAULT) }

    @Test fun `generous policy never escalates`() = runTest {
        var asked = 0
        val policy = EscalationPolicy(autoBudget = Budget.UNLIMITED, gates = listOf(user))
        val gating = Gating(policy, estimate, approve = { _, _ -> asked++; true })
        val r = WorkflowRunner { gen }.run("obj", proposer, critic, Stop.MaxIterations(3), gating = gating)
        assertEquals(3, r.iterations.size)
        assertEquals(0, asked) // within budget every time
    }

    @Test fun `tight user gate runs while approved`() = runTest {
        var asked = 0
        val policy = EscalationPolicy(autoBudget = Budget.ZERO, gates = listOf(user))
        val gating = Gating(policy, estimate, approve = { _, _ -> asked++; true })
        val r = WorkflowRunner { gen }.run("obj", proposer, critic, Stop.MaxIterations(2), gating = gating)
        assertEquals(2, r.iterations.size)
        assertEquals(2, asked) // every step is over the zero budget → asked each time
    }

    @Test fun `tight user gate halts at the first denial`() = runTest {
        val policy = EscalationPolicy(autoBudget = Budget.ZERO, gates = listOf(user))
        val gating = Gating(policy, estimate, approve = { _, _ -> false })
        val r = WorkflowRunner { gen }.run("obj", proposer, critic, Stop.MaxIterations(5), gating = gating)
        assertTrue(r.iterations.isEmpty()) // halted before any build ran
        assertTrue(r.stoppedBecause.contains("halted at you"))
    }

    @Test fun `agent gate auto-proceeds without a callback`() = runTest {
        // An AGENT rung resolves itself even with no approve callback supplied.
        val policy = EscalationPolicy(autoBudget = Budget.ZERO, gates = listOf(agent))
        val gating = Gating(policy, estimate, approve = null)
        val r = WorkflowRunner { gen }.run("obj", proposer, critic, Stop.MaxIterations(3), gating = gating)
        assertEquals(3, r.iterations.size)
    }

    @Test fun `human gate halts without a callback`() = runTest {
        // You cannot ask a human with no callback ⇒ conservative halt.
        val policy = EscalationPolicy(autoBudget = Budget.ZERO, gates = listOf(user))
        val gating = Gating(policy, estimate, approve = null)
        val r = WorkflowRunner { gen }.run("obj", proposer, critic, Stop.MaxIterations(3), gating = gating)
        assertTrue(r.iterations.isEmpty())
        assertTrue(r.stoppedBecause.contains("halted"))
    }

    @Test fun `green tests widen autonomy past the gate`() = runTest {
        var asked = 0
        val policy = EscalationPolicy(
            autoBudget = Budget.ZERO,
            gates = listOf(user),
            autoBudgetWithTestsGreen = Budget.UNLIMITED,
        )
        val gating = Gating(policy, estimate, approve = { _, _ -> asked++; true }, testsGreen = true)
        val r = WorkflowRunner { gen }.run("obj", proposer, critic, Stop.MaxIterations(2), gating = gating)
        assertEquals(2, r.iterations.size)
        assertEquals(0, asked) // the green-tests budget kept it autonomous
    }
}
