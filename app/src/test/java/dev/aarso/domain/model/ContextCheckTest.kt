package dev.aarso.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCheckTest {

    @Test
    fun fitsWhenWithinWindow() {
        val c = checkContext(usedTokens = 100, window = 8192)
        assertTrue(c.fits)
        assertEquals(0, c.overflowBy)
    }

    @Test
    fun overflowReportsTheExcess() {
        val c = checkContext(usedTokens = 9000, window = 8192)
        assertFalse(c.fits)
        assertEquals(808, c.overflowBy)
    }

    @Test
    fun exactlyFullStillFits() {
        assertTrue(checkContext(64, 64).fits)
    }

    @Test
    fun fractionIsClampedAndProportional() {
        assertEquals(0.5f, checkContext(50, 100).fraction, 1e-6f)
        assertEquals(1f, checkContext(200, 100).fraction, 1e-6f) // clamped
        assertEquals(1f, checkContext(10, 0).fraction, 1e-6f)    // guard div-by-zero
    }
}
