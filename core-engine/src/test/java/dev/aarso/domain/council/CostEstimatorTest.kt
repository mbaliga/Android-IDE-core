package dev.aarso.domain.council

import org.junit.Assert.assertEquals
import org.junit.Test

class CostEstimatorTest {

    @Test fun `on-device steps cost no money`() {
        val c = CostEstimator.estimate("x".repeat(400), ModelCostProfile.ON_DEVICE)
        assertEquals(100L, c.tokens) // 400 chars / 4
        assertEquals(0L, c.moneyCents) // free on-device
        assertEquals(1, c.calls)
        assertEquals(100L / 30, c.seconds) // tokens / throughput
    }

    @Test fun `cloud money scales with tokens`() {
        val c = CostEstimator.estimate("y".repeat(4000), ModelCostProfile.CLOUD_DEFAULT)
        assertEquals(1000L, c.tokens)
        assertEquals(1L, c.moneyCents) // 1000 tokens × 1 cent / 1k
        assertEquals(1000L / 60, c.seconds)
    }

    @Test fun `token estimate is at least one`() {
        assertEquals(1L, CostEstimator.estimate("", ModelCostProfile.ON_DEVICE).tokens)
    }

    @Test fun `call count passes through`() {
        assertEquals(3, CostEstimator.estimate("abc", ModelCostProfile.CLOUD_DEFAULT, calls = 3).calls)
    }
}
