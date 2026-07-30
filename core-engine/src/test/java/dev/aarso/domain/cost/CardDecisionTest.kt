package dev.aarso.domain.cost

import dev.aarso.domain.council.Cost
import dev.aarso.domain.pm.BoardCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardDecisionTest {

    private fun card(body: String = "") = BoardCard(
        id = "7", number = 7, title = "Fix the crash", body = body,
        isOpen = true, labels = emptyList(), assignees = emptyList(), updatedAt = "", url = "",
    )

    @Test fun `objective carries number and title, plus body when present`() {
        assertEquals("Resolve #7: Fix the crash", CardDecision.objective(card()))
        assertTrue(CardDecision.objective(card("stack trace here")).contains("stack trace here"))
    }

    @Test fun `loop cost becomes the decision's advice cost`() {
        val loop = Cost(tokens = 4000, seconds = 120, moneyCents = 25, calls = 3)
        val d = CardDecision.fromLoopCost(card(), loop)
        val f = DecisionCost.forecast(d)
        // No purchase / attempts — expected cost is exactly the advice cost.
        assertEquals(25L, f.expected.moneyMinor)
        assertEquals(4000L, f.expected.tokens)
        assertEquals(2L, f.expected.minutes) // 120s → 2 min
    }

    @Test fun `priced risk of the loop being wrong shows up in the forecast`() {
        val loop = Cost(moneyCents = 10)
        val d = CardDecision.fromLoopCost(
            card(), loop,
            risks = listOf(RiskedOutcome("loop output wrong", chance = 0.2, extra = CostVector.money(500))),
        )
        val f = DecisionCost.forecast(d)
        assertEquals(100L, f.riskContribution.moneyMinor) // 0.2 * 500
        assertEquals(110L, f.expected.moneyMinor)         // advice 10 + risk EV 100
    }
}
