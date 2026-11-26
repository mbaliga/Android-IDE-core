package dev.fonebrew.data

import dev.fonebrew.domain.GeneratedToken
import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.Role
import dev.fonebrew.domain.SamplingParams
import dev.fonebrew.domain.agent.AgentBudgets
import dev.fonebrew.domain.agent.AgentMessage
import dev.fonebrew.domain.agent.AgentSession
import dev.fonebrew.domain.agent.AgentWorkspace
import dev.fonebrew.domain.agent.BuiltinTools
import dev.fonebrew.domain.agent.CancelToken
import dev.fonebrew.domain.agent.FakeRepo
import dev.fonebrew.domain.agent.StopReason
import dev.fonebrew.domain.agent.TurnLimits
import dev.fonebrew.domain.cost.UsagePricing
import dev.fonebrew.domain.ledger.LedgerEntry
import dev.fonebrew.domain.ledger.Tier
import dev.fonebrew.domain.model.ModelSpec
import dev.fonebrew.domain.model.Runtime
import dev.fonebrew.domain.template.TemplateId
import dev.fonebrew.inference.InferenceEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The scripted fake engine behind the agent loop: ledger rows per step, priced cloud, free on-device. */
class AgentEngineTurnSourceTest {

    /** Streams each scripted reply one char-chunk at a time; counts tokens as chars/4. */
    private class ScriptedEngine(private val replies: List<String>) : InferenceEngine {
        override val tokenizerId = "cloud:test"
        override val supportsLogprobs = false
        override val supportsSamplingParams = false
        override var isLoaded = true
        var calls = 0
        val seen = mutableListOf<List<MessageNode>>()
        override suspend fun loadModel(modelPath: String, contextSize: Int) {}
        override suspend fun unload() {}
        override suspend fun countTokens(text: String) = (text.length / 4).coerceAtLeast(1)
        override fun generate(messages: List<MessageNode>, params: SamplingParams, sessionLoadPath: String?, sessionSavePath: String?): Flow<GeneratedToken> {
            seen += messages
            val text = replies[minOf(calls++, replies.size - 1)]
            return flow { text.chunked(8).forEach { emit(GeneratedToken(it)) } }
        }
    }

    private fun spec(cloud: Boolean) = ModelSpec(
        id = "m1", displayName = "M", family = "f", contextWindow = 8192, tokenizerId = "cloud:test",
        templateId = TemplateId.PLAIN, runtime = if (cloud) Runtime.CLOUD else Runtime.LOCAL_GGUF, providerId = if (cloud) "anthropic" else null,
    )

    private fun block(name: String, vararg a: Pair<String, String>) = "<<<TOOL $name>>>\n" + a.joinToString("") { "${it.first}: ${it.second}\n" } + "<<<END>>>"

    private suspend fun run(cloud: Boolean, rows: MutableList<LedgerEntry>, replies: List<String>, cancel: CancelToken = CancelToken(), budgets: AgentBudgets = AgentBudgets()) {
        val engine = ScriptedEngine(replies)
        val src = AgentEngineTurnSource(
            engine, spec(cloud), cancel, clock = { 1_000L }, idGen = { "run-${rows.size}" },
            appendLedger = { rows += it }, priceFor = { UsagePricing(100L, 200L) },
        )
        val ws = AgentWorkspace(FakeRepo(mapOf("a.txt" to "hello\n")), budgets)
        AgentSession(BuiltinTools.registry(ws), src, budgets, cancel = cancel).run("o", emptyMap(), ws)
    }

    @Test fun `every model call writes one surface-agent row, on-device free and flagged estimated`() = runTest {
        val rows = mutableListOf<LedgerEntry>()
        run(false, rows, listOf(block("list_dir"), block("read_file", "path" to "a.txt"), block("finish", "summary" to "ok")))
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.surface == "agent" && it.tier == Tier.ON_DEVICE && it.estCostMinor == 0L && it.estimated })
        assertEquals(3, rows.map { it.nodeId }.toSet().size) // one id per call
        assertTrue(rows.all { it.inputTokens > 0 && it.outputTokens > 0 })
    }

    @Test fun `cloud rows are tier cloud and priced, with tokens flagged estimated when the engine reports none`() = runTest {
        val rows = mutableListOf<LedgerEntry>()
        run(true, rows, listOf(block("finish", "summary" to "ok")))
        val e = rows.single()
        assertEquals(Tier.CLOUD, e.tier)
        assertEquals("anthropic", e.provider)
        assertTrue(e.estCostMinor > 0)
        assertTrue(e.estimated) // not a CloudEngine, so no provider-reported usage: never presented as exact
    }

    @Test fun `the transcript is a parent-linked chain with the model's own roles`() = runTest {
        val engine = ScriptedEngine(listOf(block("finish", "summary" to "x")))
        val src = AgentEngineTurnSource(engine, spec(false), CancelToken(), priceFor = { UsagePricing.ON_DEVICE })
        src.next(listOf(AgentMessage(Role.SYSTEM, "s"), AgentMessage(Role.USER, "u")), TurnLimits(10_000, 1_000))
        val chain = engine.seen.single()
        assertEquals(listOf(Role.SYSTEM, Role.USER), chain.map { it.role })
        assertEquals(null, chain[0].parentId)
        assertEquals(chain[0].id, chain[1].parentId)
    }

    @Test fun `streaming stops at the per-step token cap`() = runTest {
        val engine = ScriptedEngine(listOf("x".repeat(1000)))
        val src = AgentEngineTurnSource(engine, spec(false), CancelToken(), priceFor = { UsagePricing.ON_DEVICE })
        val r = src.next(listOf(AgentMessage(Role.USER, "u")), TurnLimits(100_000, 5))
        assertEquals(40, r.text.length) // 5 chunks of 8
    }

    @Test fun `cancelling while streaming stops the stream and the run reports it`() = runTest {
        val rows = mutableListOf<LedgerEntry>()
        val cancel = CancelToken()
        val engine = object : InferenceEngine by ScriptedEngine(listOf("")) {
            override fun generate(messages: List<MessageNode>, params: SamplingParams, sessionLoadPath: String?, sessionSavePath: String?) = flow {
                emit(GeneratedToken("<<<TOOL list_dir>>>\n")); cancel.cancel(); emit(GeneratedToken("<<<END>>>")); emit(GeneratedToken("tail"))
            }
        }
        val src = AgentEngineTurnSource(engine, spec(false), cancel, appendLedger = { rows += it }, priceFor = { UsagePricing.ON_DEVICE })
        val ws = AgentWorkspace(FakeRepo(emptyMap()))
        val out = AgentSession(BuiltinTools.registry(ws), src, cancel = cancel).run("o", emptyMap(), ws)
        assertEquals(StopReason.CANCELLED, out.stop)
        assertTrue(out.steps.single().calls.isEmpty()) // the half-streamed call was not run
        assertFalse(out.steps.single().note.isNullOrEmpty())
    }

    @Test fun `a ledger failure never fails the step`() = runTest {
        val engine = ScriptedEngine(listOf(block("finish", "summary" to "x")))
        val src = AgentEngineTurnSource(engine, spec(true), CancelToken(), appendLedger = { error("disk full") }, priceFor = { UsagePricing(1, 1) })
        val r = src.next(listOf(AgentMessage(Role.USER, "u")), TurnLimits(10_000, 1_000))
        assertTrue(r.text.contains("finish"))
    }
}
