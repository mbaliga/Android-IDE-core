package dev.fonebrew.domain.editor.find

import dev.fonebrew.domain.editor.buffer.TextBuffer
import dev.fonebrew.domain.editor.edit.CommentSyntax
import dev.fonebrew.domain.editor.edit.Editor
import dev.fonebrew.domain.workspace.grep.RegexLint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FindTest {
    private fun found(o: FindOutcome) = (o as FindOutcome.Found).matches.map { it.start to it.end }

    @Test fun `literal search is case-insensitive by default and exact when asked`() {
        val t = "Foo foo FOO"
        assertEquals(listOf(0 to 3, 4 to 7, 8 to 11), found(FindEngine.findAll(t, FindQuery("foo"))))
        assertEquals(listOf(4 to 7), found(FindEngine.findAll(t, FindQuery("foo", caseSensitive = true))))
    }

    @Test fun `literal search treats regex metacharacters literally`() {
        assertEquals(listOf(1 to 4), found(FindEngine.findAll("(a.b)", FindQuery("a.b"))))
        assertEquals(emptyList<Pair<Int, Int>>(), found(FindEngine.findAll("axb", FindQuery("a.b"))))
    }

    @Test fun `whole word excludes substrings for literal and regex`() {
        val t = "cat concat cat_x cat"
        assertEquals(listOf(0 to 3, 17 to 20), found(FindEngine.findAll(t, FindQuery("cat", wholeWord = true))))
        assertEquals(listOf(0 to 3, 17 to 20), found(FindEngine.findAll(t, FindQuery("cat", FindMode.REGEX, wholeWord = true))))
    }

    @Test fun `regex finds with groups across lines and anchors per line`() {
        val t = "ab12\ncd345\nef"
        assertEquals(listOf(2 to 4, 7 to 10), found(FindEngine.findAll(t, FindQuery("\\d+", FindMode.REGEX))))
        assertEquals(listOf(0 to 0, 5 to 5, 11 to 11), found(FindEngine.findAll(t, FindQuery("^", FindMode.REGEX))))
    }

    @Test fun `invalid regex is a typed outcome`() {
        assertTrue(FindEngine.findAll("x", FindQuery("(", FindMode.REGEX)) is FindOutcome.Invalid)
        assertTrue(FindEngine.findAll("x", FindQuery("")) is FindOutcome.EmptyPattern)
    }

    @Test fun `catastrophic shapes are screened by the lint before they run`() {
        val text = "a".repeat(40) + "!"
        assertTrue(FindEngine.findAll(text, FindQuery("(a+)+$", FindMode.REGEX)) is FindOutcome.Risky)
        assertTrue(FindEngine.findAll(text, FindQuery("(a|aa)+$", FindMode.REGEX)) is FindOutcome.Risky)
        assertTrue(FindEngine.findAll(text, FindQuery("(x+x+)+y", FindMode.REGEX)) is FindOutcome.Risky)
    }

    @Test fun `a blow-up the lint cannot see ends in a typed Timeout from the budget`() {
        // no nested group, so the lint passes it: a*a*a*...b is polynomial of high degree on all-a text
        val text = "a".repeat(400)
        val q = FindQuery("a*a*a*a*a*a*a*a*a*a*b", FindMode.REGEX)
        assertEquals(null, RegexLint.risk(q.pattern))
        val t0 = System.nanoTime()
        val o = FindEngine.findAll(text, q, budget = 500_000)
        assertTrue("expected Timeout, got $o", o is FindOutcome.Timeout)
        assertTrue("must not hang", (System.nanoTime() - t0) / 1_000_000 < 10_000)
    }

    @Test fun `risky patterns forced past the lint still end quickly and never hang`() {
        for (p in listOf("(a+)+$", "(a|aa)+$", "(a*)*b", "(x+x+)+y")) {
            val t0 = System.nanoTime()
            val o = FindEngine.findAll("a".repeat(45) + "!", FindQuery(p, FindMode.REGEX, allowRisky = true), budget = 2_000_000)
            assertTrue("$p -> $o", o is FindOutcome.Timeout || o is FindOutcome.Found)
            assertTrue("$p took too long", (System.nanoTime() - t0) / 1_000_000 < 10_000)
        }
    }

    @Test fun `deep regex recursion on a long line is a typed Timeout, not an uncaught StackOverflowError`() {
        val o = FindEngine.findAll("a".repeat(5_000), FindQuery("(?:a|b)*c", FindMode.REGEX, allowRisky = true), budget = 50_000_000)
        assertTrue("got $o", o is FindOutcome.Timeout || o is FindOutcome.Found)
    }

    @Test fun `match cap truncates and says so`() {
        val o = FindEngine.findAll("a".repeat(100), FindQuery("a"), maxMatches = 10) as FindOutcome.Found
        assertEquals(10, o.matches.size)
        assertTrue(o.truncated)
    }

    @Test fun `next and previous wrap around`() {
        val e = Editor(TextBuffer("one two one two one"), CommentSyntax.NONE)
        val s = FindSession(e)
        s.setQuery(FindQuery("one"))
        assertEquals(Match(8, 11), (s.next(5) as FindEngine.FindResult.Hit).match)
        assertEquals(Match(0, 3), (s.next(17) as FindEngine.FindResult.Hit).match)        // wrapped
        assertEquals(Match(8, 11), (s.previous(15) as FindEngine.FindResult.Hit).match)
        assertEquals(Match(16, 19), (s.previous(2) as FindEngine.FindResult.Hit).match)   // wrapped
        s.setQuery(FindQuery("zzz"))
        assertTrue(s.next(0) is FindEngine.FindResult.None)
    }

    @Test fun `matches in a window support visible-range highlighting`() {
        val e = Editor(TextBuffer("ab ab ab ab"), CommentSyntax.NONE)
        val s = FindSession(e); s.setQuery(FindQuery("ab"))
        assertEquals(listOf(3 to 5, 6 to 8), found(s.matchesIn(3, 8)))
    }

    @Test fun `replace all is one undo step and handles groups`() {
        val e = Editor(TextBuffer("john smith\nada lovelace"), CommentSyntax.NONE)
        val s = FindSession(e)
        s.setQuery(FindQuery("(\\w+) (\\w+)", FindMode.REGEX))
        val r = s.replaceAll("$2, $1")
        assertEquals(ReplaceOutcome.Replaced(2), r)
        assertEquals("smith, john\nlovelace, ada", e.text)
        assertEquals(1, e.history.undoDepth)
        e.undo()
        assertEquals("john smith\nada lovelace", e.text)
    }

    @Test fun `replace one swaps only that match and literal replacement is verbatim`() {
        val e = Editor(TextBuffer("x.y x.y"), CommentSyntax.NONE)
        val s = FindSession(e); s.setQuery(FindQuery("x.y"))
        val m = (s.next(1) as FindEngine.FindResult.Hit).match
        s.replaceOne(m, "\$1\\n")
        assertEquals("x.y \$1\\n", e.text)
    }

    @Test fun `a bad group reference fails without changing the text`() {
        val e = Editor(TextBuffer("abc"), CommentSyntax.NONE)
        val s = FindSession(e); s.setQuery(FindQuery("b", FindMode.REGEX))
        assertTrue(s.replaceAll("$3") is ReplaceOutcome.Failed)
        assertEquals("abc", e.text)
    }

    @Test fun `replace all refuses when over the cap instead of doing a partial job`() {
        val e = Editor(TextBuffer("a".repeat(50)), CommentSyntax.NONE)
        val s = FindSession(e); s.setQuery(FindQuery("a"))
        assertEquals(ReplaceOutcome.TooMany(10), s.replaceAll("b", cap = 10))
        assertEquals("a".repeat(50), e.text)
    }

    @Test fun `replace across CRLF text keeps the endings`() {
        val e = Editor(TextBuffer("a\r\nb\r\n"), CommentSyntax.NONE)
        val s = FindSession(e); s.setQuery(FindQuery("^", FindMode.REGEX))
        s.replaceAll("> ")
        assertEquals("> a\r\n> b\r\n", e.text)    // java.util.regex: ^ does not match at the very end after a final terminator
    }

    @Test fun `replace on a read-only buffer fails`() {
        val e = Editor(TextBuffer("aa", readOnly = true), CommentSyntax.NONE)
        val s = FindSession(e); s.setQuery(FindQuery("a"))
        assertTrue(s.replaceAll("b") is ReplaceOutcome.Failed)
    }
}
