package dev.fonebrew.data

import dev.fonebrew.domain.cost.UsagePricing
import dev.fonebrew.domain.cost.UsageReport
import dev.fonebrew.domain.ledger.LedgerEntry
import dev.fonebrew.domain.ledger.Provenance
import dev.fonebrew.domain.ledger.Tier
import dev.fonebrew.domain.model.ModelSpec
import dev.fonebrew.domain.model.Runtime
import dev.fonebrew.domain.template.TemplateId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The agent's model calls reach the usage ledger as `surface = "agent"`, with no invented numbers. */
class AgentUsageLedgerTest {

    private fun spec(runtime: Runtime, providerId: String? = null) = ModelSpec(
        id = "m1", displayName = "M", family = "f", contextWindow = 4096, tokenizerId = "tok",
        templateId = TemplateId.PLAIN, runtime = runtime, providerId = providerId,
    )

    private class Sink {
        val rows = mutableListOf<LedgerEntry>()
        val freeTier = mutableListOf<Triple<String, Long, Long>>()
    }

    private suspend fun run(
        spec: ModelSpec,
        reported: UsageReport?,
        count: suspend (String) -> Int,
        sink: Sink,
    ) = AgentRepoRunner.recordAgentUsage(
        spec = spec, tokenizerId = "cloud:x", reported = reported, countTokens = count,
        promptText = "prompt", outputText = "output", startedAt = 1_000, finishedAt = 1_250, runId = "run-1",
        appendLedger = { sink.rows += it },
        priceFor = { UsagePricing(centsPer1kInput = 100L, centsPer1kOutput = 200L) },
        recordFreeTier = { p, i, o -> sink.freeTier += Triple(p, i, o) },
    )

    @Test fun `cloud call with provider usage is recorded exact, priced, not estimated, surface agent`() = runTest {
        val sink = Sink()
        run(spec(Runtime.CLOUD, "anthropic"), UsageReport(1000, 500), { error("must not estimate") }, sink)
        val e = sink.rows.single()
        assertEquals("agent", e.surface)
        assertEquals(1000L, e.inputTokens)
        assertEquals(500L, e.outputTokens)
        assertFalse(e.estimated)
        assertEquals(Tier.CLOUD, e.tier)
        assertEquals(Provenance.CLOUD, e.provenance)
        assertEquals("anthropic", e.provider)
        assertEquals(250L, e.latencyMs)
        assertTrue(e.estCostMinor > 0)
        assertEquals(listOf(Triple("anthropic", 1000L, 500L)), sink.freeTier)
    }

    @Test fun `on-device call is recorded as local, free, with tokenizer counts flagged estimated`() = runTest {
        val sink = Sink()
        run(spec(Runtime.LOCAL_GGUF), null, { if (it == "prompt") 11 else 7 }, sink)
        val e = sink.rows.single()
        assertEquals("agent", e.surface)
        assertEquals(Tier.ON_DEVICE, e.tier)
        assertEquals(Provenance.LOCAL, e.provenance)
        assertEquals(0L, e.estCostMinor)
        assertEquals(11L, e.inputTokens)
        assertEquals(7L, e.outputTokens)
        assertTrue(e.estimated)
        assertEquals("on-device", e.provider)
        assertTrue(sink.freeTier.isEmpty())
    }

    @Test fun `cloud call reporting nothing falls back to estimated counts, never zero`() = runTest {
        val sink = Sink()
        run(spec(Runtime.CLOUD, "openai"), UsageReport.ZERO, { 40 }, sink)
        val e = sink.rows.single()
        assertTrue(e.estimated)
        assertEquals(40L, e.inputTokens)
        assertTrue(sink.freeTier.isEmpty()) // free tier only counts provider-reported usage
    }

    @Test fun `nothing known writes no row rather than a zero row`() = runTest {
        val sink = Sink()
        run(spec(Runtime.CLOUD, "openai"), null, { error("no tokenizer") }, sink)
        assertTrue(sink.rows.isEmpty())
    }

    @Test fun `agent rows round-trip through the ledger mapper keeping the agent surface`() {
        val e = dev.fonebrew.domain.ledger.LedgerCapture.agentRun(
            timestampMillis = 5, runId = "r", projectId = null, model = "m", provider = "p", tier = Tier.CLOUD,
            reported = UsageReport(3, 4), estimatedIn = null, estimatedOut = null, latencyMs = 9, priceMinor = { 1 },
        )
        assertNotNull(e)
        val back = LedgerMapper.toDomain(LedgerMapper.toEntity(e!!))
        assertEquals("agent", back.surface)
        assertNull(dev.fonebrew.domain.ledger.LedgerCapture.agentRun(
            5, "r", null, "m", "p", Tier.CLOUD, null, 1L, null, 9, { 1 },
        ))
    }
}
