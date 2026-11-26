package dev.fonebrew.domain.editor.edit

import dev.fonebrew.domain.editor.buffer.TextBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoHistoryTest {
    private fun ed(text: String = "", history: UndoHistory = UndoHistory()) = Editor(TextBuffer(text), CommentSyntax.SLASH, null, history)

    @Test fun `typing in a quick burst is one undo step`() {
        val e = ed()
        "hello".forEachIndexed { i, c -> e.typeText(c.toString(), nowMs = i * 100L) }
        assertEquals("hello", e.text)
        assertEquals(1, e.history.undoDepth)
        e.undo()
        assertEquals("", e.text)
        assertFalse(e.history.canUndo)
    }

    @Test fun `a pause longer than the gap starts a new step`() {
        val e = ed()
        e.typeText("a", 0); e.typeText("b", 500); e.typeText("c", 5000)
        assertEquals(2, e.history.undoDepth)
        e.undo(); assertEquals("ab", e.text)
    }

    @Test fun `moving the caret ends the burst`() {
        val e = ed()
        e.typeText("a", 0)
        e.setCaret(0)
        e.typeText("b", 100)
        assertEquals(2, e.history.undoDepth)
    }

    @Test fun `a newline is its own step and typing around it groups separately`() {
        val e = ed()
        e.typeText("ab", 0); e.insertNewline(100); e.typeText("cd", 200)
        assertEquals(3, e.history.undoDepth)
        e.undo(); assertEquals("ab\n", e.text)
        e.undo(); assertEquals("ab", e.text)
    }

    @Test fun `typing and deleting do not coalesce with each other`() {
        val e = ed()
        e.typeText("abc", 0); e.backspace(100); e.backspace(200)
        assertEquals(2, e.history.undoDepth)
        e.undo(); assertEquals("abc", e.text)
    }

    @Test fun `undo then redo restores text and selection`() {
        val e = ed("hello")
        e.setSelection(SelectionSet.single(Selection(0, 5)))
        e.typeText("bye", 0)
        assertEquals("bye", e.text)
        e.undo()
        assertEquals("hello", e.text)
        assertEquals(Selection(0, 5), e.selections.primary)
        e.redo()
        assertEquals("bye", e.text)
        assertEquals(Selection.caret(3), e.selections.primary)
    }

    @Test fun `a new edit invalidates redo`() {
        val e = ed()
        e.typeText("a", 0); e.typeText("b", 9000)
        e.undo()
        assertTrue(e.history.canRedo)
        e.typeText("z", 20000)
        assertFalse(e.history.canRedo)
        assertFalse(e.redo())
        assertEquals("az", e.text)
    }

    @Test fun `typing after an undo does not merge into the earlier step`() {
        val e = ed()
        e.typeText("a", 0)
        e.typeText("b", 5000)
        e.undo()
        e.typeText("c", 5100)
        assertEquals(2, e.history.undoDepth)
    }

    @Test fun `history is bounded by entry count and drops the oldest`() {
        val e = ed(history = UndoHistory(maxEntries = 3))
        for (i in 0 until 10) e.typeText("x", i * 5000L)
        assertEquals(3, e.history.undoDepth)
        assertEquals(7, e.history.droppedEntries)
        e.undo(); e.undo(); e.undo()
        assertEquals(7, e.text.length)           // the original is no longer reachable, and that is reported
        assertFalse(e.undo())
    }

    @Test fun `history is bounded by stored characters but keeps the newest`() {
        val e = ed(history = UndoHistory(maxEntries = 1000, maxChars = 50))
        for (i in 0 until 5) e.typeText("y".repeat(40), i * 5000L)
        assertEquals(1, e.history.undoDepth)
        e.undo()
        assertEquals(160, e.text.length)
    }

    @Test fun `replace-all style transactions are one step`() {
        val e = ed("a a a")
        e.applyTransaction(Transaction(listOf(TextEdit(0, 1, "b"), TextEdit(2, 3, "b"), TextEdit(4, 5, "b"))), EditKind.REPLACE)
        assertEquals("b b b", e.text)
        e.undo()
        assertEquals("a a a", e.text)
    }

    @Test fun `listeners see edits undo and redo`() {
        val e = ed("x")
        val causes = ArrayList<ChangeCause>()
        e.addListener { _, _, _, c -> causes += c }
        e.setCaret(1); e.typeText("y", 0); e.undo(); e.redo()
        assertEquals(listOf(ChangeCause.EDIT, ChangeCause.UNDO, ChangeCause.REDO), causes)
    }
}
