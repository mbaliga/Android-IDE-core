package dev.aarso.domain.remote.term

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

    @Test fun `erase line and erase display clear cells`() {
        val s = screen(rows = 3, cols = 6)
        VtParser(s).feed("abcdef\r${esc}[K") // CR to col0, erase to line end
        assertEquals("", s.lineText(0))
        val t = screen(rows = 3, cols = 4)
        VtParser(t).feed("aaaa\r\nbbbb${esc}[2J")
        assertEquals("", t.lineText(0))
        assertEquals("", t.lineText(1))
    }

    @Test fun `SGR sets and resets the pen`() {
        val s = screen()
        VtParser(s).feed("${esc}[1;31mR${esc}[0mN")
        val cells = s.rowsSnapshot()[0]
        assertTrue(cells[0].sgr.bold)
        assertEquals(1, cells[0].sgr.fg)   // 31 → ANSI red index 1
        assertFalse(cells[1].sgr.bold)
        assertEquals(null, cells[1].sgr.fg)
    }

    @Test fun `unknown escape sequences are swallowed, not printed`() {
        val s = screen()
        VtParser(s).feed("${esc}(0a${esc}[99Zb")  // unsupported ESC ( and CSI Z
        assertEquals("ab", s.lineText(0))
    }
}
