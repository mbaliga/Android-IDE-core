package dev.fonebrew.domain.editor.edit

import dev.fonebrew.domain.editor.buffer.TextBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorTest {
    private fun ed(text: String, comments: CommentSyntax = CommentSyntax.SLASH, indent: IndentStyle? = null) =
        Editor(TextBuffer(text), comments, indent)

    private fun Editor.at(o: Int) = apply { setCaret(o) }
    private fun Editor.sel(a: Int, b: Int) = apply { setSelection(SelectionSet.single(Selection(a, b))) }
    private val Editor.caret: Int get() = selections.primary.head

    // ---- typing, pairing, newline ------------------------------------------------------------------

    @Test fun `typing inserts at the caret and replaces a selection`() {
        val e = ed("hello world").sel(6, 11)
        e.typeText("there")
        assertEquals("hello there", e.text)
        assertEquals(11, e.caret)
    }

    @Test fun `typed and pasted newlines take the file's line ending`() {
        val e = ed("a\r\nb\r\n").at(1)
        e.typeText("\n")
        e.paste("x\ny")
        assertEquals("a\r\nx\r\ny\r\nb\r\n", e.text)
    }

    @Test fun `opening bracket auto-closes at line end and before whitespace`() {
        val e = ed("foo ").at(4)
        e.typeChar("(")
        assertEquals("foo ()", e.text)
        assertEquals(5, e.caret)
        e.typeChar(")")                                // steps over the closer
        assertEquals("foo ()", e.text)
        assertEquals(6, e.caret)
    }

    @Test fun `no auto-close before a word character`() {
        val e = ed("foo bar").at(4)
        e.typeChar("(")
        assertEquals("foo (bar", e.text)
    }

    @Test fun `quotes pair, skip over, and do not pair after a letter`() {
        val e = ed("").at(0)
        e.typeChar("\"")
        assertEquals("\"\"", e.text)
        e.typeChar("x"); e.typeChar("\"")
        assertEquals("\"x\"", e.text)
        assertEquals(3, e.caret)
        val f = ed("don").at(3)
        f.typeChar("'")
        assertEquals("don'", f.text)
    }

    @Test fun `typing an opener over a selection surrounds it and keeps the selection`() {
        val e = ed("say hello now").sel(4, 9)
        e.typeChar("(")
        assertEquals("say (hello) now", e.text)
        assertEquals(Selection(5, 10), e.selections.primary)
        e.typeChar("\"")
        assertEquals("say (\"hello\") now", e.text)
    }

    @Test fun `backspace between an empty pair removes both`() {
        val e = ed("f()").at(2)
        e.backspace()
        assertEquals("f", e.text)
    }

    @Test fun `enter keeps indentation and opens a level after a brace`() {
        val e = ed("    if (x) {", indent = IndentStyle(false, 4, true)).at(12)
        e.insertNewline()
        assertEquals("    if (x) {\n        ", e.text)
        assertEquals(e.text.length, e.caret)
    }

    @Test fun `enter between braces splits onto three lines`() {
        val e = ed("fn() {}", indent = IndentStyle(false, 2, true)).at(6)
        e.insertNewline()
        assertEquals("fn() {\n  \n}", e.text)
        assertEquals(9, e.caret)
    }

    @Test fun `enter in a tab file indents with a tab`() {
        val e = ed("\tx {", indent = IndentStyle(true, 4, true)).at(4)
        e.insertNewline()
        assertEquals("\tx {\n\t\t", e.text)
    }

    // ---- deletion ---------------------------------------------------------------------------------

    @Test fun `backspace deletes a whole grapheme not half a pair`() {
        val e = ed("a😀").at(3)
        e.backspace()
        assertEquals("a", e.text)
        val f = ed("xé").at(3)
        f.backspace()
        assertEquals("x", f.text)
        val g = ed("a\r\nb").at(3)
        g.backspace()
        assertEquals("ab", g.text)
    }

    @Test fun `forward delete takes a whole cluster`() {
        val e = ed("éz").at(0)
        e.deleteForward()
        assertEquals("z", e.text)
    }

    @Test fun `backspace in leading spaces removes one indent stop`() {
        val e = ed("        x", indent = IndentStyle(false, 4, true)).at(8)
        e.backspace()
        assertEquals("    x", e.text)
        val f = ed("      x", indent = IndentStyle(false, 4, true)).at(6)
        f.backspace()
        assertEquals("    x", f.text)
    }

    @Test fun `word delete stops at word, punctuation and newline boundaries`() {
        val e = ed("foo.bar  baz").at(12)
        e.deleteWordBackward(); assertEquals("foo.bar  ", e.text)
        e.deleteWordBackward(); assertEquals("foo.", e.text)
        e.deleteWordBackward(); assertEquals("foo", e.text)
        val f = ed("one\ntwo").at(4)
        f.deleteWordBackward(); assertEquals("onetwo", f.text)      // only the newline goes
        val g = ed("alpha beta").at(0)
        g.deleteWordForward(); assertEquals(" beta", g.text)
    }

    // ---- indentation, comments, lines -------------------------------------------------------------

    @Test fun `tab inserts to the next stop and shift-tab outdents`() {
        val e = ed("ab", indent = IndentStyle(false, 4, true)).at(2)
        e.indent()
        assertEquals("ab  ", e.text)
        val f = ed("\tx\n  y", indent = IndentStyle(true, 4, true)).sel(0, 6)
        f.outdent()
        assertEquals("x\ny", f.text)
    }

    @Test fun `indent over several lines skips blank lines`() {
        val e = ed("a\n\nb", indent = IndentStyle(false, 2, true)).sel(0, 4)
        e.indent()
        assertEquals("  a\n\n  b", e.text)
        assertEquals(Selection(0, 8), e.selections.primary)
    }

    @Test fun `indent style is detected from the file`() {
        assertEquals(true, IndentStyle.detect(sequenceOf("a", "\tb", "\t\tc", "\td")).useTabs)
        val sp = IndentStyle.detect(sequenceOf("a", "  b", "    c", "  d", "e", "  f"))
        assertEquals(false, sp.useTabs); assertEquals(2, sp.width); assertTrue(sp.detected)
        val none = IndentStyle.detect(sequenceOf("a", "b"))
        assertFalse(none.detected); assertEquals(4, none.width)
    }

    @Test fun `toggle line comment adds then removes, keeping indentation`() {
        val e = ed("    a\n  b\n\n    c").sel(0, 15)
        e.toggleLineComment()
        assertEquals("  //   a\n  // b\n\n  //   c", e.text)     // inserted at the shallowest indent, blank line skipped
        e.toggleLineComment()
        assertEquals("    a\n  b\n\n    c", e.text)
    }

    @Test fun `comment toggle uses hash for python and block form for css`() {
        val py = ed("x = 1", CommentSyntax.HASH).at(0)
        py.toggleLineComment(); assertEquals("# x = 1", py.text)
        py.toggleLineComment(); assertEquals("x = 1", py.text)
        val css = ed("a { }", CommentSyntax.BLOCK_CSS).at(0)
        css.toggleLineComment(); assertEquals("/* a { } */", css.text)
        css.toggleLineComment(); assertEquals("a { }", css.text)
        val json = ed("{}", CommentSyntax.NONE).at(0)
        assertTrue(json.toggleLineComment() is CommandResult.NoOp)
    }

    @Test fun `move lines down and up swap content and keep each terminator`() {
        val e = ed("a\r\nb\nc").at(0)
        e.moveLines(up = false)
        assertEquals("b\r\na\nc", e.text)
        assertEquals(3, e.caret)
        e.moveLines(up = false)
        assertEquals("b\r\nc\na", e.text)         // the last line has no terminator and still has none
        e.moveLines(up = true)
        assertEquals("b\r\na\nc", e.text)
        assertTrue(ed("a\nb").at(0).moveLines(up = true) is CommandResult.NoOp)
        assertTrue(ed("a\nb").at(2).moveLines(up = false) is CommandResult.NoOp)
    }

    @Test fun `duplicate lines copies below and moves the selection onto the copy`() {
        val e = ed("a\nb").at(0)
        e.duplicateLines()
        assertEquals("a\na\nb", e.text)
        assertEquals(2, e.caret)
        val last = ed("a\nb").at(3)
        last.duplicateLines()
        assertEquals("a\nb\nb", last.text)
    }

    @Test fun `delete lines removes whole lines including the last without a stray newline`() {
        val e = ed("a\nb\nc").at(2)
        e.deleteLines(); assertEquals("a\nc", e.text)
        val last = ed("a\nb").at(2)
        last.deleteLines(); assertEquals("a", last.text)
        val all = ed("only").at(1)
        all.deleteLines(); assertEquals("", all.text)
        val tail = ed("a\n").at(2)
        assertTrue(tail.deleteLines() is CommandResult.NoOp)    // the empty line after the final newline
        assertEquals("a\n", tail.text)
    }

    // ---- selections and movement --------------------------------------------------------------------

    @Test fun `caret movement never lands inside a cluster`() {
        val e = ed("a😀é").at(0)
        val seen = ArrayList<Int>()
        repeat(4) { e.moveRight(); seen += e.caret }
        assertEquals(listOf(1, 3, 5, 5), seen)
        val back = ArrayList<Int>()
        repeat(4) { e.moveLeft(); back += e.caret }
        assertEquals(listOf(3, 1, 0, 0), back)
    }

    @Test fun `setSelection snaps into grapheme boundaries`() {
        val e = ed("éx")
        e.setCaret(1)
        assertEquals(0, e.caret)
    }

    @Test fun `vertical movement keeps the goal column across short lines`() {
        val e = ed("abcdef\nab\nabcdef").at(5)
        e.moveVertical(1); assertEquals(9, e.caret)         // end of "ab"
        e.moveVertical(1); assertEquals(15, e.caret)        // back to column 5
    }

    @Test fun `smart home toggles between first non-blank and column zero`() {
        val e = ed("    code").at(8)
        e.moveLineStart(); assertEquals(4, e.caret)
        e.moveLineStart(); assertEquals(0, e.caret)
        e.moveLineStart(); assertEquals(4, e.caret)
    }

    @Test fun `multi-cursor typing and backspace act on every cursor`() {
        val e = ed("a\nb\nc")
        e.setSelection(SelectionSet.of(listOf(Selection.caret(1), Selection.caret(3), Selection.caret(5))))
        e.typeText("!")
        assertEquals("a!\nb!\nc!", e.text)
        e.backspace()
        assertEquals("a\nb\nc", e.text)
        assertEquals(3, e.selections.size)
    }

    @Test fun `overlapping selections merge`() {
        val s = SelectionSet.of(listOf(Selection(0, 5), Selection(3, 8), Selection.caret(8)))
        assertEquals(listOf(Selection(0, 8)), s.ranges)
    }

    @Test fun `read-only editors reject edits`() {
        val e = Editor(TextBuffer("x", readOnly = true))
        assertTrue(e.typeText("y") is CommandResult.Rejected)
        assertEquals("x", e.text)
    }

    @Test fun `an out-of-bounds transaction is rejected and leaves the text alone`() {
        val e = ed("abc")
        val r = e.applyTransaction(Transaction(listOf(TextEdit(2, 9, "x"))), EditKind.OTHER)
        assertTrue(r is CommandResult.Rejected && r.error is TransactionError.OutOfBounds)
        assertEquals("abc", e.text)
        val o = e.applyTransaction(Transaction(listOf(TextEdit(0, 2, "x"), TextEdit(1, 3, "y"))), EditKind.OTHER)
        assertTrue(o is CommandResult.Rejected && (o.error is TransactionError.Overlap))
    }

    // ---- no-op round trip and dirtiness ---------------------------------------------------------------

    @Test fun `dirty tracks the saved point and undo returns to clean`() {
        val e = ed("x")
        assertFalse(e.isDirty)
        e.at(1).typeText("y", 0)
        assertTrue(e.isDirty)
        e.markSaved(); assertFalse(e.isDirty)
        e.typeText("z", 5000)
        assertTrue(e.isDirty)
        e.undo(); assertFalse(e.isDirty)
        e.undo(); assertTrue(e.isDirty)
        e.redo(); assertFalse(e.isDirty)
    }

    @Test fun `editing leaves unrelated line endings untouched`() {
        val e = ed("a\r\nb\nc\rd").at(0)
        e.typeText("X")
        assertEquals("Xa\r\nb\nc\rd", e.text)
    }
}
