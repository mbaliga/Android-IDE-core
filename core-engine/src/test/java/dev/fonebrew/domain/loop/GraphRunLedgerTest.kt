package dev.fonebrew.domain.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphRunLedgerTest {

    private fun result() = GraphRunResult(
        steps = listOf(
            GraphStep(0, "prop", "proposer", "qwen", "draft", tokensIn = 40, tokensOut = 10, durationMs = 120, estimated = true),
            GraphStep(1, "crit", "critic", "claude", "APPROVE", tokensIn = 22, tokensOut = 6, durationMs = 340, estimated = false),
        ),
        stoppedBecause = "reached end",
        reachedEnd = true,
        totalTokensIn = 62,
        totalTokensOut = 16,
        elapsedMs = 460,
    )

    /** The exact [dev.fonebrew.domain.MessageNode] list [GraphRunLog.toNodes] would build for
     *  [result] — [GraphRunLedger] correlates against it, never inventing its own ids. */
    private fun treeNodes() = GraphRunLog.toNodes(
        objective = "make it airtight",
        result = result(),
        loopRunId = "run-1",
        loopId = "loop-7",
        now = 1000L,
        idGen = { "n${nextId++}" },
    )

    private var nextId = 0

    @Test fun `one ledger entry per completed step, correlated to the tree node by id`() {
        nextId = 0
        val nodes = treeNodes()
        val entries = GraphRunLedger.toEntries(
            result = result(), treeNodes = nodes, loopId = "loop-7", runId = "run-1",
            projectId = "proj-1", timestampMillis = 5000L,
        )
        assertEquals(2, entries.size)
        // nodes[0] is the objective root; nodes[1]/[2] are the two step nodes.
        assertEquals(nodes[1].id, entries[0].nodeId)
        assertEquals(nodes[2].id, entries[1].nodeId)
    }

    @Test fun `every entry is tagged surface loop with the run and loop ids`() {
        nextId = 0
        val nodes = treeNodes()
        val entries = GraphRunLedger.toEntries(result(), nodes, "loop-7", "run-1", null, 5000L)
        for (e in entries) {
            assertEquals("loop", e.surface)
            assertEquals("loop-7", e.loopId)
            assertEquals("run-1", e.chatId) // chatId doubles as the run-grouping key
        }
    }

    @Test fun `tokens duration and estimated propagate per step from GraphStep`() {
        nextId = 0
        val entries = GraphRunLedger.toEntries(result(), treeNodes(), "loop-7", "run-1", null, 5000L)
        assertEquals(40L, entries[0].inputTokens); assertEquals(10L, entries[0].outputTokens)
        assertEquals(120L, entries[0].latencyMs); assertTrue(entries[0].estimated)
        assertEquals(22L, entries[1].inputTokens); assertEquals(6L, entries[1].outputTokens)
        assertEquals(340L, entries[1].latencyMs)
        assertEquals(false, entries[1].estimated)
    }

    @Test fun `cost is always zero — the per-loop dollar boundary is not built yet`() {
        nextId = 0
        val entries = GraphRunLedger.toEntries(result(), treeNodes(), "loop-7", "run-1", null, 5000L)
        assertTrue(entries.all { it.estCostMinor == 0L })
    }

    @Test fun `a null loopId (ad-hoc, unsaved graph run) carries through as null`() {
        nextId = 0
        val entries = GraphRunLedger.toEntries(result(), treeNodes(), loopId = null, runId = "run-1", projectId = null, timestampMillis = 1L)
        assertTrue(entries.all { it.loopId == null })
        assertNull(entries.first().loopId)
    }
}
