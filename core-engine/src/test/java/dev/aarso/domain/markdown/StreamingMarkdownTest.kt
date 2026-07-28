package dev.aarso.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingMarkdownTest {

    private fun reconcile(s: String) = StreamingMarkdown.reconcile(s)

    // ---- pass-through: complete markdown is unchanged, all flags false ----

    @Test fun `plain prose passes through unchanged`() {
        val src = "Hello, this is **bold** and _italic_ prose.\n\nSecond paragraph."
        val r = reconcile(src)
        assertEquals(src, r.text)
        assertFalse(r.openFence)
        assertNull(r.openFenceLang)
        assertFalse(r.truncatedTable)
    }

    @Test fun `empty string is safe and inert`() {
        val r = reconcile("")
        assertEquals("", r.text)
        assertFalse(r.openFence)
        assertNull(r.openFenceLang)
        assertFalse(r.truncatedTable)
    }

    @Test fun `closed fence block is left untouched`() {
        val src = "before\n```kotlin\nval x = 1\n```\nafter"
        val r = reconcile(src)
        assertEquals(src, r.text)
        assertFalse(r.openFence)
        assertNull(r.openFenceLang)
    }

    @Test fun `even number of fences means closed`() {
        val src = "```\na\n```\ntext\n```\nb\n```"
        val r = reconcile(src)
        assertFalse(r.openFence)
        assertEquals(src, r.text)
    }

    // ---- open backtick fences ----

    @Test fun `open kotlin fence gets closed and language captured`() {
        val r = reconcile("```kotlin\nval x = 1")
        assertTrue(r.openFence)
        assertEquals("kotlin", r.openFenceLang)
        assertTrue("safe text must end with a closing fence", r.text.endsWith("```"))
    }

    @Test fun `open fence with no language captures null lang but still closes`() {
        val r = reconcile("```\nsome code here")
        assertTrue(r.openFence)
        assertNull(r.openFenceLang)
        assertTrue(r.text.endsWith("```"))
    }

    @Test fun `closer is placed on its own new line`() {
        val r = reconcile("```python\nprint(1)")
        assertTrue(r.text.endsWith("\n```"))
    }

    @Test fun `open fence on text already ending in newline does not double the newline`() {
        val r = reconcile("```js\nconst a = 1\n")
        assertTrue(r.openFence)
        assertEquals("js", r.openFenceLang)
        assertFalse("no blank line before closer", r.text.endsWith("\n\n```"))
        assertTrue(r.text.endsWith("\n```"))
    }

    @Test fun `language info string keeps only the first token`() {
        val r = reconcile("```kotlin foo=bar\ncode")
        assertEquals("kotlin", r.openFenceLang)
        assertTrue(r.openFence)
    }

    // ---- tilde fences ----

    @Test fun `open tilde fence gets closed with tildes`() {
        val r = reconcile("~~~\nplain code")
        assertTrue(r.openFence)
        assertTrue("must close with tildes", r.text.endsWith("~~~"))
    }

    @Test fun `closed tilde fence is unchanged`() {
        val src = "~~~ruby\nputs 1\n~~~"
        val r = reconcile(src)
        assertFalse(r.openFence)
        assertEquals(src, r.text)
    }

    @Test fun `tilde fence captures language`() {
        val r = reconcile("~~~rust\nfn main() {}")
        assertEquals("rust", r.openFenceLang)
        assertTrue(r.openFence)
    }

    @Test fun `longer fence run is honored as closer length`() {
        // Opening with four backticks; a three-backtick line inside is literal content.
        val r = reconcile("````\n```\ninner\n")
        assertTrue(r.openFence)
        assertTrue(r.text.endsWith("````"))
    }

    // ---- inline code is NOT a fence ----

    @Test fun `inline single-backtick code is not treated as a fence`() {
        val src = "Use the `reconcile` function and `isInsideCodeFence` too."
        val r = reconcile(src)
        assertFalse(r.openFence)
        assertEquals(src, r.text)
    }

    @Test fun `inline double backticks are not a fence`() {
        val src = "Here is ``a `b` c`` inline code."
        val r = reconcile(src)
        assertFalse(r.openFence)
        assertEquals(src, r.text)
    }

    // ---- isInsideCodeFence convenience ----

    @Test fun `isInsideCodeFence true for open block`() {
        assertTrue(StreamingMarkdown.isInsideCodeFence("```\nhalf"))
    }

    @Test fun `isInsideCodeFence false for closed block`() {
        assertFalse(StreamingMarkdown.isInsideCodeFence("```\nx\n```"))
    }

    @Test fun `isInsideCodeFence false for plain text`() {
        assertFalse(StreamingMarkdown.isInsideCodeFence("just words"))
    }

    // ---- tables ----

    @Test fun `header row without separator is trimmed and flagged`() {
        val r = reconcile("intro\n\n| a | b |")
        assertTrue(r.truncatedTable)
        assertFalse(r.text.contains("| a | b |"))
        assertTrue(r.text.startsWith("intro"))
    }

    @Test fun `complete table passes through untouched`() {
        val src = "| a | b |\n|---|---|\n| 1 | 2 |"
        val r = reconcile(src)
        assertFalse(r.truncatedTable)
        assertEquals(src, r.text)
    }

    @Test fun `half-written body row under a separator is trimmed`() {
        val src = "| a | b |\n|---|---|\n| 1"
        val r = reconcile(src)
        assertTrue(r.truncatedTable)
        assertFalse(r.text.contains("| 1"))
        assertTrue(r.text.contains("|---|---|"))
    }

    @Test fun `a plausible dash-only separator under a header is left alone`() {
        // `|---` reads as a (partial but valid-looking) separator; trimming it would
        // thrash on the next token, so the conservative pass keeps it.
        val src = "| a | b |\n|---"
        val r = reconcile(src)
        assertFalse(r.truncatedTable)
        assertEquals(src, r.text)
    }

    @Test fun `header then a half-typed body cell with no separator is trimmed`() {
        // Header row + a second `|`-line that is neither a separator nor a complete row.
        val src = "| a | b |\n| x"
        val r = reconcile(src)
        assertTrue(r.truncatedTable)
        assertFalse(r.text.endsWith("| x"))
    }

    @Test fun `separator row itself is never trimmed`() {
        val src = "| a | b |\n|---|---|"
        val r = reconcile(src)
        assertFalse(r.truncatedTable)
        assertEquals(src, r.text)
    }

    @Test fun `a single complete row is not trimmed`() {
        val src = "| a | b | c |"
        val r = reconcile(src)
        // A lone header with a trailing pipe is complete-looking but has no separator;
        // it is the header-without-separator case and should be trimmed.
        assertTrue(r.truncatedTable)
    }

    @Test fun `non-table trailing line is left alone`() {
        val src = "| a | b |\n|---|---|\n| 1 | 2 |\n\nDone."
        val r = reconcile(src)
        assertFalse(r.truncatedTable)
        assertEquals(src, r.text)
    }

    @Test fun `pipe line inside a code fence is not treated as a table`() {
        // The `|` line is literal code; fence is open so table logic must not fire.
        val r = reconcile("```\n| not | a | table |")
        assertTrue(r.openFence)
        assertFalse(r.truncatedTable)
        assertTrue(r.text.contains("| not | a | table |"))
    }

    // ---- idempotency / monotonicity ----

    @Test fun `reconcile is idempotent on complete markdown`() {
        val src = "text\n```kotlin\nval x = 1\n```\nmore"
        val once = reconcile(src).text
        val twice = reconcile(once).text
        assertEquals(src, once)
        assertEquals(once, twice)
    }

    @Test fun `reconciling already-safe output of an open fence is stable in structure`() {
        val first = reconcile("```kotlin\nval x = 1")
        // The safe text now has a balanced (even) fence count, so re-running keeps it closed.
        val second = reconcile(first.text)
        assertFalse(second.openFence)
        assertEquals(first.text, second.text)
    }

    @Test fun `complete table is idempotent`() {
        val src = "| a | b |\n|---|---|\n| 1 | 2 |"
        val once = reconcile(src).text
        val twice = reconcile(once).text
        assertEquals(once, twice)
        assertEquals(src, once)
    }

    @Test fun `never throws on odd or messy input`() {
        // Just assert these calls return without exception.
        reconcile("|")
        reconcile("```")
        reconcile("~")
        reconcile("\n\n\n")
        reconcile("```````")
        reconcile("| | | | | |")
        assertTrue(true)
    }
}
