package dev.fonebrew.domain.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskFromTurnTest {

    // ---- title() ----

    @Test fun `title is the first line when it is a single complete sentence`() {
        assertEquals("Fix the login bug.", TaskFromTurn.title("Fix the login bug.\nMore details here."))
    }

    @Test fun `title stops at the first sentence when the first line has more than one`() {
        assertEquals("Fix the bug.", TaskFromTurn.title("Fix the bug. Then ship it."))
    }

    @Test fun `title with no sentence punctuation is the whole first line`() {
        assertEquals("Just a title with no punctuation", TaskFromTurn.title("Just a title with no punctuation"))
    }

    @Test fun `title takes only the first line, ignoring the rest of the turn`() {
        assertEquals("Line one", TaskFromTurn.title("Line one\nLine two\nLine three"))
    }

    @Test fun `title skips leading blank lines to find the first real line`() {
        assertEquals("Fix this.", TaskFromTurn.title("\n\nFix this.\nMore text"))
    }

    @Test fun `title strips markdown from the first line`() {
        assertEquals("Fix the crash", TaskFromTurn.title("# Fix the crash\nDetails below"))
    }

    @Test fun `title unwraps inline emphasis and code on the first line`() {
        assertEquals("Fix the login bug", TaskFromTurn.title("Fix the **login** `bug`"))
    }

    @Test fun `blank turn content falls back to the default title`() {
        assertEquals(TaskFromTurn.DEFAULT_TITLE, TaskFromTurn.title(""))
    }

    @Test fun `whitespace-only turn content falls back to the default title`() {
        assertEquals(TaskFromTurn.DEFAULT_TITLE, TaskFromTurn.title("   \n   \n  "))
    }

    @Test fun `an over-long first line is truncated with a trailing ellipsis`() {
        val longLine = "A".repeat(150)
        val title = TaskFromTurn.title(longLine)
        assertEquals(TaskFromTurn.MAX_TITLE_LENGTH, title.length)
        assertTrue(title.endsWith("…"))
        assertEquals("A".repeat(TaskFromTurn.MAX_TITLE_LENGTH - 1), title.dropLast(1))
    }

    @Test fun `a title exactly at the cap is left untruncated`() {
        val line = "B".repeat(TaskFromTurn.MAX_TITLE_LENGTH)
        assertEquals(line, TaskFromTurn.title(line))
    }

    @Test fun `never throws on adversarial input`() {
        TaskFromTurn.title("`".repeat(10))
        TaskFromTurn.title("#".repeat(10))
        TaskFromTurn.title("😀 emoji and CJK 你好 mixed")
        assertTrue(true)
    }

    // ---- sourceRef() ----

    @Test fun `sourceRef mints a dotted chat-node key path`() {
        assertEquals("chat.node.abc-123", TaskFromTurn.sourceRef("abc-123"))
    }

    // ---- notes() ----

    @Test fun `notes carries the turn's full text plus a human-readable provenance line`() {
        val notes = TaskFromTurn.notes("Turn content here.", "node-123")
        assertEquals("Turn content here.\n\n— from chat message chat.node.node-123", notes)
    }

    @Test fun `notes keeps markdown intact, unlike title`() {
        val notes = TaskFromTurn.notes("**Bold** turn content", "abc")
        assertTrue(notes.startsWith("**Bold** turn content"))
        assertTrue(notes.endsWith(TaskFromTurn.sourceRef("abc")))
    }

    @Test fun `notes trims surrounding whitespace from the turn content`() {
        val notes = TaskFromTurn.notes("  padded content  \n", "n1")
        assertTrue(notes.startsWith("padded content\n\n"))
    }
}
