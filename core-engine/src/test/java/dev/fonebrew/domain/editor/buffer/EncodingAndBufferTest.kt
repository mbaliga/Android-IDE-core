package dev.fonebrew.domain.editor.buffer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class EncodingAndBufferTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun roundTrip(b: ByteArray): TextBuffer {
        val opened = TextBuffer.open(b) as OpenResult.Opened
        val out = opened.buffer.toBytes() as EncodeResult.Ok
        assertArrayEquals("no-op save must be byte-exact", b, out.bytes)
        return opened.buffer
    }

    @Test fun `utf8 round trips including CRLF and a missing final newline`() {
        val b = "line one\r\nline two\r\nlast, no newline".toByteArray()
        val buf = roundTrip(b)
        assertEquals(TextEncoding.UTF8, buf.encoding)
        assertEquals(LineEnding.CRLF, buf.lineEndings().kind)
        assertFalse(buf.lineEndings().endsWithNewline)
    }

    @Test fun `mixed endings are preserved byte for byte and reported as MIXED`() {
        val b = "a\nb\r\nc\rd\n".toByteArray()
        val buf = roundTrip(b)
        assertEquals(LineEnding.MIXED, buf.lineEndings().kind)
        assertTrue(buf.lineEndings().endsWithNewline)
    }

    @Test fun `CR only files round trip`() {
        val buf = roundTrip("a\rb\rc".toByteArray())
        assertEquals(LineEnding.CR, buf.lineEndings().kind)
        assertEquals("\r", buf.lineEndings().newline)
    }

    @Test fun `empty file round trips`() { roundTrip(ByteArray(0)) }

    @Test fun `utf8 bom is detected, stripped from the text and restored on save`() {
        val b = bytes(0xEF, 0xBB, 0xBF) + "hé".toByteArray()
        val buf = roundTrip(b)
        assertEquals(TextEncoding.UTF8_BOM, buf.encoding)
        assertEquals("hé", buf.toString())
    }

    @Test fun `utf16 le and be with bom round trip`() {
        val le = bytes(0xFF, 0xFE) + "hi \uD83D\uDE00\r\n".toByteArray(Charsets.UTF_16LE)
        val be = bytes(0xFE, 0xFF) + "hi \uD83D\uDE00\r\n".toByteArray(Charsets.UTF_16BE)
        assertEquals(TextEncoding.UTF16LE_BOM, roundTrip(le).encoding)
        assertEquals(TextEncoding.UTF16BE_BOM, roundTrip(be).encoding)
        assertEquals("hi \uD83D\uDE00\r\n", (TextBuffer.open(le) as OpenResult.Opened).buffer.toString())
    }

    @Test fun `invalid utf8 falls back to a flagged latin1 with an exact round trip`() {
        val b = bytes(0x63, 0x61, 0x66, 0xE9, 0x0A)   // "café\n" in Latin-1
        val buf = roundTrip(b)
        assertEquals(TextEncoding.LATIN1, buf.encoding)
        assertTrue("the guess must be visible", buf.encoding.isGuess)
        assertEquals("café\n", buf.toString())
    }

    @Test fun `every byte value survives the latin1 fallback`() {
        val all = ByteArray(255) { (it + 1).toByte() }   // no NUL: that is the binary check
        all[0] = 0xFF.toByte()
        assertEquals(TextEncoding.LATIN1, roundTrip(all).encoding)
    }

    @Test fun `latin1 text with a char above 255 is refused on save not substituted`() {
        val b = bytes(0xE9)
        val buf = (TextBuffer.open(b) as OpenResult.Opened).buffer
        buf.replace(0, 0, "\u20AC")
        val r = buf.toBytes()
        assertTrue(r is EncodeResult.Unrepresentable)
        assertEquals(0, (r as EncodeResult.Unrepresentable).offset)
    }

    @Test fun `a lone surrogate typed into a utf8 buffer is refused on save`() {
        val buf = TextBuffer("ab")
        buf.replace(1, 1, "\uD83D")
        val r = buf.toBytes()
        assertTrue(r is EncodeResult.Unrepresentable)
        assertEquals(1, (r as EncodeResult.Unrepresentable).offset)
    }

    @Test fun `binary and utf32 are refused with a reason`() {
        val bin = TextBuffer.open(bytes(0x89, 0x50, 0x4E, 0x47, 0x00, 0x01)) as OpenResult.Refused
        assertEquals(RefusalReason.BINARY, bin.reason)
        val u32 = TextBuffer.open(bytes(0xFF, 0xFE, 0x00, 0x00, 0x41, 0x00, 0x00, 0x00)) as OpenResult.Refused
        assertEquals(RefusalReason.UTF32_UNSUPPORTED, u32.reason)
    }

    @Test fun `malformed utf16 is refused`() {
        val odd = TextBuffer.open(bytes(0xFF, 0xFE, 0x41)) as OpenResult.Refused
        assertEquals(RefusalReason.ODD_UTF16_LENGTH, odd.reason)
        val lone = TextBuffer.open(bytes(0xFF, 0xFE, 0x3D, 0xD8, 0x41, 0x00)) as OpenResult.Refused
        assertEquals(RefusalReason.MALFORMED_UTF16, lone.reason)
    }

    @Test fun `random text round trips in every encoding it can represent`() {
        val rnd = Random(5)
        val alphabet = listOf("a", "é", "\n", "\r\n", "\r", "漢", "\uD83D\uDE00", " ")
        repeat(200) {
            val s = (0 until rnd.nextInt(60)).joinToString("") { alphabet[rnd.nextInt(alphabet.size)] }
            for (enc in listOf(TextEncoding.UTF8, TextEncoding.UTF8_BOM, TextEncoding.UTF16LE_BOM, TextEncoding.UTF16BE_BOM)) {
                val enc1 = TextEncodings.encode(s, enc) as EncodeResult.Ok
                if (enc1.bytes.any { it == 0.toByte() } && enc == TextEncoding.UTF8 && s.isNotEmpty()) continue  // NUL chars would not occur; guard anyway
                val dec = TextEncodings.decode(enc1.bytes) as DecodeResult.Ok
                assertEquals(s, dec.text)
                assertArrayEquals((TextEncodings.encode(dec.text, dec.encoding) as EncodeResult.Ok).bytes, enc1.bytes)
            }
        }
    }

    @Test fun `typed newlines follow the dominant ending`() {
        assertEquals("\r\n", LineEndings.profile("a\r\nb\r\nc\n").newline)
        assertEquals("\n", LineEndings.profile("no breaks").newline)
        assertEquals("a\r\nb", LineEndings.normalize("a\nb", "\r\n"))
        assertEquals("a\nb\nc", LineEndings.normalize("a\r\nb\rc", "\n"))
    }

    @Test fun `large file policy has stated tiers and never refuses outright`() {
        assertEquals(OpenMode.EDITABLE, OpenPolicy.decide(1024).mode)
        assertEquals(OpenMode.EDITABLE, OpenPolicy.decide(OpenPolicy.EDITABLE_MAX).mode)
        assertEquals(OpenMode.READ_ONLY, OpenPolicy.decide(OpenPolicy.EDITABLE_MAX + 1).mode)
        assertEquals(OpenMode.CHUNKED_READ_ONLY, OpenPolicy.decide(OpenPolicy.READ_ONLY_MAX + 1).mode)
        assertEquals(OpenMode.READ_ONLY, OpenPolicy.decide(-1).mode)
    }

    @Test fun `a read-only buffer refuses mutation`() {
        val big = ByteArray((OpenPolicy.EDITABLE_MAX + 10).toInt()) { 'a'.code.toByte() }
        val o = TextBuffer.open(big) as OpenResult.Opened
        assertTrue(o.buffer.readOnly)
        var threw = false
        try { o.buffer.replace(0, 1, "x") } catch (e: IllegalStateException) { threw = true }
        assertTrue(threw)
    }

    @Test fun `chunk plan covers the file exactly once`() {
        val w = ChunkPlan.windows(2_500_000, 1_000_000)
        assertEquals(listOf(0L until 1_000_000L, 1_000_000L until 2_000_000L, 2_000_000L until 2_500_000L), w)
        assertTrue(ChunkPlan.windows(0).isEmpty())
    }

    @Test fun `revision bumps on every change`() {
        val b = TextBuffer("x")
        val r0 = b.revision
        b.replace(1, 1, "y")
        assertTrue(b.revision > r0)
        assertEquals("xy", b.toString())
    }
}
