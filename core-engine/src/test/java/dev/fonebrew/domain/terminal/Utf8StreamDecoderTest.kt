package dev.fonebrew.domain.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class Utf8StreamDecoderTest {
    private fun decodeSplit(bytes: ByteArray, at: Int): String {
        val d = Utf8StreamDecoder()
        return d.decode(bytes, 0, at) + d.decode(bytes, at, bytes.size - at) + d.finish()
    }

    private fun whole(bytes: ByteArray): String { val d = Utf8StreamDecoder(); return d.decode(bytes) + d.finish() }

    @Test fun `every split point of valid multibyte text reassembles`() {
        val text = "a\u00e9\u20ac\uD83D\uDE00z \u4e2d\u6587"
        val bytes = text.toByteArray(Charsets.UTF_8)
        for (at in 0..bytes.size) assertEquals("split at $at", text, decodeSplit(bytes, at))
    }

    @Test fun `a byte-at-a-time stream equals the whole`() {
        val text = "h\u00e9llo \u20ac \uD83D\uDE00"
        val d = Utf8StreamDecoder()
        val sb = StringBuilder()
        for (b in text.toByteArray(Charsets.UTF_8)) sb.append(d.decode(byteArrayOf(b)))
        assertEquals(text, sb.toString())
    }

    @Test fun `invalid bytes become FFFD without desynchronising`() {
        // lone continuation, then ASCII; truncated 3-byte lead followed by ASCII; 0xFF; overlong C0 80
        val bytes = byteArrayOf(0x80.toByte(), 'a'.code.toByte(), 0xE2.toByte(), 0x82.toByte(), 'b'.code.toByte(),
            0xFF.toByte(), 'c'.code.toByte(), 0xC0.toByte(), 0x80.toByte(), 'd'.code.toByte())
        assertEquals("\uFFFDa\uFFFDb\uFFFDc\uFFFD\uFFFDd", whole(bytes))
    }

    @Test fun `splitting an invalid stream anywhere gives the same output as whole`() {
        val bytes = byteArrayOf(0xE2.toByte(), 0x82.toByte(), 'x'.code.toByte(), 0xF0.toByte(), 0x9F.toByte(), 0xED.toByte(),
            0xA0.toByte(), 0x80.toByte(), 'y'.code.toByte(), 0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte())
        val expected = whole(bytes)
        for (at in 0..bytes.size) assertEquals("split at $at", expected, decodeSplit(bytes, at))
    }

    @Test fun `surrogate encodings and above-10FFFF are rejected`() {
        assertEquals("\uFFFD\uFFFD\uFFFD", whole(byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte())))
        assertEquals(true, whole(byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte())).startsWith("\uFFFD"))
    }

    @Test fun `an incomplete tail is held back then flushed as FFFD`() {
        val d = Utf8StreamDecoder()
        assertEquals("", d.decode(byteArrayOf(0xE2.toByte(), 0x82.toByte())))
        assertEquals("\uFFFD", d.finish())
        assertEquals("", d.finish())
    }
}
