package dev.fonebrew.domain.remote.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenBufferWideReflowTest {
    private fun feed(s: ScreenBuffer, t: String) = VtParser(s).feed(t)

    @Test fun `width table facts`() {
        assertEquals(1, WcWidth.of('a'.code)); assertEquals(2, WcWidth.of('中'.code)); assertEquals(2, WcWidth.of(0xFF21))
        assertEquals(2, WcWidth.of(0x1F600)); assertEquals(0, WcWidth.of(0x0301)); assertEquals(0, WcWidth.of(0x200B))
        assertEquals(1, WcWidth.of(0xE9)); assertEquals(1, WcWidth.of(0xAD)); assertEquals(2, WcWidth.of(0xAC00)); assertEquals(1, WcWidth.of(0x378))
    }

    @Test fun `a wide character takes two cells and the continuation is width zero`() {
        val s = ScreenBuffer(1, 6); feed(s, "a中b")
        val row = s.rowsSnapshot()[0]
        assertEquals(listOf(1, 2, 0, 1), row.take(4).map { it.width })
        assertEquals("a中b", s.lineText(0)); assertEquals(4, s.cursor.col)
    }

    @Test fun `a wide character that does not fit wraps whole`() {
        val s = ScreenBuffer(2, 4); feed(s, "abc中")
        assertEquals("abc", s.lineText(0)); assertEquals("中", s.lineText(1))
        assertTrue(s.visibleLines()[0].wrapped)
    }

    @Test fun `combining marks attach to the previous cell`() {
        val s = ScreenBuffer(1, 6); feed(s, "éx")
        assertEquals("éx", s.lineText(0)); assertEquals(2, s.cursor.col)
        assertEquals("é", s.rowsSnapshot()[0][0].glyph)
    }

    @Test fun `non-BMP glyph is stored whole`() {
        val s = ScreenBuffer(1, 6); feed(s, "😀")
        assertEquals("😀", s.rowsSnapshot()[0][0].glyph)
    }

    @Test fun `overwriting half of a wide character blanks the other half`() {
        val s = ScreenBuffer(1, 6); feed(s, "中\u001B[1Gx")
        val row = s.rowsSnapshot()[0]
        assertEquals('x', row[0].char); assertEquals(1, row[1].width); assertEquals(' ', row[1].char)
    }

    @Test fun `scrollback is a bounded ring by lines`() {
        val s = ScreenBuffer(2, 4, maxScrollback = 50)
        for (i in 1..500) feed(s, "l$i\r\n")
        assertEquals(50, s.scrollbackSize())
        assertEquals("l450", s.scrollbackLines()[0].cells.joinToString("") { it.glyph }.trim())
    }

    @Test fun `scrollback setting is clamped to the hard cap and can be changed`() {
        assertEquals(ScreenBuffer.HARD_MAX_SCROLLBACK, ScreenBuffer(2, 4, maxScrollback = 10_000_000).maxScrollback)
        assertEquals(0, ScreenBuffer(2, 4, maxScrollback = -5).maxScrollback)
        val s = ScreenBuffer(2, 4, maxScrollback = 100)
        for (i in 1..100) feed(s, "x\r\n")
        s.setMaxScrollback(10); assertEquals(10, s.scrollbackSize())
    }

    @Test fun `scrollback is also bounded by a cell budget`() {
        val s = ScreenBuffer(2, 100, maxScrollback = 100_000, scrollbackCellBudget = 1_000)
        for (i in 1..2_000) feed(s, "x".repeat(100) + "\r\n")
        assertTrue("cells ${s.scrollbackCells()}", s.scrollbackCells() <= 1_000)
        assertTrue(s.scrollbackSize() in 1..11)
    }

    @Test fun `trailing blanks are trimmed in scrollback to save memory`() {
        val s = ScreenBuffer(2, 200); feed(s, "a\r\nb\r\nc\r\n")
        assertTrue(s.scrollbackCells() < 10)
    }

    @Test fun `a long line wraps with soft-wrap flags and copies as one line`() {
        val s = ScreenBuffer(4, 5); feed(s, "abcdefghijkl\r\nnext")
        val v = s.visibleLines()
        assertTrue(v[0].wrapped); assertTrue(v[1].wrapped); assertFalse(v[2].wrapped)
        assertEquals("abcdefghijkl\nnext\n", s.transcriptText())
    }

    @Test fun `widening reflows a soft-wrapped line back together`() {
        val s = ScreenBuffer(4, 5); feed(s, "abcdefghij\r\n\$ ")
        s.resize(4, 10)
        assertEquals("abcdefghij", s.lineText(0)); assertEquals("\$", s.lineText(1))
        assertFalse(s.visibleLines()[0].wrapped)
        assertEquals(1, s.cursor.row); assertEquals(2, s.cursor.col)
    }

    @Test fun `narrowing re-wraps and keeps the cursor line visible`() {
        val s = ScreenBuffer(3, 10); feed(s, "abcdefghij\r\nprompt")
        s.resize(3, 5)
        val all = s.transcriptText()
        assertEquals("abcdefghij\nprompt\n", all)
        assertEquals("t", s.lineText(s.cursor.row))
    }

    @Test fun `hard line breaks are never joined by reflow`() {
        val s = ScreenBuffer(4, 6); feed(s, "ab\r\ncd\r\nef")
        s.resize(4, 20)
        assertEquals("ab\ncd\nef\n", s.transcriptText())
        assertEquals("ab", s.lineText(0)); assertEquals("cd", s.lineText(1))
    }

    @Test fun `wide characters are never split by reflow`() {
        val s = ScreenBuffer(4, 6); feed(s, "中文字符串\r\n")
        s.resize(4, 5)
        for (row in s.visibleLines() + s.scrollbackLines()) {
            val cells = row.cells
            for (i in cells.indices) {
                if (cells[i].width == 2) assertTrue(i + 1 < cells.size && cells[i + 1].width == 0)
                if (cells[i].width == 0) assertTrue(i > 0 && cells[i - 1].width == 2)
            }
        }
        assertEquals("中文字符串\n", s.transcriptText())
    }

    @Test fun `resize on the alt screen crops and does not reflow, main screen is reflowed behind it`() {
        val s = ScreenBuffer(3, 10); feed(s, "abcdefghij")
        feed(s, "\u001B[?1049hALTSCREEN")
        s.resize(3, 5)
        assertEquals("ALTSC", s.lineText(0))
        feed(s, "\u001B[?1049l")
        assertEquals("abcdefghij\n", s.transcriptText())
    }

    @Test fun `shrinking rows banks the top as scrollback and growing pulls it back`() {
        val s = ScreenBuffer(4, 6); feed(s, "one\r\ntwo\r\nthree\r\nprompt")
        s.resize(2, 6)
        assertEquals("prompt", s.lineText(s.cursor.row)); assertEquals(2, s.scrollbackSize())
        s.resize(4, 6)
        assertEquals("one", s.lineText(0)); assertEquals(0, s.scrollbackSize())
        assertEquals("prompt", s.lineText(s.cursor.row))
    }
}
