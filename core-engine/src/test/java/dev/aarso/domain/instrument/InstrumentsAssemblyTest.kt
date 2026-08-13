package dev.aarso.domain.instrument

import dev.aarso.domain.GeneratedToken
import dev.aarso.domain.inspect.Availability
import dev.aarso.domain.ledger.InteractionModel
import dev.aarso.domain.ledger.LedgerEntry
import dev.aarso.domain.ledger.Provenance
import dev.aarso.domain.ledger.Status
import dev.aarso.domain.ledger.Tier
import dev.aarso.domain.scope.ContextAssembly
import dev.aarso.domain.scope.CorpusSource
import dev.aarso.domain.scope.Scope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP7 (THREAD_TOPOLOGY_PLAN.md): the pure glue behind the Instruments panel —
 * [InstrumentsAssembly.assembleConversation] (wiring `ContextAssembly` to a conversation
 * path for the first time), [InstrumentsAssembly.tokenScores] (the honest
 * `GeneratedToken` → `TokenScore`/`Availability` bridge), and
 * [InstrumentsAssembly.totalsForChat] (the ledger fold scoped to one conversation).
 */
class InstrumentsAssemblyTest {

    private fun turn(id: String, tokens: Int, pinned: Boolean = false) =
        InstrumentsAssembly.PathTurn(nodeId = id, tokenCount = tokens, label = "label-$id", pinned = pinned)

    // ---- assembleConversation --------------------------------------------------------

    @Test
    fun assembleConversation_verbatimWhenEverythingFits() {
        val turns = listOf(turn("a", 100), turn("b", 200))
        val result = InstrumentsAssembly.assembleConversation(
            turns = turns,
            convId = "chat-1",
            projectId = "proj-1",
            budget = ContextAssembly.ContextBudget(total = 1000, reserved = 0),
        )
        assertEquals(ContextAssembly.AssemblyMode.Verbatim, result.mode)
        assertEquals(Scope.ThisProject, result.scope)
        assertEquals(2, result.included.size)
        assertTrue(result.cut.isEmpty())
        assertFalse(result.meter.overBudget)
        // Attribution: every piece is filed under the project bucket and carries its own
        // conversation source — the legibility ledger's whole point.
        result.included.forEach { piece ->
            assertEquals("proj-1", piece.projectId)
            assertEquals(CorpusSource.Conversation("chat-1", piece.id), piece.source)
        }
    }

    @Test
    fun assembleConversation_newestRecencyRankIsZero() {
        val turns = listOf(turn("old", 10), turn("mid", 10), turn("new", 10))
        val result = InstrumentsAssembly.assembleConversation(
            turns = turns,
            convId = "c",
            projectId = "p",
            budget = ContextAssembly.ContextBudget(total = 1000, reserved = 0),
        )
        val byId = result.included.associateBy { it.id }
        assertEquals(2, byId.getValue("old").recencyRank)
        assertEquals(1, byId.getValue("mid").recencyRank)
        assertEquals(0, byId.getValue("new").recencyRank)
    }

    @Test
    fun assembleConversation_prioritizedTruncation_keepsNewestFirst() {
        val turns = listOf(turn("old", 700), turn("mid", 700), turn("new", 700))
        val result = InstrumentsAssembly.assembleConversation(
            turns = turns,
            convId = "c",
            projectId = "p",
            budget = ContextAssembly.ContextBudget(total = 1500, reserved = 0),
        )
        assertEquals(ContextAssembly.AssemblyMode.PrioritizedTruncation, result.mode)
        assertEquals(setOf("new", "mid"), result.included.map { it.id }.toSet())
        assertEquals(listOf("old"), result.cut.map { it.id })
    }

    @Test
    fun assembleConversation_pinnedSurvivesPastBudget_andFlagsOverBudgetHonestly() {
        val turns = listOf(turn("old", 1500, pinned = true), turn("new", 200))
        val result = InstrumentsAssembly.assembleConversation(
            turns = turns,
            convId = "c",
            projectId = "p",
            budget = ContextAssembly.ContextBudget(total = 1000, reserved = 0),
        )
        assertEquals(listOf("old"), result.included.map { it.id })
        assertEquals(listOf("new"), result.cut.map { it.id })
        assertTrue(result.meter.overBudget)
        // The meter never overstates the fraction past 1.0 even though pins pushed it over.
        assertEquals(1.0, result.meter.fractionUsed, 1e-9)
    }

    // ---- tokenScores --------------------------------------------------------------

    @Test
    fun tokenScores_emptyIsUnavailable() {
        val (scores, availability) = InstrumentsAssembly.tokenScores(emptyList())
        assertTrue(scores.isEmpty())
        assertEquals(Availability.UNAVAILABLE, availability)
    }

    @Test
    fun tokenScores_fullWhenEveryTokenCarriesBoth() {
        val tokens = listOf(
            GeneratedToken("a", logprob = -0.1f, entropy = 0.2f),
            GeneratedToken("b", logprob = -0.3f, entropy = 0.5f),
        )
        val (scores, availability) = InstrumentsAssembly.tokenScores(tokens)
        assertEquals(Availability.FULL, availability)
        assertEquals(2, scores.size)
        assertEquals("a", scores[0].token)
        assertEquals(-0.1, scores[0].logprob, 1e-6)
        assertEquals(0.2, scores[0].entropy, 1e-6)
        assertEquals("b", scores[1].token)
    }

    @Test
    fun tokenScores_unavailableWhenAnyTokenMissingEntropyOrLogprob() {
        val tokens = listOf(
            GeneratedToken("a", logprob = -0.1f, entropy = 0.2f),
            GeneratedToken("b", logprob = null, entropy = null),
        )
        val (scores, availability) = InstrumentsAssembly.tokenScores(tokens)
        assertEquals(Availability.UNAVAILABLE, availability)
        assertTrue(scores.isEmpty())
    }

    // ---- totalsForChat --------------------------------------------------------------

    private fun entry(chatId: String, input: Long, output: Long) = LedgerEntry(
        timestampMillis = 0L,
        projectId = null,
        chatId = chatId,
        nodeId = "n-$chatId-$input-$output",
        model = "m",
        provider = "on-device",
        provenance = Provenance.LOCAL,
        interactionModel = InteractionModel.SINGLE,
        councilMemberId = null,
        inputTokens = input,
        outputTokens = output,
        estCostMinor = 0,
        latencyMs = 0,
        tier = Tier.ON_DEVICE,
        status = Status.COMPLETE,
        estimated = true,
    )

    @Test
    fun totalsForChat_filtersToOneConversation() {
        val entries = listOf(entry("chat-1", 10, 20), entry("chat-2", 100, 200), entry("chat-1", 5, 5))
        val totals = InstrumentsAssembly.totalsForChat(entries, "chat-1")
        assertEquals(15L, totals.inputTokens)
        assertEquals(25L, totals.outputTokens)
        assertEquals(2, totals.turns)
    }

    @Test
    fun totalsForChat_emptyWhenNoMatch() {
        val totals = InstrumentsAssembly.totalsForChat(emptyList(), "chat-1")
        assertEquals(0L, totals.inputTokens)
        assertEquals(0L, totals.outputTokens)
        assertEquals(0, totals.turns)
    }
}
