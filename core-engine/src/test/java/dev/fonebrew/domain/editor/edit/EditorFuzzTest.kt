package dev.fonebrew.domain.editor.edit

import dev.fonebrew.domain.editor.buffer.GraphemeBreaks
import dev.fonebrew.domain.editor.buffer.TextBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The critical property tests: a random operation sequence against an independent naive String model,
 * and undo-all returning the original text exactly.
 */
class EditorFuzzTest {

    private val chunks = listOf(
        "a", "b", "word", " ", "  ", "\n", "\r\n", "\t", "(", ")", "{", "}", "[", "\"", "'", "`", "//", "é", "é",
        "😀", "👨‍👩", "漢字", "line one\nline two\n", "x = 1;", "\r",
    )

    private class Naive(var s: String) {
        fun apply(tx: Transaction) {
            for (e in tx.edits.asReversed()) s = s.substring(0, e.start) + e.text + s.substring(e.end)
        }
    }

    private fun randomText(rnd: Random, n: Int) = (0 until n).joinToString("") { chunks[rnd.nextInt(chunks.size)] }

    private fun op(e: Editor, rnd: Random, now: Long) {
        val len = e.buffer.length
        when (rnd.nextInt(24)) {
            0, 1, 2 -> e.typeText(randomText(rnd, 1 + rnd.nextInt(2)), now)
            3, 4 -> e.typeChar(chunks[rnd.nextInt(chunks.size)].take(1), now)
            5 -> e.insertNewline(now)
            6, 7 -> e.backspace(now)
            8 -> e.deleteForward(now)
            9 -> e.deleteWordBackward(now)
            10 -> e.deleteWordForward(now)
            11 -> e.indent(now)
            12 -> e.outdent(now)
            13 -> e.toggleLineComment(now)
            14 -> e.moveLines(rnd.nextBoolean(), now)
            15 -> e.duplicateLines(now)
            16 -> e.deleteLines(now)
            17 -> e.paste(randomText(rnd, 1 + rnd.nextInt(4)), now)
            18 -> { val a = rnd.nextInt(len + 1); val b = rnd.nextInt(len + 1); e.setSelection(SelectionSet.single(Selection(a, b))) }
            19 -> e.setCaret(rnd.nextInt(len + 1))
            20 -> {
                val cs = (0..rnd.nextInt(3)).map { Selection.caret(rnd.nextInt(len + 1)) }
                e.setSelection(SelectionSet.of(cs))
            }
            21 -> e.indentLines(now)
            22 -> { e.moveRight(rnd.nextBoolean()); assertTrue(GraphemeBreaks.isBoundary(e.rope.asCharSequence(), e.selections.primary.head)) }
            else -> { e.moveLeft(rnd.nextBoolean()); assertTrue(GraphemeBreaks.isBoundary(e.rope.asCharSequence(), e.selections.primary.head)) }
        }
    }

    private fun checkSelections(e: Editor, step: Int) {
        val len = e.buffer.length
        for (s in e.selections.ranges) assertTrue("selection $s out of 0..$len at step $step", s.anchor in 0..len && s.head in 0..len)
        val sorted = e.selections.ranges
        for (i in 1 until sorted.size) assertTrue("overlap at step $step", sorted[i].start >= sorted[i - 1].end)
    }

    @Test fun `10000 random operations match a naive model and undo-all restores the original`() {
        for (seed in listOf(1L, 2L, 3L)) {
            val rnd = Random(seed)
            val original = "fun main() {\n    println(\"hi\")\r\n}\n\tkept\n"
            val e = Editor(TextBuffer(original), CommentSyntax.SLASH, null, UndoHistory(maxEntries = 1_000_000, maxChars = Int.MAX_VALUE))
            val naive = Naive(original)
            e.addListener { _, _, tx, _ -> naive.apply(tx) }
            var now = 0L
            repeat(10_000) { step ->
                now += if (rnd.nextInt(3) == 0) 3000 else rnd.nextLong(0, 300)
                op(e, rnd, now)
                assertEquals("buffer vs naive model after op $step (seed $seed)", naive.s, e.text)
                checkSelections(e, step)
            }
            val finalText = e.text
            var undone = 0
            while (e.undo()) { undone++; assertEquals(naive.s, e.text) }
            assertTrue(undone > 0)
            assertEquals("undo-all must restore the original exactly (seed $seed)", original, e.text)
            assertFalse(e.isDirty)
            var redone = 0
            while (e.redo()) { redone++; assertEquals(naive.s, e.text) }
            assertEquals(undone, redone)
            assertEquals("redo-all must reach the final text (seed $seed)", finalText, e.text)
        }
    }

    @Test fun `interleaved undo and redo keep the model in step`() {
        val rnd = Random(99)
        val e = Editor(TextBuffer("start\n"), CommentSyntax.HASH)
        val naive = Naive("start\n")
        e.addListener { _, _, tx, _ -> naive.apply(tx) }
        var now = 0L
        repeat(4_000) { step ->
            now += rnd.nextLong(0, 1500)
            when (rnd.nextInt(10)) {
                0 -> e.undo()
                1 -> e.redo()
                else -> op(e, rnd, now)
            }
            assertEquals("step $step", naive.s, e.text)
        }
    }

    @Test fun `undo restores a document with mixed endings and no final newline exactly`() {
        val original = "a\r\nb\nc\rd"
        val e = Editor(TextBuffer(original), CommentSyntax.SLASH)
        e.setSelection(SelectionSet.single(Selection(0, original.length)))
        e.indentLines(); e.toggleLineComment(10_000); e.duplicateLines(20_000); e.deleteLines(30_000)
        while (e.undo()) { }
        assertEquals(original, e.text)
    }

    @Test fun `commands never split a surrogate pair or leave a stray surrogate in well-formed text`() {
        val clean = listOf("a", " ", "\n", "\r\n", "(", ")", "\"", "é", "e\u0301", "\uD83D\uDE00", "\uD83D\uDC68\u200D\uD83D\uDC69", "漢", "x y")
        for (seed in 1L..3L) {
            val rnd = Random(seed)
            val e = Editor(TextBuffer("\uD83D\uDE00 start e\u0301\n\uD83D\uDC68\u200D\uD83D\uDC69 end\n"), CommentSyntax.SLASH)
            var now = 0L
            repeat(3000) { step ->
                now += rnd.nextLong(0, 400)
                val len = e.buffer.length
                when (rnd.nextInt(14)) {
                    0, 1 -> e.typeText(clean[rnd.nextInt(clean.size)], now)
                    2 -> e.typeChar(listOf("(", "\"", "a", ")")[rnd.nextInt(4)], now)
                    3 -> e.insertNewline(now)
                    4, 5 -> e.backspace(now)
                    6 -> e.deleteForward(now)
                    7 -> e.deleteWordBackward(now)
                    8 -> e.deleteWordForward(now)
                    9 -> e.moveLines(rnd.nextBoolean(), now)
                    10 -> e.setSelection(SelectionSet.single(Selection(rnd.nextInt(len + 1), rnd.nextInt(len + 1))))
                    11 -> e.duplicateLines(now)
                    12 -> e.undo()
                    else -> e.redo()
                }
                assertEquals("stray surrogate at step $step (seed $seed)", -1, dev.fonebrew.domain.editor.buffer.TextEncodings.firstLoneSurrogate(e.text))
            }
        }
    }

    @Test fun `open edit undo-all and save returns the original bytes exactly`() {
        val original = "a\r\nb\r\n\tc".toByteArray()
        val buf = (TextBuffer.open(original) as dev.fonebrew.domain.editor.buffer.OpenResult.Opened).buffer
        val e = Editor(buf, CommentSyntax.SLASH)
        e.setCaret(0); e.typeText("zz"); e.insertNewline(5000); e.backspace(9000); e.duplicateLines(20000); e.deleteLines(30000)
        while (e.undo()) { }
        val out = buf.toBytes() as dev.fonebrew.domain.editor.buffer.EncodeResult.Ok
        org.junit.Assert.assertArrayEquals(original, out.bytes)
    }
}
