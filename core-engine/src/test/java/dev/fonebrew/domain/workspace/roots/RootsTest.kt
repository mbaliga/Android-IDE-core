package dev.fonebrew.domain.workspace.roots

import dev.fonebrew.contracts.workspace.ResourceProvider
import dev.fonebrew.contracts.workspace.ResourceUri
import dev.fonebrew.contracts.workspace.ResourceWrite
import dev.fonebrew.data.workspace.WorkspaceFileStore
import dev.fonebrew.data.workspace.WorkspaceLoad
import dev.fonebrew.domain.workspace.fuzzy.FrecencyEntry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

internal fun tmp(prefix: String = "ws"): File = Files.createTempDirectory(prefix).toFile().canonicalFile

internal fun symlink(link: File, target: File) {
    Files.createSymbolicLink(link.toPath(), target.toPath())
}

class WorkspacePathTest {
    @Test fun `normalises dots slashes`() {
        assertEquals("a/b", WorkspacePath.of("r", "a//./b/").rel)
        assertTrue(WorkspacePath.of("r", "").isRoot)
        assertTrue(WorkspacePath.of("r", ".").isRoot)
    }

    @Test fun `refuses escaping forms`() {
        val bad = mapOf(
            "../x" to PathRefusal.PARENT_SEGMENT, "a/../../x" to PathRefusal.PARENT_SEGMENT, "a/.." to PathRefusal.PARENT_SEGMENT,
            "/etc/passwd" to PathRefusal.ABSOLUTE, "\\windows" to PathRefusal.ABSOLUTE, "C:\\x" to PathRefusal.DRIVE_PREFIX,
            "a\u0000b" to PathRefusal.NUL_BYTE, "a".repeat(5000) to PathRefusal.TOO_LONG,
        )
        for ((raw, why) in bad) {
            val r = WorkspacePath.parse("r", raw)
            assertTrue("$raw", r is PathParse.Refused)
            assertEquals(raw, why, (r as PathParse.Refused).reason)
        }
        assertEquals(PathRefusal.EMPTY_ROOT_ID, (WorkspacePath.parse(" ", "a") as PathParse.Refused).reason)
    }

    @Test fun `names containing dots are fine`() {
        assertEquals("..a/b..", WorkspacePath.of("r", "..a/b..").rel)
        assertEquals("a/.hidden", WorkspacePath.of("r", "a/.hidden").rel)
    }

    @Test fun `uri keeps root id so two roots never collide`() {
        val a = WorkspacePath.of("r1", "README.md").toResourceUri()
        val b = WorkspacePath.of("r2", "README.md").toResourceUri()
        assertTrue(a.raw != b.raw)
        assertEquals(ResourceProvider.LOCAL, a.provider)
    }

    @Test fun `parent and child`() {
        val p = WorkspacePath.of("r", "a/b/c")
        assertEquals("a/b", p.parent!!.rel); assertEquals("c", p.name)
        assertNull(WorkspacePath.root("r").parent)
        assertTrue(p.child("..") is PathParse.Refused)
    }
}

class PathGuardTest {
    @Test fun `symlink to outside directory is refused for reads and writes through it`() {
        val root = tmp(); val outside = tmp("outside")
        File(outside, "secret.txt").writeText("s")
        symlink(File(root, "out"), outside)
        val g = PathGuard(root)
        assertTrue(g.resolve("out") is Guarded.Escape)
        assertTrue(g.resolve("out/secret.txt") is Guarded.Escape)
        assertTrue(g.resolve("out/newfile.txt") is Guarded.Escape)
    }

    @Test fun `symlink to outside file is refused`() {
        val root = tmp(); val outside = tmp("outside")
        File(outside, "f").writeText("x")
        symlink(File(root, "l"), File(outside, "f"))
        assertTrue(PathGuard(root).resolve("l") is Guarded.Escape)
    }

    @Test fun `final symlink is allowed when not followed so delete acts on the link`() {
        val root = tmp(); val outside = tmp("outside")
        symlink(File(root, "l"), outside)
        assertTrue(PathGuard(root).resolve("l", followFinalSymlink = false) is Guarded.Ok)
    }

    @Test fun `symlink staying inside the root is allowed`() {
        val root = tmp(); File(root, "real").mkdirs(); File(root, "real/f").writeText("x")
        symlink(File(root, "alias"), File(root, "real"))
        val r = PathGuard(root).resolve("alias/f")
        assertTrue(r is Guarded.Ok)
    }

    @Test fun `dangling and looping symlinks are refused`() {
        val root = tmp()
        symlink(File(root, "dangling"), File(root, "nowhere"))
        symlink(File(root, "loopa"), File(root, "loopb")); symlink(File(root, "loopb"), File(root, "loopa"))
        val g = PathGuard(root)
        assertTrue(g.resolve("dangling") is Guarded.Escape)
        assertTrue(g.resolve("dangling/x") is Guarded.Escape)
        assertTrue(g.resolve("loopa") is Guarded.Escape)
    }

    @Test fun `new paths under real directories resolve inside the root`() {
        val root = tmp(); File(root, "d").mkdirs()
        val r = PathGuard(root).resolve("d/new/deep.txt") as Guarded.Ok
        assertTrue(r.path.startsWith(root.toPath()))
    }

    @Test fun `sibling directory with a common name prefix is not mistaken for inside`() {
        val base = tmp(); val root = File(base, "proj").also { it.mkdirs() }; val evil = File(base, "proj-evil").also { it.mkdirs() }
        symlink(File(root, "x"), evil)
        assertTrue(PathGuard(root).resolve("x") is Guarded.Escape)
    }
}

class WorkspaceTest {
    private var n = 0
    private fun ws(snap: WorkspaceSnapshot = WorkspaceSnapshot()) = Workspace(snap) { "id${++n}" }

    @Test fun `add rename reorder remove`() {
        val w = ws(); val a = tmp(); val b = tmp(); val c = tmp()
        val ra = (w.addRoot("A", a) as WorkspaceEdit.Ok).root!!
        val rb = (w.addRoot("B", b) as WorkspaceEdit.Ok).root!!
        val rc = (w.addRoot("C", c) as WorkspaceEdit.Ok).root!!
        assertEquals(listOf("A", "B", "C"), w.roots().map { it.name })
        assertTrue(w.moveRoot(rc.id, 0) is WorkspaceEdit.Ok)
        assertEquals(listOf("C", "A", "B"), w.roots().map { it.name })
        assertTrue(w.moveRoot(rc.id, 9) is WorkspaceEdit.Refused)
        assertTrue(w.renameRoot(ra.id, "B") is WorkspaceEdit.Refused)
        assertTrue(w.renameRoot(ra.id, "Alpha") is WorkspaceEdit.Ok)
        assertTrue(w.removeRoot(rb.id) is WorkspaceEdit.Ok)
        assertEquals(listOf("C", "Alpha"), w.roots().map { it.name })
        assertTrue(w.removeRoot("nope") is WorkspaceEdit.Refused)
    }

    @Test fun `refuses duplicate and overlapping roots and non directories`() {
        val w = ws(); val a = tmp(); File(a, "inner").mkdirs()
        assertTrue(w.addRoot("A", a) is WorkspaceEdit.Ok)
        assertEquals(WorkspaceRefusal.DUPLICATE_ROOT, (w.addRoot("A2", a) as WorkspaceEdit.Refused).reason)
        assertEquals(WorkspaceRefusal.OVERLAPPING_ROOT, (w.addRoot("In", File(a, "inner")) as WorkspaceEdit.Refused).reason)
        assertEquals(WorkspaceRefusal.OVERLAPPING_ROOT, (w.addRoot("Up", a.parentFile) as WorkspaceEdit.Refused).reason)
        assertEquals(WorkspaceRefusal.NOT_A_DIRECTORY, (w.addRoot("F", File(a, "nope")) as WorkspaceEdit.Refused).reason)
        assertEquals(WorkspaceRefusal.BLANK_NAME, (w.addRoot(" ", tmp()) as WorkspaceEdit.Refused).reason)
    }

    @Test fun `a symlink alias of an existing root counts as a duplicate`() {
        val w = ws(); val a = tmp(); val holder = tmp()
        w.addRoot("A", a); symlink(File(holder, "alias"), a)
        assertEquals(WorkspaceRefusal.DUPLICATE_ROOT, (w.addRoot("B", File(holder, "alias")) as WorkspaceEdit.Refused).reason)
    }

    @Test fun `ui state is bounded and removed with the root`() {
        val w = ws(); val r = (w.addRoot("A", tmp()) as WorkspaceEdit.Ok).root!!
        w.setExpandedDirs(r.id, (0 until 800).map { "d$it" })
        assertEquals(Workspace.MAX_EXPANDED, w.uiState(r.id).expandedDirs.size)
        for (i in 0 until 40) w.recordOpenFile(r.id, "f$i")
        w.recordOpenFile(r.id, "f39"); w.recordOpenFile(r.id, "f5")
        val last = w.uiState(r.id).lastOpenFiles
        assertEquals(Workspace.MAX_LAST_OPEN, last.size); assertEquals(listOf("f5", "f39", "f38"), last.take(3))
        w.removeRoot(r.id)
        assertTrue(w.uiState(r.id).expandedDirs.isEmpty())
    }

    @Test fun `resolve is guarded per root and unknown root is refused`() {
        val w = ws(); val a = tmp(); val out = tmp("out")
        val r = (w.addRoot("A", a) as WorkspaceEdit.Ok).root!!
        symlink(File(a, "l"), out)
        assertTrue(w.resolve(WorkspacePath.of(r.id, "ok.txt")) is Guarded.Ok)
        assertTrue(w.resolve(WorkspacePath.of(r.id, "l/x")) is Guarded.Escape)
        assertTrue(w.resolve(WorkspacePath.of("ghost", "x")) is Guarded.Escape)
    }

    @Test fun `settings update persists on the root`() {
        val w = ws(); val r = (w.addRoot("A", tmp()) as WorkspaceEdit.Ok).root!!
        w.updateSettings(r.id, RootSettings(excludes = listOf("*.bak"), useDefaultIgnores = false))
        assertEquals(listOf("*.bak"), w.root(r.id)!!.settings.excludes); assertFalse(w.root(r.id)!!.settings.useDefaultIgnores)
    }
}

class WorkspacePersistenceTest {
    private val snap = WorkspaceSnapshot(
        roots = listOf(
            WorkspaceRoot("r1", "Alpha", "/data/a", settings = RootSettings(listOf("*.bak", "tmp/"), useDefaultIgnores = false, useGitignore = true)),
            WorkspaceRoot("r2", "Beta", "/data/b"),
        ),
        ui = mapOf("r1" to RootUiState(listOf("src", "src/main"), listOf("src/A.kt"), listOf(FrecencyEntry("src/A.kt", 2.5, 1234L)))),
    )

    @Test fun `codec round trips`() {
        assertEquals(snap, WorkspaceCodec.decode(WorkspaceCodec.encode(snap)))
    }

    @Test fun `codec rejects wrong version garbage and duplicate ids`() {
        for (bad in listOf("", "not json", "{}", """{"version":99,"roots":[]}""", """{"version":1}""",
            """{"version":1,"roots":[{"id":"x","name":"a","path":"/p"},{"id":"x","name":"b","path":"/q"}]}""",
            """{"version":1,"roots":[{"id":"x","name":"a","path":"/p","kind":"BOGUS"}]}""")) {
            try { WorkspaceCodec.decode(bad); fail("accepted: $bad") } catch (_: WorkspaceFormatException) {}
        }
    }

    @Test fun `ui state for unknown roots is dropped on decode`() {
        val text = """{"version":1,"roots":[{"id":"a","name":"A","path":"/p"}],"ui":{"ghost":{"expanded":["x"]},"a":{"expanded":["y"]}}}"""
        val d = WorkspaceCodec.decode(text)
        assertEquals(setOf("a"), d.ui.keys)
    }

    @Test fun `store saves loads and starts fresh`() {
        val f = File(tmp(), "state/workspace.json"); val s = WorkspaceFileStore(f)
        assertTrue(s.load() is WorkspaceLoad.Fresh)
        s.save(snap)
        assertEquals(snap, (s.load() as WorkspaceLoad.Loaded).snapshot)
    }

    @Test fun `corrupt file is quarantined not deleted and an empty workspace returned`() {
        val dir = tmp(); val f = File(dir, "workspace.json"); f.writeText("{ truncated")
        val r = WorkspaceFileStore(f).load() as WorkspaceLoad.Recovered
        assertTrue(r.snapshot.roots.isEmpty()); assertNotNull(r.quarantined)
        assertEquals("{ truncated", r.quarantined!!.readText()); assertFalse(f.exists())
        f.writeText("again bad")
        val r2 = WorkspaceFileStore(f).load() as WorkspaceLoad.Recovered
        assertTrue(r2.quarantined!!.name.endsWith("corrupt-2"))
    }

    @Test fun `newer version file is quarantined not misread`() {
        val f = File(tmp(), "workspace.json"); f.writeText("""{"version":2,"roots":[{"id":"a","name":"A","path":"/p"}]}""")
        val r = WorkspaceFileStore(f).load() as WorkspaceLoad.Recovered
        assertTrue(r.reason.contains("version"))
    }
}

class LocalFileProviderTest {
    private fun uri(p: String) = ResourceUri(ResourceProvider.LOCAL, "local://$p")

    @Test fun `write read stat with conflict`() = runTest {
        val root = tmp(); val p = LocalFileProvider(root)
        val w = p.write(uri("d/a.txt"), "one".toByteArray(), null) as ResourceWrite.Success
        assertEquals(w.newRevision, p.stat(uri("d/a.txt")).revision)
        assertEquals("one", String(p.read(uri("d/a.txt")).bytes))
        val c = p.write(uri("d/a.txt"), "two".toByteArray(), "stale")
        assertTrue(c is ResourceWrite.Conflict)
        assertEquals("one", File(root, "d/a.txt").readText())
        assertTrue(p.write(uri("d/a.txt"), "two".toByteArray(), w.newRevision) is ResourceWrite.Success)
    }

    @Test fun `escape attempts through the provider throw`() = runTest {
        val root = tmp(); val out = tmp("out"); File(out, "s").writeText("secret")
        symlink(File(root, "l"), out)
        val p = LocalFileProvider(root)
        for (path in listOf("../x", "/etc/passwd", "l/s", "a/../../x")) {
            try { p.read(uri(path)); fail("read $path") } catch (_: IllegalArgumentException) {}
            try { p.write(uri(path), byteArrayOf(1), null); fail("write $path") } catch (_: IllegalArgumentException) {}
        }
        assertEquals("secret", File(out, "s").readText()); assertFalse(File(out, "x").exists())
    }

    @Test fun `list is natural sorted directories first and paged with a cursor`() = runTest {
        val root = tmp(); File(root, "zdir").mkdirs()
        for (i in listOf(10, 2, 1)) File(root, "f$i.txt").writeText("x")
        for (i in 0 until 250) File(root, "g$i").writeText("")
        val p = LocalFileProvider(root)
        val first = p.list(uri("."), null)
        assertEquals(200, first.items.size); assertEquals("zdir", first.items[0].uri.raw.removePrefix("local://"))
        assertEquals(listOf("f1.txt", "f2.txt", "f10.txt"), first.items.drop(1).take(3).map { it.uri.raw.removePrefix("local://") })
        assertNull(first.items[1].revision)   // listing never hashes
        val second = p.list(uri("."), first.nextCursor)
        assertEquals(254 - 200, second.items.size); assertNull(second.nextCursor)
    }

    @Test fun `listDir skips entries that vanish and flags escaping symlinks`() {
        val root = tmp(); val out = tmp("out")
        File(root, "a").writeText("x"); symlink(File(root, "esc"), out); File(root, "d").mkdirs(); symlink(File(root, "inner"), File(root, "d"))
        val r = LocalFileProvider(root).listDir("", 100) as DirRead.Ok
        val byName = r.entries.associateBy { it.name }
        assertTrue(byName.getValue("esc").linkEscapes); assertFalse(byName.getValue("esc").isDir)
        assertTrue(byName.getValue("inner").isDir); assertTrue(byName.getValue("inner").isSymlink)
        assertTrue(LocalFileProvider(root).listDir("a", 10) is DirRead.NotDirectory)
        assertTrue(LocalFileProvider(root).listDir("zzz", 10) is DirRead.Missing)
        assertTrue(LocalFileProvider(root).listDir("esc", 10) is DirRead.Refused)
    }

    @Test fun `scan cap truncates and says so`() {
        val root = tmp(); for (i in 0 until 50) File(root, "f$i").writeText("")
        val r = LocalFileProvider(root).listDir("", 10) as DirRead.Ok
        assertEquals(10, r.entries.size); assertTrue(r.truncated)
    }
}
