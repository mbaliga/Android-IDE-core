package dev.aarso.domain.curation

import dev.aarso.domain.MessageNode
import dev.aarso.domain.Role
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactionEngineTest {

    private fun node(id: String, content: String) = MessageNode(
        id = id, parentId = null, role = Role.ASSISTANT, content = content, createdAt = 0L,
    )

    /** A well-behaved agent: never touches F3 (the engine doesn't call it for F3 anyway), gists everything else to a fixed marker so tests can assert on it without caring about prose quality. */
    private val honestAgent = CompactionAgent { _, fate, _ -> "[$fate]" }

    /** A misbehaving agent used only to prove the mechanical verifier catches a broken contract — it's never asked to touch KEPT_VERBATIM messages, so the only way this could matter is a verifier bug, which is exactly what this test guards against. */
    private val tamperingAgent = CompactionAgent { original, fate, _ ->
        if (fate == MessageFate.KEPT_VERBATIM) "TAMPERED: ${original.content}" else "[$fate]"
    }

    @Test fun `a run with no signals gists every ordinary message`() = runTest {
        val messages = listOf(node("m1", "hello"), node("m2", "world"))
        val result = CompactionEngine.run(
            messages = messages,
            directives = emptyMap(),
            verdicts = emptyMap(),
            bookmarkedIds = emptySet(),
            versionSpineIds = emptySet(),
            agent = honestAgent,
            now = 1000L,
        )
        val success = result as CompactionRunResult.Success
        assertEquals(listOf(MessageFate.GIST, MessageFate.GIST), success.receipt.entries.map { it.fate })
    }

    @Test fun `a plus-2 verdict message is kept byte-for-byte verbatim, the agent is never asked to touch it`() = runTest {
        var agentCalls = 0
        val trackingAgent = CompactionAgent { _, fate, _ -> agentCalls++; "[$fate]" }
        val messages = listOf(node("m1", "the exact original text"))
        val result = CompactionEngine.run(
            messages = messages,
            directives = emptyMap(),
            verdicts = mapOf("m1" to Verdict("m1", 2, 0L)),
            bookmarkedIds = emptySet(),
            versionSpineIds = emptySet(),
            agent = trackingAgent,
            now = 1000L,
        )
        val success = result as CompactionRunResult.Success
        assertEquals("the exact original text", success.receipt.entries.single().text)
        assertEquals(0, agentCalls)
    }

    @Test fun `a tampering agent that alters F3 content is caught and the run fails loudly`() = runTest {
        // Force a fate through KEPT_VERBATIM's actual code path by having the engine keep it
        // verbatim itself (per contract, the engine never even calls the agent for F3) — so to
        // exercise the verifier's own defense-in-depth, we simulate a hypothetically broken
        // engine by verifying directly against a tampered CompactedMessage list.
        val original = node("m1", "the exact original text")
        val tampered = CompactedMessage(
            msgId = "m1",
            fate = MessageFate.KEPT_VERBATIM,
            resolution = ResolvedFidelity(Fidelity.F3, mustInclude = false, isFailureTombstone = false, reason = FidelityReason.VERDICT_POSITIVE),
            text = "TAMPERED: the exact original text",
        )
        val violations = CompactionVerifier.verify(listOf(original), listOf(tampered))
        assertEquals(1, violations.size)
        assertEquals("m1", violations.single().msgId)
        assertEquals("the exact original text", violations.single().original)
    }

    @Test fun `a minus-2 verdict message is tombstoned, not gisted as ordinary`() = runTest {
        val messages = listOf(node("m1", "a wrong approach"))
        val result = CompactionEngine.run(
            messages = messages,
            directives = emptyMap(),
            verdicts = mapOf("m1" to Verdict("m1", -2, 0L)),
            bookmarkedIds = emptySet(),
            versionSpineIds = emptySet(),
            agent = honestAgent,
            now = 1000L,
        )
        val success = result as CompactionRunResult.Success
        assertEquals(MessageFate.TOMBSTONE, success.receipt.entries.single().fate)
    }

    @Test fun `a dropped F0 message produces no agent call and null text`() = runTest {
        var agentCalls = 0
        val trackingAgent = CompactionAgent { _, fate, _ -> agentCalls++; "[$fate]" }
        val directive = CompactionDirective("m1", mustInclude = false, fidelity = Fidelity.F0)
        val result = CompactionEngine.run(
            messages = listOf(node("m1", "droppable")),
            directives = mapOf("m1" to directive),
            verdicts = emptyMap(),
            bookmarkedIds = emptySet(),
            versionSpineIds = emptySet(),
            agent = trackingAgent,
            now = 1000L,
        )
        val success = result as CompactionRunResult.Success
        assertEquals(MessageFate.DROPPED, success.receipt.entries.single().fate)
        assertEquals(null, success.receipt.entries.single().text)
        assertEquals(0, agentCalls)
    }

    @Test fun `the receipt counts how many directives were actually honored`() = runTest {
        val d1 = CompactionDirective("m1", mustInclude = false, fidelity = Fidelity.F2)
        val d2 = CompactionDirective("m2", mustInclude = false, fidelity = Fidelity.F3)
        val result = CompactionEngine.run(
            messages = listOf(node("m1", "a"), node("m2", "b")),
            directives = mapOf("m1" to d1, "m2" to d2),
            verdicts = emptyMap(),
            bookmarkedIds = emptySet(),
            versionSpineIds = emptySet(),
            agent = honestAgent,
            now = 1000L,
        )
        val success = result as CompactionRunResult.Success
        // Both directives are honored by construction (a user directive always wins per the
        // contract) - this asserts the counting mechanism itself, which matters once directives
        // can conflict with something stronger in a future revision of the contract.
        assertEquals(2, success.receipt.directivesTotal)
        assertEquals(2, success.receipt.directivesHonored)
    }

    @Test fun `directives for messages outside this run's batch don't count toward the receipt total`() = runTest {
        // Regression test for a bug adversarial review found: `directives` is keyed by the
        // caller's full directive map, which (once a Compaction Preview submits only a windowed
        // subset of the conversation) can carry entries for messages this run never sees. Those
        // can never appear in `entries`, so counting them toward directivesTotal without a
        // matching honored count under-reported the receipt's "n/n honored" trust figure even
        // when nothing in this run actually violated anything.
        val inBatch = CompactionDirective("m1", mustInclude = false, fidelity = Fidelity.F2)
        val outOfBatch = CompactionDirective("m-not-in-this-run", mustInclude = false, fidelity = Fidelity.F3)
        val result = CompactionEngine.run(
            messages = listOf(node("m1", "a")),
            directives = mapOf("m1" to inBatch, "m-not-in-this-run" to outOfBatch),
            verdicts = emptyMap(),
            bookmarkedIds = emptySet(),
            versionSpineIds = emptySet(),
            agent = honestAgent,
            now = 1000L,
        )
        val success = result as CompactionRunResult.Success
        assertEquals(1, success.receipt.directivesTotal)
        assertEquals(1, success.receipt.directivesHonored)
    }

    @Test fun `verify returns no violations for a well-behaved run`() = runTest {
        val messages = listOf(node("m1", "verbatim text"))
        val result = CompactionEngine.run(
            messages = messages,
            directives = emptyMap(),
            verdicts = mapOf("m1" to Verdict("m1", 2, 0L)),
            bookmarkedIds = emptySet(),
            versionSpineIds = emptySet(),
            agent = tamperingAgent, // never called for F3 messages, so tampering is moot here
            now = 1000L,
        )
        assertTrue(result is CompactionRunResult.Success)
    }
}
