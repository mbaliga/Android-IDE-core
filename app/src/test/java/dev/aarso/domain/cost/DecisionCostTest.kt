package dev.aarso.domain.cost

import dev.aarso.domain.council.Cost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionCostTest {

    @Test fun `vector add and scale`() {
        val v = CostVector(moneyMinor = 100, minutes = 60, tokens = 1000)
        assertEquals(CostVector(150, 90, 1500), v + CostVector(50, 30, 500))
        assertEquals(CostVector(50, 30, 500), v.scaled(0.5))
    }

    @Test fun `valuation rolls non-money axes into money, alongside the breakdown`() {
        val val_ = Valuation(moneyPerHour = 600, moneyPer1kTokens = 50)
        // money 100 + 120min@600/hr (=1200) + 2000tok@50/1k (=100) = 1400
        assertEquals(1400L, val_.toMinor(CostVector(moneyMinor = 100, minutes = 120, tokens = 2000)))
    }

    @Test fun `certain single-attempt decision is just its parts`() {
        val d = Decision(
            label = "buy a thing",
            onSuccess = CostVector.money(500),
            perAttempt = CostVector.money(100),
            successChance = 1.0,
        )
        val f = DecisionCost.forecast(d)
        assertEquals(1.0, f.expectedAttempts, 1e-9)
        assertEquals(1.0, f.successProbability, 1e-9)
        assertEquals(600L, f.expected.moneyMinor) // 100 attempt + 500 success
    }

    /**
     * The BlackBerry-keyboard case. Naive estimate: ₹500. Reality, modelled:
     *  - seller price ₹1000 on success
     *  - ₹250 travel every attempt (recurs — a second "win" nets the same)
     *  - 50% you close the deal per trip, up to 3 trips
     *  - 30% chance the apparatus is unusable → ₹3000 wasted
     * The expected cost is ~₹2213 and the worst case ₹4750 — both far past ₹500.
     */
    @Test fun `blackberry keyboard — the real cost dwarfs the naive estimate`() {
        val d = Decision(
            label = "salvage BB keyboard",
            onSuccess = CostVector.money(1000),
            perAttempt = CostVector.money(250),
            successChance = 0.5,
            risks = listOf(RiskedOutcome("unusable apparatus", chance = 0.3, extra = CostVector.money(3000))),
        )
        val f = DecisionCost.forecast(d, maxAttempts = 3)

        assertEquals(1.75, f.expectedAttempts, 1e-9)   // 1 + .5 + .25
        assertEquals(0.875, f.successProbability, 1e-9) // 1 - .5^3
        assertEquals(900L, f.riskContribution.moneyMinor) // 0.3 * 3000

        // expected = 250*1.75 (438) + 1000*0.875 (875) + 900 = 2213
        assertEquals(2213L, f.expected.moneyMinor)
        // worst = 250*3 + 1000 + 3000 = 4750
        assertEquals(4750L, f.worst.moneyMinor)

        // The naive "₹500" point estimate is nowhere near either bound.
        assertTrue(f.expected.moneyMinor > 500 && f.worst.moneyMinor > f.expected.moneyMinor)
    }

    @Test fun `a decision you can never close still costs every wasted attempt`() {
        val d = Decision(
            label = "doomed errand",
            onSuccess = CostVector.money(1000),
            perAttempt = CostVector.money(250),
            successChance = 0.0,
        )
        val f = DecisionCost.forecast(d, maxAttempts = 3)
        assertEquals(3.0, f.expectedAttempts, 1e-9)
        assertEquals(0.0, f.successProbability, 1e-9)
        assertEquals(750L, f.expected.moneyMinor) // 3 wasted trips, the thing never bought
    }

    @Test fun `advice cost — the price of asking the model, money plus tokens`() {
        val advice = LlmAdvice.cost(
            tokensIn = 1000, tokensOut = 500,
            moneyPer1kIn = 30, moneyPer1kOut = 60, readingMinutes = 5,
        )
        assertEquals(CostVector(moneyMinor = 60, minutes = 5, tokens = 1500), advice)
    }

    @Test fun `the loop's internal Cost bridges into a decision's advice cost`() {
        val loopCost = Cost(tokens = 1000, seconds = 120, moneyCents = 50, calls = 2)
        assertEquals(CostVector(moneyMinor = 50, minutes = 2, tokens = 1000), loopCost.toCostVector())
    }
}
