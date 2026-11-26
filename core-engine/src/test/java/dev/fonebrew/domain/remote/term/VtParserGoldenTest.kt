package dev.fonebrew.domain.remote.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Authored byte transcripts in the shape of what full-screen programs emit (vim-like, less-like,
 * htop-like). They are written from the public control-sequence documentation, NOT captured from
 * those programs: they prove the parser's behaviour on that class of stream, not that the real
 * programs render correctly on a device (owner-verified).
 */
class VtParserGoldenTest {
    private val ESC = "\u001B"

    @Test fun `vim-like session enters alt screen paints with a status line and restores the shell`() {
        val s = ScreenBuffer(6, 20); val m = TerminalModes(); val p = VtParser(s, m)
        p.feed("\$ vim x\r\n")
        p.feed("$ESC[?1049h$ESC[?1h$ESC=$ESC[22;0;0t$ESC[2J$ESC[H")      // alt screen, app cursor keys, keypad, title push
        p.feed("hello world$ESC[2;1H~$ESC[3;1H~$ESC[4;1H~$ESC[5;1H~")
        p.feed("$ESC[6;1H$ESC[7m\"x\" 1L, 11B$ESC[0m$ESC[K$ESC[1;6H")
        assertTrue(s.altScreen); assertTrue(m.applicationCursorKeys)
        assertEquals("hello world", s.lineText(0)); assertEquals("~", s.lineText(1))
        assertTrue(s.lineText(5).startsWith("\"x\" 1L, 11B"))
        assertTrue(s.rowsSnapshot()[5][0].sgr.inverse)
        assertEquals(0, s.cursor.row); assertEquals(5, s.cursor.col)
        assertEquals("nothing leaked to scrollback from the alt screen", 0, s.scrollbackSize())
        p.feed("$ESC[?1l$ESC>$ESC[?1049l")
        assertFalse(s.altScreen); assertFalse(m.applicationCursorKeys)
        assertEquals("\$ vim x", s.lineText(0))
    }

    @Test fun `less-like pager uses a scroll region pinned status line and reverse index`() {
        val s = ScreenBuffer(5, 12); val p = VtParser(s)
        p.feed("$ESC[?1049h$ESC[1;4r$ESC[H")            // text pane = rows 1-4, status on row 5
        for (i in 1..6) p.feed("line$i\r\n")
        p.feed("$ESC[5;1H$ESC[7m:$ESC[0m$ESC[H$ESC[M$ESC[H$ESC[L")  // status, delete line, insert line at top
        // 6 lines through a 4-row region leave line4..line6; DL then IL at the top leave ["", line5, line6, ""].
        assertEquals(":", s.lineText(4))
        assertEquals("", s.lineText(0)); assertEquals("line5", s.lineText(1)); assertEquals("line6", s.lineText(2))
        p.feed("$ESC[H$ESC[M$ESC[4;1H$ESC[T")   // DL at top -> [line5, line6, "", ""], then SD -> ["", line5, line6, ""]
        assertEquals(":", s.lineText(4))
        assertEquals("", s.lineText(0)); assertEquals("line5", s.lineText(1)); assertEquals("line6", s.lineText(2))
        assertEquals("the pinned status line never entered scrollback", 0, s.scrollbackSize())
    }

    @Test fun `htop-like repaint with colours cursor hide and erase`() {
        val s = ScreenBuffer(4, 24); val p = VtParser(s)
        p.feed("$ESC[?1049h$ESC[?25l$ESC[H$ESC[2J")
        p.feed("$ESC[1;1H$ESC[38;5;46m  1$ESC[0m [||||    25%]")
        p.feed("$ESC[2;1H$ESC[48;2;0;0;128m$ESC[K PID USER$ESC[0m")
        p.feed("$ESC[3;5H$ESC[1m$ESC[32mroot$ESC[0m$ESC[3;1H$ESC[5@>")
        assertFalse(s.cursorVisible)
        assertEquals("  1 [||||    25%]", s.lineText(0))
        val row0 = s.rowsSnapshot()[0]
        assertEquals(TermColor.Indexed(46), row0[2].sgr.fg)
        assertEquals(TermColor.Rgb(0x000080), s.rowsSnapshot()[1][23].sgr.bg)  // erase-line painted with the pen
        assertEquals(">", s.rowsSnapshot()[2][0].char.toString())               // ICH inserted 5 blanks then wrote '>'
        p.feed("$ESC[?25h$ESC[?1049l")
        assertTrue(s.cursorVisible)
    }

    @Test fun `a transcript fed one character at a time equals feeding it whole`() {
        val t = "$ESC[?1049h$ESC[2J$ESC[H$ESC[38;2;1;2;3mhi$ESC[0mé😀$ESC]0;t\u0007$ESC[5;5Hx$ESC[?1049l"
        val a = ScreenBuffer(6, 20); VtParser(a).feed(t)
        val b = ScreenBuffer(6, 20); val pb = VtParser(b); for (c in t) pb.feed(c.toString())
        assertEquals(a.rowsSnapshot(), b.rowsSnapshot())
        assertEquals(a.cursor, b.cursor)
    }
}
