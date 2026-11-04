package dev.fonebrew.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationStatusTest {

    private var clock = 100L
    private val status = GenerationStatus { clock }

    @Test
    fun startsIdle_andUnreported() {
        val s = status.state.value
        assertEquals(GenerationStatus.Phase.IDLE, s.phase)
        assertFalse(s.reported)
        assertNull(s.since)
        assertNull(s.lastEndedAt)
    }

    @Test
    fun aTurn_stampsSinceOnStart_keepsItThroughThePhase_andStampsTheEnd() {
        status.report(GenerationStatus.Phase.IDLE, GenerationStatus.Mode.TEXT, "m")
        assertTrue(status.state.value.reported)
        assertNull(status.state.value.lastEndedAt)

        clock = 200L
        status.report(GenerationStatus.Phase.LOADING, GenerationStatus.Mode.TEXT, "m")
        assertEquals(200L, status.state.value.since)

        clock = 300L
        status.report(GenerationStatus.Phase.GENERATING, GenerationStatus.Mode.TEXT, "m")
        assertEquals("a new phase restarts the clock", 300L, status.state.value.since)

        clock = 350L
        status.report(GenerationStatus.Phase.GENERATING, GenerationStatus.Mode.TEXT, "m2")
        assertEquals("same phase, other model: since is kept", 300L, status.state.value.since)

        clock = 400L
        status.report(GenerationStatus.Phase.IDLE, GenerationStatus.Mode.TEXT, "m2")
        assertNull(status.state.value.since)
        assertEquals(400L, status.state.value.lastEndedAt)
        assertEquals("m2", status.state.value.activeModelId)
    }

    @Test
    fun repeatingTheSameReport_isANoOp() {
        status.report(GenerationStatus.Phase.GENERATING, GenerationStatus.Mode.COUNCIL, "m")
        val first = status.state.value
        clock = 999L
        status.report(GenerationStatus.Phase.GENERATING, GenerationStatus.Mode.COUNCIL, "m")
        assertSame(first, status.state.value)
    }

    @Test
    fun aTurnsOwnProvenance_isCarriedWhileInFlight_andDroppedWhenIdle() {
        status.report(GenerationStatus.Phase.GENERATING, GenerationStatus.Mode.IMAGE, "m", runOnDevice = false)
        assertEquals(false, status.state.value.runOnDevice)

        // Same phase, but the turn's provenance changed: that is a new report, not a no-op.
        status.report(GenerationStatus.Phase.GENERATING, GenerationStatus.Mode.IMAGE, "m", runOnDevice = true)
        assertEquals(true, status.state.value.runOnDevice)

        // Idle never claims a provenance, even if the caller still passes one.
        status.report(GenerationStatus.Phase.IDLE, GenerationStatus.Mode.IMAGE, "m", runOnDevice = false)
        assertNull(status.state.value.runOnDevice)

        status.report(GenerationStatus.Phase.GENERATING, GenerationStatus.Mode.COUNCIL, "m", runOnDevice = false)
        status.chatGone()
        assertNull("a dead chat's turn has no provenance left to show", status.state.value.runOnDevice)
    }

    @Test
    fun chatGone_endsAnythingInFlight_butLeavesAnIdleStateAlone() {
        status.report(GenerationStatus.Phase.GENERATING, GenerationStatus.Mode.IMAGE, "m")
        clock = 500L
        status.chatGone()
        assertEquals(GenerationStatus.Phase.IDLE, status.state.value.phase)
        assertEquals(500L, status.state.value.lastEndedAt)

        val idle = status.state.value
        clock = 600L
        status.chatGone()
        assertSame(idle, status.state.value)
    }
}
