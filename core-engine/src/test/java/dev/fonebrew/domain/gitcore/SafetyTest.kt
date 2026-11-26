package dev.fonebrew.domain.gitcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * The attacks: each is written the way an attacker would, run against the engine, and checked for
 * (a) a typed refusal and (b) no side effect outside the work tree / inside .git.
 */
class SafetyTest {
    private fun expectAny(vararg kinds: GitErrorKind, block: () -> Unit): GitException {
        try { block(); fail("expected one of ${kinds.toList()}") } catch (e: GitException) {
            assertTrue("got ${e.kind}: ${e.message}", e.kind in kinds); return e
        }
        throw IllegalStateException()
    }

    /** A commit whose root tree has exactly [entries] (names as given - hostile ones included). */
    private fun hostileCommit(t: TestRepo, vararg entries: Pair<String, Int>, content: String = "PWNED"): ObjectId {
        val blob = t.repo.db.write(ObjectType.BLOB, content.toByteArray())
        val tree = t.repo.db.write(Tree(entries.map { (n, m) -> TreeEntry(m, n, blob) }))
        val sig = t.repo.signature(Identity("E", "e@e"))
        val c = t.repo.db.write(Commit(tree, emptyList(), sig, sig, "evil\n"))
        t.repo.refs.update("refs/heads/evil", c)
        return c
    }

    /** Snapshot of everything under [dir] (relative path -> contents or link target). */
    private fun snapshot(dir: File): Map<String, String> {
        val out = sortedMapOf<String, String>()
        dir.walkTopDown().forEach { f ->
            val rel = f.relativeTo(dir).path
            if (rel.isEmpty()) return@forEach
            out[rel] = when {
                Files.isSymbolicLink(f.toPath()) -> "-> " + Files.readSymbolicLink(f.toPath())
                f.isFile -> f.readText()
                else -> "<dir>"
            }
        }
        return out
    }

    @Test fun `PathSafety rejects every traversal and git-dir variant`() {
        val bad = listOf(
            "", "..", ".", "../x", "a/../b", "a/..", "./a", "a/./b", "/etc/passwd", "a//b", "a/", "back\\slash", "a\\..\\b",
            "C:/win", "c:evil", "nul\u0000byte", ".git", ".git/config", "a/.git", "a/.git/hooks/pre-commit", ".GIT", ".Git/x", "a/.gIt",
            "git~1", "GIT~1/config", ".git.", ".git ", ".git. .", ".git...", "x/.git ", ".g\u200cit", ".\u200dgit/x", "\ufeff.git", ".git\u200e",
        )
        for (p in bad) assertNotNull("'${p.replace("\u0000", "\\0")}' must be unsafe", PathSafety.violation(p))
        val good = listOf("a", "a/b/c.txt", ".gitignore", ".gitattributes", "dir/.gitkeep", "git", "a.git", ".github/workflows/ci.yml", "gitx/y", "é/世界", "a b/c d", "..a", "a..", "...")
        for (p in good) assertNull("'$p' must be allowed", PathSafety.violation(p))
        assertNotNull(PathSafety.violation("a/".repeat(3000)))
        assertNull(PathSafety.violation("x".repeat(100)))
    }

    @Test fun `hostile tree entries are refused on checkout and nothing is written`() {
        val bad = listOf("..", ".", ".git", ".GIT", ".Git", "git~1", ".git ", ".git.", ".g\u200cit", "a\\b", "x/y", "")
        for (name in bad) {
            val t = TestRepo()
            val outer = t.dir.parentFile
            try {
                hostileCommit(t, name to FileMode.REGULAR)
            } catch (e: GitException) { continue } // an empty name cannot even be built: also fine
            val before = snapshot(t.dir)
            val e = expectAny(GitErrorKind.UNSAFE_PATH, GitErrorKind.MALFORMED) { t.repo.checkout("evil") }
            assertEquals("worktree/.git unchanged after refused checkout of '$name'", before, snapshot(t.dir))
            assertFalse(File(outer, "PWNED").exists())
            assertNull(t.repo.refs.read("HEAD").let { if (it is RefValue.Direct) it else null })
            assertNotNull(e.message)
        }
    }

    @Test fun `nested hostile path inside a subtree is refused`() {
        val t = TestRepo()
        val db = t.repo.db
        val blob = db.write(ObjectType.BLOB, "x".toByteArray())
        val hooks = db.write(Tree(listOf(TreeEntry(FileMode.EXECUTABLE, "pre-commit", blob))))
        val dotgit = db.write(Tree(listOf(TreeEntry(FileMode.TREE, "hooks", hooks))))
        val sub = db.write(Tree(listOf(TreeEntry(FileMode.TREE, ".git", dotgit))))
        val root = db.write(Tree(listOf(TreeEntry(FileMode.TREE, "sub", sub), TreeEntry(FileMode.TREE, "up", db.write(Tree(listOf(TreeEntry(FileMode.REGULAR, "..", blob))))))))
        val sig = t.repo.signature(Identity("E", "e@e"))
        t.repo.refs.update("refs/heads/evil", db.write(Commit(root, emptyList(), sig, sig, "e\n")))
        val before = snapshot(t.dir)
        expectAny(GitErrorKind.UNSAFE_PATH) { t.repo.checkout("evil") }
        assertEquals(before, snapshot(t.dir))
        // fsck-style flatten of the same tree is refused too
        expectAny(GitErrorKind.UNSAFE_PATH) { db.flattenTree(root) }
    }

    @Test fun `case insensitive git dir is refused wherever it appears`() {
        for (n in listOf(".GiT", ".gIT", "dir/.GIT/config")) {
            val t = TestRepo()
            val parts = n.split('/')
            val db = t.repo.db
            var id = db.write(ObjectType.BLOB, "x".toByteArray()); var mode = FileMode.REGULAR
            for (p in parts.reversed()) { val tr = db.write(Tree(listOf(TreeEntry(mode, p, id)))); id = tr; mode = FileMode.TREE }
            val sig = t.repo.signature(Identity("E", "e@e"))
            t.repo.refs.update("refs/heads/evil", db.write(Commit(id, emptyList(), sig, sig, "e\n")))
            expectAny(GitErrorKind.UNSAFE_PATH) { t.repo.checkout("evil") }
        }
    }

    @Test fun `checkout never writes through a symlinked directory that points outside`() {
        val t = TestRepo()
        val outside = GitTestSupport.tempDir("outside")
        t.write("sub/file", "v1"); t.commitAll("c1")
        t.repo.checkoutNewBranch("next")
        t.write("sub/file", "v2"); t.write("sub/new", "n"); t.commitAll("c2")
        t.repo.checkout("main")
        // attacker/owner replaces the directory with a symlink out of the tree
        File(t.dir, "sub").deleteRecursively()
        Files.createSymbolicLink(File(t.dir, "sub").toPath(), outside.toPath())
        File(outside, "file").writeText("OUTSIDE ORIGINAL")
        expectAny(GitErrorKind.UNSAFE_PATH, GitErrorKind.DIRTY_TREE) { t.repo.checkout("next") }
        // force may replace the link itself with a real directory, but must never write through it
        try { t.repo.checkout("next", force = true) } catch (e: GitException) { assertTrue(e.kind == GitErrorKind.UNSAFE_PATH || e.kind == GitErrorKind.DIRTY_TREE) }
        assertEquals("OUTSIDE ORIGINAL", File(outside, "file").readText())
        assertFalse(File(outside, "new").exists())
        // (force may have replaced the link by a real directory) put the hostile symlink back
        File(t.dir, "sub").let { if (Files.isSymbolicLink(it.toPath())) it.delete() else it.deleteRecursively() }
        Files.createSymbolicLink(File(t.dir, "sub").toPath(), outside.toPath())
        // a forced checkout may replace the symlink with a real directory (safe); put the link back
        File(t.dir, "sub").let { f -> if (java.nio.file.Files.isSymbolicLink(f.toPath())) f.delete() else f.deleteRecursively() }
        Files.createSymbolicLink(File(t.dir, "sub").toPath(), outside.toPath())
        // restore / add / remove / move refuse to go through it as well
        expectAny(GitErrorKind.UNSAFE_PATH, GitErrorKind.DIRTY_TREE, GitErrorKind.NOT_FOUND) { t.repo.restoreWorktree(listOf("sub/file"), discardLocalChanges = true) }
        assertEquals("OUTSIDE ORIGINAL", File(outside, "file").readText())
        expectAny(GitErrorKind.UNSAFE_PATH) { t.repo.add(listOf("sub/file")) }
        expectAny(GitErrorKind.UNSAFE_PATH) { t.repo.move("sub/file", "sub/other") }
        assertFalse(File(outside, "other").exists())
    }

    @Test fun `a symlink leaf is replaced not followed`() {
        val t = TestRepo()
        val outsideFile = File(GitTestSupport.tempDir("outside"), "victim.txt").also { it.writeText("DO NOT TOUCH") }
        t.write("target.txt", "from repo"); t.commitAll("c1")
        t.repo.checkoutNewBranch("n"); t.write("target.txt", "from repo v2"); t.commitAll("c2"); t.repo.checkout("main")
        // work tree has a symlink where the file should be (clean w.r.t. content would be a lie: it is a type change)
        t.file("target.txt").delete()
        Files.createSymbolicLink(t.file("target.txt").toPath(), outsideFile.toPath())
        expectAny(GitErrorKind.DIRTY_TREE) { t.repo.checkout("n") }
        assertEquals("DO NOT TOUCH", outsideFile.readText())
        t.repo.checkout("n", force = true)
        assertFalse(Files.isSymbolicLink(t.file("target.txt").toPath()))
        assertEquals("from repo v2", t.read("target.txt"))
        assertEquals("DO NOT TOUCH", outsideFile.readText())
    }

    @Test fun `a symlink entry cannot be followed by later entries in the same checkout`() {
        // tree: "d" is a symlink to an outside dir. A second commit wants "d/x" as a real file path.
        val t = TestRepo()
        val outside = GitTestSupport.tempDir("outside")
        val db = t.repo.db
        val link = db.write(ObjectType.BLOB, outside.absolutePath.toByteArray())
        val blob = db.write(ObjectType.BLOB, "pwn".toByteArray())
        val sig = t.repo.signature(Identity("E", "e@e"))
        val withLink = db.write(Commit(db.write(Tree(listOf(TreeEntry(FileMode.SYMLINK, "d", link)))), emptyList(), sig, sig, "link\n"))
        t.repo.refs.update("refs/heads/link", withLink)
        t.repo.checkout("link")
        assertTrue(Files.isSymbolicLink(t.file("d").toPath()))
        val dir = db.write(Tree(listOf(TreeEntry(FileMode.REGULAR, "x", blob))))
        val withFile = db.write(Commit(db.write(Tree(listOf(TreeEntry(FileMode.TREE, "d", dir)))), listOf(withLink), sig, sig, "file\n"))
        t.repo.refs.update("refs/heads/file", withFile)
        expectAny(GitErrorKind.UNSAFE_PATH, GitErrorKind.DIRTY_TREE) { t.repo.checkout("file") }
        assertFalse(File(outside, "x").exists())
        try { t.repo.checkout("file", force = true) } catch (e: GitException) { assertTrue(e.kind == GitErrorKind.UNSAFE_PATH || e.kind == GitErrorKind.DIRTY_TREE) }
        assertFalse("force must not write through the link either", File(outside, "x").exists())
    }

    @Test fun `add and status do not follow symlinked directories`() {
        val t = TestRepo()
        val outside = GitTestSupport.tempDir("outside")
        File(outside, "secret.txt").writeText("s3cret")
        Files.createSymbolicLink(t.file("lnk").toPath(), outside.toPath())
        t.write("real.txt", "r")
        val st = t.repo.status().porcelain()
        assertEquals(listOf("?? lnk", "?? real.txt"), st)
        val r = t.repo.add(listOf("."))
        assertEquals(listOf("lnk", "real.txt"), r.staged.sorted())
        val flat = t.repo.readIndex().stage0()
        assertEquals(FileMode.SYMLINK, flat["lnk"]!!.mode)
        assertNull(flat["lnk/secret.txt"])
        expectAny(GitErrorKind.UNSAFE_PATH) { t.repo.add(listOf("lnk/secret.txt")) }
    }

    @Test fun `paths with traversal are refused by the porcelain`() {
        val t = TestRepo()
        t.write("a", "1"); t.commitAll("c")
        for (p in listOf("../outside", "a/../../x", "/etc/passwd", ".git/config", ".git", "x\\y")) {
            expectAny(GitErrorKind.UNSAFE_PATH) { t.repo.add(listOf(p)) }
            expectAny(GitErrorKind.UNSAFE_PATH) { t.repo.remove(listOf(p)) }
            expectAny(GitErrorKind.UNSAFE_PATH) { t.repo.move("a", p) }
            expectAny(GitErrorKind.UNSAFE_PATH) { t.repo.restoreWorktree(listOf(p)) }
        }
        assertEquals("1", t.read("a"))
        assertTrue(File(t.dir, ".git/config").readText().contains("[core]"))
    }

    @Test fun `tree depth entry count and index entry caps hold`() {
        val t = TestRepo(limits = GitLimits(maxTreeDepth = 8, maxTreeEntries = 50, maxIndexEntries = 100))
        val db = t.repo.db
        val blob = db.write(ObjectType.BLOB, "x".toByteArray())
        var id = db.write(Tree(listOf(TreeEntry(FileMode.REGULAR, "leaf", blob))))
        repeat(20) { id = db.write(Tree(listOf(TreeEntry(FileMode.TREE, "d", id)))) }
        expectAny(GitErrorKind.LIMIT_EXCEEDED) { db.flattenTree(id) }
        val wide = db.write(Tree((0 until 60).map { TreeEntry(FileMode.REGULAR, "f$it", blob) }))
        expectAny(GitErrorKind.LIMIT_EXCEEDED) { db.readTree(wide) }
        // a wide tree above the index cap even though per-tree cap passes
        val t2 = TestRepo(limits = GitLimits(maxIndexEntries = 10))
        val b2 = t2.repo.db.write(ObjectType.BLOB, "x".toByteArray())
        val subs = (0 until 3).map { s -> TreeEntry(FileMode.TREE, "s$s", t2.repo.db.write(Tree((0 until 6).map { TreeEntry(FileMode.REGULAR, "f$it", b2) }))) }
        expectAny(GitErrorKind.LIMIT_EXCEEDED) { t2.repo.db.flattenTree(t2.repo.db.write(Tree(subs))) }
    }

    @Test fun `non utf8 and duplicate tree names are typed`() {
        val t = TestRepo(); val db = t.repo.db
        val blob = db.write(ObjectType.BLOB, "x".toByteArray())
        val raw = "100644 ".toByteArray() + byteArrayOf(0x66, 0xE9.toByte()) + byteArrayOf(0) + blob.toByteArray()
        val id = db.write(ObjectType.TREE, raw)
        expectAny(GitErrorKind.UNSUPPORTED) { db.flattenTree(id) }
        val dup = "100644 a\u0000".toByteArray() + blob.toByteArray() + "100644 a\u0000".toByteArray() + blob.toByteArray()
        expectAny(GitErrorKind.MALFORMED) { db.flattenTree(db.write(ObjectType.TREE, dup)) }
    }

    @Test fun `hostile pack sizes never allocate the claimed size`() {
        val t = TestRepo(limits = GitLimits(maxInMemoryObject = 1 shl 20))
        // whole blob whose header claims 1 TiB but whose zlib stream is 4 bytes of payload
        val head = java.io.ByteArrayOutputStream()
        val claim = 1L shl 40
        var c = (3 shl 4) or (claim and 15).toInt(); var s = claim ushr 4
        while (s != 0L) { head.write(c or 0x80); c = (s and 0x7f).toInt(); s = s ushr 7 }
        head.write(c)
        val raw = "PACK".toByteArray() + byteArrayOf(0, 0, 0, 2, 0, 0, 0, 1) + head.toByteArray() + Zlib.deflate(byteArrayOf(1, 2, 3, 4))
        val sealed = raw + java.security.MessageDigest.getInstance("SHA-1").digest(raw)
        expectAny(GitErrorKind.MALFORMED, GitErrorKind.LIMIT_EXCEEDED) { t.repo.db.indexPack(sealed.inputStream()) }
        // zip bomb inside a pack entry that declares a small size
        val bomb = Zlib.deflate(ByteArray(64 * 1024 * 1024))
        val raw2 = "PACK".toByteArray() + byteArrayOf(0, 0, 0, 2, 0, 0, 0, 1) + byteArrayOf(((3 shl 4) or 5).toByte()) + bomb
        val sealed2 = raw2 + java.security.MessageDigest.getInstance("SHA-1").digest(raw2)
        expectAny(GitErrorKind.MALFORMED) { t.repo.db.indexPack(sealed2.inputStream()) }
    }

    @Test fun `pack size ceiling aborts the stream`() {
        val t = TestRepo(limits = GitLimits(maxPackBytes = 1000))
        expectAny(GitErrorKind.LIMIT_EXCEEDED) { t.repo.db.indexPack(ByteArray(5000).inputStream()) }
        // and nothing partial is left in objects/pack
        assertTrue(File(t.gitDir(), "objects/pack").list().orEmpty().isEmpty())
    }

    @Test fun `error messages carry no secrets and sanitise control characters`() {
        val t = TestRepo()
        val e = expectAny(GitErrorKind.UNSAFE_PATH) { t.repo.add(listOf("../a\nINJECT\u001b[31m")) }
        assertFalse(e.message!!.contains('\n')); assertFalse(e.message!!.contains('\u001b'))
    }

    @Test fun `hard reset and checkout never delete untracked work silently`() {
        val t = TestRepo()
        t.write("tracked", "t"); val c1 = t.commitAll("c1")
        t.write("notes.txt", "my untracked notes")
        t.repo.reset(ResetMode.HARD, c1.hex)
        assertEquals("my untracked notes", t.read("notes.txt"))
        // tracked file edited: both hard reset and checkout refuse until told explicitly
        t.write("tracked", "edited")
        expectAny(GitErrorKind.DIRTY_TREE) { t.repo.reset(ResetMode.HARD, c1.hex) }
        assertEquals("edited", t.read("tracked"))
        t.repo.checkoutNewBranch("b") // target == current tree: local edit carries over, nothing is overwritten
        assertEquals("edited", t.read("tracked"))
        assertNotNull(t.repo.currentBranch())
    }
}
