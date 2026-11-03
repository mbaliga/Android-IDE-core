package dev.fonebrew.domain.ledger

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

    @Test
    fun loopStepIsTaggedSurfaceLoopWithZeroCost() {
        val e = LedgerCapture.loopStep(
            timestampMillis = 500,
            runId = "run-1",
            loopId = "loop-1",
            nodeId = "node-3",
            projectId = null,
            model = "qwen2.5-7b",
            tier = Tier.ON_DEVICE,
            inputTokens = 40,
            outputTokens = 10,
            latencyMs = 120,
            estimated = true,
        )
        assertEquals("loop", e.surface)
        assertEquals("loop-1", e.loopId)
        assertEquals("run-1", e.chatId)
        assertEquals("node-3", e.nodeId)
        assertEquals("on-device", e.provider)
        assertEquals(Provenance.LOCAL, e.provenance)
        assertEquals(InteractionModel.SINGLE, e.interactionModel)
        assertNull(e.councilMemberId)
        assertEquals(0, e.estCostMinor)
        assertEquals(Status.COMPLETE, e.status)
        assertTrue(e.estimated)
    }

    @Test
    fun loopStepOnCloudTierMapsToCloudProvenanceAndProvider() {
        val e = LedgerCapture.loopStep(
            timestampMillis = 500, runId = "run-2", loopId = null, nodeId = "node-1",
            projectId = "proj", model = "claude-sonnet", tier = Tier.CLOUD,
            inputTokens = 100, outputTokens = 50, latencyMs = 900, estimated = false,
        )
        assertEquals(Provenance.CLOUD, e.provenance)
        assertEquals("cloud", e.provider)
        assertNull(e.loopId)
        assertFalse(e.estimated)
    }

    @Test
    fun loopStepFloorsNegativeTokens() {
        val e = LedgerCapture.loopStep(
            timestampMillis = 0, runId = "r", loopId = null, nodeId = "n", projectId = null,
            model = "m", tier = Tier.ON_DEVICE, inputTokens = -1, outputTokens = -1,
            latencyMs = -1, estimated = true,
        )
        assertEquals(0, e.inputTokens)
        assertEquals(0, e.outputTokens)
        assertEquals(0, e.latencyMs)
    }

    @Test
    fun loopStepDefaultsToZeroCostWhenCallerDoesNotPrice() {
        val e = LedgerCapture.loopStep(
            timestampMillis = 500, runId = "run-1", loopId = "loop-1", nodeId = "node-3",
            projectId = null, model = "qwen2.5-7b", tier = Tier.ON_DEVICE,
            inputTokens = 40, outputTokens = 10, latencyMs = 120, estimated = true,
        )
        assertEquals(0, e.estCostMinor)
    }

    @Test
    fun loopStepRecordsWhateverCostItsCallerPriced() {
        // GraphRunLedger is the real caller that prices a cloud step (Cost epic, last mile);
        // this only asserts the builder records — and floors — what it's handed.
        val e = LedgerCapture.loopStep(
            timestampMillis = 500, runId = "run-2", loopId = null, nodeId = "node-1",
            projectId = "proj", model = "claude-sonnet", tier = Tier.CLOUD,
            inputTokens = 100, outputTokens = 50, latencyMs = 900, estimated = false,
            estCostMinor = 42,
        )
        assertEquals(42, e.estCostMinor)
    }

    @Test
    fun loopStepFloorsNegativeCost() {
        val e = LedgerCapture.loopStep(
            timestampMillis = 0, runId = "r", loopId = null, nodeId = "n", projectId = null,
            model = "m", tier = Tier.CLOUD, inputTokens = 1, outputTokens = 1,
            latencyMs = 0, estimated = false, estCostMinor = -9,
        )
        assertEquals(0, e.estCostMinor)
    }
}
