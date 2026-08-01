package dev.aarso.domain.curation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VerdictTest {

    @Test fun `zero is not a valid grade`() {
        assertThrows(IllegalArgumentException::class.java) {
            Verdict(msgId = "m1", grade = 0, at = 1L)
        }
    }

    @Test fun `the four detents are all valid`() {
        for (grade in listOf(-2, -1, 1, 2)) {
            Verdict(msgId = "m1", grade = grade, at = 1L) // does not throw
        }
    }

    @Test fun `an out-of-range grade is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            Verdict(msgId = "m1", grade = 3, at = 1L)
        }
    }

    @Test fun `fromValue round-trips every detent`() {
        assertEquals(VerdictGrade.WRONG, VerdictGrade.fromValue(-2))
        assertEquals(VerdictGrade.OFF, VerdictGrade.fromValue(-1))
        assertEquals(VerdictGrade.USEFUL, VerdictGrade.fromValue(1))
        assertEquals(VerdictGrade.REFERENCE, VerdictGrade.fromValue(2))
    }

    @Test fun `isPositive is true only for the two positive detents`() {
        assertTrue(Verdicts.isPositive(Verdict("m", 1, 0L)))
        assertTrue(Verdicts.isPositive(Verdict("m", 2, 0L)))
        assertFalse(Verdicts.isPositive(Verdict("m", -1, 0L)))
        assertFalse(Verdicts.isPositive(Verdict("m", -2, 0L)))
    }

    @Test fun `isNegative is the exact complement of isPositive`() {
        for (grade in listOf(-2, -1, 1, 2)) {
            val v = Verdict("m", grade, 0L)
            assertTrue(Verdicts.isPositive(v) != Verdicts.isNegative(v))
        }
    }

    @Test fun `mergeLww keeps the newer verdict regardless of argument order`() {
        val old = Verdict("m", grade = 1, at = 10L)
        val new = Verdict("m", grade = -2, at = 20L)
        // existing=old, incoming=new (newer) -> incoming wins.
        assertEquals(new, Verdicts.mergeLww(old, new))
        // existing=new, incoming=old (stale) -> existing is kept.
        assertEquals(new, Verdicts.mergeLww(new, old))
    }

    @Test fun `mergeLww with a null existing always takes the incoming verdict`() {
        val incoming = Verdict("m", grade = 2, at = 5L)
        assertEquals(incoming, Verdicts.mergeLww(null, incoming))
    }

    @Test fun `mergeLww at equal timestamps takes the incoming verdict`() {
        val existing = Verdict("m", grade = 1, at = 10L)
        val incoming = Verdict("m", grade = -1, at = 10L)
        assertEquals(incoming, Verdicts.mergeLww(existing, incoming))
    }
}
