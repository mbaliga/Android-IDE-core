package dev.fonebrew.ui

import dev.fonebrew.domain.GeneratedToken
import dev.fonebrew.domain.Role
import dev.fonebrew.domain.ledger.Status
import dev.fonebrew.domain.ledger.Tier
import dev.fonebrew.domain.thread.MessageConfidence
import dev.fonebrew.inference.aiCoreNanoSpec
import dev.fonebrew.ui.chatharness.ChatHarness
import dev.fonebrew.ui.chatharness.MainDispatcherRule
import dev.fonebrew.ui.chatharness.observe
import dev.fonebrew.ui.chatharness.ofRole
import dev.fonebrew.ui.chatharness.settle
import dev.fonebrew.ui.chatharness.toks
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test

/** Streaming, Stop, and engine failure, through the real [ChatViewModel]. */
class ChatViewModelStreamingTest {
    @get:Rule val main = MainDispatcherRule()

    private val scored = listOf(
        GeneratedToken("The", logprob = -0.25f, entropy = 0.5f),
        GeneratedToken(" cat", logprob = -1.5f, entropy = 2.0f),
        GeneratedToken(" sat", logprob = -0.75f, entropy = 1.0f),
    )

    @Test fun `streamed tokens reach state in order with logprob and entropy from the first token`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.echoA.script = scored
        h.echoA.pauseAfter = 2 // park the engine after the first two tokens
        val vm = h.newViewModel(); observe(vm); settle()

        vm.send("go")
        settle()

        val live = vm.uiState.value
        assertTrue(live.isGenerating)
        assertEquals(GenPhase.GENERATING, live.genPhase)
        assertEquals(scored.take(2), live.streamingTokens) // exact objects: text AND logprob AND entropy
        assertEquals("The cat", live.streamingText)

        h.echoA.release()
        settle()

        val done = vm.uiState.value
        assertFalse(done.isGenerating)
        assertEquals(GenPhase.IDLE, done.genPhase)
        assertTrue(done.streamingTokens.isEmpty())
        assertEquals(scored, done.lastTurnTokens) // kept past the reset for the Instruments heatmap
        assertEquals("The cat sat", h.nodes().ofRole(Role.ASSISTANT).single().content)
    }

    @Test fun `a turn with entropy is stamped with its mean confidence and one without is never given a number`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        val vm = h.newViewModel(); observe(vm); settle()

        h.echoA.script = scored
        vm.send("scored"); settle()
        h.echoA.script = toks("plain", " text") // no logprob, no entropy
        vm.send("unscored"); settle()

        val replies = h.nodes().ofRole(Role.ASSISTANT)
        assertEquals(2, replies.size)
        assertNotNull(replies[0].metadata[MessageConfidence.METADATA_KEY])
        assertNull("the engine never invents a confidence it was not given", replies[1].metadata[MessageConfidence.METADATA_KEY])
    }

    @Test fun `Stop mid-stream keeps the partial reply, marks it stopped, and leaves a consistent tree`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.echoA.script = toks("one ", "two ", "three")
        h.echoA.pauseAfter = 2
        val vm = h.newViewModel(); observe(vm); settle()

        vm.send("count")
        settle()
        vm.stopGeneration()
        settle()

        val nodes = h.nodes()
        assertEquals(listOf(Role.USER, Role.ASSISTANT), nodes.map { it.role })
        assertEquals("one two", nodes[1].content)
        assertEquals("true", nodes[1].metadata["stopped"])
        assertEquals(nodes[0].id, nodes[1].parentId)
        val ui = vm.uiState.value
        assertFalse(ui.isGenerating)
        assertEquals(GenPhase.IDLE, ui.genPhase)
        assertNull("a user Stop is not an error", ui.error)
        assertEquals(nodes[1].id, h.session.activeLeafId.value)
        assertEquals(Status.STOPPED.name, h.ledgerDao.rows.value.single().status)
        assertEquals(1, h.echoA.generateCalls)
    }

    @Test fun `Stop before any token arrives keeps the user turn and writes no reply`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.echoA.script = toks("never")
        h.echoA.pauseAfter = 0
        val vm = h.newViewModel(); observe(vm); settle()

        vm.send("hello")
        settle()
        vm.stopGeneration()
        settle()

        assertEquals(listOf(Role.USER), h.nodes().map { it.role })
        assertTrue(vm.uiState.value.canRegenerate)
        assertEquals(GenPhase.IDLE, vm.uiState.value.genPhase)
    }

    @Test fun `the app is usable after a Stop - the next send streams a fresh complete reply`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.echoA.script = toks("a", "b")
        h.echoA.pauseAfter = 1
        val vm = h.newViewModel(); observe(vm); settle()
        vm.send("first"); settle()
        vm.stopGeneration(); settle()

        h.echoA.pauseAfter = null
        h.echoA.script = toks("full ", "answer")
        vm.send("second"); settle()

        val replies = h.nodes().ofRole(Role.ASSISTANT)
        assertEquals(listOf("a", "full answer"), replies.map { it.content })
        assertNull(replies[1].metadata["stopped"])
    }

    @Test fun `an engine failure mid-stream surfaces as an error and never as an assistant answer`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.echoA.script = toks("par", "tial")
        h.echoA.failAfterScript = IllegalStateException("model crashed")
        val vm = h.newViewModel(); observe(vm); settle()

        vm.send("question")
        settle()

        val nodes = h.nodes()
        assertEquals("only the user's turn is persisted - no fake answer", listOf(Role.USER), nodes.map { it.role })
        val ui = vm.uiState.value
        assertEquals("model crashed", ui.error)
        assertTrue("Retry is offered", ui.canRegenerate)
        assertFalse(ui.isGenerating)
        assertEquals(GenPhase.IDLE, ui.genPhase)
        assertTrue(ui.streamingTokens.isEmpty())
        assertEquals(nodes[0].id, h.session.activeLeafId.value)
    }

    @Test fun `a model load failure is a legible error with no generation attempted`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.echoA.failLoad = java.io.IOException("cannot map weights")
        val vm = h.newViewModel(); observe(vm); settle()

        vm.send("hi"); settle()

        assertEquals("cannot map weights", vm.uiState.value.error)
        assertEquals(0, h.echoA.generateCalls)
        assertEquals(listOf(Role.USER), h.nodes().map { it.role })
    }

    @Test fun `retry after a failure answers the same user turn without duplicating it`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.echoA.failLoad = java.io.IOException("boom")
        val vm = h.newViewModel(); observe(vm); settle()
        vm.send("ask once"); settle()
        assertNotNull(vm.uiState.value.error)

        h.echoA.failLoad = null
        h.echoA.script = toks("recovered")
        vm.regenerate(); settle()

        val nodes = h.nodes()
        assertEquals(listOf(Role.USER, Role.ASSISTANT), nodes.map { it.role })
        assertEquals("recovered", nodes[1].content)
        assertEquals(nodes[0].id, nodes[1].parentId)
        assertNull(vm.uiState.value.error)
    }

    @Test fun `a second send while a turn is generating is ignored`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.echoA.script = toks("x", "y")
        h.echoA.pauseAfter = 1
        val vm = h.newViewModel(); observe(vm); settle()
        vm.send("first"); settle()

        vm.send("second"); settle()

        assertEquals(listOf("first"), h.nodes().ofRole(Role.USER).map { it.content })
        h.echoA.release(); settle()
        assertEquals(1, h.echoA.generateCalls)
    }

    @Test fun `blank input is not sent`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        val vm = h.newViewModel(); observe(vm); settle()
        vm.send("   \n "); settle()
        assertTrue(h.nodes().isEmpty())
        assertEquals(0, h.echoA.generateCalls)
    }

    // Regression: the tier was once keyed on runtime == LOCAL_GGUF, which wrote Gemini Nano and
    // Echo turns to the ledger as CLOUD.
    @Test fun `an on-device Gemini Nano turn is ledgered as ON_DEVICE`() = runTest(main.dispatcher) {
        val nano = aiCoreNanoSpec()
        val h = ChatHarness(specs = listOf(nano))
        h.engines.bySpecId[nano.id] = h.echoA
        h.echoA.script = toks("on device")
        val vm = h.newViewModel(); observe(vm); settle()

        vm.send("hi"); settle()

        assertEquals(Tier.ON_DEVICE.name, h.ledgerDao.rows.value.single().tier)
    }

    @Test fun `each finished on-device turn writes one honest ledger entry and token counts for both nodes`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.echoA.script = toks("twelve chars")
        val vm = h.newViewModel(); observe(vm); settle()

        vm.send("abcdefgh"); settle() // 8 chars -> 2 tokens under the scripted counter

        val ledger = h.ledgerDao.rows.value.single()
        assertEquals("on-device", ledger.provider)
        assertEquals("echo-a", ledger.model)
        assertEquals(Status.COMPLETE.name, ledger.status)
        assertEquals(0L, ledger.estCostMinor)
        val (user, reply) = h.nodes()
        assertEquals(2, h.tokenDao.counts.single { it.nodeId == user.id }.count)
        assertEquals(3, h.tokenDao.counts.single { it.nodeId == reply.id }.count)
        assertEquals("echo:a", h.tokenDao.counts.single { it.nodeId == reply.id }.tokenizerId)
    }
}
