package dev.aarso.domain.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerCaptureTest {

    @Test
    fun onDeviceTurnIsLocalFreeAndSingle() {
        val e = LedgerCapture.singleTurn(
            timestampMillis = 100,
            chatId = "root1",
            nodeId = "n1",
            projectId = null,
            model = "qwen2.5-7b",
            provider = "on-device",
            tier = Tier.ON_DEVICE,
            inputTokens = 42,
            outputTokens = 108,
            estCostMinor = 0,
            latencyMs = 1200,
            status = Status.COMPLETE,
            estimated = true,
        )
        assertEquals(Provenance.LOCAL, e.provenance)
        assertEquals(InteractionModel.SINGLE, e.interactionModel)
        assertNull(e.councilMemberId)
        assertEquals(0, e.estCostMinor)
        assertEquals(150, e.totalTokens)
        assertTrue(e.estimated)
    }

    @Test
    fun cloudTurnIsCloudAndAuthoritative() {
        val e = LedgerCapture.singleTurn(
            timestampMillis = 200,
            chatId = "root2",
            nodeId = "n2",
            projectId = "Monsoon",
            model = "claude-x",
            provider = "anthropic",
            tier = Tier.CLOUD,
            inputTokens = 1000,
            outputTokens = 500,
            estCostMinor = 37,
            latencyMs = 800,
            status = Status.COMPLETE,
            estimated = false,
        )
        assertEquals(Provenance.CLOUD, e.provenance)
        assertEquals("anthropic", e.provider)
        assertEquals("Monsoon", e.projectId)
        assertEquals(37, e.estCostMinor)
        assertFalse(e.estimated)
    }

    @Test
    fun runnerTierCountsAsCloudProvenance() {
        assertEquals(Provenance.CLOUD, LedgerCapture.provenanceFor(Tier.RUNNER))
        assertEquals(Provenance.LOCAL, LedgerCapture.provenanceFor(Tier.ON_DEVICE))
        assertEquals(Provenance.CLOUD, LedgerCapture.provenanceFor(Tier.CLOUD))
    }

    @Test
    fun negativesAreFlooredToZero() {
        val e = LedgerCapture.singleTurn(
            timestampMillis = 0, chatId = "c", nodeId = "n", projectId = null,
            model = "m", provider = "on-device", tier = Tier.ON_DEVICE,
            inputTokens = -5, outputTokens = -1, estCostMinor = -9, latencyMs = -3,
            status = Status.STOPPED, estimated = true,
        )
        assertEquals(0, e.inputTokens)
        assertEquals(0, e.outputTokens)
        assertEquals(0, e.estCostMinor)
        assertEquals(0, e.latencyMs)
        assertEquals(Status.STOPPED, e.status)
    }
}
