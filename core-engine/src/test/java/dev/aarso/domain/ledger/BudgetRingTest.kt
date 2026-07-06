package dev.aarso.domain.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetRingTest {

    private fun e(
        provider: String = "anthropic",
        provenance: Provenance = Provenance.CLOUD,
        inTok: Long = 100,
        outTok: Long = 100,
        cost: Long = 10,
    ) = LedgerEntry(
        timestampMillis = 1773482400000L, projectId = null, chatId = "c", nodeId = "n",
        model = "m", provider = provider, provenance = provenance,
        interactionModel = InteractionModel.SINGLE, councilMemberId = null,
        inputTokens = inTok, outputTokens = outTok, estCostMinor = cost,
        latencyMs = 100, tier = Tier.CLOUD, status = Status.COMPLETE, estimated = true,
    )

    @Test fun `cost budget fraction is used over ceiling`() {
        val entries = listOf(e(cost = 30), e(cost = 20)) // used = 50
        val r = BudgetRing.fill(entries, Budget(BudgetKind.COST_MINOR, 100))
        assertEquals(50L, r.used)
        assertEquals(100L, r.ceiling)
        assertEquals(0.5, r.fraction, 1e-9)
        assertFalse(r.crossed)
    }

    @Test fun `cost budget crossed when used equals ceiling`() {
        val r = BudgetRing.fill(listOf(e(cost = 100)), Budget(BudgetKind.COST_MINOR, 100))
        assertTrue(r.crossed)
        assertEquals(1.0, r.fraction, 1e-9)
    }

    @Test fun `cost budget fraction clamps at one when over ceiling`() {
        val r = BudgetRing.fill(listOf(e(cost = 250)), Budget(BudgetKind.COST_MINOR, 100))
        assertEquals(250L, r.used)
        assertEquals(1.0, r.fraction, 1e-9) // clamped, not 2.5
        assertTrue(r.crossed)
    }

    @Test fun `cloud token budget counts only non-local entries`() {
        val entries = listOf(
            e(provenance = Provenance.LOCAL, inTok = 1000, outTok = 1000, cost = 0), // ignored
            e(provenance = Provenance.CLOUD, inTok = 200, outTok = 100, cost = 9),    // 300 tokens
        )
        val r = BudgetRing.fill(entries, Budget(BudgetKind.CLOUD_TOKENS, 600))
        assertEquals(300L, r.used)
        assertEquals(0.5, r.fraction, 1e-9)
        assertFalse(r.crossed)
    }

    @Test fun `provider cost budget filters to the named provider`() {
        val entries = listOf(
            e(provider = "anthropic", cost = 40),
            e(provider = "openai-compat", cost = 999),
            e(provider = "anthropic", cost = 10),
        )
        val r = BudgetRing.fill(entries, Budget(BudgetKind.PROVIDER_COST, 100, provider = "anthropic"))
        assertEquals(50L, r.used) // only the two anthropic rows
        assertEquals(0.5, r.fraction, 1e-9)
    }

    @Test fun `provider cost budget with null provider uses zero`() {
        val r = BudgetRing.fill(listOf(e(cost = 999)), Budget(BudgetKind.PROVIDER_COST, 100, provider = null))
        assertEquals(0L, r.used)
        assertEquals(0.0, r.fraction, 1e-9)
        assertFalse(r.crossed)
    }

    @Test fun `non-positive ceiling is treated as unbounded`() {
        val r = BudgetRing.fill(listOf(e(cost = 500)), Budget(BudgetKind.COST_MINOR, 0))
        assertEquals(500L, r.used)
        assertEquals(0.0, r.fraction, 1e-9)
        assertFalse(r.crossed)
    }

    @Test fun `empty entries give zero used and not crossed`() {
        val r = BudgetRing.fill(emptyList(), Budget(BudgetKind.COST_MINOR, 100))
        assertEquals(0L, r.used)
        assertEquals(0.0, r.fraction, 1e-9)
        assertFalse(r.crossed)
    }

    // --- reconciliation ---------------------------------------------------------

    @Test fun `reconciliation available reports positive delta when provider billed more`() {
        val r = Reconciliation.delta(localMinor = 80, providerMinor = 100)
        assertTrue(r is ReconResult.Available)
        r as ReconResult.Available
        assertEquals(80L, r.local)
        assertEquals(100L, r.provider)
        assertEquals(20L, r.delta) // provider - local
    }

    @Test fun `reconciliation available reports negative delta when ledger over-counted`() {
        val r = Reconciliation.delta(localMinor = 120, providerMinor = 100) as ReconResult.Available
        assertEquals(-20L, r.delta)
    }

    @Test fun `reconciliation unavailable when provider has no usage api`() {
        val r = Reconciliation.delta(localMinor = 80, providerMinor = null)
        assertTrue(r is ReconResult.Unavailable)
        assertEquals(80L, r.local)
    }
}
