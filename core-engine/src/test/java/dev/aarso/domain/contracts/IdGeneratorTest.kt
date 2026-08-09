package dev.aarso.domain.contracts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class IdGeneratorTest {

    @Test
    fun `generate produces a 26-character well-formed ULID`() {
        val id = IdGenerator.generate()
        assertEquals(26, id.length)
        assertTrue(IdGenerator.isWellFormed(id))
    }

    @Test
    fun `generate never produces the excluded Crockford letters I, L, O, U`() {
        val id = IdGenerator.generate()
        assertTrue(id.none { it in "ILOU" })
    }

    @Test
    fun `isWellFormed rejects the wrong length`() {
        assertFalse(IdGenerator.isWellFormed("TOOSHORT"))
        assertFalse(IdGenerator.isWellFormed(IdGenerator.generate() + "X"))
    }

    @Test
    fun `isWellFormed rejects characters outside the Crockford alphabet`() {
        assertFalse(IdGenerator.isWellFormed("ILOU1234567890ILOU1234567890".take(26)))
    }

    @Test
    fun `two generated ids at the same instant are distinct`() {
        val fixedTime = 1_700_000_000_000L
        val a = IdGenerator.generate(fixedTime)
        val b = IdGenerator.generate(fixedTime)
        assertFalse(a == b)
        // Both still carry the same 10-character timestamp segment.
        assertEquals(a.take(10), b.take(10))
    }

    @Test
    fun `ids generated at increasing timestamps sort lexicographically in time order`() {
        val random = SecureRandom()
        val earlier = IdGenerator.generate(1_700_000_000_000L, random)
        val later = IdGenerator.generate(1_700_000_000_001L, random)
        assertTrue(earlier < later)
    }

    @Test
    fun `generate rejects a negative timestamp`() {
        try {
            IdGenerator.generate(-1L)
            org.junit.Assert.fail("expected IllegalArgumentException for a negative timestamp")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `generate is deterministic for a fixed timestamp and seeded random source`() {
        val a = IdGenerator.generate(1_700_000_000_000L, SecureRandom.getInstance("SHA1PRNG").apply { setSeed(42L) })
        val b = IdGenerator.generate(1_700_000_000_000L, SecureRandom.getInstance("SHA1PRNG").apply { setSeed(42L) })
        assertEquals(a, b)
    }
}
