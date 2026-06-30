package dev.aarso.hyle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FinishTest {

    @Test fun `radiant carries a tint and defaults to the watched pulse`() {
        val r = Finish.Radiant(RadiantHues.RADIUM)
        assertEquals(RadiantHues.RADIUM, r.tint)
        assertEquals(Pulse.WATCHED, r.pulse)
    }

    @Test fun `reflective is a singleton`() {
        assertSame(Finish.Reflective, Finish.Reflective)
    }

    @Test fun `both candidate hues are opaque and distinct`() {
        assertEquals(2, RadiantHues.candidates.size)
        for (c in RadiantHues.candidates) {
            assertEquals("hue must be fully opaque", 0xFF, ((c shr 24) and 0xFF).toInt())
        }
        assertNotEquals(RadiantHues.RADIUM, RadiantHues.COLD_CYAN)
    }

    @Test fun `pulse rejects invalid timing`() {
        assertThrows(IllegalArgumentException::class.java) { Pulse(0, 10, 20) }
        assertThrows(IllegalArgumentException::class.java) { Pulse(100, 80, 20) } // min > max
    }

    @Test fun `the watched pulse is a calm, low-amplitude breath`() {
        // heartbeat, not weather: a slow period, never full-off/full-on flashing.
        assertTrue(Pulse.WATCHED.periodMs in 1500..4000)
        assertTrue(Pulse.WATCHED.minAlphaPct > 0)
        assertTrue(Pulse.WATCHED.maxAlphaPct < 100)
    }
}
