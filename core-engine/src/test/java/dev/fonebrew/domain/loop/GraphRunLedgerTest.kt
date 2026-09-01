package dev.fonebrew.domain.loop

import dev.fonebrew.domain.cost.PricingBook
import dev.fonebrew.domain.cost.UsagePricing
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

    @Test fun `cost stays zero when no pricing is wired — never invents a number`() {
        // Default call: no PricingBook, no tokenizer-id resolver. The cloud step's raw model
        // string ("claude") isn't a cloud-prefixed tokenizer id, so PricingBook.priceFor treats
        // it as unpriced/on-device rather than guessing — same honest degradation a chat turn
        // gets from an unresolvable engine id.
        nextId = 0
        val entries = GraphRunLedger.toEntries(result(), treeNodes(), "loop-7", "run-1", null, 5000L)
        assertTrue(entries.all { it.estCostMinor == 0L })
    }

    @Test fun `on-device step never carries a cost even when the model would otherwise price`() {
        nextId = 0
        val book = PricingBook().with("cloud:qwen", UsagePricing(centsPer1kInput = 100, centsPer1kOutput = 200))
        val entries = GraphRunLedger.toEntries(
            result(), treeNodes(), "loop-7", "run-1", null, 5000L,
            pricingBook = book,
            resolveTokenizerId = { id -> if (id == "qwen") "cloud:qwen" else id },
        )
        // entries[0] is the proposer step (estimated = true -> ON_DEVICE tier).
        assertEquals(0L, entries[0].estCostMinor)
    }

    @Test fun `cloud step prices through the same PricingBook path a chat turn uses`() {
        nextId = 0
        val book = PricingBook().with("cloud:claude-x", UsagePricing(centsPer1kInput = 300, centsPer1kOutput = 1500))
        val entries = GraphRunLedger.toEntries(
            result(), treeNodes(), "loop-7", "run-1", null, 5000L,
            pricingBook = book,
            resolveTokenizerId = { id -> if (id == "claude") "cloud:claude-x" else id },
        )
        // entries[1] is the critic step (estimated = false -> CLOUD tier), tokensIn=22, tokensOut=6:
        // 22*300/1000 (=6) + 6*1500/1000 (=9) = 15.
        assertEquals(15L, entries[1].estCostMinor)
        assertEquals(0L, entries[0].estCostMinor) // the on-device step is still never priced
    }

    /** A [result] where neither step carries a per-node model override — [GraphStep.model] is
     *  null on both, the default authoring state (`LoopRoom.kt`'s `LoopNode.modelId` defaults to
     *  null) — plus the tree nodes [GraphRunLedger] correlates rows against, built the same way
     *  [treeNodes] builds them for [result]. */
    private fun nullModelResult() = result().let { r -> r.copy(steps = r.steps.map { it.copy(model = null) }) }
    private fun nullModelTreeNodes() = GraphRunLog.toNodes(
        objective = "make it airtight", result = nullModelResult(), loopRunId = "run-1", loopId = "loop-7",
        now = 1000L, idGen = { "n${nextId++}" },
    )

    @Test fun `a default-model step (null) prices via the run's cloud fallback model, not on-device`() {
        // Regression for the DEFAULT-case pricing miss: a node with no per-node override still
        // executes on the run's fallback model (LoopRoom.kt:552's `?: fallback`), so the resolver
        // wired at LoopRoom.kt must mirror that exact fallback resolution — mimicked here by
        // resolving a null step model to the run's (cloud) fallback tokenizer id, exactly like
        // `{ id -> (id?.let { mid -> runnable.firstOrNull { it.id == mid } } ?: fallback).tokenizerId }`
        // would for an id with no per-node override. Previously this resolved to null and the
        // step was silently priced as UsagePricing.ON_DEVICE despite being real cloud usage.
        nextId = 0
        val book = PricingBook().withFallback(UsagePricing(centsPer1kInput = 300, centsPer1kOutput = 1500))
        val entries = GraphRunLedger.toEntries(
            nullModelResult(), nullModelTreeNodes(), "loop-7", "run-1", null, 5000L,
            pricingBook = book,
            resolveTokenizerId = { id -> id ?: "cloud:run-fallback" }, // null -> the run's cloud fallback
        )
        // entries[1] is the critic step (estimated = false -> CLOUD tier), tokensIn=22, tokensOut=6,
        // priced through the fallback rate exactly like a resolvable step: 22*300/1000 (=6) +
        // 6*1500/1000 (=9) = 15 — same math as the resolvable-model case below.
        assertEquals(15L, entries[1].estCostMinor)
        assertEquals(0L, entries[0].estCostMinor) // the on-device step is still never priced
    }

    @Test fun `a default-model step still prices zero when the run's fallback is genuinely on-device`() {
        // Zero cost must remain only for genuinely on-device work: when the run's fallback model
        // itself doesn't resolve to a cloud tokenizer id, PricingBook.priceFor honestly treats it
        // as on-device (never invents a price) even though this step's tier is CLOUD.
        nextId = 0
        val book = PricingBook().withFallback(UsagePricing(centsPer1kInput = 300, centsPer1kOutput = 1500))
        val entries = GraphRunLedger.toEntries(
            nullModelResult(), nullModelTreeNodes(), "loop-7", "run-1", null, 5000L,
            pricingBook = book,
            resolveTokenizerId = { id -> id ?: "gguf:run-fallback" }, // null -> the run's on-device fallback
        )
        assertTrue(entries.all { it.estCostMinor == 0L })
    }

    @Test fun `a null loopId (ad-hoc, unsaved graph run) carries through as null`() {
        nextId = 0
        val entries = GraphRunLedger.toEntries(result(), treeNodes(), loopId = null, runId = "run-1", projectId = null, timestampMillis = 1L)
        assertTrue(entries.all { it.loopId == null })
        assertNull(entries.first().loopId)
    }
}
