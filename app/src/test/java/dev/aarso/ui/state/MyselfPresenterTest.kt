package dev.aarso.ui.state

import dev.aarso.domain.ledger.Budget
import dev.aarso.domain.ledger.BudgetKind
import dev.aarso.domain.ledger.InteractionModel
import dev.aarso.domain.ledger.LedgerEntry
import dev.aarso.domain.ledger.Provenance
import dev.aarso.domain.ledger.Status
import dev.aarso.domain.ledger.Tier
import dev.aarso.domain.state.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the pure [MyselfPresenter] state derivation. No Android, no clock,
 * no random — every fixture timestamp is a fixed epoch-millis constant, so the whole
 * surface is deterministic and machine-verified.
 */
class MyselfPresenterTest {

    // --- fixtures ---------------------------------------------------------------

    /** A fixed reference instant; offsets below are added to keep timestamps distinct. */
    private val t0 = 1_700_000_000_000L

    private fun entry(
        nodeId: String,
        model: String,
        provider: String,
        provenance: Provenance,
        inputTokens: Long,
        outputTokens: Long,
        estCostMinor: Long,
        estimated: Boolean,
        interactionModel: InteractionModel = InteractionModel.SINGLE,
        councilMemberId: String? = null,
        chatId: String = "chat-1",
        timestampMillis: Long = t0,
    ) = LedgerEntry(
        timestampMillis = timestampMillis,
        projectId = null,
        chatId = chatId,
        nodeId = nodeId,
        model = model,
        provider = provider,
        provenance = provenance,
        interactionModel = interactionModel,
        councilMemberId = councilMemberId,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        estCostMinor = estCostMinor,
        latencyMs = 100L,
        tier = if (provenance == Provenance.LOCAL) Tier.ON_DEVICE else Tier.CLOUD,
        status = Status.COMPLETE,
        estimated = estimated,
    )

    /**
     * A small fixed ledger engineered so the sovereignty ratio is exactly 0.7:
     *  - on-device tokens: 400 + 300 = 700
     *  - cloud tokens:     200 + 100 = 300
     *  → 700 / 1000 = 0.70
     * Cloud cost is 150 + 50 = 200 minor (on-device costs 0). Two entries are estimated.
     */
    private fun sampleEntries(): List<LedgerEntry> = listOf(
        entry(
            nodeId = "n1", model = "qwen2.5-7b", provider = "on-device",
            provenance = Provenance.LOCAL, inputTokens = 250, outputTokens = 150,
            estCostMinor = 0, estimated = false, timestampMillis = t0,
        ),
        entry(
            nodeId = "n2", model = "qwen2.5-7b", provider = "on-device",
            provenance = Provenance.LOCAL, inputTokens = 200, outputTokens = 100,
            estCostMinor = 0, estimated = false, timestampMillis = t0 + 1_000,
        ),
        entry(
            nodeId = "n3", model = "claude-3.5", provider = "anthropic",
            provenance = Provenance.CLOUD, inputTokens = 120, outputTokens = 80,
            estCostMinor = 150, estimated = true, timestampMillis = t0 + 2_000,
        ),
        entry(
            nodeId = "n4", model = "gpt-4o", provider = "openai-compat",
            provenance = Provenance.CLOUD, inputTokens = 60, outputTokens = 40,
            estCostMinor = 50, estimated = true, timestampMillis = t0 + 3_000,
        ),
    )

    // --- empty case -------------------------------------------------------------

    @Test
    fun emptyEntriesProduceEmptyState() {
        val state = MyselfPresenter.present(emptyList())
        assertSame("empty ledger must map to the useful Empty state", UiState.Empty, state)
    }

    @Test
    fun emptyEntriesIgnoreBudgets() {
        // Even with budgets supplied, no entries means Empty (nothing to reflect on yet).
        val state = MyselfPresenter.present(
            emptyList(),
            listOf(Budget(BudgetKind.COST_MINOR, ceilingMinorOrTokens = 1000)),
        )
        assertSame(UiState.Empty, state)
    }

    // --- ready case -------------------------------------------------------------

    private fun readyView(
        budgets: List<Budget> = emptyList(),
    ): MyselfView {
        val state = MyselfPresenter.present(sampleEntries(), budgets)
        assertTrue("non-empty ledger must be Ready", state is UiState.Ready)
        return (state as UiState.Ready).value
    }

    @Test
    fun nonEmptyEntriesProduceReadyState() {
        val state = MyselfPresenter.present(sampleEntries())
        assertTrue(state is UiState.Ready)
    }

    @Test
    fun totalsAreSummedCorrectly() {
        val v = readyView()
        assertEquals("input tokens", 250L + 200L + 120L + 60L, v.totals.inputTokens)
        assertEquals("output tokens", 150L + 100L + 80L + 40L, v.totals.outputTokens)
        assertEquals("total tokens", 1000L, v.totals.totalTokens)
        assertEquals("est cost", 200L, v.totals.estCostMinor)
        assertEquals("turns", 4, v.totals.turns)
    }

    @Test
    fun provenanceSplitReportsSovereigntyRatioOfPointSeven() {
        val v = readyView()
        assertEquals("on-device tokens", 700L, v.provenanceSplit.onDeviceTokens)
        assertEquals("cloud tokens", 300L, v.provenanceSplit.cloudTokens)
        assertEquals("on-device cost is always 0", 0L, v.provenanceSplit.onDeviceCostMinor)
        assertEquals("cloud cost", 200L, v.provenanceSplit.cloudCostMinor)
        assertEquals("sovereignty ratio", 0.70, v.provenanceSplit.sovereigntyRatio, 1e-9)
    }

    @Test
    fun byProviderIsSortedByTotalTokensDescending() {
        val v = readyView()
        val providers = v.byProvider.keys.toList()
        // on-device 700 > anthropic 200 > openai-compat 100
        assertEquals(listOf("on-device", "anthropic", "openai-compat"), providers)
        assertEquals(700L, v.byProvider["on-device"]!!.totalTokens)
        assertEquals(2, v.byProvider["on-device"]!!.calls)
        assertEquals(200L, v.byProvider["anthropic"]!!.totalTokens)
        assertEquals(150L, v.byProvider["anthropic"]!!.estCostMinor)
    }

    @Test
    fun byModelRollsUpAndOrdersByTokens() {
        val v = readyView()
        val models = v.byModel.keys.toList()
        // qwen2.5-7b 700 > claude-3.5 200 > gpt-4o 100
        assertEquals(listOf("qwen2.5-7b", "claude-3.5", "gpt-4o"), models)
        assertEquals(2, v.byModel["qwen2.5-7b"]!!.calls)
        assertEquals(Provenance.LOCAL, v.byModel["qwen2.5-7b"]!!.provenance)
        assertEquals(Provenance.CLOUD, v.byModel["claude-3.5"]!!.provenance)
    }

    @Test
    fun estimatedCountReflectsFlaggedEntries() {
        val v = readyView()
        assertEquals("two cloud entries are estimated", 2, v.estimatedCount)
    }

    @Test
    fun noBudgetsMeansNoRings() {
        val v = readyView()
        assertTrue(v.budgetRings.isEmpty())
    }

    @Test
    fun budgetRingsAreFilledInOrder() {
        val budgets = listOf(
            // total cost 200, ceiling 400 → fraction 0.5, not crossed
            Budget(BudgetKind.COST_MINOR, ceilingMinorOrTokens = 400),
            // cloud tokens 300, ceiling 300 → fraction 1.0, crossed
            Budget(BudgetKind.CLOUD_TOKENS, ceilingMinorOrTokens = 300),
        )
        val v = readyView(budgets)
        assertEquals("one ring per budget, in order", 2, v.budgetRings.size)

        val costRing = v.budgetRings[0]
        assertEquals(200L, costRing.used)
        assertEquals(400L, costRing.ceiling)
        assertEquals(0.5, costRing.fraction, 1e-9)
        assertFalse(costRing.crossed)

        val cloudRing = v.budgetRings[1]
        assertEquals(300L, cloudRing.used)
        assertEquals(1.0, cloudRing.fraction, 1e-9)
        assertTrue("cloud-token ceiling reached → crossed", cloudRing.crossed)
    }

    @Test
    fun providerCostBudgetMetersOnlyNamedProvider() {
        val budgets = listOf(
            Budget(BudgetKind.PROVIDER_COST, ceilingMinorOrTokens = 300, provider = "anthropic"),
        )
        val v = readyView(budgets)
        val ring = v.budgetRings.single()
        assertEquals("only anthropic's 150 minor counts", 150L, ring.used)
        assertFalse(ring.crossed)
    }

    @Test
    fun presentIsDeterministic() {
        // Same input twice → equal derived view (pure fold, no clock/random).
        val a = (MyselfPresenter.present(sampleEntries()) as UiState.Ready).value
        val b = (MyselfPresenter.present(sampleEntries()) as UiState.Ready).value
        assertEquals(a, b)
    }

    @Test
    fun singleLocalEntryIsFullySovereign() {
        val state = MyselfPresenter.present(
            listOf(
                entry(
                    nodeId = "solo", model = "qwen2.5-7b", provider = "on-device",
                    provenance = Provenance.LOCAL, inputTokens = 10, outputTokens = 5,
                    estCostMinor = 0, estimated = false,
                ),
            ),
        )
        val v = (state as UiState.Ready).value
        assertEquals(1.0, v.provenanceSplit.sovereigntyRatio, 1e-9)
        assertEquals(0, v.estimatedCount)
        assertEquals(1, v.totals.turns)
    }
}
