package dev.fonebrew.domain.editor.buffer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphemeAndUnitsTest {
    private fun clusters(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) { val e = GraphemeBreaks.next(s, i); out += s.substring(i, e); i = e }
        return out
    }

    @Test fun `ascii is one cluster per char`() = assertEquals(listOf("a", "b"), clusters("ab"))

    @Test fun `a surrogate pair is never split`() {
        val smile = "\uD83D\uDE00"
        assertEquals(listOf("a", smile, "b"), clusters("a${smile}b"))
        assertFalse(TextUnits.isCodePointBoundary("a${smile}b", 2))
        assertEquals(1, TextUnits.floorCodePoint("a${smile}b", 2))
    }

    @Test fun `combining marks stay with their base`() {
        assertEquals(listOf("e\u0301", "x"), clusters("e\u0301x"))
        assertEquals(listOf("a\u0300\u0301\u0302"), clusters("a\u0300\u0301\u0302"))
    }

    @Test fun `CRLF is one cluster but LF CR is two`() {
        assertEquals(listOf("\r\n", "a"), clusters("\r\na"))
        assertEquals(listOf("\n", "\r"), clusters("\n\r"))
    }

    @Test fun `emoji ZWJ sequence and modifier are one cluster`() {
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"
        assertEquals(listOf(family), clusters(family))
        val thumbs = "\uD83D\uDC4D\uD83C\uDFFD"
        assertEquals(listOf(thumbs), clusters(thumbs))
    }

    @Test fun `regional indicators pair up`() {
        val fr = "\uD83C\uDDEB\uD83C\uDDF7"
        val de = "\uD83C\uDDE9\uD83C\uDDEA"
        assertEquals(listOf(fr, de), clusters(fr + de))
        assertEquals(3, clusters(fr + de + "\uD83C\uDDFA").size) // an odd one out stands alone
    }

    @Test fun `hangul jamo compose into a syllable cluster`() {
        assertEquals(listOf("\u1100\u1161\u11A8"), clusters("\u1100\u1161\u11A8"))
    }

    @Test fun `variation selector and keycap stay attached`() {
        assertEquals(listOf("1\uFE0F\u20E3"), clusters("1\uFE0F\u20E3"))
    }

    @Test fun `previous walks back one cluster and floor snaps into a cluster start`() {
        val s = "ae\u0301\uD83D\uDE00z"
        assertEquals(listOf(0, 1, 3, 5, 6), listOf(0) + clusters(s).runningFold(0) { acc, c -> acc + c.length }.drop(1))
        assertEquals(5, GraphemeBreaks.previous(s, 6))
        assertEquals(3, GraphemeBreaks.previous(s, 5))
        assertEquals(1, GraphemeBreaks.previous(s, 3))
        assertEquals(0, GraphemeBreaks.previous(s, 1))
        assertEquals(1, GraphemeBreaks.floor(s, 2))       // inside e + combining acute
        assertEquals(3, GraphemeBreaks.floor(s, 4))       // inside the surrogate pair
        assertTrue(GraphemeBreaks.isBoundary(s, 3))
        assertFalse(GraphemeBreaks.isBoundary(s, 2))
    }

    @Test fun `a lone surrogate is a cluster of its own and does not loop`() {
        assertEquals(listOf("\uD83D", "a"), clusters("\uD83Da"))
        assertEquals(listOf("\uDE00", "a"), clusters("\uDE00a"))
    }

    @Test fun `a very long combining run is bounded`() {
        val s = "a" + "\u0301".repeat(5000) + "b"
        assertEquals(2, clusters(s).size)
        assertTrue(GraphemeBreaks.previous(s, s.length) < s.length)
    }

    @Test fun `unit conversions are explicit`() {
        val s = "a\uD83D\uDE00e\u0301b"
        assertEquals(6, s.length)
        assertEquals(5, TextUnits.codePointsBefore(s, s.length))   // a, emoji, e, combining, b
        assertEquals(4, TextUnits.graphemesBefore(s, s.length))
        assertEquals(3, TextUnits.offsetOfCodePoint(s, 2))
        assertEquals(5, TextUnits.offsetOfGrapheme(s, 3))
    }

    @Test fun `position conversion on a rope`() {
        val r = Rope.of("ab\r\ncd\nef")
        assertEquals(Position(1, 1), TextUnits.positionOf(r, 5))
        assertEquals(5, TextUnits.offsetOf(r, 1, 1))
        assertEquals(6, TextUnits.offsetOf(r, 1, 99))   // clamped to the content end, never into the terminator
        assertEquals(Position(0, 2), TextUnits.positionOf(r, 2))
    }
}
