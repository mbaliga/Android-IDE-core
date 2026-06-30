package dev.aarso.domain.remote.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenBufferTest {

    @Test fun `line feed at the bottom scrolls the top line into scrollback`() {
        val s = ScreenBuffer(rows = 2, cols = 4)
        val p = VtParser(s)
        p.feed("aa\r\nbb\r\ncc")     // three lines into a 2-row screen
        assertEquals("bb", s.lineText(0))
        assertEquals("cc", s.lineText(1))
        val back = s.scrollbackSnapshot()
        assertEquals(1, back.size)
        assertEquals("aa", back[0].joinToString("") { it.char.toString() }.trimEnd())
    }

    @Test fun `scrollback is capped at maxScrollback`() {
        val s = ScreenBuffer(rows = 1, cols = 2, maxScrollback = 3)
        val p = VtParser(s)
        repeat(10) { p.feed("x\r\n") }
        assertTrue(s.scrollbackSnapshot().size <= 3)
    }

    @Test fun `resize preserves overlapping content and clamps the cursor`() {
        val s = ScreenBuffer(rows = 3, cols = 6)
        VtParser(s).feed("hello")
        s.setCursor(2, 5)
        s.resize(2, 3)
        assertEquals("hel", s.lineText(0))   // overlap kept
        assertTrue(s.cursor.row <= 1 && s.cursor.col <= 2) // clamped
    }
}
