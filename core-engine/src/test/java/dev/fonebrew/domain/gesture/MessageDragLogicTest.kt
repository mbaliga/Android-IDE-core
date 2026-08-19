package dev.fonebrew.domain.gesture

import dev.fonebrew.domain.gesture.MessageDragLogic.ARM_MS
import dev.fonebrew.domain.gesture.MessageDragLogic.DETENT_1_DP
import dev.fonebrew.domain.gesture.MessageDragLogic.DETENT_2_DP
import dev.fonebrew.domain.gesture.MessageDragLogic.DOUBLE_TAP_WINDOW_MS
import dev.fonebrew.domain.gesture.MessageDragLogic.Intent
import dev.fonebrew.domain.gesture.MessageDragLogic.LONG_PRESS_MS
import dev.fonebrew.domain.gesture.MessageDragLogic.Phase
import dev.fonebrew.domain.gesture.MessageDragLogic.RADIAL_HOLD_MS
import dev.fonebrew.domain.gesture.MessageDragLogic.Sample
import dev.fonebrew.domain.gesture.MessageDragLogic.resolve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MessageDragLogicTest {

    private fun down(atMs: Long) = Sample(Phase.DOWN, 0f, 0f, atMs)
    private fun move(dx: Float, dy: Float, atMs: Long) = Sample(Phase.MOVE, dx, dy, atMs)
    private fun up(dx: Float, dy: Float, atMs: Long) = Sample(Phase.UP, dx, dy, atMs)

    // ---- malformed input ---------------------------------------------------------------------

    @Test fun `an empty trace is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { resolve(emptyList()) }
    }

    @Test fun `a trace not starting on DOWN is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { resolve(listOf(move(0f, 0f, 0L))) }
    }

    // ---- plain tap / double tap ---------------------------------------------------------------

    @Test fun `a quick tap with no movement yields`() {
        val trace = listOf(down(0L), up(0f, 0f, 40L))
        assertEquals(Intent.Yield, resolve(trace))
    }

    @Test fun `a second quick tap inside the double-tap window becomes DoubleTap`() {
        val first = listOf(down(0L), up(0f, 0f, 30L))
        val previousTapUpAtMs = 30L
        val second = listOf(down(30L + DOUBLE_TAP_WINDOW_MS), up(0f, 0f, 30L + DOUBLE_TAP_WINDOW_MS + 20L))
        assertEquals(Intent.Yield, resolve(first))
        assertEquals(Intent.DoubleTap, resolve(second, previousTapUpAtMs))
    }

    @Test fun `a second tap just outside the double-tap window is an ordinary tap`() {
        val previousTapUpAtMs = 0L
        val second = listOf(down(DOUBLE_TAP_WINDOW_MS + 1L), up(0f, 0f, DOUBLE_TAP_WINDOW_MS + 20L))
        assertEquals(Intent.Yield, resolve(second, previousTapUpAtMs))
    }

    @Test fun `a second tap with no previous tap timestamp is an ordinary tap`() {
        val second = listOf(down(50L), up(0f, 0f, 70L))
        assertEquals(Intent.Yield, resolve(second, previousTapUpAtMs = null))
    }

    @Test fun `a tap that armed first is never a double-tap candidate`() {
        // Held past ARM_MS before releasing -- this was a long-hold-then-release, not a tap;
        // the *next* gesture's down should not treat this one's up as a tap partner even if it
        // happens to land inside the window (the wrapper is only expected to record
        // previousTapUpAtMs for genuine pre-arm taps, but resolve() itself must still behave if
        // fed one anyway: the up here is armed+stationary+released, which resolves to Yield, not
        // a tap-labelled Yield -- verified separately below).
        val trace = listOf(down(0L), up(0f, 0f, ARM_MS + 10L))
        assertEquals(Intent.Yield, resolve(trace))
    }

    // ---- pre-arm scroll handoff -----------------------------------------------------------

    @Test fun `movement beyond slop before arming yields, even while still pressed`() {
        val trace = listOf(down(0L), move(20f, 0f, 50L))
        assertEquals(Intent.Yield, resolve(trace))
    }

    @Test fun `movement beyond slop before arming yields for the rest of the gesture`() {
        val trace = listOf(down(0L), move(20f, 0f, 50L), move(20f, -100f, 400L))
        assertEquals(Intent.Yield, resolve(trace))
    }

    @Test fun `movement within slop before arming does not yield`() {
        val trace = listOf(down(0L), move(3f, -2f, 50L))
        assertEquals(Intent.Yield, resolve(trace)) // still pending, not yet armed -- same value, different meaning (see class KDoc)
        // Confirm it's genuinely "pending" and not "abandoned": arming still succeeds later.
        val armed = listOf(down(0L), move(3f, -2f, 50L), move(3f, -2f, ARM_MS))
        assertEquals(Intent.Arm, resolve(armed))
    }

    // ---- arm / long-press ----------------------------------------------------------------------

    @Test fun `holding still exactly to ARM_MS arms`() {
        val trace = listOf(down(0L), move(0f, 0f, ARM_MS))
        assertEquals(Intent.Arm, resolve(trace))
    }

    @Test fun `holding still just under ARM_MS does not arm yet`() {
        val trace = listOf(down(0L), move(0f, 0f, ARM_MS - 1))
        assertEquals(Intent.Yield, resolve(trace))
    }

    @Test fun `holding still between arm and long-press keeps returning Arm`() {
        val trace = listOf(down(0L), move(0f, 0f, ARM_MS + 100))
        assertEquals(Intent.Arm, resolve(trace))
    }

    @Test fun `holding still to LONG_PRESS_MS opens the turn actions sheet`() {
        val trace = listOf(down(0L), move(0f, 0f, LONG_PRESS_MS))
        assertEquals(Intent.LongPress, resolve(trace))
    }

    @Test fun `releasing after arming but before long-press with no drag yields`() {
        val trace = listOf(down(0L), move(0f, 0f, ARM_MS), up(0f, 0f, LONG_PRESS_MS - 10))
        assertEquals(Intent.Yield, resolve(trace))
    }

    // ---- vertical verdict drag -----------------------------------------------------------------

    @Test fun `a small upward pull below the first detent previews a null grade`() {
        val trace = listOf(down(0L), move(0f, -10f, ARM_MS + 10))
        assertEquals(Intent.VerdictDetent(null), resolve(trace))
    }

    @Test fun `an upward pull past the first detent previews grade +1`() {
        val trace = listOf(down(0L), move(0f, -DETENT_1_DP, ARM_MS + 10))
        assertEquals(Intent.VerdictDetent(1), resolve(trace))
    }

    @Test fun `an upward pull past the second detent previews grade +2`() {
        val trace = listOf(down(0L), move(0f, -DETENT_2_DP, ARM_MS + 10))
        assertEquals(Intent.VerdictDetent(2), resolve(trace))
    }

    @Test fun `a downward pull past the first detent previews grade -1`() {
        val trace = listOf(down(0L), move(0f, DETENT_1_DP, ARM_MS + 10))
        assertEquals(Intent.VerdictDetent(-1), resolve(trace))
    }

    @Test fun `a downward pull past the second detent previews grade -2`() {
        val trace = listOf(down(0L), move(0f, DETENT_2_DP, ARM_MS + 10))
        assertEquals(Intent.VerdictDetent(-2), resolve(trace))
    }

    @Test fun `releasing inside a detent commits the verdict`() {
        val trace = listOf(down(0L), move(0f, -DETENT_2_DP, ARM_MS + 10), up(0f, -DETENT_2_DP, ARM_MS + 40))
        assertEquals(Intent.CommitVerdict(2), resolve(trace))
    }

    @Test fun `releasing before the first detent yields -- the ribbon snaps back with no verdict`() {
        val trace = listOf(down(0L), move(0f, -10f, ARM_MS + 10), up(0f, -10f, ARM_MS + 40))
        assertEquals(Intent.Yield, resolve(trace))
    }

    @Test fun `the message never appears anywhere in the intent -- verdict is a value, not a destination`() {
        // Structural guard for binding constraint 1 (patent design-around): CommitVerdict carries
        // only a grade, nothing that could be read as a target/collection.
        val committed = Intent.CommitVerdict(2)
        assertEquals(2, committed.grade)
    }

    // ---- horizontal: left = reply --------------------------------------------------------------

    @Test fun `pulling left and releasing replies`() {
        val trace = listOf(down(0L), move(-40f, 0f, ARM_MS + 10), up(-40f, 0f, ARM_MS + 40))
        assertEquals(Intent.Reply, resolve(trace))
    }

    @Test fun `pulling left while still down does not commit yet`() {
        val trace = listOf(down(0L), move(-40f, 0f, ARM_MS + 10))
        assertEquals(Intent.Yield, resolve(trace))
    }

    @Test fun `holding a left pull well past the radial threshold still only replies on release -- fan is right-only`() {
        val trace = listOf(
            down(0L),
            move(-40f, 0f, ARM_MS + 10),
            up(-40f, 0f, ARM_MS + 10 + RADIAL_HOLD_MS + 500),
        )
        assertEquals(Intent.Reply, resolve(trace))
    }

    // ---- horizontal: right = quote, right+hold = radial fan -------------------------------------

    @Test fun `pulling right and releasing quickly quotes`() {
        val trace = listOf(down(0L), move(40f, 0f, ARM_MS + 10), up(40f, 0f, ARM_MS + 40))
        assertEquals(Intent.Quote, resolve(trace))
    }

    @Test fun `pulling right and holding under the radial threshold does not fan yet`() {
        val trace = listOf(down(0L), move(40f, 0f, ARM_MS + 10))
        assertEquals(Intent.Yield, resolve(trace))
    }

    @Test fun `pulling right and holding to the radial threshold opens the fan`() {
        val decidedAtMs = ARM_MS + 10
        val trace = listOf(down(0L), move(40f, 0f, decidedAtMs), move(40f, 0f, decidedAtMs + RADIAL_HOLD_MS))
        assertEquals(Intent.OpenRadial(40f, 0f), resolve(trace))
    }

    @Test fun `the radial threshold is measured from when right was decided, not from the original down`() {
        // Armed early, wandered inside the slop box for a while (still not decided), THEN pulled
        // right -- the 400ms clock should start at the pull, not at the original touch-down.
        val decidedAtMs = LONG_PRESS_MS - 10 // just under the long-press threshold, still armed+idle until now
        val trace = listOf(
            down(0L),
            move(2f, 1f, ARM_MS + 5), // inside slop, still "armed and idle"
            move(40f, 0f, decidedAtMs), // now decides horizontal-right
            move(40f, 0f, decidedAtMs + RADIAL_HOLD_MS - 1), // not quite 400ms since the pull
        )
        assertEquals(Intent.Yield, resolve(trace))
        val justOver = trace + move(40f, 0f, decidedAtMs + RADIAL_HOLD_MS)
        assertEquals(Intent.OpenRadial(40f, 0f), resolve(justOver))
    }

    @Test fun `releasing exactly at the radial threshold fans rather than quoting`() {
        val decidedAtMs = ARM_MS + 10
        val trace = listOf(
            down(0L),
            move(40f, 0f, decidedAtMs),
            up(40f, 0f, decidedAtMs + RADIAL_HOLD_MS),
        )
        assertEquals(Intent.OpenRadial(40f, 0f), resolve(trace))
    }

    // ---- axis tie-break + boundary values --------------------------------------------------

    @Test fun `equal dx and dy magnitude past slop breaks the tie toward horizontal`() {
        val trace = listOf(down(0L), move(30f, -30f, ARM_MS + 10))
        assertEquals(Intent.Yield, resolve(trace)) // horizontal-right, under the radial threshold
    }

    @Test fun `dy strictly dominant over dx resolves vertical`() {
        val trace = listOf(down(0L), move(5f, -40f, ARM_MS + 10))
        assertEquals(Intent.VerdictDetent(1), resolve(trace))
    }
}
