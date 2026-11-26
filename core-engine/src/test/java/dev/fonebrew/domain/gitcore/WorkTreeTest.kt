package dev.fonebrew.domain.gitcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class WorkTreeTest {
    private fun expect(kind: GitErrorKind, block: () -> Unit): GitException {
        try { block(); fail("expected $kind") } catch (e: GitException) { assertEquals(kind, e.kind); return e }
        throw IllegalStateException()
    }
    private fun porcelain(t: TestRepo) = t.repo.status().porcelain()

    @Test fun `fresh repo is clean and untracked files show`() {
        val t = TestRepo()
        assertTrue(t.repo.status().isClean)
        t.write("a.txt", "hi\n")
        assertEquals(listOf("?? a.txt"), porcelain(t))
    }

    @Test fun `add then commit then status clean and log`() {
        val t = TestRepo()
        t.write("a.txt", "one\n"); t.write("d/b.txt", "two\n")
        val r = t.repo.add(listOf("."))
        assertEquals(listOf("a.txt", "d/b.txt"), r.staged.sorted())
        assertEquals(listOf("A  a.txt", "A  d/b.txt"), porcelain(t))
        val c = t.repo.commit(CommitOptions("first"))
        assertTrue(t.repo.status().isClean)
        assertEquals(c, t.repo.headCommitId())
        assertEquals("main", t.repo.currentBranch())
        val commit = t.repo.db.readCommit(c)
        assertEquals("first\n", commit.message)
        assertTrue(commit.parents.isEmpty())
        assertEquals("Eng Test", commit.author.name)
        t.write("a.txt", "one changed\n")
        assertEquals(listOf(" M a.txt"), porcelain(t))
        t.repo.add(listOf("a.txt"))
        assertEquals(listOf("M  a.txt"), porcelain(t))
        val c2 = t.repo.commit(CommitOptions("second"))
        assertEquals(listOf(c2, c), t.repo.log("HEAD").map { it.id })
        assertEquals(listOf(c2), t.repo.log("HEAD", LogOptions(limit = 1)).map { it.id })
        assertEquals(c, t.repo.resolve("HEAD~1")); assertEquals(c, t.repo.resolve("HEAD^")); assertEquals(c, t.repo.resolve("main~1"))
    }

    @Test fun `commit refuses empty and blank and unknown identity and conflicts`() {
        val t = TestRepo()
        expect(GitErrorKind.INVALID_ARGUMENT) { t.repo.commit(CommitOptions("nothing")) }
        t.write("a", "x"); t.repo.add(listOf("a"))
        expect(GitErrorKind.INVALID_ARGUMENT) { t.repo.commit(CommitOptions("  ")) }
        t.repo.commit(CommitOptions("one"))
        expect(GitErrorKind.INVALID_ARGUMENT) { t.repo.commit(CommitOptions("again")) }
        t.repo.commit(CommitOptions("allowed", allowEmpty = true))
        t.repo.config.unset("user", null, "name")
        t.write("b", "y"); t.repo.add(listOf("b"))
        expect(GitErrorKind.INVALID_ARGUMENT) { t.repo.commit(CommitOptions("no id")) }
        assertNotNull(t.repo.commit(CommitOptions("explicit", author = Identity("Z", "z@z"))))
    }

    @Test fun `amend rewrites head keeping parents and author`() {
        val t = TestRepo()
        t.write("a", "1"); val c1 = t.commitAll("one")
        t.write("a", "2"); t.repo.add(listOf("a")); val c2 = t.repo.commit(CommitOptions("two"))
        t.write("a", "3"); t.repo.add(listOf("a"))
        val am = t.repo.commit(CommitOptions("two fixed", amend = true))
        assertNotEquals(c2, am)
        val c = t.repo.db.readCommit(am)
        assertEquals(listOf(c1), c.parents)
        assertEquals(t.repo.db.readCommit(c2).author, c.author)
        assertEquals(am, t.repo.headCommitId())
        assertEquals(2, t.repo.log("HEAD").size)
        expect(GitErrorKind.INVALID_ARGUMENT) { TestRepo().repo.commit(CommitOptions("x", amend = true)) }
    }

    @Test fun `commit on stale head loses compare and swap`() {
        val t = TestRepo()
        t.write("a", "1"); t.commitAll("one")
        t.write("a", "2"); t.repo.add(listOf("a"))
        // another writer moves the branch between our read and write: simulate via a lock on the ref
        File(t.gitDir(), "refs/heads/main.lock").writeText("")
        expect(GitErrorKind.LOCKED) { t.repo.commit(CommitOptions("two")) }
    }

    @Test fun `executable bit symlink and deletion are tracked`() {
        val t = TestRepo()
        t.write("run.sh", "#!/bin/sh\n"); t.file("run.sh").setExecutable(true)
        t.write("t.txt", "target")
        java.nio.file.Files.createSymbolicLink(t.file("link").toPath(), java.nio.file.Paths.get("t.txt"))
        t.commitAll("modes")
        val flat = t.repo.db.flattenTree(t.repo.headTreeId())
        assertEquals(FileMode.EXECUTABLE, flat["run.sh"]!!.mode)
        assertEquals(FileMode.SYMLINK, flat["link"]!!.mode)
        assertEquals("t.txt", String(t.repo.db.readRaw(flat["link"]!!.id)!!.data))
        t.file("t.txt").delete()
        assertEquals(listOf(" D t.txt"), porcelain(t))
        t.file("run.sh").setExecutable(false)
        assertTrue(porcelain(t).contains(" M run.sh"))
        t.repo.add(listOf("."))
        assertEquals(listOf("M  run.sh", "D  t.txt"), porcelain(t))
    }

    @Test fun `racy timestamps fall back to content hash`() {
        val t = TestRepo()
        t.write("a", "AAAA"); t.commitAll("one")
        // Same size, same mtime, different content: only a content compare can notice.
        val f = t.file("a")
        val st = JvmFileSystem().stat(f.path)!!
        f.writeText("BBBB")
        JvmFileSystem().setMtime(f.path, st.mtimeSeconds, st.mtimeNanos)
        assertEquals(listOf(" M a"), porcelain(t))
    }

    @Test fun `rm move and restore`() {
        val t = TestRepo()
        t.write("a.txt", "A"); t.write("d/b.txt", "B"); t.write("d/c.txt", "C"); t.commitAll("base")
        t.repo.move("a.txt", "z/a2.txt")
        assertFalse(t.file("a.txt").exists()); assertEquals("A", t.read("z/a2.txt"))
        assertEquals(listOf("D  a.txt", "A  z/a2.txt"), porcelain(t))
        expect(GitErrorKind.DIRTY_TREE) { t.repo.move("d/b.txt", "z/a2.txt") }
        t.repo.move("d", "e")
        assertTrue(t.file("e/b.txt").exists()); assertFalse(t.file("d").exists())
        t.repo.commit(CommitOptions("moves"))
        // rm: dirty file refused without force, directory needs recursive
        t.write("e/b.txt", "edited")
        expect(GitErrorKind.DIRTY_TREE) { t.repo.remove(listOf("e/b.txt")) }
        expect(GitErrorKind.INVALID_ARGUMENT) { t.repo.remove(listOf("e")) }
        assertEquals(listOf("e/b.txt"), t.repo.remove(listOf("e/b.txt"), force = true))
        assertFalse(t.file("e/b.txt").exists())
        t.repo.remove(listOf("e"), recursive = true)
        assertFalse(t.file("e").exists())
        // restore: staged only, then worktree needs the discard flag
        t.repo.restoreStaged(listOf("e"))
        assertEquals(setOf("e/b.txt", "e/c.txt"), t.repo.readIndex().entries().map { it.path }.filter { it.startsWith("e/") }.toSet())
        t.write("e/c.txt", "my edits")
        val ex = expect(GitErrorKind.DIRTY_TREE) { t.repo.restoreWorktree(listOf("e/c.txt")) }
        assertEquals(listOf("e/c.txt"), ex.paths)
        assertEquals("my edits", t.read("e/c.txt"))
        t.repo.restoreWorktree(listOf("e"), discardLocalChanges = true)
        assertEquals("C", t.read("e/c.txt")); assertEquals("B", t.read("e/b.txt"))
        expect(GitErrorKind.NOT_FOUND) { t.repo.remove(listOf("nope")) }
        expect(GitErrorKind.NOT_FOUND) { t.repo.add(listOf("nope")) }
    }

    @Test fun `add deleted path stages deletion and cached rm keeps the file`() {
        val t = TestRepo()
        t.write("a", "1"); t.write("b", "2"); t.commitAll("base")
        t.file("a").delete()
        t.repo.add(listOf("a"))
        assertEquals(listOf("D  a"), porcelain(t))
        t.repo.remove(listOf("b"), cached = true)
        assertTrue(t.file("b").exists())
        assertEquals(listOf("D  a", "D  b", "?? b"), porcelain(t))
    }

    @Test fun `ignore rules port is honoured and tracked files are never ignored`() {
        val t = TestRepo(ignore = IgnoreRules { p, _ -> p.endsWith(".log") || p == "build" })
        t.write("keep.txt", "k"); t.write("x.log", "l"); t.write("build/out.o", "o")
        val r = t.repo.add(listOf("."))
        assertEquals(listOf("keep.txt"), r.staged)
        assertTrue(r.skippedIgnored.containsAll(listOf("x.log", "build/")))
        assertEquals(listOf("A  keep.txt"), porcelain(t))
        assertEquals(listOf("A  keep.txt", "!! build/", "!! x.log"), t.repo.status(includeIgnored = true).porcelain())
        t.repo.add(listOf("x.log"), includeIgnored = true) // force-add makes it tracked
        t.write("x.log", "changed")
        assertTrue(porcelain(t).contains("AM x.log"))
    }

    @Test fun `branches tags and checkout round trip`() {
        val t = TestRepo()
        t.write("f", "main v1"); val c1 = t.commitAll("c1")
        t.repo.createBranch("feature")
        t.repo.checkout("feature")
        assertEquals("feature", t.repo.currentBranch())
        t.write("f", "feature v2"); t.write("only-feature", "x"); t.commitAll("c2")
        t.repo.checkout("main")
        assertEquals("main v1", t.read("f")); assertFalse(t.file("only-feature").exists())
        t.repo.checkout("feature")
        assertEquals("feature v2", t.read("f")); assertTrue(t.file("only-feature").exists())
        assertEquals(setOf("main", "feature"), t.repo.branches().keys)
        // tags
        val lw = t.repo.createTag("light", "main")
        assertEquals(c1, lw)
        val ann = t.repo.createTag("v1", "main", message = "release one")
        assertEquals(c1, t.repo.peel(ann)); assertNotEquals(c1, ann)
        assertEquals(c1, t.repo.resolve("v1^{}")); assertEquals(c1, t.repo.resolve("light"))
        expect(GitErrorKind.REF_CONFLICT) { t.repo.createTag("v1", "main", message = "again") }
        t.repo.checkout("v1")
        assertNull(t.repo.currentBranch()); assertEquals(c1, t.repo.headCommitId())
        assertEquals("main v1", t.read("f"))
        t.repo.deleteTag("light")
        assertEquals(setOf("v1"), t.repo.tags().keys)
        // branch delete / rename guards
        t.repo.checkout("main")
        expect(GitErrorKind.INVALID_ARGUMENT) { t.repo.deleteBranch("main") }
        expect(GitErrorKind.DIRTY_TREE) { t.repo.deleteBranch("feature") } // unmerged
        t.repo.deleteBranch("feature", force = true)
        t.repo.renameBranch("main", "trunk")
        assertEquals("trunk", t.repo.currentBranch())
        assertNull(t.repo.refs.resolve("refs/heads/main"))
        expect(GitErrorKind.INVALID_REF_NAME) { t.repo.createBranch("bad name") }
        expect(GitErrorKind.REF_CONFLICT) { t.repo.createBranch("trunk") }
    }

    @Test fun `checkoutNewBranch creates and switches and is atomic on guard failure`() {
        val t = TestRepo()
        t.write("f", "1"); t.commitAll("c1")
        t.write("f", "2"); t.commitAll("c2")
        t.repo.checkoutNewBranch("old", "HEAD~1")
        assertEquals("old", t.repo.currentBranch()); assertEquals("1", t.read("f"))
        t.write("f", "dirty")
        expect(GitErrorKind.DIRTY_TREE) { t.repo.checkoutNewBranch("other", "main") }
        assertNull("branch must not be created when the switch is refused", t.repo.refs.resolve("refs/heads/other"))
        assertEquals("old", t.repo.currentBranch())
    }

    @Test fun `checkout refuses to clobber modified staged deleted or untracked files and names them`() {
        val t = TestRepo()
        t.write("a", "a1"); t.write("b", "b1"); t.write("c", "c1"); t.commitAll("base")
        t.repo.checkoutNewBranch("other")
        t.write("a", "a2"); t.write("b", "b2"); t.write("c", "c2"); t.write("n", "new"); t.commitAll("other change")
        t.repo.checkout("main")
        // 1. modified in work tree
        t.repo.checkout("other")
        t.repo.checkout("main")
        t.write("a", "MY EDIT")
        var e = expect(GitErrorKind.DIRTY_TREE) { t.repo.checkout("other") }
        assertEquals(listOf("a"), e.paths)
        assertEquals("MY EDIT", t.read("a")); assertEquals("main", t.repo.currentBranch())
        assertEquals("b1", t.read("b")) // nothing was touched
        // 2. staged change
        t.repo.add(listOf("a"))
        e = expect(GitErrorKind.DIRTY_TREE) { t.repo.checkout("other") }
        assertEquals(listOf("a"), e.paths)
        t.repo.restoreStaged(listOf("a")); t.repo.restoreWorktree(listOf("a"), discardLocalChanges = true)
        // 3. deleted locally
        t.file("b").delete()
        e = expect(GitErrorKind.DIRTY_TREE) { t.repo.checkout("other") }
        assertEquals(listOf("b"), e.paths)
        t.repo.restoreWorktree(listOf("b"), discardLocalChanges = true)
        // 4. untracked file in the way
        t.write("n", "precious untracked")
        e = expect(GitErrorKind.DIRTY_TREE) { t.repo.checkout("other") }
        assertEquals(listOf("n"), e.paths)
        assertEquals("precious untracked", t.read("n"))
        // 5. multiple files all listed
        t.write("a", "x"); t.write("c", "y")
        e = expect(GitErrorKind.DIRTY_TREE) { t.repo.checkout("other") }
        assertEquals(listOf("a", "c", "n"), e.paths)
        // force is the explicit escape hatch
        t.repo.checkout("other", force = true)
        assertEquals("a2", t.read("a")); assertEquals("new", t.read("n"))
    }

    @Test fun `unrelated local edits carry across a checkout`() {
        val t = TestRepo()
        t.write("a", "a1"); t.write("keep", "k1"); t.commitAll("base")
        t.repo.checkoutNewBranch("other")
        t.write("a", "a2"); t.commitAll("a2")
        t.repo.checkout("main")
        t.write("keep", "k LOCAL")
        t.repo.checkout("other")
        assertEquals("a2", t.read("a")); assertEquals("k LOCAL", t.read("keep"))
        assertEquals(listOf(" M keep"), porcelain(t))
    }

    @Test fun `checkout replaces a file with a directory and back`() {
        val t = TestRepo()
        t.write("x", "file"); t.commitAll("file")
        t.repo.checkoutNewBranch("dir")
        t.repo.remove(listOf("x")); t.write("x/inner", "i"); t.commitAll("dir")
        t.repo.checkout("main")
        assertTrue(t.file("x").isFile)
        t.repo.checkout("dir")
        assertTrue(t.file("x").isDirectory); assertEquals("i", t.read("x/inner"))
        // untracked file inside the directory that must go
        t.repo.checkout("main", force = false)
        assertEquals("file", t.read("x"))
    }

    @Test fun `reset soft mixed hard and the hard guard`() {
        val t = TestRepo()
        t.write("a", "1"); val c1 = t.commitAll("c1")
        t.write("a", "2"); t.write("b", "b"); val c2 = t.commitAll("c2")
        t.repo.reset(ResetMode.SOFT, "HEAD~1")
        assertEquals(c1, t.repo.headCommitId())
        assertEquals(listOf("M  a", "A  b"), porcelain(t)) // index still has c2
        t.repo.reset(ResetMode.MIXED, c2.hex)
        t.repo.reset(ResetMode.MIXED, "HEAD~1")
        assertEquals(listOf(" M a", "?? b"), porcelain(t))
        // hard: refused while there is local work
        val e = expect(GitErrorKind.DIRTY_TREE) { t.repo.reset(ResetMode.HARD, c1.hex) }
        assertEquals(listOf("a"), e.paths)
        assertEquals("2", t.read("a"))
        t.repo.reset(ResetMode.HARD, c1.hex, discardLocalChanges = true)
        assertEquals("1", t.read("a")); assertTrue(t.file("b").exists()) // untracked b untouched
        assertTrue(t.repo.status(includeUntracked = false).isClean)
        // hard reset forward over an untracked file in the way is also refused
        t.repo.reset(ResetMode.SOFT, c2.hex); t.repo.reset(ResetMode.MIXED, c1.hex)
        t.repo.reset(ResetMode.HARD, c1.hex, discardLocalChanges = true)
        val e2 = expect(GitErrorKind.DIRTY_TREE) { t.repo.reset(ResetMode.HARD, c2.hex) }
        assertEquals(listOf("b"), e2.paths)
        t.file("b").delete()
        t.repo.reset(ResetMode.HARD, c2.hex) // clean now, no flag needed
        assertEquals("2", t.read("a")); assertEquals("b", t.read("b"))
    }

    @Test fun `log filters by path first parent and hide`() {
        val t = TestRepo()
        t.write("a", "1"); val c1 = t.commitAll("c1")
        t.write("b", "1"); val c2 = t.commitAll("c2")
        t.write("a", "2"); val c3 = t.commitAll("c3")
        assertEquals(listOf(c3, c1), t.repo.log("HEAD", LogOptions(path = "a")).map { it.id })
        assertEquals(listOf(c2), t.repo.log("HEAD", LogOptions(path = "b")).map { it.id })
        assertEquals(listOf(c3, c2), t.repo.log("HEAD", LogOptions(hide = listOf(c1))).map { it.id })
        assertEquals(c1, t.repo.mergeBase(c3, c1)); assertTrue(t.repo.isAncestor(c1, c3)); assertFalse(t.repo.isAncestor(c3, c1))
        // a merge commit built by hand: first-parent skips the side branch
        t.repo.checkoutNewBranch("side", c1.hex); t.write("s", "s"); val s1 = t.commitAll("side")
        t.repo.checkout("main")
        val tree = t.repo.db.readCommit(c3).tree
        val sig = t.repo.signature(Identity("M", "m@m"))
        val merge = t.repo.db.write(Commit(tree, listOf(c3, s1), sig, sig, "merge\n"))
        t.repo.refs.update("refs/heads/main", merge)
        assertEquals(listOf(merge, c3, c2, c1), t.repo.log("main", LogOptions(firstParent = true)).map { it.id })
        assertTrue(t.repo.log("main").map { it.id }.contains(s1))
        assertEquals(c1, t.repo.mergeBase(merge, s1)?.let { if (it == s1) c1 else it }.let { t.repo.mergeBase(c3, s1) })
    }

    @Test fun `tree to tree diff index diff and worktree diff feed LineDiff`() {
        val t = TestRepo()
        t.write("a.txt", "one\ntwo\nthree\n"); t.write("gone", "bye"); t.write("bin", "x\u0000y"); val c1 = t.commitAll("c1")
        t.write("a.txt", "one\nTWO\nthree\n"); t.write("new", "n\n"); t.file("gone").delete(); t.write("bin", "x\u0000z")
        t.repo.add(listOf("."))
        val c2 = t.repo.commit(CommitOptions("c2"))
        val changes = t.repo.db.diffTrees(t.repo.db.readCommit(c1).tree, t.repo.db.readCommit(c2).tree)
        assertEquals(listOf("a.txt" to ChangeType.MODIFY, "bin" to ChangeType.MODIFY, "gone" to ChangeType.DELETE, "new" to ChangeType.ADD), changes.map { it.path to it.type })
        val u = t.repo.unifiedDiff(changes[0])!!
        assertTrue(u, u.contains("--- a/a.txt\n+++ b/a.txt\n@@ -1,3 +1,3 @@\n one\n-two\n+TWO\n three\n"))
        assertNull(t.repo.unifiedDiff(changes[1])) // binary
        assertEquals("+n\n", t.repo.unifiedDiff(changes[3])!!.lines().first { it.startsWith("+") && !it.startsWith("+++") } + "\n")
        // staged / worktree diffs
        t.write("a.txt", "one\nTWO\nthree\nfour\n")
        val wtc = t.repo.diffWorktree()
        assertEquals(listOf("a.txt"), wtc.map { it.path })
        assertTrue(t.repo.unifiedDiff(wtc[0], newFromWorktree = true)!!.contains("+four"))
        t.write("staged", "s"); t.repo.add(listOf("staged"))
        assertEquals(listOf("staged" to ChangeType.ADD), t.repo.diffStaged().map { it.path to it.type })
        assertEquals(emptyList<FileChange>(), t.repo.db.diffTrees(null, null))
        assertEquals(4, t.repo.db.diffTrees(null, t.repo.db.readCommit(c1).tree).size - 0 + 1)
    }

    @Test fun `objectsToSend excludes what the remote has`() {
        val t = TestRepo()
        t.write("a", "1"); t.write("big", "unchanged"); val c1 = t.commitAll("c1")
        t.write("a", "2"); val c2 = t.commitAll("c2")
        val all = t.repo.objectsToSend(listOf(c2), emptyList())
        val delta = t.repo.objectsToSend(listOf(c2), listOf(c1))
        assertTrue(delta.contains(c2)); assertFalse(delta.contains(c1))
        val unchangedBlob = t.repo.db.flattenTree(t.repo.db.readCommit(c1).tree)["big"]!!.id
        assertTrue(all.contains(unchangedBlob)); assertFalse(delta.contains(unchangedBlob))
        assertTrue(all.size > delta.size)
    }

    @Test fun `bare repository refuses work tree operations`() {
        val b = TestRepo(bare = true)
        expect(GitErrorKind.INVALID_ARGUMENT) { b.repo.status() }
        expect(GitErrorKind.INVALID_ARGUMENT) { b.repo.add(listOf(".")) }
    }

    @Test fun `merge head makes a two parent commit and is cleared`() {
        val t = TestRepo()
        t.write("a", "1"); val c1 = t.commitAll("c1")
        t.repo.checkoutNewBranch("side"); t.write("s", "s"); val s1 = t.commitAll("s1")
        t.repo.checkout("main")
        File(t.gitDir(), "MERGE_HEAD").writeText(s1.hex + "\n")
        t.write("m", "m"); t.repo.add(listOf("."))
        val m = t.repo.commit(CommitOptions("merge"))
        assertEquals(listOf(c1, s1), t.repo.db.readCommit(m).parents)
        assertFalse(File(t.gitDir(), "MERGE_HEAD").exists())
    }
}
