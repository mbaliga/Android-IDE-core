package dev.fonebrew.domain.editor.buffer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RopeTest {

    /** Naive line model: start offsets of each line, terminators `\n`, `\r\n`, lone `\r`. */
    private fun naiveStarts(s: String): List<Int> {
        val out = arrayListOf(0)
        var i = 0
        while (i < s.length) {
            if (s[i] == '\n') out += i + 1
            else if (s[i] == '\r') { if (i + 1 < s.length && s[i + 1] == '\n') { i++ }; out += i + 1 }
            i++
        }
        return out
    }

    private fun checkAgainst(rope: Rope, s: String) {
        assertEquals(s.length, rope.length)
        assertEquals(s, rope.toString())
        val starts = naiveStarts(s)
        assertEquals("line count for ${s.take(60)}", starts.size, rope.lineCount)
        for ((l, st) in starts.withIndex()) assertEquals("lineStart($l) in ${s.take(60).replace("\r", "\\r").replace("\n", "\\n")}", st, rope.lineStart(l))
    }

    @Test fun `empty rope`() {
        assertEquals(0, Rope.EMPTY.length)
        assertEquals(1, Rope.EMPTY.lineCount)
        assertEquals("", Rope.EMPTY.lineText(0))
    }

    @Test fun `lines of a mixed-terminator text`() {
        val s = "a\nbb\r\nccc\rdddd"
        val r = Rope.of(s)
        assertEquals(4, r.lineCount)
        assertEquals(listOf("a", "bb", "ccc", "dddd"), (0 until 4).map { r.lineText(it) })
        assertEquals(listOf("\n", "\r\n", "\r", ""), (0 until 4).map { r.terminatorAt(it) })
        assertEquals(0, r.lineOfOffset(0)); assertEquals(0, r.lineOfOffset(1))
        assertEquals(1, r.lineOfOffset(2)); assertEquals(1, r.lineOfOffset(5)) // between \r and \n stays on the line it ends
        assertEquals(2, r.lineOfOffset(7)); assertEquals(3, r.lineOfOffset(s.length))
    }

    @Test fun `final newline gives an empty last line`() {
        val r = Rope.of("x\n")
        assertEquals(2, r.lineCount)
        assertEquals("", r.lineText(1))
    }

    @Test fun `inserting between CR and LF splits the pair into two breaks`() {
        val r = Rope.of("a\r\nb").insert(2, "X")
        checkAgainst(r, "a\rX\nb")
    }

    @Test fun `deleting between to join CR with LF merges two breaks`() {
        val r = Rope.of("a\rX\nb").delete(2, 3)
        checkAgainst(r, "a\r\nb")
        assertEquals(2, r.lineCount)
    }

    @Test fun `large text spans many chunks and keeps the index exact`() {
        val sb = StringBuilder()
        for (i in 0 until 5000) sb.append("line number ").append(i).append(if (i % 3 == 0) "\r\n" else "\n")
        val s = sb.toString()
        val r = Rope.of(s)
        checkAgainst(r, s)
        assertEquals(s.substring(1234, 9999), r.substring(1234, 9999))
        assertEquals(s[4321], r[4321])
    }

    @Test fun `a single huge line does not blow up`() {
        val s = "x".repeat(3_000_000)
        val r = Rope.of(s).insert(1_500_000, "YY")
        assertEquals(3_000_002, r.length)
        assertEquals('Y', r[1_500_000])
        assertEquals(1, r.lineCount)
    }

    @Test fun `snapshots are immutable`() {
        val a = Rope.of("hello")
        val b = a.insert(5, " world")
        assertEquals("hello", a.toString())
        assertEquals("hello world", b.toString())
    }

    @Test fun `random edits equal a naive String model and the line index stays exact`() {
        val rnd = Random(20260826)
        val alphabet = listOf("a", "b", "xyz", "\n", "\r", "\r\n", "\uD83D\uDE00", " ", "e\u0301", "long text chunk ".repeat(40))
        var model = ""
        var rope = Rope.EMPTY
        repeat(10_000) { step ->
            val len = model.length
            val s = rnd.nextInt(len + 1)
            val e = minOf(len, s + if (rnd.nextInt(4) == 0) rnd.nextInt(30) else 0)
            val ins = if (rnd.nextInt(5) == 0) "" else (0..rnd.nextInt(3)).joinToString("") { alphabet[rnd.nextInt(alphabet.size)] }
            model = model.substring(0, s) + ins + model.substring(e)
            rope = rope.replace(s, e, ins)
            assertEquals("length at step $step", model.length, rope.length)
            if (step % 97 == 0 || model.length < 200) checkAgainst(rope, model)
        }
        checkAgainst(rope, model)
    }

    @Test fun `lineOfOffset agrees with the naive model at every offset`() {
        val rnd = Random(7)
        val alphabet = listOf("a", "\n", "\r", "\r\n", "bc")
        val s = (0 until 400).joinToString("") { alphabet[rnd.nextInt(alphabet.size)] }
        val r = Rope.of(s)
        val starts = naiveStarts(s)
        for (o in 0..s.length) {
            val expected = starts.indexOfLast { it <= o }.let { idx ->
                // an offset between \r and \n is still on the line that terminator ends
                if (o in 1 until s.length && s[o - 1] == '\r' && s[o] == '\n') starts.indexOfLast { it <= o - 1 } else idx
            }
            assertEquals("offset $o", expected, r.lineOfOffset(o))
        }
    }

    @Test fun `chunk count stays bounded under keystroke edits`() {
        var r = Rope.EMPTY
        repeat(5000) { r = r.insert(r.length, "x") }
        var chunks = 0
        r.forEachChunk { chunks++ }
        assertTrue("chunks=$chunks", chunks <= 5000 / (Rope.MAX_CHUNK / 2) + 2)
    }
}
