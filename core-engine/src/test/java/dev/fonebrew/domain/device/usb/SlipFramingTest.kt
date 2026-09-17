package dev.fonebrew.domain.device.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SlipFramingTest {

    @Test
    fun `a payload with no special bytes round-trips unchanged, wrapped in a single END byte on each side`() {
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val framed = SlipFraming.encode(payload)
        assertEquals(0xC0.toByte(), framed.first())
        assertEquals(0xC0.toByte(), framed.last())
        assertArrayEquals(payload, SlipFraming.decode(framed))
    }

    @Test
    fun `a payload byte equal to END (0xC0) is escaped, never appearing raw mid-frame`() {
        val payload = byteArrayOf(0x01, 0xC0.toByte(), 0x02)
        val framed = SlipFraming.encode(payload)
        // The only two 0xC0 bytes in the whole frame are the leading/trailing delimiters.
        val interiorC0Count = framed.drop(1).dropLast(1).count { it == 0xC0.toByte() }
        assertEquals(0, interiorC0Count)
        assertArrayEquals(payload, SlipFraming.decode(framed))
    }

    @Test
    fun `a payload byte equal to ESC (0xDB) is escaped, and round-trips exactly`() {
        val payload = byteArrayOf(0xDB.toByte(), 0x05)
        val framed = SlipFraming.encode(payload)
        assertArrayEquals(payload, SlipFraming.decode(framed))
    }

    @Test
    fun `a payload containing both END and ESC bytes, including adjacent, round-trips exactly`() {
        val payload = byteArrayOf(0xC0.toByte(), 0xDB.toByte(), 0xC0.toByte(), 0x00, 0xDB.toByte())
        val framed = SlipFraming.encode(payload)
        assertArrayEquals(payload, SlipFraming.decode(framed))
    }

    @Test
    fun `an empty payload round-trips to an empty payload, just the two delimiters`() {
        val framed = SlipFraming.encode(ByteArray(0))
        assertEquals(2, framed.size)
        assertArrayEquals(ByteArray(0), SlipFraming.decode(framed))
    }

    @Test
    fun `decode rejects a frame missing its trailing END delimiter`() {
        val malformed = byteArrayOf(0xC0.toByte(), 0x01, 0x02) // no trailing 0xC0
        try {
            SlipFraming.decode(malformed)
            org.junit.Assert.fail("expected FramingException")
        } catch (expected: SlipFraming.FramingException) {
            // expected
        }
    }

    @Test
    fun `decode rejects a dangling ESC byte with nothing following it`() {
        val malformed = byteArrayOf(0xC0.toByte(), 0xDB.toByte(), 0xC0.toByte()) // ESC immediately before the closing delimiter
        try {
            SlipFraming.decode(malformed)
            org.junit.Assert.fail("expected FramingException")
        } catch (expected: SlipFraming.FramingException) {
            // expected
        }
    }

    @Test
    fun `decode rejects an unrecognized escape sequence`() {
        val malformed = byteArrayOf(0xC0.toByte(), 0xDB.toByte(), 0x99.toByte(), 0xC0.toByte())
        try {
            SlipFraming.decode(malformed)
            org.junit.Assert.fail("expected FramingException")
        } catch (expected: SlipFraming.FramingException) {
            // expected
        }
    }
}
