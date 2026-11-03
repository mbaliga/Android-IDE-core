package dev.fonebrew.domain.loop.authoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drag-a-wire's pure decision core -- LoopCanvas's touch mechanics are owner-verified, this
 *  covers everything they hand off to: [WireDragGesture]. */
class WireDragGestureTest {

    @Test
    fun `begin reaches AWAITING_DESTINATION with the source recorded`() {
        val draft = WireDragGesture.begin("proposer")
        assertEquals(ConnectionDraftState.AWAITING_DESTINATION, draft.state)
        assertEquals("proposer", draft.sourceNodeId)
    }

    @Test
    fun `release onto a different node connects, via ChooseDestination`() {
        val draft = WireDragGesture.begin("proposer")
        val outcome = WireDragGesture.release(draft, "critic")
        assertEquals(WireDragGesture.Outcome.Connect("proposer", "critic"), outcome)
    }

    @Test
    fun `release onto empty canvas cancels`() {
        val draft = WireDragGesture.begin("proposer")
        val outcome = WireDragGesture.release(draft, null)
        assertEquals(WireDragGesture.Outcome.Cancelled, outcome)
    }

    @Test
    fun `release back onto the drag's own source cancels, same no-op as the tap flow's from equals id guard`() {
        val draft = WireDragGesture.begin("proposer")
        val outcome = WireDragGesture.release(draft, "proposer")
        assertEquals(WireDragGesture.Outcome.Cancelled, outcome)
    }

    @Test
    fun `release with no source on the draft cancels rather than throwing`() {
        val draft = TouchConnectionGrammar.start() // IDLE, sourceNodeId null
        val outcome = WireDragGesture.release(draft, "anything")
        assertEquals(WireDragGesture.Outcome.Cancelled, outcome)
    }

    @Test
    fun `hitTest finds the nearest candidate within radius and ignores the rest`() {
        val candidates = listOf(
            WireDragGesture.NodeHitTarget("far", 500f, 500f, 40f),
            WireDragGesture.NodeHitTarget("near", 100f, 100f, 40f),
            WireDragGesture.NodeHitTarget("nearer", 110f, 100f, 40f),
        )
        // (108,100) is inside both "near" and "nearer"'s radius; "nearer" is the closer center.
        assertEquals("nearer", WireDragGesture.hitTest(108f, 100f, candidates))
    }

    @Test
    fun `hitTest returns null when the point is outside every candidate's radius`() {
        val candidates = listOf(WireDragGesture.NodeHitTarget("only", 0f, 0f, 10f))
        assertNull(WireDragGesture.hitTest(100f, 100f, candidates))
    }

    @Test
    fun `hitTest with no candidates returns null`() {
        assertNull(WireDragGesture.hitTest(0f, 0f, emptyList()))
    }

    @Test
    fun `isPortStart is true at the right-edge center and false at the node's own center`() {
        val width = 148f; val height = 62f; val radius = 22f
        assertTrue(WireDragGesture.isPortStart(width, height / 2f, width, height, radius))
        assertTrue(!WireDragGesture.isPortStart(width / 2f, height / 2f, width, height, radius))
    }

    @Test
    fun `isPortStart honors the hit radius boundary`() {
        val width = 100f; val height = 60f
        // Exactly on the radius boundary counts as a hit (uses less-than-or-equal).
        assertTrue(WireDragGesture.isPortStart(width - 20f, height / 2f, width, height, 20f))
        assertTrue(!WireDragGesture.isPortStart(width - 21f, height / 2f, width, height, 20f))
    }
}
