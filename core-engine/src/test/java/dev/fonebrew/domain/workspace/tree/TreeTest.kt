package dev.fonebrew.domain.workspace.tree

import dev.fonebrew.contracts.workspace.ResourceChangeKind
import dev.fonebrew.domain.workspace.ignore.IgnoreMatcher
import dev.fonebrew.domain.workspace.ignore.IgnoreSource
import dev.fonebrew.domain.workspace.roots.DirEntry
import dev.fonebrew.domain.workspace.roots.DirRead
import dev.fonebrew.domain.workspace.roots.DirectoryReader
import dev.fonebrew.domain.workspace.roots.LocalFileProvider
import dev.fonebrew.domain.workspace.roots.tmp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NaturalOrderTest {
    private fun sort(vararg n: String) = n.sortedWith(NaturalOrder.names)

    @Test fun `numbers compare by value`() = assertEquals(listOf("f1", "f2", "f10", "f100"), sort("f100", "f10", "f2", "f1"))
    @Test fun `case insensitive`() = assertEquals(listOf("apple", "Banana", "cherry"), sort("cherry", "Banana", "apple"))
    @Test fun `leading zeros tie breaks deterministically`() = assertEquals(listOf("a1", "a01", "a001"), sort("a001", "a01", "a1"))
    @Test fun `huge digit runs do not overflow`() {
        val a = "x" + "9".repeat(60); val b = "x1" + "0".repeat(60)
        assertTrue(NaturalOrder.compareNames(a, b) < 0)
    }
    @Test fun `total order - equal ignoring case still differs`() {
        assertTrue(NaturalOrder.compareNames("A", "a") != 0)
        assertEquals(0, NaturalOrder.compareNames("a", "a"))
    }
    @Test fun `directories first`() {
        val d = DirEntry("zdir", true, false, 0, 0); val f = DirEntry("a.txt", false, false, 0, 0)
        assertEquals(listOf(d, f), listOf(f, d).sortedWith(NaturalOrder.entries))
    }
}

class FileTreeModelTest {
    private fun model(root: File, ignore: IgnoreMatcher? = null, page: Int = 200) =
        FileTreeModel(DirectoryLister(LocalFileProvider(root), ignore), pageSize = page)

    private fun names(m: FileTreeModel) = m.rows().filterIsInstance<TreeRow.Item>().map { it.rel }

    @Test fun `lazy - only expanded directories are read`() {
        val root = tmp(); File(root, "a/b").mkdirs(); File(root, "a/b/f.txt").writeText("")
        val reads = ArrayList<String>()
        val real = LocalFileProvider(root)
        val m = FileTreeModel(DirectoryLister(DirectoryReader { d, n -> reads.add(d); real.listDir(d, n) }))
        assertEquals(listOf(""), reads)
        m.expand("a"); assertEquals(listOf("", "a"), reads)
        assertEquals(listOf("a", "a/b"), names(m))
        m.expand("a/b"); assertEquals(listOf("a", "a/b", "a/b/f.txt"), names(m))
        m.collapse("a"); assertEquals(listOf("a"), names(m))
    }

    @Test fun `paging shows load more and truncation is reported`() {
        val root = tmp(); for (i in 0 until 25) File(root, "f$i").writeText("")
        val m = model(root, page = 10)
        assertEquals(10, names(m).size)
        assertEquals(15, (m.rows().last() as TreeRow.LoadMore).remaining)
        m.loadMore(""); m.loadMore("")
        assertEquals(25, names(m).size); assertTrue(m.rows().none { it is TreeRow.LoadMore })
        val capped = FileTreeModel(DirectoryLister(LocalFileProvider(root), scanCap = 5))
        assertTrue(capped.rows().last() is TreeRow.Truncated)
    }

    @Test fun `ignored entries are hidden and counted`() {
        val root = tmp(); File(root, "build").mkdirs(); File(root, "keep.kt").writeText("")
        val l = DirectoryLister(LocalFileProvider(root), IgnoreMatcher(defaults = listOf("build/")))
        val r = (l.list("") as ListOutcome.Ok).listing
        assertEquals(listOf("keep.kt"), r.entries.map { it.name }); assertEquals(1, r.hiddenByIgnore)
        assertEquals(2, ((l.list("", showIgnored = true)) as ListOutcome.Ok).listing.entries.size)
    }

    @Test fun `restore expanded drops vanished directories`() {
        val root = tmp(); File(root, "a/b").mkdirs()
        val m = model(root)
        val lost = m.restoreExpanded(listOf("a", "a/b", "gone", "gone/x"))
        assertEquals(setOf("gone", "gone/x"), lost.toSet()); assertEquals(setOf("a", "a/b"), m.expandedDirs())
    }

    @Test fun `directory deleted between listings collapses on refresh`() {
        val root = tmp(); File(root, "a").mkdirs(); File(root, "a/f").writeText("")
        val m = model(root); m.expand("a")
        File(root, "a/f").delete(); File(root, "a").delete()
        m.refresh("a")
        assertFalse(m.isExpanded("a")); assertEquals(emptyList<String>(), names(m).filter { it.startsWith("a/") })
    }

    @Test fun `concurrent change during listing never throws - vanished entries are skipped`() {
        val root = tmp(); for (i in 0 until 300) File(root, "f$i").writeText("x")
        val provider = LocalFileProvider(root)
        val t = Thread { for (i in 0 until 300) File(root, "f$i").delete() }
        t.start()
        repeat(30) { val r = provider.listDir("", 1000); assertTrue(r is DirRead.Ok || r is DirRead.Unreadable) }
        t.join()
    }

    @Test fun `unreadable directory shows a row instead of throwing`() {
        val root = tmp(); File(root, "a").mkdirs()
        val m = FileTreeModel(DirectoryLister(DirectoryReader { d, n -> if (d == "a") DirRead.Unreadable("denied") else LocalFileProvider(root).listDir(d, n) }))
        m.expand("a")
        assertTrue(m.rows().any { it is TreeRow.Unreadable })
    }
}

class ChangeTest {
    private fun ev(n: String, k: ResourceChangeKind) = FsEvent("", n, k)

    @Test fun `coalescer nets out bursts`() {
        val c = ChangeCoalescer()
        c.offer(ev("a", ResourceChangeKind.CREATED)); c.offer(ev("a", ResourceChangeKind.MODIFIED))
        c.offer(ev("b", ResourceChangeKind.CREATED)); c.offer(ev("b", ResourceChangeKind.DELETED))
        c.offer(ev("c", ResourceChangeKind.MODIFIED)); c.offer(ev("c", ResourceChangeKind.DELETED))
        c.offer(ev("d", ResourceChangeKind.DELETED)); c.offer(ev("d", ResourceChangeKind.CREATED))
        assertEquals(
            listOf(ev("a", ResourceChangeKind.CREATED), ev("c", ResourceChangeKind.DELETED), ev("d", ResourceChangeKind.MODIFIED)),
            c.drain(),
        )
        assertFalse(c.hasPending)
    }

    @Test fun `polling differ reports create modify delete`() {
        val old = mapOf("a" to EntrySig(false, 1, 1), "b" to EntrySig(false, 1, 1), "c" to EntrySig(false, 1, 1))
        val new = mapOf("a" to EntrySig(false, 1, 1), "b" to EntrySig(false, 2, 1), "d" to EntrySig(false, 1, 1))
        assertEquals(
            listOf(FsEvent("", "b", ResourceChangeKind.MODIFIED), FsEvent("", "d", ResourceChangeKind.CREATED), FsEvent("", "c", ResourceChangeKind.DELETED)),
            PollingDiffer.diff("", old, new),
        )
    }

    private fun await(timeoutMs: Long = 8000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(25) }
        return cond()
    }

    private fun watchRoundTrip(useService: Boolean) {
        val dir = tmp(); val got = java.util.concurrent.CopyOnWriteArrayList<FsEvent>()
        DirectoryWatcher(debounceMs = 50, pollMs = 100, useWatchService = useService) { got.addAll(it) }.use { w ->
            w.watch("", dir.toPath())
            File(dir, "new.txt").writeText("x")
            assertTrue("created seen", await { got.any { it.name == "new.txt" } })
            File(dir, "new.txt").delete()
            assertTrue("deleted seen", await { got.any { it.name == "new.txt" && it.kind == ResourceChangeKind.DELETED } })
        }
    }

    @Test fun `watch service delivers events`() = watchRoundTrip(true)
    @Test fun `polling fallback delivers events`() = watchRoundTrip(false)
}

class FileWalkerTest {
    @Test fun `walk is sorted deterministic ignores and skips symlinks`() {
        val root = tmp(); val out = tmp("out"); File(out, "leak.txt").writeText("x")
        File(root, "b").mkdirs(); File(root, "a").mkdirs(); File(root, "node_modules").mkdirs()
        for (f in listOf("b/2.txt", "a/10.txt", "a/9.txt", "top.txt", "node_modules/x.js")) File(root, f).writeText("x")
        java.nio.file.Files.createSymbolicLink(File(root, "esc").toPath(), out.toPath())
        val ig = IgnoreMatcher(IgnoreSource.NONE, defaults = listOf("node_modules/"))
        val w = FileWalker(root.toPath(), ig)
        val got = w.files().map { it.rel }.toList()
        assertEquals(listOf("top.txt", "a/9.txt", "a/10.txt", "b/2.txt"), got)
        assertEquals(got, FileWalker(root.toPath(), ig).files().map { it.rel }.toList())
    }

    @Test fun `file bound truncates and says so`() {
        val root = tmp(); for (i in 0 until 20) File(root, "f$i").writeText("")
        val w = FileWalker(root.toPath(), null, maxFiles = 5)
        assertEquals(5, w.files().count()); assertTrue(w.truncated)
    }

    @Test fun `very deep tree does not overflow the stack`() {
        val root = tmp(); var d = root
        for (i in 0 until 400) { d = File(d, "d"); }
        d.mkdirs(); File(d, "leaf.txt").writeText("")
        assertEquals(1, FileWalker(root.toPath()).files().count())
    }
}
