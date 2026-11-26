package dev.fonebrew.domain.workspace.grep

import dev.fonebrew.domain.workspace.roots.Workspace
import dev.fonebrew.domain.workspace.roots.WorkspaceEdit
import dev.fonebrew.domain.workspace.roots.WorkspaceRoot
import dev.fonebrew.domain.workspace.roots.symlink
import dev.fonebrew.domain.workspace.roots.tmp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private class Fixture {
    val dir = tmp("grep"); var n = 0
    val ws = Workspace { "r${++n}" }
    val root: WorkspaceRoot = (ws.addRoot("R", dir) as WorkspaceEdit.Ok).root!!
    val engine = SearchInFiles(ws)
    fun file(rel: String, text: String) { File(dir, rel).also { it.parentFile.mkdirs() }.writeText(text) }
    fun run(req: SearchRequest): List<SearchEvent> = runBlocking { withTimeout(60_000) { engine.search(req).toList() } }
    fun hits(req: SearchRequest) = run(req).filterIsInstance<SearchEvent.FileHit>()
    fun done(req: SearchRequest) = run(req).last() as SearchEvent.Done
}

class SearchInFilesTest {
    @Test fun `literal search finds positions line numbers and context`() {
        val f = Fixture()
        f.file("a.txt", "zero\none needle two\nthree\nneedle again\nlast\n")
        val h = f.hits(SearchRequest("needle", contextLines = 1)).single()
        assertEquals("a.txt", h.rel); assertEquals(2, h.matches.size)
        val m = h.matches[0]
        assertEquals(2, m.line); assertEquals(4, m.startCol); assertEquals(10, m.endCol); assertEquals("one needle two", m.lineText)
        assertEquals(listOf("zero"), m.before); assertEquals(listOf("three"), m.after)
        assertEquals(listOf("three"), h.matches[1].before); assertEquals(listOf("last"), h.matches[1].after)
    }

    @Test fun `case sensitivity whole word and several matches per line`() {
        val f = Fixture(); f.file("c.txt", "Foo foo FOO foobar foo_bar foo\n")
        assertEquals(6, f.hits(SearchRequest("foo")).single().matches.size)
        assertEquals(1, f.hits(SearchRequest("Foo", caseSensitive = true)).single().matches.size)
        val whole = f.hits(SearchRequest("foo", wholeWord = true)).single().matches
        assertEquals(listOf(0, 4, 8, 27), whole.map { it.startCol })
    }

    @Test fun `regex search with groups and invalid pattern is typed`() {
        val f = Fixture(); f.file("r.txt", "id=42 id=7 name=x\n")
        val m = f.hits(SearchRequest("id=(\\d+)", regex = true)).single().matches
        assertEquals(listOf(0, 6), m.map { it.startCol }); assertEquals(listOf(5, 10), m.map { it.endCol })
        val bad = f.done(SearchRequest("(unclosed", regex = true))
        assertTrue(bad.outcome is SearchOutcome.InvalidPattern)
        assertTrue(f.done(SearchRequest("")).outcome is SearchOutcome.InvalidPattern)
    }

    @Test fun `regex whole word wrapper does not shift groups`() {
        val f = Fixture(); f.file("w.txt", "cat concat cat\n")
        val m = f.hits(SearchRequest("(c)at", regex = true, wholeWord = true)).single().matches
        assertEquals(listOf(0, 11), m.map { it.startCol })
    }

    @Test fun `ignore rules and root excludes are respected`() {
        val f = Fixture()
        f.file(".gitignore", "secret.txt\nlogs/\n"); f.file("secret.txt", "needle"); f.file("logs/x.txt", "needle"); f.file("keep.txt", "needle")
        f.file("node_modules/pkg/i.js", "needle"); f.file("build/o.txt", "needle")
        assertEquals(listOf("keep.txt"), f.hits(SearchRequest("needle")).map { it.rel }.filter { it != ".gitignore" })
        f.ws.updateSettings(f.root.id, f.root.settings.copy(useDefaultIgnores = false, useGitignore = false, excludes = listOf("logs/")))
        val rels = f.hits(SearchRequest("needle")).map { it.rel }.toSet()
        assertEquals(setOf("secret.txt", "keep.txt", "node_modules/pkg/i.js", "build/o.txt"), rels)
    }

    @Test fun `include and exclude globs`() {
        val f = Fixture(); f.file("a.kt", "x"); f.file("b.java", "x"); f.file("src/c.kt", "x"); f.file("src/gen/d.kt", "x"); f.file("docs/e.md", "x")
        fun rels(inc: List<String>, exc: List<String>) = f.hits(SearchRequest("x", includeGlobs = inc, excludeGlobs = exc)).map { it.rel }.toSet()
        assertEquals(setOf("a.kt", "src/c.kt", "src/gen/d.kt"), rels(listOf("*.kt"), emptyList()))
        assertEquals(setOf("a.kt", "src/c.kt"), rels(listOf("*.kt"), listOf("gen")))
        assertEquals(setOf("src/c.kt", "src/gen/d.kt"), rels(listOf("src/**"), emptyList()))
        assertEquals(setOf("a.kt", "b.java", "src/c.kt", "src/gen/d.kt"), rels(emptyList(), listOf("docs/")))
    }

    @Test fun `binary files and oversize files are reported as skipped never silently dropped`() {
        val f = Fixture()
        File(f.dir, "bin.dat").writeBytes(byteArrayOf(0x6e, 0x65, 0x65, 0x64, 0x6c, 0x65, 0, 1, 2))
        f.file("late.txt", "x".repeat(9000) + "\u0000needle"); f.file("big.txt", "needle ".repeat(4000)); f.file("ok.txt", "needle")
        val ev = f.run(SearchRequest("needle", maxFileBytes = 20_000))
        val skipped = ev.filterIsInstance<SearchEvent.Skipped>().associate { it.rel to it.reason }
        assertEquals(SkipReason.BINARY, skipped["bin.dat"]); assertEquals(SkipReason.BINARY, skipped["late.txt"]); assertEquals(SkipReason.TOO_LARGE, skipped["big.txt"])
        assertEquals(listOf("ok.txt"), ev.filterIsInstance<SearchEvent.FileHit>().map { it.rel })
        val d = ev.last() as SearchEvent.Done
        assertEquals(2, d.stats.skippedBinary); assertEquals(1, d.stats.skippedTooLarge); assertEquals(SearchOutcome.Completed, d.outcome)
    }

    @Test fun `symlinks are not followed so nothing outside the root is read`() {
        val f = Fixture(); val out = tmp("out"); File(out, "s.txt").writeText("needle")
        symlink(File(f.dir, "esc"), out); symlink(File(f.dir, "esc.txt"), File(out, "s.txt")); f.file("in.txt", "needle")
        assertEquals(listOf("in.txt"), f.hits(SearchRequest("needle")).map { it.rel })
    }

    @Test fun `result limits are explicit`() {
        val f = Fixture(); for (i in 0 until 20) f.file("f$i.txt", "needle\nneedle\n")
        val d = f.done(SearchRequest("needle", maxMatches = 7))
        assertEquals(SearchOutcome.ResultLimit(7), d.outcome); assertEquals(7, d.stats.matches)
        val per = f.hits(SearchRequest("needle", maxMatchesPerFile = 1, maxMatches = 100))
        assertTrue(per.all { it.matches.size == 1 && it.moreInFile })
    }

    @Test fun `replace preview computes lines and writes nothing`() {
        val f = Fixture(); f.file("p.txt", "a-1 b-22 a-3\nnothing\n")
        val m = f.hits(SearchRequest("a-(\\d)", regex = true, replacement = "X\$1")).single().matches
        assertEquals("X1 b-22 X3", m[0].replacedLine); assertEquals("X1 b-22 X3", m[1].replacedLine)
        val lit = f.hits(SearchRequest("b-22", replacement = "[gone]")).single().matches.single()
        assertEquals("a-1 [gone] a-3", lit.replacedLine)
        assertEquals("a-1 b-22 a-3\nnothing\n", File(f.dir, "p.txt").readText())
        assertNull(f.hits(SearchRequest("a-1")).single().matches.single().replacedLine)
        assertTrue(f.done(SearchRequest("a-(\\d)", regex = true, replacement = "\$9")).outcome is SearchOutcome.InvalidPattern)
    }

    @Test fun `CRLF and CR line splitting and empty regex matches`() {
        val f = Fixture(); f.file("l.txt", "one\r\ntwo needle\rthree\n")
        assertEquals(2, f.hits(SearchRequest("needle")).single().matches.single().line)
        assertTrue(f.hits(SearchRequest("x*", regex = true)).isEmpty())   // zero-length matches are not results
    }

    @Test fun `walk is deterministic`() {
        val f = Fixture(); for (d in listOf("b", "a", "c")) for (i in listOf(10, 2, 1)) f.file("$d/f$i.txt", "needle")
        val first = f.hits(SearchRequest("needle")).map { it.rel }
        assertEquals(first, f.hits(SearchRequest("needle")).map { it.rel })
        assertEquals(listOf("a/f1.txt", "a/f2.txt", "a/f10.txt", "b/f1.txt"), first.take(4))
    }

    @Test fun `several roots are searched in workspace order and can be filtered`() {
        val f = Fixture(); val other = tmp("other"); File(other, "o.txt").writeText("needle"); f.file("m.txt", "needle")
        val r2 = (f.ws.addRoot("Two", other) as WorkspaceEdit.Ok).root!!
        assertEquals(listOf(f.root.id to "m.txt", r2.id to "o.txt"), f.hits(SearchRequest("needle")).map { it.rootId to it.rel })
        assertEquals(listOf("o.txt"), f.hits(SearchRequest("needle", rootIds = setOf(r2.id))).map { it.rel })
    }

    @Test fun `cancelling the collector stops the search`() {
        val f = Fixture(); for (i in 0 until 300) f.file("f$i.txt", "needle")
        val first = runBlocking { f.engine.search(SearchRequest("needle")).first() }
        assertTrue(first is SearchEvent.FileHit)
        val two = runBlocking { f.engine.search(SearchRequest("needle")).take(2).toList() }
        assertEquals(2, two.size)
    }

    @Test fun `very long lines are not regex searched but are counted`() {
        val f = Fixture(); f.file("min.js", "a".repeat(30_000) + " needle\n")
        val d = f.run(SearchRequest("needle", regex = true))
        assertEquals(1, (d.last() as SearchEvent.Done).stats.longLinesSkipped)
        assertEquals(1, f.hits(SearchRequest("needle")).size)   // literal search still finds it
    }
}

class RegexSafetyTest {
    private val risky = listOf("(a+)+\$", "(a|aa)+\$", "(a*)*b", "^(a+)+\$", "(x+x+)+y")

    @Test fun `risky shapes are refused up front without running`() {
        val f = Fixture(); f.file("evil.txt", "a".repeat(60) + "!\n")
        for (p in risky) {
            val t0 = System.nanoTime()
            val d = f.done(SearchRequest(p, regex = true))
            assertTrue("$p -> ${d.outcome}", d.outcome is SearchOutcome.RiskyPattern)
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 5_000)
        }
    }

    @Test fun `lint does not flag ordinary patterns`() {
        for (p in listOf("foo.*bar", "\\d+", "[a-z]+@[a-z]+", "(foo)+", "(?:ab){2,3}c", "a{1,5}b+", "\\(a+\\)+", "[(a+)+]")) {
            assertNull("$p", RegexLint.risk(p))
        }
        for (p in risky + listOf("(\\w+\\s*)*", "((a+))+", "(?:a|b)*")) assertTrue("$p", RegexLint.risk(p) != null)
    }

    @Test fun `with risk allowed the per-line budget ends polynomial blow-up in a typed Timeout`() {
        val f = Fixture()
        f.file("evil.txt", "a".repeat(60) + "\n")
        // ten adjacent unbounded repeats then a letter that never comes: O(n^10) on this JDK, no group so the lint passes it
        val d = f.done(SearchRequest("a*a*a*a*a*a*a*a*a*a*b", regex = true, regexBudgetPerLine = 300_000))
        val t = d.outcome as? SearchOutcome.Timeout
        println("REGEX-TIMEOUT outcome=${d.outcome}")
        assertTrue("outcome was ${d.outcome}", t != null)
        assertEquals("evil.txt", t!!.rel); assertEquals(1, t.line)
        // the same pattern with a generous budget still ends (quickly) because it is cancelled by the budget, not left to run
    }

    @Test fun `a regex that recurses too deeply on a long line ends in a typed Timeout, never an uncaught StackOverflowError`() {
        val f = Fixture()
        f.file("long.txt", "a".repeat(5_000) + "\n")
        // an alternation inside a repeat recurses once per character in java.util.regex; allowed past the lint on purpose
        val d = f.done(SearchRequest("(?:a|b)*c", regex = true, allowRiskyRegex = true))
        assertTrue("outcome was ${d.outcome}", d.outcome is SearchOutcome.Timeout || d.outcome == SearchOutcome.Completed)
    }

    @Test fun `default budget stops the blow-up quickly`() {
        val f = Fixture(); f.file("evil.txt", "a".repeat(60) + "\n")
        val t0 = System.nanoTime()
        val d = f.done(SearchRequest("a*a*a*a*a*a*a*a*a*a*b", regex = true))
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("REGEX-DEFAULT-BUDGET ${d.outcome} in ${ms}ms")
        assertTrue(d.outcome is SearchOutcome.Timeout)
        assertTrue("${ms}ms", ms < 15_000)
    }

    @Test fun `risky patterns run to a bounded end and never hang`() {
        val f = Fixture(); f.file("evil.txt", "a".repeat(40) + "!\n")
        var timeouts = 0
        for (p in risky) {
            val t0 = System.nanoTime()
            val d = f.done(SearchRequest(p, regex = true, allowRiskyRegex = true, regexBudgetPerLine = 300_000))
            val ms = (System.nanoTime() - t0) / 1_000_000
            println("REGEX-RISKY $p -> ${d.outcome} in ${ms}ms")
            assertTrue("$p -> ${d.outcome}", d.outcome is SearchOutcome.Timeout || d.outcome == SearchOutcome.Completed)
            assertTrue("$p took ${ms}ms", ms < 20_000)
            if (d.outcome is SearchOutcome.Timeout) timeouts++
        }
        println("REGEX-RISKY timeouts=$timeouts of ${risky.size}")
    }

    @Test fun `a well behaved regex is unaffected by the budget`() {
        val f = Fixture(); f.file("ok.txt", "x".repeat(5000) + " needle\n")
        assertTrue(f.done(SearchRequest("ne+dle", regex = true)).outcome == SearchOutcome.Completed)
    }

    @Test fun `budget char sequence counts and throws`() {
        val b = BudgetCharSequence("abcdef", 5)
        for (i in 0 until 5) b[i]
        try { b[0]; org.junit.Assert.fail() } catch (_: RegexBudgetExceeded) {}
        assertEquals("abcdef", b.toString()); assertEquals(6, b.length); assertEquals("bc", b.subSequence(1, 3))
    }

    @Test fun `tick callback can abort a running match`() {
        class Stop : RuntimeException()
        val b = BudgetCharSequence("a".repeat(100) + "!", Long.MAX_VALUE) { throw Stop() }
        try { java.util.regex.Pattern.compile("(a+)+\$").matcher(b).find(); org.junit.Assert.fail() } catch (_: Stop) {}
    }
}

class GlobFilterTest {
    @Test fun `name globs at any depth anchored globs and double star`() {
        val g = GlobFilter(listOf("*.kt", "app/**"), listOf("gen/", "/top.kt"))
        assertTrue(g.accepts("a/b/C.kt")); assertTrue(g.accepts("app/x/y.txt"))
        assertFalse(g.accepts("a/gen/C.kt")); assertFalse(g.accepts("top.kt")); assertFalse(g.accepts("readme.md"))
        assertTrue(GlobFilter(emptyList(), emptyList()).accepts("anything/at/all"))
    }
}
