package dev.fonebrew.inference

import dev.fonebrew.domain.GeneratedToken
import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.Role
import dev.fonebrew.domain.SamplingParams
import dev.fonebrew.domain.curation.Fidelity
import dev.fonebrew.domain.curation.FidelityReason
import dev.fonebrew.domain.curation.MessageFate
import dev.fonebrew.domain.curation.ResolvedFidelity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A scriptable [InferenceEngine] that records the exact messages it was asked to generate from
 *  and replies with a fixed [reply], so tests can assert on both the prompt EngineCompactionAgent
 *  built and the text it hands back — no native/network dependency, pure JVM. */
private class RecordingEngine(private val reply: String) : InferenceEngine {
    override val tokenizerId: String = "recording"
    override val supportsLogprobs: Boolean = false
    override val supportsSamplingParams: Boolean = false
    override var isLoaded: Boolean = true
    val calls = mutableListOf<List<MessageNode>>()

    override suspend fun loadModel(modelPath: String, contextSize: Int) { isLoaded = true }
    override suspend fun unload() { isLoaded = false }
    override suspend fun countTokens(text: String): Int = text.length

    override fun generate(
        messages: List<MessageNode>,
        params: SamplingParams,
        sessionLoadPath: String?,
        sessionSavePath: String?,
    ): Flow<GeneratedToken> {
        calls += messages
        return flow { emit(GeneratedToken(text = reply)) }
    }
}

class EngineCompactionAgentTest {

    private fun original(content: String) = MessageNode(
        id = "m1", parentId = null, role = Role.ASSISTANT, content = content, createdAt = 0L,
    )

    private fun resolution(fidelity: Fidelity) = ResolvedFidelity(
        fidelity = fidelity, mustInclude = false, isFailureTombstone = false, reason = FidelityReason.DEFAULT_ORDINARY,
    )

    @Test fun `GIST returns the engine's trimmed reply`() = runTest {
        val engine = RecordingEngine(reply = "  the one-line gist  ")
        val agent = EngineCompactionAgent(engine)
        val result = agent.compact(original("a long message"), MessageFate.GIST, resolution(Fidelity.F1))
        assertEquals("the one-line gist", result)
    }

    @Test fun `the original message's content is passed to the engine verbatim as the user turn`() = runTest {
        val engine = RecordingEngine(reply = "gist")
        val agent = EngineCompactionAgent(engine)
        agent.compact(original("EXACT original text"), MessageFate.GIST, resolution(Fidelity.F1))
        val userMsg = engine.calls.single().last()
        assertEquals(Role.USER, userMsg.role)
        assertEquals("EXACT original text", userMsg.content)
    }

    @Test fun `each fate uses a distinct system instruction`() = runTest {
        val engine = RecordingEngine(reply = "x")
        val agent = EngineCompactionAgent(engine)
        agent.compact(original("c"), MessageFate.GIST, resolution(Fidelity.F1))
        agent.compact(original("c"), MessageFate.FAITHFUL, resolution(Fidelity.F2))
        agent.compact(original("c"), MessageFate.TOMBSTONE, resolution(Fidelity.F1))
        val instructions = engine.calls.map { call -> call.first { it.role == Role.SYSTEM }.content }
        assertEquals(3, instructions.toSet().size) // all three distinct
        assertTrue(instructions[1].contains("Paraphrase", ignoreCase = true))
        assertTrue(instructions[2].contains("wrong", ignoreCase = true))
    }

    @Test fun `a blank reply falls back to a labelled placeholder, never an empty string`() = runTest {
        val engine = RecordingEngine(reply = "   ")
        val agent = EngineCompactionAgent(engine)
        val result = agent.compact(original("c"), MessageFate.GIST, resolution(Fidelity.F1))
        assertTrue(result.isNotBlank())
        assertTrue(result.contains("GIST"))
    }

    @Test fun `compact throws for KEPT_VERBATIM -- CompactionEngine must never call it for this fate`() = runTest {
        val agent = EngineCompactionAgent(RecordingEngine(reply = "x"))
        try {
            agent.compact(original("c"), MessageFate.KEPT_VERBATIM, resolution(Fidelity.F3))
            org.junit.Assert.fail("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("KEPT_VERBATIM"))
        }
    }

    @Test fun `compact throws for DROPPED -- CompactionEngine must never call it for this fate`() = runTest {
        val agent = EngineCompactionAgent(RecordingEngine(reply = "x"))
        try {
            agent.compact(original("c"), MessageFate.DROPPED, resolution(Fidelity.F0))
            org.junit.Assert.fail("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("DROPPED"))
        }
    }

    @Test fun `runs correctly over the real EchoInferenceEngine, not just a fake`() = runTest {
        val echo = EchoInferenceEngine()
        val agent = EngineCompactionAgent(echo)
        val result = agent.compact(original("hello world"), MessageFate.GIST, resolution(Fidelity.F1))
        assertTrue(result.contains("hello world"))
    }
}
