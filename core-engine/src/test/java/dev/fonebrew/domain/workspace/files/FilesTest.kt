package dev.fonebrew.domain.workspace.files

import dev.fonebrew.contracts.workspace.DocumentBufferState
import dev.fonebrew.contracts.workspace.LineEndings
import dev.fonebrew.domain.workspace.roots.Workspace
import dev.fonebrew.domain.workspace.roots.WorkspaceEdit
import dev.fonebrew.domain.workspace.roots.WorkspacePath
import dev.fonebrew.domain.workspace.roots.symlink
import dev.fonebrew.domain.workspace.roots.tmp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

private class Env(clockStart: Long = 1_000L, maxItems: Int = 50, maxBytes: Long = 512L * 1024 * 1024, maxAge: Long = 7L * 24 * 3600 * 1000) {
    var now = clockStart
    val dirA = tmp("a"); val dirB = tmp("b"); val trashDir = tmp("trash")
    var n = 0
    val ws = Workspace { "r${++n}" }
    val a = (ws.addRoot("A", dirA) as WorkspaceEdit.Ok).root!!
    val b = (ws.addRoot("B", dirB) as WorkspaceEdit.Ok).root!!
    val trash = Trash(trashDir, { now }, maxItems, maxAge, maxBytes)
    val ops = FileOperations(ws, trash)
    fun pa(rel: String) = WorkspacePath.of(a.id, rel)
    fun pb(rel: String) = WorkspacePath.of(b.id, rel)
}

class FileOperationsTest {
    @Test fun `create file and directory respecting overwrite`() {
        val e = Env()
        assertTrue(e.ops.createFile(e.pa("d/x.txt"), "hi".toByteArray()) is FileOpResult.Done)
        assertEquals("hi", File(e.dirA, "d/x.txt").readText())
        val again = e.ops.createFile(e.pa("d/x.txt"), "no".toByteArray()) as FileOpResult.Refused
        assertEquals(FileOpRefusal.ALREADY_EXISTS, again.reason); assertEquals("hi", File(e.dirA, "d/x.txt").readText())
        assertTrue(e.ops.createFile(e.pa("d/x.txt"), "yes".toByteArray(), overwrite = true) is FileOpResult.Done)
        assertEquals("yes", File(e.dirA, "d/x.txt").readText())
        assertEquals(FileOpRefusal.WRONG_TYPE, (e.ops.createFile(e.pa("d"), overwrite = true) as FileOpResult.Refused).reason)
        assertTrue(e.ops.createDirectory(e.pa("n/m")) is FileOpResult.Done)
        assertEquals(FileOpRefusal.ALREADY_EXISTS, (e.ops.createDirectory(e.pa("n/m")) as FileOpResult.Refused).reason)
        assertEquals(FileOpRefusal.IS_ROOT, (e.ops.createDirectory(WorkspacePath.root(e.a.id)) as FileOpResult.Refused).reason)
    }

    @Test fun `rename validates the name and refuses to clobber`() {
        val e = Env(); File(e.dirA, "a.txt").writeText("A"); File(e.dirA, "b.txt").writeText("B")
        for (bad in listOf("", ".", "..", "x/y", "a\u0000")) {
            assertEquals(bad, FileOpRefusal.INVALID_NAME, (e.ops.rename(e.pa("a.txt"), bad) as FileOpResult.Refused).reason)
        }
        assertEquals(FileOpRefusal.ALREADY_EXISTS, (e.ops.rename(e.pa("a.txt"), "b.txt") as FileOpResult.Refused).reason)
        assertEquals("B", File(e.dirA, "b.txt").readText())
        assertTrue(e.ops.rename(e.pa("a.txt"), "c.txt") is FileOpResult.Done)
        assertTrue(File(e.dirA, "c.txt").exists() && !File(e.dirA, "a.txt").exists())
        assertTrue(e.ops.rename(e.pa("c.txt"), "b.txt", overwrite = true) is FileOpResult.Done)
        assertEquals("A", File(e.dirA, "b.txt").readText())
        assertEquals(FileOpRefusal.NOT_FOUND, (e.ops.rename(e.pa("ghost"), "q") as FileOpResult.Refused).reason)
        assertEquals(FileOpRefusal.IS_ROOT, (e.ops.rename(WorkspacePath.root(e.a.id), "q") as FileOpResult.Refused).reason)
    }

    @Test fun `a rename or move across roots is refused with a reason`() {
        val e = Env(); File(e.dirA, "a.txt").writeText("A")
        val r = e.ops.move(e.pa("a.txt"), e.pb("a.txt")) as FileOpResult.Refused
        assertEquals(FileOpRefusal.CROSS_ROOT, r.reason); assertTrue(r.message.contains("cross"))
        assertTrue(File(e.dirA, "a.txt").exists()); assertFalse(File(e.dirB, "a.txt").exists())
    }

    @Test fun `move a directory and refuse moving it into itself or over a directory`() {
        val e = Env(); File(e.dirA, "src/sub").mkdirs(); File(e.dirA, "src/sub/f").writeText("f"); File(e.dirA, "dst").mkdirs()
        assertEquals(FileOpRefusal.INTO_SELF, (e.ops.move(e.pa("src"), e.pa("src/sub/src")) as FileOpResult.Refused).reason)
        assertEquals(FileOpRefusal.ALREADY_EXISTS, (e.ops.move(e.pa("src"), e.pa("dst")) as FileOpResult.Refused).reason)
        assertTrue(e.ops.move(e.pa("src"), e.pa("dst/moved")) is FileOpResult.Done)
        assertEquals("f", File(e.dirA, "dst/moved/sub/f").readText()); assertFalse(File(e.dirA, "src").exists())
    }

    @Test fun `copy file dir and across roots, skipping symlinks and refusing into self`() {
        val e = Env(); val outside = tmp("out")
        File(e.dirA, "d/inner").mkdirs(); File(e.dirA, "d/inner/f").writeText("f"); symlink(File(e.dirA, "d/link"), outside)
        val r = e.ops.copy(e.pa("d"), e.pb("copy")) as FileOpResult.Done
        assertTrue(r.note!!.contains("symlink")); assertEquals("f", File(e.dirB, "copy/inner/f").readText()); assertFalse(File(e.dirB, "copy/link").exists())
        assertEquals(FileOpRefusal.INTO_SELF, (e.ops.copy(e.pa("d"), e.pa("d/inner/again")) as FileOpResult.Refused).reason)
        File(e.dirA, "one").writeText("1"); File(e.dirA, "two").writeText("2")
        assertEquals(FileOpRefusal.ALREADY_EXISTS, (e.ops.copy(e.pa("one"), e.pa("two")) as FileOpResult.Refused).reason)
        assertTrue(e.ops.copy(e.pa("one"), e.pa("two"), overwrite = true) is FileOpResult.Done); assertEquals("1", File(e.dirA, "two").readText())
    }

    @Test fun `delete goes to trash and undo restores it`() {
        val e = Env(); File(e.dirA, "d").mkdirs(); File(e.dirA, "d/f.txt").writeText("keep me")
        val r = e.ops.delete(e.pa("d")) as FileOpResult.Done
        assertNotNull(r.undo); assertFalse(File(e.dirA, "d").exists())
        assertEquals(1, e.trash.list().size)
        assertTrue(e.ops.undoDelete(r.undo!!) is FileOpResult.Done)
        assertEquals("keep me", File(e.dirA, "d/f.txt").readText()); assertEquals(0, e.trash.list().size)
        assertEquals(FileOpRefusal.NOT_FOUND, (e.ops.undoDelete(r.undo!!) as FileOpResult.Refused).reason)
    }

    @Test fun `undo never clobbers something that took the place`() {
        val e = Env(); File(e.dirA, "f").writeText("old")
        val tok = (e.ops.delete(e.pa("f")) as FileOpResult.Done).undo!!
        File(e.dirA, "f").writeText("new")
        assertEquals(FileOpRefusal.ALREADY_EXISTS, (e.ops.undoDelete(tok) as FileOpResult.Refused).reason)
        assertEquals("new", File(e.dirA, "f").readText()); assertEquals(1, e.trash.list().size)
    }

    @Test fun `undo recreates a removed parent directory and is refused when the root is gone`() {
        val e = Env(); File(e.dirA, "p/q").mkdirs(); File(e.dirA, "p/q/f").writeText("x")
        val tok = (e.ops.delete(e.pa("p/q/f")) as FileOpResult.Done).undo!!
        File(e.dirA, "p").deleteRecursively()
        assertTrue(e.ops.undoDelete(tok) is FileOpResult.Done); assertEquals("x", File(e.dirA, "p/q/f").readText())
        val tok2 = (e.ops.delete(e.pa("p/q/f")) as FileOpResult.Done).undo!!
        e.ws.removeRoot(e.a.id)
        assertTrue(e.ops.undoDelete(tok2) is FileOpResult.Refused); assertEquals(1, e.trash.list().size)
    }

    @Test fun `permanent delete is explicit and a link is removed without touching its target`() {
        val e = Env(); val outside = tmp("out"); File(outside, "precious").writeText("p")
        symlink(File(e.dirA, "lnk"), outside); File(e.dirA, "gone").writeText("g")
        val l = e.ops.delete(e.pa("lnk")) as FileOpResult.Done
        assertNotNull(l.undo); assertFalse(Files.isSymbolicLink(File(e.dirA, "lnk").toPath())); assertEquals("p", File(outside, "precious").readText())
        symlink(File(e.dirA, "lnk2"), outside)
        assertTrue(e.ops.delete(e.pa("lnk2"), permanent = true) is FileOpResult.Done); assertEquals("p", File(outside, "precious").readText())
        val p = e.ops.delete(e.pa("gone"), permanent = true) as FileOpResult.Done
        assertNull(p.undo); assertEquals(1, e.trash.list().size)
    }

    @Test fun `delete refuses roots and unknown paths`() {
        val e = Env()
        assertEquals(FileOpRefusal.IS_ROOT, (e.ops.delete(WorkspacePath.root(e.a.id)) as FileOpResult.Refused).reason)
        assertEquals(FileOpRefusal.NOT_FOUND, (e.ops.delete(e.pa("nothing")) as FileOpResult.Refused).reason)
        assertEquals(FileOpRefusal.UNKNOWN_ROOT, (e.ops.delete(WorkspacePath.of("ghost", "x")) as FileOpResult.Refused).reason)
    }

    @Test fun `every operation refuses to act through a symlink that leaves the root`() {
        val e = Env(); val outside = tmp("out"); File(outside, "f").writeText("o"); File(outside, "dir").mkdirs()
        symlink(File(e.dirA, "esc"), outside); File(e.dirA, "mine").writeText("m")
        val results = listOf(
            e.ops.createFile(e.pa("esc/new")), e.ops.createDirectory(e.pa("esc/newdir")), e.ops.delete(e.pa("esc/f")),
            e.ops.rename(e.pa("esc/f"), "g"), e.ops.move(e.pa("mine"), e.pa("esc/mine")), e.ops.copy(e.pa("mine"), e.pa("esc/mine")),
            e.ops.copy(e.pa("esc/f"), e.pa("stolen")),
        )
        for (r in results) assertTrue("$r", r is FileOpResult.Refused && r.reason == FileOpRefusal.PATH_ESCAPE)
        assertEquals(setOf("f", "dir"), outside.list()!!.toSet()); assertFalse(File(e.dirA, "stolen").exists()); assertEquals("o", File(outside, "f").readText())
    }

    @Test fun `parent traversal never reaches the operations because paths cannot be built`() {
        val e = Env()
        try { WorkspacePath.of(e.a.id, "../x"); org.junit.Assert.fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun `case only rename works`() {
        val e = Env(); File(e.dirA, "readme.md").writeText("r")
        assertTrue(e.ops.rename(e.pa("readme.md"), "README.md") is FileOpResult.Done)
        assertTrue(File(e.dirA, "README.md").list() == null && e.dirA.list()!!.contains("README.md"))
    }
}

class TrashTest {
    @Test fun `retention by item count evicts oldest`() {
        val e = Env(maxItems = 3)
        val toks = (1..5).map { i -> e.now += 10; File(e.dirA, "f$i").writeText("x$i"); (e.ops.delete(e.pa("f$i")) as FileOpResult.Done).undo!! }
        assertEquals(3, e.trash.list().size)
        assertEquals(FileOpRefusal.NOT_FOUND, (e.ops.undoDelete(toks[0]) as FileOpResult.Refused).reason)
        assertTrue(e.ops.undoDelete(toks[4]) is FileOpResult.Done)
    }

    @Test fun `retention by age`() {
        val e = Env(maxAge = 1000)
        File(e.dirA, "old").writeText("o"); val old = (e.ops.delete(e.pa("old")) as FileOpResult.Done).undo!!
        e.now += 5000; File(e.dirA, "new").writeText("n"); e.ops.delete(e.pa("new"))
        assertEquals(listOf("new"), e.trash.list().map { it.rel })
        assertTrue(e.ops.undoDelete(old) is FileOpResult.Refused)
    }

    @Test fun `retention by total bytes evicts oldest and an oversize item is refused not kept`() {
        val e = Env(maxBytes = 100)
        File(e.dirA, "a").writeBytes(ByteArray(60)); e.ops.delete(e.pa("a")); e.now += 10
        File(e.dirA, "b").writeBytes(ByteArray(60)); e.ops.delete(e.pa("b"))
        assertEquals(listOf("b"), e.trash.list().map { it.rel })
        File(e.dirA, "huge").writeBytes(ByteArray(500))
        val r = e.ops.delete(e.pa("huge")) as FileOpResult.Refused
        assertEquals(FileOpRefusal.TOO_LARGE_FOR_TRASH, r.reason); assertTrue(File(e.dirA, "huge").exists())
        assertTrue(e.ops.delete(e.pa("huge"), permanent = true) is FileOpResult.Done)
    }

    @Test fun `a forged token cannot traverse out of the trash`() {
        val e = Env()
        assertEquals(FileOpRefusal.NOT_FOUND, (e.ops.undoDelete(UndoToken("../../etc")) as FileOpResult.Refused).reason)
    }
}

class AtomicAndDocumentsTest {
    private fun setup(): Triple<Workspace, WorkspacePath, File> {
        val dir = tmp(); var n = 0
        val ws = Workspace { "r${++n}" }; val r = (ws.addRoot("R", dir) as WorkspaceEdit.Ok).root!!
        return Triple(ws, WorkspacePath.root(r.id), dir)
    }

    @Test fun `atomic write replaces content keeps mode and leaves no temp file`() {
        val dir = tmp(); val f = File(dir, "script.sh"); f.writeText("old")
        Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rwxr-x---"))
        val w = AtomicFileWriter.write(f, "new".toByteArray())
        assertTrue(w.atomic && w.modePreserved); assertEquals("new", f.readText())
        assertEquals("rwxr-x---", PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath())))
        assertEquals(listOf("script.sh"), dir.list()!!.toList())
    }

    @Test fun `open reports revision line endings and refuses binary and non utf8`() {
        val (ws, root, dir) = setup(); val docs = OpenDocuments(ws)
        File(dir, "crlf.txt").writeBytes("a\r\nb\r\n".toByteArray()); File(dir, "bin").writeBytes(byteArrayOf(1, 0, 2)); File(dir, "latin").writeBytes(byteArrayOf(0x63, 0xE9.toByte(), 0x66))
        val o = docs.open(root.child("crlf.txt").let { (it as dev.fonebrew.domain.workspace.roots.PathParse.Ok).path }) as OpenResult.Opened
        assertEquals(LineEndings.CRLF, o.doc.lineEndings); assertEquals("a\r\nb\r\n", o.text); assertTrue(o.doc.state is DocumentBufferState.OpenClean)
        assertEquals(OpenRefusal.BINARY, (docs.open(WorkspacePath.of(root.rootId, "bin")) as OpenResult.Refused).reason)
        assertEquals(OpenRefusal.NOT_UTF8, (docs.open(WorkspacePath.of(root.rootId, "latin")) as OpenResult.Refused).reason)
        assertEquals(OpenRefusal.NOT_FOUND, (docs.open(WorkspacePath.of(root.rootId, "nope")) as OpenResult.Refused).reason)
        assertEquals(OpenRefusal.ALREADY_OPEN, (docs.open(WorkspacePath.of(root.rootId, "crlf.txt")) as OpenResult.Refused).reason)
    }

    @Test fun `save preserves CRLF mode and BOM and updates the base revision`() {
        val (ws, root, dir) = setup(); val docs = OpenDocuments(ws)
        val f = File(dir, "w.txt"); f.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "x\r\ny\r\n".toByteArray())
        Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-r-----"))
        val p = WorkspacePath.of(root.rootId, "w.txt"); val opened = docs.open(p) as OpenResult.Opened
        docs.markEdited(p); assertTrue(docs.get(p)!!.dirty)
        val s = docs.save(p, "x\nz\n") as SaveResult.Saved
        assertEquals("x\r\nz\r\n", String(f.readBytes()).removePrefix("﻿"))
        assertEquals(0xEF.toByte(), f.readBytes()[0])
        assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath())))
        assertFalse(s.doc.dirty); assertTrue(s.doc.baseRevision != opened.doc.baseRevision)
    }

    @Test fun `mixed endings are written as given and stay mixed`() {
        val (ws, root, dir) = setup(); val docs = OpenDocuments(ws)
        File(dir, "m.txt").writeBytes("a\r\nb\nc\r".toByteArray())
        val p = WorkspacePath.of(root.rootId, "m.txt")
        assertEquals(LineEndings.MIXED, (docs.open(p) as OpenResult.Opened).doc.lineEndings)
        docs.save(p, "a\r\nb\nc\rd\n")
        assertEquals("a\r\nb\nc\rd\n", File(dir, "m.txt").readText()); assertEquals(LineEndings.MIXED, docs.get(p)!!.lineEndings)
    }

    @Test fun `file changed on disk while dirty becomes a typed conflict and save refuses to overwrite`() {
        val (ws, root, dir) = setup(); val docs = OpenDocuments(ws)
        File(dir, "c.txt").writeText("v1"); val p = WorkspacePath.of(root.rootId, "c.txt"); docs.open(p)
        docs.markEdited(p)
        File(dir, "c.txt").writeText("v2 by someone else")
        val ev = docs.checkExternal(p)!!
        assertEquals(ExternalChange.Kind.MODIFIED, ev.kind); assertTrue(ev.whileDirty)
        assertTrue(docs.get(p)!!.state is DocumentBufferState.Conflicted)
        assertNull(docs.checkExternal(p))                       // same disk state is reported once
        val c = docs.save(p, "mine") as SaveResult.Conflict
        assertEquals("v2 by someone else", File(dir, "c.txt").readText()); assertNotNull(c.diskRevision)
        assertTrue(docs.save(p, "mine", overwrite = true) is SaveResult.Saved)
        assertEquals("mine", File(dir, "c.txt").readText()); assertFalse(docs.get(p)!!.dirty)
    }

    @Test fun `file changed on disk while clean is flagged stale not conflicted and deletion is reported`() {
        val (ws, root, dir) = setup(); val docs = OpenDocuments(ws)
        File(dir, "s.txt").writeText("v1"); val p = WorkspacePath.of(root.rootId, "s.txt"); docs.open(p)
        assertNull(docs.checkExternal(p))
        File(dir, "s.txt").writeText("v2")
        val ev = docs.checkExternal(p)!!
        assertFalse(ev.whileDirty); assertTrue(docs.get(p)!!.staleOnDisk); assertTrue(docs.get(p)!!.state is DocumentBufferState.OpenClean)
        File(dir, "s.txt").delete()
        val gone = docs.checkExternal(p)!!; assertEquals(ExternalChange.Kind.DELETED, gone.kind); assertTrue(docs.get(p)!!.missingOnDisk)
        assertNull(docs.checkExternal(p))
    }

    @Test fun `identical rewrite is not an external change`() {
        val (ws, root, dir) = setup(); val docs = OpenDocuments(ws)
        File(dir, "t.txt").writeText("same"); val p = WorkspacePath.of(root.rootId, "t.txt"); docs.open(p)
        File(dir, "t.txt").writeText("same"); assertNull(docs.checkExternal(p))
    }

    @Test fun `dirty documents cannot be closed without discard`() {
        val (ws, root, dir) = setup(); val docs = OpenDocuments(ws)
        File(dir, "d.txt").writeText("x"); val p = WorkspacePath.of(root.rootId, "d.txt"); docs.open(p); docs.markEdited(p)
        assertFalse(docs.close(p)); assertNotNull(docs.get(p))
        assertTrue(docs.close(p, discard = true)); assertNull(docs.get(p))
    }

    @Test fun `save failure keeps edits open as SaveFailed and a retry works`() {
        val (ws, root, dir) = setup(); val docs = OpenDocuments(ws)
        File(dir, "f.txt").writeText("x"); val p = WorkspacePath.of(root.rootId, "f.txt"); docs.open(p)
        val locked = File(dir, "f.txt")
        // make the directory read-only so the temp file cannot be created (skipped when running as root)
        dir.setWritable(false)
        val r = docs.save(p, "y")
        dir.setWritable(true)
        if (r is SaveResult.Failed) {
            assertTrue(docs.get(p)!!.state is DocumentBufferState.SaveFailed); assertEquals("x", locked.readText())
            assertTrue(docs.save(p, "y") is SaveResult.Saved); assertEquals("y", locked.readText())
        } else assertTrue(r is SaveResult.Saved) // root ignores permission bits: still must be coherent
    }

    @Test fun `reload and rekey`() {
        val (ws, root, dir) = setup(); val docs = OpenDocuments(ws)
        File(dir, "r.txt").writeText("1"); val p = WorkspacePath.of(root.rootId, "r.txt"); docs.open(p); docs.markEdited(p)
        File(dir, "r.txt").writeText("2")
        val o = docs.reload(p) as OpenResult.Opened; assertEquals("2", o.text); assertFalse(o.doc.dirty)
        val q = WorkspacePath.of(root.rootId, "q.txt"); docs.rekey(p, q); assertNotNull(docs.get(q)); assertNull(docs.get(p))
    }

    @Test fun `save on a path that is not open is typed`() {
        val (ws, root, _) = setup()
        assertTrue(OpenDocuments(ws).save(WorkspacePath.of(root.rootId, "x"), "y") is SaveResult.NotOpen)
    }
}
