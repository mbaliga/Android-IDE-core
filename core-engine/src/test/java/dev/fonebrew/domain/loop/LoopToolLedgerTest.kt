package dev.fonebrew.domain.loop

import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.ledger.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopToolLedgerTest {
    @Test fun `tool and http steps are ledgered zero-token zero-cost but recorded with their host`() {
        val http = GraphStep(
            0, "n1", "tool:http_request", null, "body", tokensIn = 0, tokensOut = 0, durationMs = 42, estimated = false,
            cost = StepCost.Free, toolId = "http_request", argsSummary = "GET https://a.example/", hosts = listOf("a.example"),
            sideEffect = ToolSideEffect.NETWORK, resultChars = 4,
        )
        val local = http.copy(index = 1, nodeId = "n2", role = "tool:search_workspace", toolId = "search_workspace", hosts = emptyList(), sideEffect = ToolSideEffect.READ_LOCAL)
        val result = GraphRunResult(listOf(http, local), "reached end", true)
        val nodes = GraphRunLog.toNodes("obj", result, "run1", "loop1", 1L) { java.util.UUID.randomUUID().toString() }
        val entries = GraphRunLedger.toEntries(result, nodes, "loop1", "run1", null, 1L)
        assertEquals(2, entries.size)
        val e = entries[0]
        assertEquals("network", e.provider)
        assertEquals("tool:http_request@a.example", e.model)
        assertEquals(0L, e.inputTokens); assertEquals(0L, e.outputTokens); assertEquals(0L, e.estCostMinor)
        assertEquals(Tier.ON_DEVICE, e.tier)
        assertEquals("loop", e.surface)
        assertEquals(42L, e.latencyMs)
        assertEquals("on-device", entries[1].provider)
        // The tree node carries the egress audit tags.
        val treeNode = nodes[1]
        assertEquals("a.example", treeNode.metadata[GraphRunLog.TAG_HOSTS])
        assertEquals("http_request", treeNode.metadata[GraphRunLog.TAG_TOOL])
        assertTrue(treeNode.metadata[GraphRunLog.TAG_ARGS]!!.startsWith("GET https://a.example/"))
    }
}
