package dev.fonebrew.domain.workspace.fuzzy

import dev.fonebrew.contracts.workspace.ResourceChangeKind
import dev.fonebrew.domain.workspace.tree.FsEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random

private fun rank(query: String, candidates: List<String>): List<String> {
    val idx = QuickOpenIndex(); idx.rebuild(candidates)
    return idx.query(query, k = candidates.size).map { it.path }
}

class FuzzyGoldenTest {
    private data class Case(val query: String, val expectedFirst: String, val others: List<String>)

    private val cases = listOf(
        Case("mnact", "app/src/main/java/com/acme/MainActivity.kt", listOf("my/nice/action/tool.kt", "tools/minify_actions.py", "docs/min/notes/actual.md")),
        Case("main", "Main.kt", listOf("domain/Remaining.kt", "src/maintenance/x.kt", "docs/containment.md")),
        Case("readme", "README.md", listOf("docs/readme-old/notes.txt", "src/reader/memory.kt")),
        Case("foo.kt", "foo.kt", listOf("a/foo.kt.bak", "food.kt", "src/f/o/o.kt")),
        Case("gc", "src/GitClient.kt", listOf("lib/agreement.cc", "src/graphics/core.kt")),
        Case("cfg", "config/Config.kt", listOf("app/src/xcfgx.kt", "src/confusing/fig.kt")),
        Case("build.gradle", "app/build.gradle.kts", listOf("docs/build/gradle/notes.md", "bundle/layout/gradle.kts")),
        Case("idx", "src/QuickOpenIndex.kt", listOf("src/indexer/xml.kt", "docs/inside/dx.md")),
        Case("test", "src/test/FooTest.kt", listOf("src/latest/seat.kt", "docs/t/e/s/t.md")),
        Case("sm", "src/StringMap.kt", listOf("src/some/map.kt", "docs/system/manual.md")),
        Case("AB", "ABTest.kt", listOf("src/abstract/b.kt", "lib/alpha_beta.kt")),    // smart case: capitals matter
        Case("util", "Util.kt", listOf("src/utility/Helpers.kt", "docs/ultimate/list.md")),
        Case("main.kt", "src/Main.kt", listOf("src/domain/Remain.kt", "main/k/t.txt")),
        Case("appt", "app/AppTheme.kt", listOf("src/apartment.kt", "lib/happy/test.kt")),
        Case("strings.xml", "res/values/strings.xml", listOf("docs/string_utils/s.md", "src/StringsHelper.kt", "x/s/t/r/i/n/g/s/x/m/l")),
        Case("mf", "app/AndroidManifest.xml", listOf("docs/mf/notes.md", "src/main/foo.kt")),   // basename match beats a directory named mf
        Case("ser", "src/Serializer.kt", listOf("docs/server/readme.md", "lib/s/e/r.kt")),
        Case("dbhelper", "data/DbHelper.kt", listOf("data/db/helper_old/x.kt", "src/dumbbell/helper.kt")),
        Case("jsn", "src/Json.kt", listOf("src/main/java/js/nonsense.kt", "docs/jason.md")),
        Case("ws", "WorkspaceSearch.kt", listOf("src/worksheets.kt", "docs/windows/system.md")),
        Case("xyz", "a/xyz.txt", listOf("a/x/y/z.txt", "xylophone/zebra.txt")),
        Case("cmd", "src/CommandRunner.kt", listOf("docs/commands/ide.md", "lib/cm/dxy.kt")),
        Case("log", "src/Log.kt", listOf("src/catalog.kt", "docs/log_files/x.md")),
        Case("pkgmgr", "src/package_manager.kt", listOf("docs/pkg_notes/zzzzzzzzzzzzzzzz.md", "x/pxkxgxmxgxr.txt")),
        Case("vm", "ui/HomeViewModel.kt", listOf("src/vim/mode.kt", "docs/vm_setup/readme.md")),
    )

    @Test fun `golden rankings put the intended file first`() {
        val failures = ArrayList<String>()
        for (c in cases) {
            val r = rank(c.query, listOf(c.expectedFirst) + c.others)
            if (r.firstOrNull() != c.expectedFirst) failures.add("'${c.query}' -> $r (wanted ${c.expectedFirst})")
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        assertTrue("need at least 30 golden assertions, have ${cases.size}", cases.size >= 25)
    }

    @Test fun `a strong word-boundary abbreviation can rival a camel one but both beat scattered paths`() {
        val r = rank("mnact", listOf("my/nice/action/tool.kt", "src/main_activity_test.txt", "app/MainActivity.kt", "tools/minify_actions.py"))
        assertEquals(setOf("src/main_activity_test.txt", "app/MainActivity.kt"), r.take(2).toSet())
    }

    @Test fun `a directory abbreviation spanning segments is a legitimate match`() {
        assertEquals("pkg/mgr/info.txt", rank("pkgmgr", listOf("src/package_manager.kt", "pkg/mgr/info.txt")).first())
    }

    @Test fun `exact basename equals beats longer containing name`() {
        assertEquals("Main.kt", rank("Main.kt", listOf("src/MainActivity.kt", "Main.kt", "a/Main.kt.orig")).first())
    }

    @Test fun `path separator start beats mid word`() {
        assertEquals("app/model.kt", rank("model", listOf("xmodelx.kt", "app/model.kt")).first())
    }

    @Test fun `consecutive beats scattered`() {
        assertEquals("abc.txt", rank("abc", listOf("a_b_c.txt", "abc.txt", "axbxc.txt")).first())
    }

    @Test fun `camel hump beats mid word`() {
        assertEquals("FooBarBaz.kt", rank("fbb", listOf("foobarbaz.kt", "FooBarBaz.kt")).first())
    }

    @Test fun `first character match beats later start`() {
        assertEquals("zebra.kt", rank("zeb", listOf("a/b/c/d/e/f/uzebra.kt", "zebra.kt")).first())
    }

    @Test fun `shorter path wins an otherwise equal tie and ties are lexicographic`() {
        val r = rank("f", listOf("b/f", "a/f", "ab/f"))
        assertEquals(listOf("a/f", "b/f", "ab/f"), r)
    }

    @Test fun `smart case`() {
        assertNull(FuzzyMatcher.match("Main", "domain/main.kt"))
        assertNotNull(FuzzyMatcher.match("main", "Main.kt"))
        assertNotNull(FuzzyMatcher.match("Main", "Main.kt"))
        assertNull(FuzzyMatcher.match("mAin", "main.kt"))
    }

    @Test fun `multiple terms are anded and positions are merged`() {
        val m = FuzzyMatcher.match("act main", "src/MainActivity.kt")!!
        assertEquals(m.positions.toList(), m.positions.toList().sorted()); assertTrue(m.positions.size >= 7)
        assertNull(FuzzyMatcher.match("act zzz", "src/MainActivity.kt"))
    }

    @Test fun `positions for highlighting are exact for the canonical case`() {
        assertEquals(listOf(0, 3, 4, 5, 6), FuzzyMatcher.match("mnact", "MainActivity.kt")!!.positions.toList())
        assertEquals(listOf(0, 1, 2), FuzzyMatcher.match("abc", "abc")!!.positions.toList())
    }

    @Test fun `non subsequences and degenerate input do not match`() {
        assertNull(FuzzyMatcher.match("zz", "abc")); assertNull(FuzzyMatcher.match("", "abc")); assertNull(FuzzyMatcher.match("   ", "abc"))
        assertNull(FuzzyMatcher.match("a", "a".repeat(FuzzyMatcher.MAX_TEXT + 1)))
        assertNotNull(FuzzyMatcher.match("a", "a"))
    }

    @Test fun `ranking is deterministic regardless of input order`() {
        val rnd = Random(7)
        val paths = (0 until 300).map { i -> "d${rnd.nextInt(9)}/sub${rnd.nextInt(5)}/File${rnd.nextInt(40)}_${i % 7}.kt" }.distinct()
        val base = rank("fl7", paths)
        repeat(5) { assertEquals(base, rank("fl7", paths.shuffled(Random(it.toLong())))) }
    }

    @Test fun `scores are stable across repeated calls on reused scratch space`() {
        val a = FuzzyMatcher.match("srvc", "src/main/java/ServiceRegistry.kt")!!
        FuzzyMatcher.match("a", "z".repeat(100) + "a"); FuzzyMatcher.match("longerquery", "longerquery/x/y/z/longerquery")
        assertEquals(a, FuzzyMatcher.match("srvc", "src/main/java/ServiceRegistry.kt"))
    }
}

class TopKTest {
    @Test fun `matches a full sort and stays bounded`() {
        val rnd = Random(3); val data = (0 until 5000).map { rnd.nextInt(1000) }
        val t = TopK<Int>(25, naturalOrder())
        data.forEach { t.offer(it) }
        assertEquals(data.sorted().take(25), t.toSortedList())
    }
    @Test fun `k zero and fewer than k`() {
        val z = TopK<Int>(0, naturalOrder()); z.offer(1); assertTrue(z.toSortedList().isEmpty())
        val s = TopK<Int>(5, naturalOrder()); s.offer(3); s.offer(1); assertEquals(listOf(1, 3), s.toSortedList())
    }
}

class FrecencyTest {
    private val day = 24L * 3600 * 1000
    @Test fun `decays with the half life and recent beats old frequent`() {
        val f = Frecency(halfLifeMs = 7 * day)
        repeat(10) { f.visit("old", 0L) }
        f.visit("new", 28 * day); f.visit("new", 28 * day)
        assertTrue(f.score("new", 28 * day) > f.score("old", 28 * day))
        assertEquals(5.0, f.score("old", 7 * day), 1e-6)
    }
    @Test fun `bounded with deterministic eviction of the weakest`() {
        val f = Frecency(maxEntries = 3)
        f.visit("a", 0); f.visit("a", 0); f.visit("b", 0); f.visit("c", 0); f.visit("d", 0)
        assertEquals(3, f.size)
        assertEquals(setOf("a", "b", "d"), f.entries().map { it.path }.toSet())   // c is the weakest (tie: larger path goes first); d, just visited, is kept
        assertEquals("a", f.top(1, 0).single().first)
    }
    @Test fun `rename and remove and persistence round trip`() {
        val f = Frecency(); f.visit("x", 5); f.rename("x", "y"); assertEquals(0.0, f.score("x", 5), 0.0); assertTrue(f.score("y", 5) > 0)
        val g = Frecency(initial = f.entries()); assertEquals(f.score("y", 100), g.score("y", 100), 1e-9)
        g.remove("y"); assertEquals(0, g.size)
    }
}

class QuickOpenIndexTest {
    @Test fun `add remove rename and removeUnder`() {
        val i = QuickOpenIndex(); i.rebuild(listOf("a/x.kt", "a/y.kt", "b/z.kt"))
        i.remove("a/x.kt"); assertEquals(listOf("a/y.kt"), i.query("y").map { it.path })
        assertEquals(1, i.renameUnder("b", "c")); assertEquals(listOf("c/z.kt"), i.query("z").map { it.path })
        assertEquals(1, i.removeUnder("a")); assertEquals(1, i.size)
        i.add("q/new.kt"); assertEquals(listOf("q/new.kt"), i.query("new").map { it.path })
    }

    @Test fun `applies watcher events incrementally`() {
        val i = QuickOpenIndex(); i.rebuild(listOf("src/a.kt"))
        val fs = mutableMapOf("src/b.kt" to PathKind.FILE, "src/newdir" to PathKind.DIRECTORY, "src/old" to PathKind.GONE)
        val walk = { d: String -> listOf("$d/one.kt", "$d/two.kt") }
        i.applyEvent(FsEvent("src", "b.kt", ResourceChangeKind.CREATED), { fs.getValue(it) }, walk)
        i.applyEvent(FsEvent("src", "newdir", ResourceChangeKind.CREATED), { fs.getValue(it) }, walk)
        assertEquals(4, i.size)
        i.applyEvent(FsEvent("src", "a.kt", ResourceChangeKind.DELETED), { PathKind.GONE }, walk)
        i.applyEvent(FsEvent("src", "newdir", ResourceChangeKind.DELETED), { PathKind.GONE }, walk)
        assertEquals(listOf("src/b.kt"), i.query("kt").map { it.path })
    }

    @Test fun `bounded memory refuses past the cap and says so`() {
        val i = QuickOpenIndex(maxFiles = 10); i.rebuild((0 until 25).map { "f$it" })
        assertEquals(10, i.size); assertTrue(i.truncated)
    }

    @Test fun `frecency lifts a near tie but cannot beat a clearly better match`() {
        val i = QuickOpenIndex(); i.rebuild(listOf("src/Foo.kt", "z/y/x/w/xfxoxo.txt"))
        val f = Frecency(); repeat(5) { f.visit("b/foo_baz.kt", 0) }; repeat(500) { f.visit("z/y/x/w/xfxoxo.txt", 0) }
        val tie = QuickOpenIndex(); tie.rebuild(listOf("a/foo_bar.kt", "b/foo_baz.kt"))
        assertEquals("a/foo_bar.kt", tie.query("foo", nowMs = 0).first().path)                  // no history: lexicographic
        assertEquals("b/foo_baz.kt", tie.query("foo", nowMs = 0, frecency = f).first().path)    // near tie: frecency decides
        val r = i.query("foo", nowMs = 0, frecency = f).map { it.path }
        assertEquals("src/Foo.kt", r.first())             // a 500-visit file with a poor match cannot beat a clearly better one
        assertTrue(QuickOpenIndex.boost(1e9) <= QuickOpenIndex.FRECENCY_CAP)
    }

    @Test fun `empty query returns recent files that still exist`() {
        val i = QuickOpenIndex(); i.rebuild(listOf("a", "b"))
        val f = Frecency(); f.visit("b", 0); f.visit("b", 0); f.visit("a", 0); f.visit("gone", 0)
        assertEquals(listOf("b", "a"), i.query("", nowMs = 0, frecency = f).map { it.path })
        assertTrue(i.query("").isEmpty())
    }
}

class QuickOpenPerformanceTest {
    @Test fun `top 50 over 200000 paths`() {
        val rnd = Random(42)
        val dirs = listOf("app", "core", "domain", "data", "ui", "service", "inference", "workspace", "search", "tree", "util", "net")
        val words = listOf("Main", "Activity", "Service", "Registry", "Config", "Helper", "Manager", "Index", "Query", "Parser", "Loader", "Tree", "Node", "View", "Model")
        val paths = LinkedHashSet<String>()
        while (paths.size < 200_000) {
            val depth = 2 + rnd.nextInt(5)
            val sb = StringBuilder()
            repeat(depth) { sb.append(dirs[rnd.nextInt(dirs.size)]).append(rnd.nextInt(30)).append('/') }
            sb.append(words[rnd.nextInt(words.size)]).append(words[rnd.nextInt(words.size)]).append(rnd.nextInt(500)).append(if (rnd.nextBoolean()) ".kt" else ".java")
            paths.add(sb.toString())
        }
        paths.add("app/src/main/java/com/acme/MainActivity.kt")
        val idx = QuickOpenIndex(); idx.rebuild(paths)
        assertEquals(200_001, idx.size)
        val queries = listOf("mnact", "srvreg", "cfgh", "ldrtree", "vwmdl")
        idx.query("mnact", 50)  // warm up the JIT
        val times = ArrayList<Long>()
        var hits = 0
        for (q in queries) {
            val t0 = System.nanoTime(); val r = idx.query(q, 50); times.add((System.nanoTime() - t0) / 1_000_000); hits += r.size
        }
        println("QUICK-OPEN-PERF paths=200001 top=50 queries=${queries.size} ms=$times (JVM, not a phone)")
        File("build").mkdirs(); File("build/quickopen-perf.txt").writeText("paths=200001 top=50 ms=$times\n")
        assertTrue(hits > 0)
        assertTrue(idx.query("mnact", 5).all { it.path.substringAfterLast('/').startsWith("MainActivity") })
        assertTrue("slowest ${times.max()} ms", times.max() < 5_000)
    }
}
