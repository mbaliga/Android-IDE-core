package dev.fonebrew.domain.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalHistoryTest {
    private val meta = SnapshotMeta("t", "/home", 1234L, "ended")

    @Test fun `round trip keeps meta and text`() {
        val bytes = TerminalHistory.encode(meta, "a\nb\n")
        val s = TerminalHistory.decode(bytes)!!
        assertEquals(meta, s.meta); assertEquals("a\nb\n", s.text); assertFalse(s.truncated)
    }

    @Test fun `body is capped at the byte limit keeping the newest lines`() {
        val text = (1..1000).joinToString("") { "line$it\n" }
        val bytes = TerminalHistory.encode(meta, text, maxBodyBytes = 200)
        val s = TerminalHistory.decode(bytes, maxBodyBytes = 200)!!
        assertTrue(s.truncated)
        assertTrue(s.text.toByteArray().size <= 200)
        assertTrue(s.text.endsWith("line1000\n"))
        assertFalse(s.text.contains("line1\n"))
    }

    @Test fun `multibyte text is clipped on a character boundary within the cap`() {
        val text = (1..200).joinToString("") { "日本語$it\n" }
        val s = TerminalHistory.decode(TerminalHistory.encode(meta, text, 300), 300)!!
        assertTrue(s.text.toByteArray().size <= 300)
        assertFalse(s.text.contains('�'))
    }

    @Test fun `a single line larger than the cap yields an empty flagged body, never an overrun`() {
        val s = TerminalHistory.decode(TerminalHistory.encode(meta, "x".repeat(500) + "\n", 100), 100)!!
        assertEquals("", s.text); assertTrue(s.truncated)
    }

    @Test fun `foreign or oversized files are refused`() {
        assertNull(TerminalHistory.decode("not a snapshot".toByteArray()))
        assertNull(TerminalHistory.decode(ByteArray(10 * 1024 * 1024)))
        val bad = ("FONEBREW-TERMINAL-HISTORY 1\nsaved: nope\n---\nx").toByteArray()
        assertNull(TerminalHistory.decode(bad))
    }

    @Test fun `header fields cannot be broken out of by newlines in a title`() {
        val s = TerminalHistory.decode(TerminalHistory.encode(meta.copy(title = "a\n---\nb"), "body\n"))!!
        assertEquals("body\n", s.text)
    }
}
