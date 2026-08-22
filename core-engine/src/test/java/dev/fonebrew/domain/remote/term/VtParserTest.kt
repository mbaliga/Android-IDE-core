package dev.fonebrew.domain.remote.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VtParserTest {

    private val esc = 27.toChar().toString()
    private fun screen(rows: Int = 5, cols: Int = 20) = ScreenBuffer(rows, cols)

    @Test fun `printable text lands on the first line and advances the cursor`() {
        val s = screen()
        VtParser(s).feed("hello")
        assertEquals("hello", s.lineText(0))
        assertEquals(Cursor(0, 5), s.cursor)
    }

    @Test fun `CR and LF move the cursor and wrap to the next line`() {
        val s = screen()
        VtParser(s).feed("ab\r\ncd")
        assertEquals("ab", s.lineText(0))
        assertEquals("cd", s.lineText(1))
        assertEquals(Cursor(1, 2), s.cursor)
    }

    @Test fun `auto-wrap at the right margin starts a new line`() {
        val s = screen(rows = 3, cols = 4)
        VtParser(s).feed("abcdef")
        assertEquals("abcd", s.lineText(0))
        assertEquals("ef", s.lineText(1))
    }

    @Test fun `backspace and tab move the cursor`() {
        val s = screen()
        VtParser(s).feed("ab\bX")          // overwrite 'b' with 'X'
        assertEquals("aX", s.lineText(0))
        val t = screen()
        VtParser(t).feed("\tZ")            // tab to col 8
        assertEquals(8, t.cursor.col - 1)
        assertEquals('Z', t.rowsSnapshot()[0][8].char)
    }

    @Test fun `DEL is a control code, not a glyph`() {
        val s = screen()
        VtParser(s).feed("a\u007Fb")
        assertEquals("ab", s.lineText(0))
    }

    @Test fun `CSI absolute cursor position is 1-based`() {
        val s = screen()
        VtParser(s).feed("${esc}[2;3Hx")   // row 2, col 3 → 0-based (1,2)
        assertEquals('x', s.rowsSnapshot()[1][2].char)
    }

    @Test fun `CSI relative cursor moves`() {
        val s = screen()
        VtParser(s).feed("X${esc}[2CY")    // X at (0,0), forward 2, Y at (0,3)
        assertEquals('X', s.rowsSnapshot()[0][0].char)
        assertEquals('Y', s.rowsSnapshot()[0][3].char)
    }

    @Test fun `CHA and VPA set an absolute column and row`() {
        val s = screen()
        val p = VtParser(s)
        p.feed("abcdef${esc}[3GX")         // column 3 (1-based) → index 2
        assertEquals('X', s.rowsSnapshot()[0][2].char)
        p.feed("${esc}[3dY")               // row 3 (1-based) → index 2, column unchanged
        assertEquals('Y', s.rowsSnapshot()[2][3].char)
    }

    @Test fun `erase line and erase display clear cells`() {
        val s = screen(rows = 3, cols = 6)
        VtParser(s).feed("abcdef\r${esc}[K") // CR to col0, erase to line end
        assertEquals("", s.lineText(0))
        val t = screen(rows = 3, cols = 4)
        VtParser(t).feed("aaaa\r\nbbbb${esc}[2J")
        assertEquals("", t.lineText(0))
        assertEquals("", t.lineText(1))
    }

    @Test fun `erase to the START of line and screen (mode 1)`() {
        val s = screen(rows = 2, cols = 6)
        val p = VtParser(s)
        p.feed("abcdef${esc}[1;3H${esc}[1K")   // cursor at col 2, erase back through it
        assertEquals("   def", s.lineText(0))
        val t = screen(rows = 3, cols = 3)
        VtParser(t).feed("aaa\r\nbbb\r\nccc${esc}[2;2H${esc}[1J")
        assertEquals("", t.lineText(0))
        assertEquals("  b", t.lineText(1))
        assertEquals("ccc", t.lineText(2))
    }

    @Test fun `insert and delete LINE shift the rows below`() {
        val s = screen(rows = 4, cols = 4)
        val p = VtParser(s)
        p.feed("aaa\r\nbbb\r\nccc")
        p.feed("${esc}[1;1H${esc}[L")          // home, insert one line
        assertEquals("", s.lineText(0))
        assertEquals("aaa", s.lineText(1))
        p.feed("${esc}[M")                     // and delete it again
        assertEquals("aaa", s.lineText(0))
        assertEquals("bbb", s.lineText(1))
    }

    @Test fun `delete, insert and erase CHAR shift or blank within the line`() {
        val s = screen(rows = 2, cols = 8)
        val p = VtParser(s)
        p.feed("abcdef${esc}[1;2H${esc}[2P")   // cursor on 'b', delete two
        assertEquals("adef", s.lineText(0))
        p.feed("${esc}[2@")                    // put two blanks back
        assertEquals("a  def", s.lineText(0))
        val t = screen(rows = 1, cols = 6)
        VtParser(t).feed("abcdef${esc}[1;2H${esc}[3X")
        assertEquals("a   ef", t.lineText(0))
    }

    @Test fun `CSI S and T scroll the screen`() {
        val s = screen(rows = 3, cols = 4)
        val p = VtParser(s)
        p.feed("aa\r\nbb\r\ncc")
        p.feed("${esc}[S")                     // scroll up one
        assertEquals("bb", s.lineText(0))
        assertEquals("cc", s.lineText(1))
        p.feed("${esc}[T")                     // and back down
        assertEquals("", s.lineText(0))
        assertEquals("bb", s.lineText(1))
    }

    @Test fun `a scroll region confines LF scrolling to its own rows`() {
        val s = screen(rows = 4, cols = 4)
        val p = VtParser(s)
        p.feed("top\r\naa\r\nbb\r\nfoot")
        p.feed("${esc}[2;3r")                  // DECSTBM rows 2..3 (indices 1..2), homes cursor
        p.feed("${esc}[3;1Hx\n")               // to the region's bottom row, write, feed
        assertEquals("top", s.lineText(0))     // outside the region: untouched
        assertEquals("foot", s.lineText(3))
        assertEquals("xb", s.lineText(1))      // the region scrolled, taking "xb" up with it
        // A line pushed out of a *region* never left the screen, so it is not transcript.
        assertTrue(s.scrollbackSnapshot().isEmpty())
    }

    @Test fun `ESC M reverse-indexes and ESC 7 slash 8 save and restore the cursor`() {
        val s = screen(rows = 3, cols = 4)
        val p = VtParser(s)
        p.feed("aa\r\nbb")
        p.feed("${esc}7")                      // DECSC at (1,2)
        p.feed("${esc}[1;1H${esc}M")           // home, then RI scrolls the screen down
        assertEquals("", s.lineText(0))
        assertEquals("aa", s.lineText(1))
        p.feed("${esc}8Z")                     // DECRC, then write there
        assertEquals('Z', s.rowsSnapshot()[1][2].char)
    }

    @Test fun `SGR sets and resets the pen`() {
        val s = screen()
        VtParser(s).feed("${esc}[1;31mR${esc}[0mN")
        val cells = s.rowsSnapshot()[0]
        assertTrue(cells[0].sgr.bold)
        assertEquals(TermColor.Indexed(1), cells[0].sgr.fg)   // 31 → ANSI red index 1
        assertFalse(cells[1].sgr.bold)
        assertEquals(null, cells[1].sgr.fg)
    }

    @Test fun `the bright aixterm colours are a real half of the palette, not a no-op`() {
        val s = screen()
        VtParser(s).feed("${esc}[92mA${esc}[104mB")
        val cells = s.rowsSnapshot()[0]
        assertEquals(TermColor.Indexed(10), cells[0].sgr.fg)  // 92 → bright green
        assertEquals(TermColor.Indexed(12), cells[1].sgr.bg)  // 104 → bright blue background
    }

    // The bug this whole colour rework exists for: the old parser split SGR on ';' and folded
    // every number as a standalone code, so this sequence executed "30" — foreground black —
    // and painted the prompt the darkest colour in the palette on a near-black panel.
    @Test fun `truecolour SGR is read as ONE colour, not as five separate codes`() {
        val s = screen()
        VtParser(s).feed("${esc}[38;2;30;144;255mX")
        assertEquals(TermColor.Rgb(0x1E90FF), s.rowsSnapshot()[0][0].sgr.fg)
    }

    @Test fun `codes after an extended colour still apply`() {
        val s = screen()
        VtParser(s).feed("${esc}[38;2;30;144;255;1;4mX")
        val cell = s.rowsSnapshot()[0][0]
        assertEquals(TermColor.Rgb(0x1E90FF), cell.sgr.fg)
        assertTrue(cell.sgr.bold)
        assertTrue(cell.sgr.underline)
    }

    @Test fun `xterm-256 indexed colour, foreground and background`() {
        val s = screen()
        VtParser(s).feed("${esc}[38;5;99m${esc}[48;5;196mX")
        val cell = s.rowsSnapshot()[0][0]
        assertEquals(TermColor.Indexed(99), cell.sgr.fg)
        assertEquals(TermColor.Indexed(196), cell.sgr.bg)
    }

    @Test fun `the ISO 8613-6 colon form parses the same as the semicolon form`() {
        val s = screen()
        VtParser(s).feed("${esc}[38:2:30:144:255mA${esc}[38:5:99mB")
        val cells = s.rowsSnapshot()[0]
        assertEquals(TermColor.Rgb(0x1E90FF), cells[0].sgr.fg)
        assertEquals(TermColor.Indexed(99), cells[1].sgr.fg)
    }

    @Test fun `39 and 49 return to the terminal's own default colours`() {
        val s = screen()
        VtParser(s).feed("${esc}[38;2;1;2;3m${esc}[41m${esc}[39;49mX")
        val cell = s.rowsSnapshot()[0][0]
        assertEquals(null, cell.sgr.fg)
        assertEquals(null, cell.sgr.bg)
    }

    @Test fun `unknown escape sequences are swallowed, not printed`() {
        val s = screen()
        VtParser(s).feed("${esc}(0a${esc}[99Zb")  // unsupported ESC ( and CSI Z
        assertEquals("ab", s.lineText(0))
    }

    // The parser's own doc has always promised this; ']' fell straight through to GROUND, so a
    // title-setting prompt (or vim, or tmux) printed its OSC payload as literal text.
    @Test fun `OSC payloads are swallowed through BEL or ST, not printed`() {
        val s = screen()
        VtParser(s).feed("${esc}]0;my title\u0007hello")
        assertEquals("hello", s.lineText(0))
        val t = screen()
        VtParser(t).feed("${esc}]0;another title${esc}\\hi")
        assertEquals("hi", t.lineText(0))
    }

    @Test fun `DCS and APC strings are swallowed too`() {
        val s = screen()
        VtParser(s).feed("${esc}P1\$r0m${esc}\\${esc}_payload${esc}\\ok")
        assertEquals("ok", s.lineText(0))
    }

    @Test fun `CSI finals and private modes we do not implement are swallowed, never printed`() {
        val s = screen()
        // status report, cursor style (with an intermediate byte), window op, bracketed paste,
        // mouse tracking — all things a curses app or a modern prompt emits routinely.
        VtParser(s).feed("${esc}[6n${esc}[ q${esc}[18t${esc}[?2004h${esc}[?1000h${esc}[>4;2mok")
        assertEquals("ok", s.lineText(0))
    }

    @Test fun `DECTCEM hides and shows the cursor`() {
        val s = screen()
        val p = VtParser(s)
        p.feed("${esc}[?25l")
        assertFalse(s.cursorVisible)
        p.feed("${esc}[?25h")
        assertTrue(s.cursorVisible)
    }

    // Without the alternate screen, vi/nano/htop paint their full-screen UI straight into the
    // transcript and leave the wreckage on screen when they exit.
    @Test fun `the alternate screen keeps a full-screen app out of the transcript`() {
        val s = screen(rows = 3, cols = 6)
        val p = VtParser(s)
        p.feed("shell\r\n")
        p.feed("${esc}[?1049h")
        p.feed("vim ui\r\nmore\r\nyet\r\nover")   // enough output to scroll the alt screen
        assertTrue(s.altScreen)
        assertTrue(s.scrollbackSnapshot().isEmpty())
        p.feed("${esc}[?1049l")
        assertFalse(s.altScreen)
        assertEquals("shell", s.lineText(0))     // the shell's screen, exactly as it was
        assertEquals(Cursor(1, 0), s.cursor)     // and the cursor it left behind
    }

    @Test fun `the older 47h alt-screen pair works the same way`() {
        val s = screen(rows = 2, cols = 4)
        val p = VtParser(s)
        p.feed("main")
        p.feed("${esc}[?47h")
        assertEquals("", s.lineText(0))
        p.feed("alt")
        p.feed("${esc}[?47l")
        assertEquals("main", s.lineText(0))
    }
}
