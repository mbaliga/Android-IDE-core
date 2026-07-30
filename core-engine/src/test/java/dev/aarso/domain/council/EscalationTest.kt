package dev.aarso.domain.council

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EscalationTest {

    private val lead = Gate(Approver(ApproverKind.TEAM_MEMBER, "Lead"), Budget(tokens = 100_000, moneyCents = 500))
    private val user = Gate(Approver(ApproverKind.USER, "you"), Budget.UNLIMITED)
    private val policy = EscalationPolicy(
        autoBudget = Budget(tokens = 10_000, moneyCents = 50),
        gates = listOf(lead, user),
    )

    @Test fun `cheap step proceeds without asking`() {
        assertEquals(GateDecision.Proceed, Escalation.decide(Cost(tokens = 500, moneyCents = 1), policy))
    }

    @Test fun `mid step escalates to the first gate that covers it`() {
        val d = Escalation.decide(Cost(tokens = 50_000, moneyCents = 200), policy)
        assertEquals(GateDecision.Escalate(lead), d)
    }

    @Test fun `expensive step escalates to the terminal user gate`() {
        val d = Escalation.decide(Cost(tokens = 5_000_000, moneyCents = 9_999), policy)
        assertEquals(GateDecision.Escalate(user), d)
    }

    @Test fun `any dimension over the budget triggers escalation`() {
        // tokens fine, money over the auto budget → escalate.
        val d = Escalation.decide(Cost(tokens = 100, moneyCents = 80), policy)
        assertTrue(d is GateDecision.Escalate)
    }

    @Test fun `green tests widen the autonomy budget`() {
        val p = policy.copy(autoBudgetWithTestsGreen = Budget(tokens = 80_000, moneyCents = 400))
        val cost = Cost(tokens = 50_000, moneyCents = 200)
        // Without the signal it would escalate; with green tests it proceeds.
        assertTrue(Escalation.decide(cost, p) is GateDecision.Escalate)
        assertEquals(GateDecision.Proceed, Escalation.decide(cost, p, testsGreen = true))
    }

    @Test fun `budget covers semantics`() {
        assertTrue(Budget(tokens = 10).covers(Cost(tokens = 10)))
        assertFalse(Budget(tokens = 10).covers(Cost(tokens = 11)))
        assertTrue(Budget.UNLIMITED.covers(Cost(tokens = Long.MAX_VALUE, calls = Int.MAX_VALUE)))
    }
}
