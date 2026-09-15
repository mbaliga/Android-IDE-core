package dev.fonebrew.domain.loop

import dev.fonebrew.domain.bpmn.BpmnEdge
import dev.fonebrew.domain.bpmn.BpmnGraph
import dev.fonebrew.domain.bpmn.BpmnNode
import dev.fonebrew.domain.bpmn.BpmnNodeKind
import dev.fonebrew.domain.council.Generator
import dev.fonebrew.domain.cost.PricingBook
import dev.fonebrew.domain.cost.UsagePricing
import dev.fonebrew.domain.ledger.Tier
import kotlinx.coroutines.test.runTest
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

    // ── tokenCounter wiring closes the loop (asoc-reachability audit, 2026-09-15): these run a
    // REAL GraphRunner with a tokenCounter shaped exactly like LoopRoom.kt's real one — a
    // provider-authoritative StepTokens for a "cloud" step, null (the engine truly reported
    // nothing) for an "on-device"/engine-silent step — then feed the actual GraphRunResult
    // through GraphRunLedger, pinning that the two honesty paths a chat turn already gets
    // (ChatViewModel.kt's CloudEngine.lastUsage vs. countTokens pattern) now also hold for a loop
    // run end to end, not just at the GraphStep/GraphRunLedger unit boundary above. ─────────────

    private fun twoStepGraph() = BpmnGraph(
        id = "g2",
        nodes = listOf(
            BpmnNode("start", BpmnNodeKind.START_EVENT),
            BpmnNode("silent", BpmnNodeKind.TASK, "Silent", ext = mapOf("model" to "local-gguf")),
            BpmnNode("counted", BpmnNodeKind.TASK, "Counted", ext = mapOf("model" to "claude")),
            BpmnNode("end", BpmnNodeKind.END_EVENT),
        ),
        edges = listOf(
            BpmnEdge("e1", "start", "silent"),
            BpmnEdge("e2", "silent", "counted"),
            BpmnEdge("e3", "counted", "end"),
        ),
    )

    @Test fun `a real run's engine-silent step stays estimated with zero cost, and its real-counts step prices correctly`() = runTest {
        // The stateful wrapper LoopRoom.kt's real wiring uses: generatorFor sets which node is
        // "current" the instant it resolves a generator, tokenCounter reads it right after —
        // safe because GraphRunner walks one step at a time, never concurrently. "silent" stands
        // in for an on-device/engine-silent step (tokenCounter reports null — the engine truly
        // has nothing); "counted" stands in for a cloud step whose engine reported a real,
        // provider-authoritative count.
        var currentNodeId: String? = null
        val result = GraphRunner(
            generatorFor = { node -> currentNodeId = node.id; Generator { _, _ -> if (node.id == "silent") "local draft" else "APPROVE, ship it" } },
            tokenCounter = { _, _, _ ->
                if (currentNodeId == "counted") StepTokens(inputTokens = 22, outputTokens = 6, estimated = false) else null
            },
        ).run(twoStepGraph(), objective = "make it airtight")

        assertTrue(result.reachedEnd)
        assertEquals(2, result.steps.size)

        val silentStep = result.steps.single { it.nodeId == "silent" }
        assertNull(silentStep.tokensIn); assertNull(silentStep.tokensOut); assertTrue(silentStep.estimated)

        val countedStep = result.steps.single { it.nodeId == "counted" }
        assertEquals(22L, countedStep.tokensIn); assertEquals(6L, countedStep.tokensOut); assertTrue(!countedStep.estimated)

        val runId = "run-2"
        val nodes = GraphRunLog.toNodes(
            objective = "make it airtight", result = result, loopRunId = runId, loopId = "loop-9",
            now = 1000L, idGen = { "n${nextId++}" },
        )
        nextId = 0
        val book = PricingBook().with("cloud:claude-x", UsagePricing(centsPer1kInput = 300, centsPer1kOutput = 1500))
        val entries = GraphRunLedger.toEntries(
            result, nodes, loopId = "loop-9", runId = runId, projectId = null, timestampMillis = 5000L,
            pricingBook = book,
            resolveTokenizerId = { id -> if (id == "claude") "cloud:claude-x" else id },
        )
        val silentEntry = entries.first { it.model == "local-gguf" }
        assertEquals(Tier.ON_DEVICE, silentEntry.tier)
        assertTrue(silentEntry.estimated)
        assertEquals(0L, silentEntry.inputTokens); assertEquals(0L, silentEntry.outputTokens)
        assertEquals(0L, silentEntry.estCostMinor)

        val countedEntry = entries.first { it.model == "claude" }
        assertEquals(Tier.CLOUD, countedEntry.tier)
        assertTrue(!countedEntry.estimated)
        assertEquals(22L, countedEntry.inputTokens); assertEquals(6L, countedEntry.outputTokens)
        // 22*300/1000 (=6) + 6*1500/1000 (=9) = 15, the same math as the fixture-based pricing
        // test above — now derived from a real GraphRunner run instead of a hand-built GraphStep.
        assertEquals(15L, countedEntry.estCostMinor)
    }
}
