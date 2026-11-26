package dev.fonebrew.domain.remote.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineRingTest {
    @Test fun `evicts the oldest by line count and keeps order across wraparound`() {
        val r = LineRing<String>(3, 1000) { it.length }
        for (i in 1..10) r.push("l$i")
        assertEquals(listOf("l8", "l9", "l10"), r.toList())
        assertEquals(3, r.size)
    }

    @Test fun `cell budget evicts even below the line cap and tracks the total`() {
        val r = LineRing<String>(100, 10) { it.length }
        repeat(20) { r.push("abcd") }       // 4 cells each, budget 10 -> at most 2 lines
        assertTrue(r.size <= 2)
        assertTrue(r.cells <= 10)
        assertEquals(r.size * 4, r.cells)
    }

    @Test fun `a single oversize line is kept alone rather than emptying the ring`() {
        val r = LineRing<String>(10, 5) { it.length }
        r.push("x"); r.push("0123456789")
        assertEquals(listOf("0123456789"), r.toList())
    }

    @Test fun `lowering the line limit drops the oldest and zero keeps nothing`() {
        val r = LineRing<String>(10, 1000) { 1 }
        for (i in 1..5) r.push("l$i")
        r.setMaxLines(2)
        assertEquals(listOf("l4", "l5"), r.toList())
        r.setMaxLines(0); r.push("z")
        assertEquals(0, r.size)
    }

    @Test fun `screen scrollback honours the setting, the hard cap and the cell budget`() {
        assertEquals(ScreenBuffer.DEFAULT_SCROLLBACK, ScreenBuffer(2, 5).maxScrollback)
        assertEquals(5000, ScreenBuffer.DEFAULT_SCROLLBACK)
        assertEquals(100_000, ScreenBuffer(2, 5, maxScrollback = 10_000_000).maxScrollback)
        val s = ScreenBuffer(1, 10, maxScrollback = 50_000, scrollbackCellBudget = 200)
        val p = VtParser(s)
        repeat(1000) { p.feed("0123456789\r\n") }   // soft-trimmed rows still count real cells
        assertTrue(s.scrollbackCells() <= 200)
        assertTrue(s.scrollbackSize() <= 20)
    }
}
