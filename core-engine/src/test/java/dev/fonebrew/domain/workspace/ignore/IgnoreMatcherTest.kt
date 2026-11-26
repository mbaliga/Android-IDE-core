package dev.fonebrew.domain.workspace.ignore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IgnoreMatcherTest {

    private fun m(root: String, nested: Map<String, String> = emptyMap(), defaults: List<String> = emptyList(), excludes: List<String> = emptyList()) =
        IgnoreMatcher(IgnoreSource { d -> if (d.isEmpty()) root else nested[d] }, defaults, excludes)

    @Test fun `plain name matches at any depth`() {
        val x = m("foo\n")
        assertTrue(x.isIgnored("foo", false)); assertTrue(x.isIgnored("a/b/foo", false))
        assertFalse(x.isIgnored("foobar", false))
    }

    @Test fun `star does not cross slash but crosses nothing in anchored path`() {
        val x = m("a/*.txt\n")
        assertTrue(x.isIgnored("a/x.txt", false)); assertFalse(x.isIgnored("a/b/x.txt", false)); assertFalse(x.isIgnored("z/a/x.txt", false))
    }

    @Test fun `leading slash anchors to the gitignore directory`() {
        val x = m("/build\n")
        assertTrue(x.isIgnored("build", true)); assertFalse(x.isIgnored("sub/build", true))
    }

    @Test fun `directory only pattern ignores dirs and their content but not a file of that name`() {
        val x = m("logs/\n")
        assertTrue(x.isIgnored("logs", true)); assertFalse(x.isIgnored("logs", false))
        assertTrue(x.isIgnored("logs/a.txt", false))
    }

    @Test fun `negation re-includes a file`() {
        val x = m("*.log\n!keep.log\n")
        assertTrue(x.isIgnored("a.log", false)); assertFalse(x.isIgnored("keep.log", false))
    }

    @Test fun `cannot re-include a file when a parent directory is excluded`() {
        val x = m("out/\n!out/keep.txt\n")
        assertTrue(x.isIgnored("out/keep.txt", false))
        val why = x.explain("out/keep.txt", false)
        assertEquals("out", why.decidedOn)
    }

    @Test fun `negating the directory then ignoring contents works`() {
        val x = m("out/*\n!out/keep.txt\n")
        assertFalse(x.isIgnored("out/keep.txt", false)); assertTrue(x.isIgnored("out/other", false))
    }

    @Test fun `double star forms`() {
        val x = m("**/gen\na/**/z.txt\nlib/**\n")
        assertTrue(x.isIgnored("gen", true)); assertTrue(x.isIgnored("q/r/gen", true))
        assertTrue(x.isIgnored("a/z.txt", false)); assertTrue(x.isIgnored("a/b/c/z.txt", false)); assertFalse(x.isIgnored("b/z.txt", false))
        assertTrue(x.isIgnored("lib/x", false)); assertFalse(x.isIgnored("lib", true))
    }

    @Test fun `escapes and trailing spaces`() {
        val x = m("\\#hash\n\\!bang\nsp\\ \ntrail   \n")
        assertTrue(x.isIgnored("#hash", false)); assertTrue(x.isIgnored("!bang", false))
        assertTrue(x.isIgnored("sp ", false)); assertFalse(x.isIgnored("sp", false))
        assertTrue(x.isIgnored("trail", false)); assertFalse(x.isIgnored("trail   ", false))
    }

    @Test fun `comments blank lines and CRLF are tolerated`() {
        val x = m("# c\r\n\r\nfoo\r\n")
        assertTrue(x.isIgnored("foo", false)); assertFalse(x.isIgnored("# c", false))
    }

    @Test fun `character classes and question mark`() {
        val x = m("[a-c]x\n?.o\n[!a]y\n[[:digit:]]z\n")
        assertTrue(x.isIgnored("bx", false)); assertFalse(x.isIgnored("dx", false))
        assertTrue(x.isIgnored("a.o", false)); assertFalse(x.isIgnored("ab.o", false))
        assertTrue(x.isIgnored("by", false)); assertFalse(x.isIgnored("ay", false))
        assertTrue(x.isIgnored("7z", false)); assertFalse(x.isIgnored("qz", false))
    }

    @Test fun `nested gitignore overrides parent and applies relative to its directory`() {
        val x = m("*.tmp\n", mapOf("sub" to "!keep.tmp\n/only.txt\n"))
        assertTrue(x.isIgnored("a.tmp", false)); assertFalse(x.isIgnored("sub/keep.tmp", false))
        assertTrue(x.isIgnored("sub/only.txt", false)); assertFalse(x.isIgnored("sub/deeper/only.txt", false)); assertFalse(x.isIgnored("only.txt", false))
    }

    @Test fun `layers - defaults lowest, user excludes next, gitignore wins`() {
        val x = m("!build/\n", defaults = DefaultIgnores.PATTERNS, excludes = listOf("*.bak"))
        assertFalse(x.isIgnored("build", true))                 // gitignore re-includes what defaults hid
        assertTrue(x.isIgnored("node_modules", true)); assertTrue(x.isIgnored("q/x.bak", false))
        assertTrue(x.isIgnored(".git/config", false))
    }

    @Test fun `defaults can be off`() {
        val x = m("")
        assertFalse(x.isIgnored("node_modules", true))
    }

    @Test fun `explain gives provenance`() {
        val x = m("a\n*.log\n")
        val d = x.explain("q/z.log", false)
        assertTrue(d.ignored); assertEquals("*.log", d.pattern); assertEquals(".gitignore", d.source); assertEquals(2, d.line)
        assertNull(x.explain("clean", false).pattern)
    }

    @Test fun `invalidate re-reads a changed gitignore`() {
        var text = "a\n"
        val x = IgnoreMatcher(IgnoreSource { if (it.isEmpty()) text else null })
        assertTrue(x.isIgnored("a", false)); assertFalse(x.isIgnored("b", false))
        text = "b\n"
        assertTrue(x.isIgnored("a", false)) // cached
        x.invalidate("")
        assertFalse(x.isIgnored("a", false)); assertTrue(x.isIgnored("b", false))
    }

    @Test fun `pathological star pattern does not blow up`() {
        val x = m("a*a*a*a*a*a*a*a*a*a*b\n")
        val start = System.nanoTime()
        assertFalse(x.isIgnored("a".repeat(2000), false))
        assertTrue((System.nanoTime() - start) / 1_000_000 < 3000)
    }

    @Test fun `unterminated class and trailing backslash never match`() {
        val x = m("[abc\nfoo\\\n")
        assertFalse(x.isIgnored("[abc", false)); assertFalse(x.isIgnored("foo", false))
    }
}
