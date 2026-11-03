package dev.fonebrew.domain.remote.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenBufferTest {

    private fun text(row: List<Cell>) = row.joinToString("") { it.char.toString() }.trimEnd()

    @Test fun `line feed at the bottom scrolls the top line into scrollback`() {
        val s = ScreenBuffer(rows = 2, cols = 4)
        val p = VtParser(s)
        p.feed("aa\r\nbb\r\ncc")     // three lines into a 2-row screen
        assertEquals("bb", s.lineText(0))
        assertEquals("cc", s.lineText(1))
        val back = s.scrollbackSnapshot()
        assertEquals(1, back.size)
        assertEquals("aa", text(back[0]))
    }

    @Test fun `scrollback is capped at maxScrollback`() {
        val s = ScreenBuffer(rows = 1, cols = 2, maxScrollback = 3)
        val p = VtParser(s)
        repeat(10) { p.feed("x\r\n") }
        assertTrue(s.scrollbackSnapshot().size <= 3)
    }

    /** The renderer asks for a bounded tail rather than all thousand lines — composing every
     *  one of them on each output chunk is a phone-scale cost for lines nobody scrolled to. */
    @Test fun `scrollbackSnapshot can return just the newest lines`() {
        val s = ScreenBuffer(rows = 1, cols = 4)
        val p = VtParser(s)
        for (i in 1..5) p.feed("l$i\r\n")
        val tail = s.scrollbackSnapshot(limit = 2)
        assertEquals(2, tail.size)
        assertEquals("l4", text(tail[0]))
        assertEquals("l5", text(tail[1]))
    }

    /**
     * Resize used to be dead code that kept the top-left corner. Now that the view drives the
     * real window size in, shrinking is the ordinary case (the soft keyboard takes rows away) and
     * keeping the top would push the live prompt — the line the cursor is on — off the bottom.
     */
    @Test fun `shrinking keeps the cursor's line and banks the dropped rows as scrollback`() {
        val s = ScreenBuffer(rows = 4, cols = 6)
        val p = VtParser(s)
        p.feed("one\r\ntwo\r\nthree\r\nprompt")   // cursor ends on row 3
        s.resize(2, 6)
        assertEquals("three", s.lineText(0))
        assertEquals("prompt", s.lineText(1))
        assertEquals(1, s.cursor.row)             // still on the prompt
        val back = s.scrollbackSnapshot()
        assertEquals(listOf("one", "two"), back.map { text(it) })
    }

    @Test fun `shrinking never drops past the cursor's own row`() {
        val s = ScreenBuffer(rows = 10, cols = 4)
        VtParser(s).feed("top")                   // cursor still on row 0
        s.resize(3, 4)
        assertEquals("top", s.lineText(0))        // nothing above the cursor to give up
        assertEquals(0, s.cursor.row)
        assertTrue(s.scrollbackSnapshot().isEmpty())
    }

    @Test fun `resize preserves overlapping content and clamps the cursor`() {
        val s = ScreenBuffer(rows = 3, cols = 6)
        VtParser(s).feed("hello")
        s.resize(3, 3)
        assertEquals("hel", s.lineText(0))        // overlap kept
        assertTrue(s.cursor.row <= 2 && s.cursor.col <= 2) // clamped
    }

    @Test fun `resize resets the scroll region to the new screen`() {
        val s = ScreenBuffer(rows = 6, cols = 4)
        s.setScrollRegion(1, 3)
        s.resize(4, 4)
        assertEquals(0, s.scrollTop)
        assertEquals(3, s.scrollBottom)
    }

    @Test fun `the alternate screen never feeds scrollback`() {
        val s = ScreenBuffer(rows = 2, cols = 4)
        s.enterAltScreen()
        val p = VtParser(s)
        p.feed("aa\r\nbb\r\ncc")
        assertTrue(s.scrollbackSnapshot().isEmpty())
        s.exitAltScreen()
        assertEquals("", s.lineText(0))           // the (blank) main screen is back
    }

    @Test fun `saved cursor survives a restore, pen and all`() {
        val s = ScreenBuffer(rows = 3, cols = 4)
        s.setCursor(2, 1)
        s.pen = Sgr(fg = TermColor.Indexed(3), bold = true)
        s.saveCursor()
        s.setCursor(0, 0)
        s.pen = Sgr.DEFAULT
        s.restoreCursor()
        assertEquals(Cursor(2, 1), s.cursor)
        assertEquals(TermColor.Indexed(3), s.pen.fg)
        assertTrue(s.pen.bold)
    }
}
