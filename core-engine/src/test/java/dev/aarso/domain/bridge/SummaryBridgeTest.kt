package dev.aarso.domain.bridge

import dev.aarso.domain.provenance.ProvenanceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the pure summary-bridge structural model — header framing, the carry-forward
 * selection heuristic, the cap budgets, bullet truncation, the empty case, and full payload wiring.
 * Deterministic: no clock, no random, so every assertion is exact.
 */
class SummaryBridgeTest {

    // ---- header() ----------------------------------------------------------------------------

    @Test
    fun header_modelSwitch_usesModelNoun() {
        val event = SwitchEvent(SwitchKind.MODEL, from = "Llama-3", to = "Claude")
        assertEquals("Switched model: Llama-3 → Claude", SummaryBridges.header(event))
    }

    @Test
    fun header_interactionModelSwitch_usesInteractionModelNoun() {
        val event = SwitchEvent(SwitchKind.INTERACTION_MODEL, from = "Single", to = "Council")
        assertEquals("Switched interaction model: Single → Council", SummaryBridges.header(event))
    }

    @Test
    fun header_rendersNamesVerbatim() {
        val event = SwitchEvent(SwitchKind.MODEL, from = "gpt-4o-mini", to = "gemini-1.5")
        val header = SummaryBridges.header(event)
        assertTrue(header.contains("gpt-4o-mini"))
        assertTrue(header.contains("gemini-1.5"))
        assertTrue(header.contains("→"))
    }

    @Test
    fun header_modelVsInteractionModel_differ() {
        val model = SummaryBridges.header(SwitchEvent(SwitchKind.MODEL, "A", "B"))
        val interaction =
            SummaryBridges.header(SwitchEvent(SwitchKind.INTERACTION_MODEL, "A", "B"))
        assertFalse(model == interaction)
        assertTrue(interaction.contains("interaction model"))
        assertFalse(model.contains("interaction"))
    }

    // ---- selectCarryForward() : empty ---------------------------------------------------------

    @Test
    fun select_emptyPrior_returnsEmpty() {
        assertTrue(SummaryBridges.selectCarryForward(emptyList()).isEmpty())
    }

    @Test
    fun select_zeroBulletsCap_returnsEmpty() {
        val turns = listOf(PriorTurn("user", "hello", 1))
        assertTrue(SummaryBridges.selectCarryForward(turns, maxBullets = 0).isEmpty())
    }

    @Test
    fun select_zeroTokenCap_returnsEmpty() {
        val turns = listOf(PriorTurn("user", "hello", 1))
        assertTrue(SummaryBridges.selectCarryForward(turns, maxTokens = 0).isEmpty())
    }

    // ---- selectCarryForward() : recency + first-user anchor -----------------------------------

    @Test
    fun select_prefersRecency_keepsNewestTurns() {
        val turns = (1..10).map { PriorTurn("assistant", "turn $it", 1) }
        val bullets = SummaryBridges.selectCarryForward(turns, maxBullets = 3, maxTokens = 1000)
        // No user turn, so purely recency: the three newest, chronological.
        assertEquals(3, bullets.size)
        assertEquals("assistant: turn 8", bullets[0])
        assertEquals("assistant: turn 9", bullets[1])
        assertEquals("assistant: turn 10", bullets[2])
    }

    @Test
    fun select_alwaysIncludesFirstUserTurn_evenWhenOld() {
        val turns = buildList {
            add(PriorTurn("user", "ORIGINAL GOAL", 1))
            for (i in 1..20) add(PriorTurn("assistant", "noise $i", 1))
        }
        val bullets = SummaryBridges.selectCarryForward(turns, maxBullets = 3, maxTokens = 1000)
        assertEquals(3, bullets.size)
        // The first user turn must survive despite being the oldest of 21 turns.
        assertEquals("user: ORIGINAL GOAL", bullets[0])
        // ...and the rest are the two newest, in chronological order.
        assertEquals("assistant: noise 19", bullets[1])
        assertEquals("assistant: noise 20", bullets[2])
    }

    @Test
    fun select_firstUserTurn_notDuplicatedWhenAlsoRecent() {
        val turns = listOf(
            PriorTurn("user", "only user turn", 1),
            PriorTurn("assistant", "reply", 1),
        )
        val bullets = SummaryBridges.selectCarryForward(turns, maxBullets = 5, maxTokens = 1000)
        assertEquals(2, bullets.size)
        assertEquals(1, bullets.count { it == "user: only user turn" })
    }

    @Test
    fun select_onlyFirstUserTurnAnchored_laterUserTurnsAreJustRecency() {
        val turns = listOf(
            PriorTurn("user", "first goal", 1),
            PriorTurn("assistant", "a1", 1),
            PriorTurn("user", "second ask", 1),
        )
        val bullets = SummaryBridges.selectCarryForward(turns, maxBullets = 2, maxTokens = 1000)
        // Anchor (first user) + newest (second ask). The middle assistant is dropped.
        assertEquals(2, bullets.size)
        assertEquals("user: first goal", bullets[0])
        assertEquals("user: second ask", bullets[1])
    }

    @Test
    fun select_resultIsChronological() {
        val turns = listOf(
            PriorTurn("user", "u0", 1),
            PriorTurn("assistant", "a1", 1),
            PriorTurn("assistant", "a2", 1),
            PriorTurn("assistant", "a3", 1),
        )
        val bullets = SummaryBridges.selectCarryForward(turns, maxBullets = 3, maxTokens = 1000)
        assertEquals(listOf("user: u0", "assistant: a2", "assistant: a3"), bullets)
    }

    // ---- selectCarryForward() : caps ----------------------------------------------------------

    @Test
    fun select_respectsMaxBullets() {
        val turns = (1..10).map { PriorTurn("assistant", "t$it", 1) }
        val bullets = SummaryBridges.selectCarryForward(turns, maxBullets = 4, maxTokens = 1000)
        assertEquals(4, bullets.size)
    }

    @Test
    fun select_respectsMaxTokens() {
        val turns = (1..10).map { PriorTurn("assistant", "t$it", 100) }
        // Budget 250 admits only two 100-token turns.
        val bullets = SummaryBridges.selectCarryForward(turns, maxBullets = 10, maxTokens = 250)
        assertEquals(2, bullets.size)
    }

    @Test
    fun select_oversizedTurnSkipped_smallerLaterTurnStillFits() {
        val turns = listOf(
            PriorTurn("assistant", "huge", 1000),
            PriorTurn("assistant", "tiny", 1),
        )
        // No user turn; recency order considers "tiny" (newest) first — it fits; "huge" is skipped.
        val bullets = SummaryBridges.selectCarryForward(turns, maxBullets = 5, maxTokens = 10)
        assertEquals(1, bullets.size)
        assertEquals("assistant: tiny", bullets[0])
    }

    @Test
    fun select_negativeTokenEstimateTreatedAsZero() {
        val turns = listOf(PriorTurn("assistant", "weird", -50))
        val bullets = SummaryBridges.selectCarryForward(turns, maxBullets = 5, maxTokens = 1)
        assertEquals(1, bullets.size)
    }

    @Test
    fun select_anchorWinsBudgetOverRecentTurns() {
        val turns = buildList {
            add(PriorTurn("user", "goal", 5))
            for (i in 1..5) add(PriorTurn("assistant", "noise $i", 5))
        }
        // Budget only fits one 5-token turn — the anchor must take it.
        val bullets = SummaryBridges.selectCarryForward(turns, maxBullets = 5, maxTokens = 5)
        assertEquals(1, bullets.size)
        assertEquals("user: goal", bullets[0])
    }

    // ---- bullet formatting / truncation -------------------------------------------------------

    @Test
    fun select_truncatesLongText_withEllipsis() {
        val longText = "x".repeat(200)
        val turns = listOf(PriorTurn("user", longText, 1))
        val bullets = SummaryBridges.selectCarryForward(turns, maxBullets = 1, maxTokens = 1000)
        val expectedBody = "x".repeat(80) + "…"
        assertEquals("user: $expectedBody", bullets[0])
    }

    @Test
    fun select_shortText_notTruncated_noEllipsis() {
        val turns = listOf(PriorTurn("assistant", "short", 1))
        val bullets = SummaryBridges.selectCarryForward(turns, maxBullets = 1, maxTokens = 1000)
        assertEquals("assistant: short", bullets[0])
        assertFalse(bullets[0].contains("…"))
    }

    @Test
    fun select_collapsesWhitespaceToSingleLine() {
        val turns = listOf(PriorTurn("user", "line one\n\tline   two", 1))
        val bullets = SummaryBridges.selectCarryForward(turns, maxBullets = 1, maxTokens = 1000)
        assertEquals("user: line one line two", bullets[0])
        assertFalse(bullets[0].contains("\n"))
    }

    @Test
    fun select_bulletStartsWithRole() {
        val turns = listOf(PriorTurn("assistant", "body", 1))
        val bullets = SummaryBridges.selectCarryForward(turns, maxBullets = 1, maxTokens = 1000)
        assertTrue(bullets[0].startsWith("assistant: "))
    }

    // ---- build() : wiring ---------------------------------------------------------------------

    @Test
    fun build_wiresHeaderFromEvent() {
        val bridge = SummaryBridges.build(
            event = SwitchEvent(SwitchKind.MODEL, "A", "B"),
            priorTurns = emptyList(),
            authorModel = null,
            authorProvenance = ProvenanceState.UNKNOWN,
        )
        assertEquals("Switched model: A → B", bridge.header)
    }

    @Test
    fun build_emptyPrior_fullPriorAvailableFalse_emptyCarryForward() {
        val bridge = SummaryBridges.build(
            event = SwitchEvent(SwitchKind.MODEL, "A", "B"),
            priorTurns = emptyList(),
            authorModel = "Claude",
            authorProvenance = ProvenanceState.CLOUD,
        )
        assertFalse(bridge.fullPriorAvailable)
        assertTrue(bridge.carriedForward.isEmpty())
    }

    @Test
    fun build_withPrior_fullPriorAvailableTrue() {
        val bridge = SummaryBridges.build(
            event = SwitchEvent(SwitchKind.MODEL, "A", "B"),
            priorTurns = listOf(PriorTurn("user", "hi", 1)),
            authorModel = "local-model",
            authorProvenance = ProvenanceState.LOCAL,
        )
        assertTrue(bridge.fullPriorAvailable)
        assertEquals(listOf("user: hi"), bridge.carriedForward)
    }

    @Test
    fun build_threadsAuthorModel() {
        val bridge = SummaryBridges.build(
            event = SwitchEvent(SwitchKind.INTERACTION_MODEL, "Single", "Council"),
            priorTurns = listOf(PriorTurn("user", "go", 1)),
            authorModel = "gemini-1.5",
            authorProvenance = ProvenanceState.CLOUD,
        )
        assertEquals("gemini-1.5", bridge.authorModel)
    }

    @Test
    fun build_threadsProvenance_cloudIsWatched() {
        val bridge = SummaryBridges.build(
            event = SwitchEvent(SwitchKind.MODEL, "A", "B"),
            priorTurns = emptyList(),
            authorModel = "Claude",
            authorProvenance = ProvenanceState.CLOUD,
        )
        assertEquals(ProvenanceState.CLOUD, bridge.authorProvenance)
        assertTrue(bridge.authorProvenance.watched)
    }

    @Test
    fun build_threadsProvenance_localNotWatched() {
        val bridge = SummaryBridges.build(
            event = SwitchEvent(SwitchKind.MODEL, "A", "B"),
            priorTurns = emptyList(),
            authorModel = "local-model",
            authorProvenance = ProvenanceState.LOCAL,
        )
        assertEquals(ProvenanceState.LOCAL, bridge.authorProvenance)
        assertFalse(bridge.authorProvenance.watched)
    }

    @Test
    fun build_nullAuthorModel_preserved() {
        val bridge = SummaryBridges.build(
            event = SwitchEvent(SwitchKind.MODEL, "A", "B"),
            priorTurns = emptyList(),
            authorModel = null,
            authorProvenance = ProvenanceState.UNKNOWN,
        )
        assertNull(bridge.authorModel)
    }

    @Test
    fun build_honoursCustomCaps() {
        val turns = (1..10).map { PriorTurn("assistant", "t$it", 1) }
        val bridge = SummaryBridges.build(
            event = SwitchEvent(SwitchKind.MODEL, "A", "B"),
            priorTurns = turns,
            authorModel = null,
            authorProvenance = ProvenanceState.UNKNOWN,
            maxBullets = 2,
            maxTokens = 1000,
        )
        assertEquals(2, bridge.carriedForward.size)
    }

    // ---- determinism --------------------------------------------------------------------------

    @Test
    fun select_isDeterministic_acrossRepeatedCalls() {
        val turns = buildList {
            add(PriorTurn("user", "goal", 3))
            for (i in 1..8) add(PriorTurn("assistant", "step $i", 3))
        }
        val first = SummaryBridges.selectCarryForward(turns, maxBullets = 4, maxTokens = 12)
        val second = SummaryBridges.selectCarryForward(turns, maxBullets = 4, maxTokens = 12)
        val third = SummaryBridges.selectCarryForward(turns, maxBullets = 4, maxTokens = 12)
        assertEquals(first, second)
        assertEquals(second, third)
    }

    @Test
    fun build_isDeterministic() {
        val event = SwitchEvent(SwitchKind.INTERACTION_MODEL, "Single", "Council")
        val turns = listOf(PriorTurn("user", "objective", 2), PriorTurn("assistant", "ok", 2))
        val a = SummaryBridges.build(event, turns, "Claude", ProvenanceState.CLOUD)
        val b = SummaryBridges.build(event, turns, "Claude", ProvenanceState.CLOUD)
        assertEquals(a, b)
    }
}
