package dev.fonebrew.domain.gitcore.merge

import dev.fonebrew.domain.gitcore.*
import dev.fonebrew.domain.gitcore.textmerge.Choice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MergeOpsTest {
    private fun expect(kind: GitErrorKind, block: () -> Unit): GitException {
        try { block() } catch (e: GitException) { assertEquals(e.message, kind, e.kind); return e }
        fail("expected $kind"); throw IllegalStateException()
    }

    /** main: base + ours change; topic: base + theirs change. */
    private fun fork(base: List<Pair<String, String>>, ours: List<Pair<String, String?>>, theirs: List<Pair<String, String?>>): TestRepo {
        val t = TestRepo()
        t.commitFiles("base", *base.toTypedArray())
        t.branchFrom("topic")
        t.commitFiles("ours", *ours.toTypedArray())
        t.on("topic") { t.commitFiles("theirs", *theirs.toTypedArray()) }
        t.repo.checkout("main")
        return t
    }

    // ------------------------------------------------------------------ the easy outcomes

    @Test fun `up to date and fast forward`() {
        val t = TestRepo()
        t.commitFiles("c1", "a" to "1\n")
        t.branchFrom("topic")
        assertEquals(MergeOutcomeKind.UP_TO_DATE, t.repo.merge("topic").kind)
        t.on("topic") { t.commitFiles("c2", "b" to "2\n") }
        t.repo.checkout("main")
        val ff = t.repo.merge("topic")
        assertEquals(MergeOutcomeKind.FAST_FORWARD, ff.kind)
        assertEquals(t.repo.resolve("topic"), t.repo.headCommitId())
        assertEquals("2\n", t.read("b"))
    }

    @Test fun `no-ff makes a merge commit and ff-only refuses when diverged`() {
        val t = TestRepo()
        t.commitFiles("c1", "a" to "1\n")
        t.branchFrom("topic")
        t.on("topic") { t.commitFiles("c2", "b" to "2\n") }
        t.repo.checkout("main")
        val m = t.repo.merge("topic", MergeOptions(fastForward = FastForward.NEVER)) as MergeOutcome.Clean
        assertEquals(2, t.repo.db.readCommit(m.commit!!).parents.size)
        assertEquals("Merge branch 'topic'\n", t.repo.db.readCommit(m.commit!!).message)

        val t2 = fork(listOf("a" to "1\n"), listOf("x" to "x\n"), listOf("y" to "y\n"))
        expect(GitErrorKind.INVALID_ARGUMENT) { t2.repo.merge("topic", MergeOptions(fastForward = FastForward.ONLY)) }
        assertNull(t2.repo.mergeState())
    }

    @Test fun `a clean three way merge commits with two parents and keeps both sides`() {
        val t = fork(listOf("a" to "1\n2\n3\n4\n5\n6\n7\n"), listOf("a" to "ONE\n2\n3\n4\n5\n6\n7\n"), listOf("a" to "1\n2\n3\n4\n5\n6\nSEVEN\n", "n" to "new\n"))
        val o = t.repo.merge("topic") as MergeOutcome.Clean
        assertEquals("ONE\n2\n3\n4\n5\n6\nSEVEN\n", t.read("a")); assertEquals("new\n", t.read("n"))
        assertEquals(listOf(t.repo.resolve("main^"), t.repo.resolve("topic")), t.repo.db.readCommit(o.commit!!).parents)
        assertNull(t.repo.mergeState())
        assertTrue(t.repo.status().isClean)
    }

    @Test fun `unrelated histories are refused unless allowed`() {
        val t = TestRepo()
        t.commitFiles("c1", "a" to "1\n")
        val sig = t.repo.signature(Identity("x", "x@x"))
        val tree = t.repo.db.write(Tree(listOf(TreeEntry(FileMode.REGULAR, "other", t.repo.db.write(ObjectType.BLOB, "o".toByteArray())))))
        val orphan = t.repo.db.write(Commit(tree, emptyList(), sig, sig, "orphan\n"))
        t.repo.refs.update("refs/heads/orphan", orphan)
        expect(GitErrorKind.INVALID_ARGUMENT) { t.repo.merge("orphan") }
        val ok = t.repo.merge("orphan", MergeOptions(allowUnrelatedHistories = true))
        assertEquals(MergeOutcomeKind.CLEAN, ok.kind)
        assertEquals("o", t.read("other"))
    }

    // ------------------------------------------------------------------ conflicts leave git's state

    @Test fun `a conflicting merge leaves stages markers and state files exactly as git does`() {
        val t = fork(listOf("f" to "a\nb\nc\n"), listOf("f" to "a\nOURS\nc\n"), listOf("f" to "a\nTHEIRS\nc\n"))
        val headBefore = t.repo.headCommitId()!!
        val o = t.repo.merge("topic") as MergeOutcome.Conflicted
        assertEquals("f", o.conflicts.single().path); assertEquals(ConflictKind.CONTENT, o.conflicts.single().kind)
        assertEquals("a\n<<<<<<< HEAD\nOURS\n=======\nTHEIRS\n>>>>>>> topic\nc\n", t.read("f"))
        val ix = t.repo.readIndex()
        assertEquals(listOf(1, 2, 3), (1..3).filter { ix.get("f", it) != null })
        assertNull(ix.get("f", 0))
        assertEquals(headBefore, t.repo.headCommitId())
        assertEquals(t.repo.resolve("topic"), t.repo.readStateId(StateFiles.MERGE_HEAD))
        assertEquals(headBefore, t.repo.readStateId(StateFiles.ORIG_HEAD))
        assertTrue(t.repo.readStateText(StateFiles.MERGE_MSG)!!.startsWith("Merge branch 'topic'\n\n# Conflicts:\n#\tf\n"))
        val st = t.repo.mergeState()!!
        assertEquals(OperationKind.MERGE, st.kind); assertEquals(listOf("f"), st.unresolved)
        assertEquals(listOf("UU f"), t.repo.status().porcelain())
        // another operation is refused while one is open
        expect(GitErrorKind.INVALID_ARGUMENT) { t.repo.merge("topic") }
    }

    @Test fun `continue refuses while any path is unresolved and then commits when resolved`() {
        val t = fork(listOf("f" to "a\nb\nc\n", "g" to "x\ny\nz\n"), listOf("f" to "a\nO\nc\n", "g" to "x\nO\nz\n"), listOf("f" to "a\nT\nc\n", "g" to "x\nT\nz\n"))
        t.repo.merge("topic")
        val e = expect(GitErrorKind.INVALID_ARGUMENT) { t.repo.continueMerge() }
        assertEquals(listOf("f", "g"), e.paths)
        // resolve only f
        t.write("f", "a\nboth\nc\n"); t.repo.add(listOf("f"))
        val e2 = expect(GitErrorKind.INVALID_ARGUMENT) { t.repo.continueMerge() }
        assertEquals(listOf("g"), e2.paths)
        assertNotNull(t.repo.mergeState())
        t.write("g", "x\nboth\nz\n"); t.repo.add(listOf("g"))
        val id = t.repo.continueMerge()
        val c = t.repo.db.readCommit(id)
        assertEquals(2, c.parents.size)
        assertNull(t.repo.mergeState())
        assertFalse(t.repo.hasState(StateFiles.MERGE_HEAD)); assertFalse(t.repo.hasState(StateFiles.MERGE_MSG)); assertFalse(t.repo.hasState(StateFiles.JOURNAL))
        assertEquals("Merge branch 'topic'", c.message.trim())
        assertTrue(t.repo.status().isClean)
    }

    @Test fun `continue refuses to commit leftover conflict markers unless told`() {
        val t = fork(listOf("f" to "a\nb\nc\n"), listOf("f" to "a\nO\nc\n"), listOf("f" to "a\nT\nc\n"))
        t.repo.merge("topic")
        t.repo.add(listOf("f"))                     // staging the marker file "resolves" it in the index
        val e = expect(GitErrorKind.INVALID_ARGUMENT) { t.repo.continueMerge() }
        assertEquals(listOf("f"), e.paths)
        assertTrue(e.message!!.contains("markers"))
        val id = t.repo.continueMerge(ContinueOptions(allowMarkers = true))
        assertTrue(t.repo.db.readCommit(id).parents.size == 2)
    }

    @Test fun `delete versus modify keeps the modified file and leaves stages 1 and 2`() {
        val t = fork(listOf("f" to "a\nb\nc\n", "k" to "k\n"), listOf("f" to "a\nB\nc\n"), listOf("f" to null))
        val o = t.repo.merge("topic") as MergeOutcome.Conflicted
        assertEquals(ConflictKind.MODIFY_DELETE, o.conflicts.single().kind)
        assertEquals("UD", o.conflicts.single().statusCode)
        assertEquals("a\nB\nc\n", t.read("f"))                 // the modification survives in the work tree
        val ix = t.repo.readIndex()
        assertEquals(listOf(1, 2), (1..3).filter { ix.get("f", it) != null })
    }

    @Test fun `identical edits and identical adds merge cleanly and nothing is lost`() {
        val t = fork(listOf("f" to "a\nb\n"), listOf("f" to "a\nX\n", "n" to "same\n"), listOf("f" to "a\nX\n", "n" to "same\n"))
        assertEquals(MergeOutcomeKind.CLEAN, t.repo.merge("topic").kind)
        assertEquals("a\nX\n", t.read("f")); assertEquals("same\n", t.read("n"))
    }

    @Test fun `a delete on one side and an untouched file on the other deletes`() {
        val t = fork(listOf("f" to "a\n", "g" to "g\n"), listOf("g" to "g2\n"), listOf("f" to null))
        assertEquals(MergeOutcomeKind.CLEAN, t.repo.merge("topic").kind)
        assertFalse(t.file("f").exists()); assertEquals("g2\n", t.read("g"))
    }

    @Test fun `add add with different content conflicts and keeps both versions`() {
        val t = fork(listOf("k" to "k\n"), listOf("n" to "ours\n"), listOf("n" to "theirs\n"))
        val o = t.repo.merge("topic") as MergeOutcome.Conflicted
        assertEquals(ConflictKind.ADD_ADD, o.conflicts.single().kind); assertEquals("AA", o.conflicts.single().statusCode)
        val txt = t.read("n")
        assertTrue(txt.contains("ours") && txt.contains("theirs") && txt.contains("<<<<<<<"))
    }

    @Test fun `binary content conflict leaves ours in the tree and no markers`() {
        val t = TestRepo()
        t.write("bin", "\u0000\u0001base"); t.commitAll("base")
        t.branchFrom("topic")
        t.write("bin", "\u0000\u0001ours"); t.commitAll("ours")
        t.on("topic") { t.write("bin", "\u0000\u0001theirs"); t.commitAll("theirs") }
        t.repo.checkout("main")
        val o = t.repo.merge("topic") as MergeOutcome.Conflicted
        assertEquals(ConflictKind.BINARY, o.conflicts.single().kind)
        assertEquals("\u0000\u0001ours", t.read("bin"))
    }

    @Test fun `mode change on one side and content change on the other merge cleanly`() {
        val t = TestRepo()
        t.write("s.sh", "echo 1\n"); t.commitAll("base")
        t.branchFrom("topic")
        t.write("s.sh", "echo 2\n"); t.commitAll("edit")
        t.on("topic") { t.file("s.sh").setExecutable(true); t.commitAll("chmod") }
        t.repo.checkout("main")
        assertEquals(MergeOutcomeKind.CLEAN, t.repo.merge("topic").kind)
        assertEquals("echo 2\n", t.read("s.sh"))
        assertEquals(FileMode.EXECUTABLE, t.flat()["s.sh"]!!.mode)
    }

    @Test fun `file against directory moves the file aside as git does`() {
        val t = fork(listOf("k" to "k\n"), listOf("a/b" to "dir\n"), listOf("a" to "file\n"))
        val o = t.repo.merge("topic") as MergeOutcome.Conflicted
        assertEquals(ConflictKind.FILE_DIRECTORY, o.conflicts.single().kind)
        assertEquals("a~topic", o.conflicts.single().path)
        assertEquals("dir\n", t.read("a/b")); assertEquals("file\n", t.read("a~topic"))
        val ix = t.repo.readIndex()
        assertEquals(3, ix.get("a~topic", 3)?.stage)
        assertEquals("UA", o.conflicts.single().statusCode)
    }

    // ------------------------------------------------------------------ renames

    private val tenLines = (1..10).joinToString("") { "line $it\n" }

    @Test fun `exact rename on one side and edit on the other follows the rename`() {
        val t = fork(listOf("old.txt" to tenLines), listOf("new.txt" to tenLines, "old.txt" to null), listOf("old.txt" to tenLines.replace("line 5", "FIVE")))
        assertEquals(MergeOutcomeKind.CLEAN, t.repo.merge("topic").kind)
        assertFalse(t.file("old.txt").exists())
        assertEquals(tenLines.replace("line 5", "FIVE"), t.read("new.txt"))
    }

    @Test fun `inexact rename above fifty percent is followed and below is not`() {
        val similar = tenLines.replace("line 9", "nine").replace("line 10", "ten")   // 8 of 10 lines kept
        val t = fork(listOf("old.txt" to tenLines), listOf("new.txt" to similar, "old.txt" to null), listOf("old.txt" to tenLines.replace("line 2", "TWO")))
        val o = t.repo.merge("topic") as MergeOutcome.Clean
        assertEquals(similar.replace("line 2", "TWO"), t.read("new.txt"))
        assertNotNull(o.commit)

        val different = (1..10).joinToString("") { "other $it\n" }
        val t2 = fork(listOf("old.txt" to tenLines), listOf("new.txt" to different, "old.txt" to null), listOf("old.txt" to tenLines.replace("line 2", "TWO")))
        val o2 = t2.repo.merge("topic") as MergeOutcome.Conflicted               // delete vs modify, no rename found
        assertEquals(ConflictKind.MODIFY_DELETE, o2.conflicts.single().kind)
    }

    @Test fun `rename on one side against delete on the other is a rename delete conflict at the new name`() {
        val t = fork(listOf("old.txt" to tenLines, "k" to "k\n"), listOf("old.txt" to null), listOf("new.txt" to tenLines, "old.txt" to null))
        val o = t.repo.merge("topic") as MergeOutcome.Conflicted
        val c = o.conflicts.single()
        assertEquals(ConflictKind.RENAME_DELETE, c.kind); assertEquals("new.txt", c.path); assertEquals("DU", c.statusCode)
        assertEquals(tenLines, t.read("new.txt"))
    }

    @Test fun `rename on both sides to different names conflicts with stages at each name`() {
        val t = fork(listOf("old.txt" to tenLines, "k" to "k\n"), listOf("a.txt" to tenLines, "old.txt" to null), listOf("b.txt" to tenLines, "old.txt" to null))
        val o = t.repo.merge("topic") as MergeOutcome.Conflicted
        assertEquals(ConflictKind.RENAME_RENAME, o.conflicts.single().kind)
        val ix = t.repo.readIndex()
        assertNotNull(ix.get("old.txt", 1)); assertNotNull(ix.get("a.txt", 2)); assertNotNull(ix.get("b.txt", 3))
    }

    @Test fun `renamed on both sides to the same name with edits on both merges the edits`() {
        val t = fork(listOf("old.txt" to tenLines), listOf("n.txt" to tenLines.replace("line 1\n", "ONE\n"), "old.txt" to null), listOf("n.txt" to tenLines.replace("line 10", "TEN"), "old.txt" to null))
        assertEquals(MergeOutcomeKind.CLEAN, t.repo.merge("topic").kind)
        assertEquals(tenLines.replace("line 1\n", "ONE\n").replace("line 10", "TEN"), t.read("n.txt"))
    }

    @Test fun `a rename colliding with another change at the same path is refused not merged by dropping one`() {
        val t = fork(listOf("a" to tenLines, "k" to "k\n"), listOf("b" to tenLines, "a" to null), listOf("a" to tenLines.replace("line 5", "FIVE"), "b" to "something else entirely\n"))
        val before = t.snapshot()
        val e = expect(GitErrorKind.UNSUPPORTED) { t.repo.merge("topic") }
        assertTrue(e.message!!.contains("collision"))
        assertEquals(before, t.snapshot()); assertNull(t.repo.mergeState())
    }

    // ------------------------------------------------------------------ safety

    @Test fun `staged changes and local edits to affected paths refuse before anything is written`() {
        val t = fork(listOf("f" to "a\nb\nc\n", "g" to "g\n"), listOf("x" to "x\n"), listOf("f" to "a\nT\nc\n"))
        t.write("f", "local edit\n")                        // the merge would touch f
        val before = t.snapshot()
        val e = expect(GitErrorKind.DIRTY_TREE) { t.repo.merge("topic") }
        assertTrue(e.paths.contains("f"))
        assertEquals(before, t.snapshot())
        assertNull(t.repo.mergeState()); assertFalse(t.repo.hasState(StateFiles.JOURNAL)); assertFalse(t.repo.hasState(StateFiles.MERGE_HEAD))
        // an unrelated local edit is carried across
        t.write("f", "a\nb\nc\n")
        t.write("g", "my unrelated edit\n")
        assertEquals(MergeOutcomeKind.CLEAN, t.repo.merge("topic").kind)
        assertEquals("my unrelated edit\n", t.read("g"))
        // a staged change refuses
        val t2 = fork(listOf("f" to "a\n"), listOf("x" to "x\n"), listOf("y" to "y\n"))
        t2.write("z", "staged\n"); t2.repo.add(listOf("z"))
        expect(GitErrorKind.DIRTY_TREE) { t2.repo.merge("topic") }
    }

    @Test fun `an untracked file in the way of an incoming file refuses`() {
        val t = fork(listOf("k" to "k\n"), listOf("x" to "x\n"), listOf("n" to "theirs\n"))
        t.write("n", "my untracked\n")
        val e = expect(GitErrorKind.DIRTY_TREE) { t.repo.merge("topic") }
        assertEquals(listOf("n"), e.paths)
        assertEquals("my untracked\n", t.read("n"))
    }

    // ------------------------------------------------------------------ abort

    @Test fun `abort restores the exact pre merge state`() {
        val t = fork(listOf("f" to "a\nb\nc\n", "g" to "g\n"), listOf("f" to "a\nO\nc\n"), listOf("f" to "a\nT\nc\n", "g" to "g2\n", "n" to "new\n"))
        t.write("untracked", "keep me\n")
        val snapBefore = t.snapshot()
        val headBefore = t.repo.headCommitId()
        val idxBefore = t.repo.readIndex().entries().map { it.path to it.id }
        t.repo.merge("topic")
        assertNotNull(t.repo.mergeState())
        val r = t.repo.abortMerge()
        assertTrue(r.restored.containsAll(listOf("f", "g", "n")))
        assertEquals(snapBefore, t.snapshot())
        assertEquals(headBefore, t.repo.headCommitId())
        assertEquals(idxBefore, t.repo.readIndex().entries().map { it.path to it.id })
        assertNull(t.repo.mergeState())
        assertFalse(t.repo.hasState(StateFiles.JOURNAL)); assertFalse(t.repo.hasState(StateFiles.MERGE_HEAD))
        assertTrue(t.repo.status().isClean)
    }

    @Test fun `abort refuses to discard edits made after the merge and says which`() {
        val t = fork(listOf("f" to "a\nb\nc\n", "g" to "g\n"), listOf("f" to "a\nO\nc\n"), listOf("f" to "a\nT\nc\n", "g" to "g2\n"))
        t.repo.merge("topic")
        t.write("f", "a\nmy careful resolution\nc\n")           // the user resolved f by hand
        val e = expect(GitErrorKind.DIRTY_TREE) { t.repo.abortMerge() }
        assertEquals(listOf("f"), e.paths)
        assertTrue(e.message!!.contains("discardLocalChanges"))
        assertEquals("a\nmy careful resolution\nc\n", t.read("f"))   // nothing was touched
        assertNotNull(t.repo.mergeState())
        // an edit to a cleanly merged file is protected too
        t.write("g", "g2 but edited\n")
        val e2 = expect(GitErrorKind.DIRTY_TREE) { t.repo.abortMerge() }
        assertEquals(listOf("f", "g"), e2.paths)
        // only the explicit opt-in throws the edits away
        t.repo.abortMerge(discardLocalChanges = true)
        assertEquals("a\nO\nc\n", t.read("f")); assertEquals("g\n", t.read("g"))
        assertNull(t.repo.mergeState())
    }

    @Test fun `abort refuses a resolution that was staged`() {
        val t = fork(listOf("f" to "a\nb\nc\n"), listOf("f" to "a\nO\nc\n"), listOf("f" to "a\nT\nc\n"))
        t.repo.merge("topic")
        t.write("f", "resolved\n"); t.repo.add(listOf("f"))
        expect(GitErrorKind.DIRTY_TREE) { t.repo.abortMerge() }
        assertEquals("resolved\n", t.read("f"))
    }

    @Test fun `an interrupted merge can be aborted but not continued`() {
        val t = fork(listOf("f" to "a\nb\nc\n"), listOf("f" to "a\nO\nc\n"), listOf("f" to "a\nT\nc\n", "n" to "new\n"))
        t.repo.merge("topic")
        // simulate a crash after the journal was written but before the index: mark the journal "applying"
        val j = Journal.read(t.repo)!!
        Journal(j.op, false, j.orig, j.entries).write(t.repo)
        t.repo.add(listOf("f"))
        val e = expect(GitErrorKind.INVALID_ARGUMENT) { t.repo.continueMerge(ContinueOptions(allowMarkers = true)) }
        assertTrue(e.message!!.contains("interrupted"))
        expect(GitErrorKind.DIRTY_TREE) { t.repo.abortMerge() }             // f was staged since
        t.repo.abortMerge(discardLocalChanges = true)
        assertTrue(t.repo.status().isClean)
    }

    // ------------------------------------------------------------------ adversarial: never drop a side

    @Test fun `every merge outcome keeps both sides information`() {
        // For a battery of shapes, the merged tree (or the conflict) must contain each side's change.
        class Case(val name: String, val base: List<Pair<String, String>>, val ours: List<Pair<String, String?>>, val theirs: List<Pair<String, String?>>)
        val cases = listOf(
            Case("both add different files", listOf("k" to "k\n"), listOf("a" to "1\n"), listOf("b" to "2\n")),
            Case("one deletes other edits another file", listOf("a" to "1\n", "b" to "2\n"), listOf("a" to null), listOf("b" to "22\n")),
            Case("identical delete", listOf("a" to "1\n", "k" to "k\n"), listOf("a" to null), listOf("a" to null)),
            Case("edit vs delete", listOf("a" to "1\n2\n"), listOf("a" to "1\nTWO\n"), listOf("a" to null)),
            Case("delete vs edit", listOf("a" to "1\n2\n"), listOf("a" to null), listOf("a" to "1\nTWO\n")),
        )
        for (c in cases) {
            val t = fork(c.base, c.ours, c.theirs)
            val ours = t.flat("main"); val theirs = t.flat("topic"); val base = t.flat("main~1")
            val o = t.repo.merge("topic")
            val merged = if (o is MergeOutcome.Clean) t.flat() else null
            val conflictPaths = (o as? MergeOutcome.Conflicted)?.conflicts?.flatMap { it.paths }?.toSet() ?: emptySet()
            for (p in (ours.keys + theirs.keys + base.keys)) {
                val b = base[p]; val oe = ours[p]; val te = theirs[p]
                val oursChanged = oe != b; val theirsChanged = te != b
                if (p in conflictPaths) continue
                val m = (merged ?: t.repo.readIndex().stage0().mapValues { FlatEntry(it.value.mode, it.value.id) })[p]
                when {
                    oursChanged && !theirsChanged -> assertEquals("${c.name}: ours' change to $p lost", oe, m)
                    theirsChanged && !oursChanged -> assertEquals("${c.name}: theirs' change to $p lost", te, m)
                    oursChanged && theirsChanged && oe == te -> assertEquals("${c.name}: identical change to $p lost", oe, m)
                }
            }
            if (o is MergeOutcome.Conflicted) t.repo.abortMerge()
        }
    }

    // ------------------------------------------------------------------ cherry-pick

    @Test fun `cherry pick applies a commit with the original author and message`() {
        val t = TestRepo()
        t.commitFiles("base", "a" to "1\n2\n3\n", "k" to "k\n")
        t.branchFrom("topic")
        t.on("topic") { t.commitFiles("feature: change line 3", "a" to "1\n2\nTHREE\n") }
        t.repo.checkout("main")
        t.commitFiles("on main", "m" to "m\n")
        val pick = t.repo.resolve("topic")
        val r = t.repo.cherryPick("topic") as CherryPickOutcome.Clean
        val c = t.repo.db.readCommit(r.commit!!)
        assertEquals(1, c.parents.size)
        assertEquals("feature: change line 3\n", c.message)
        assertEquals(t.repo.db.readCommit(pick).author, c.author)
        assertEquals("1\n2\nTHREE\n", t.read("a")); assertEquals("m\n", t.read("m"))
    }

    @Test fun `cherry pick conflict then continue and abort`() {
        val t = TestRepo()
        t.commitFiles("base", "a" to "1\n2\n3\n")
        t.branchFrom("topic")
        t.on("topic") { t.commitFiles("topic edit", "a" to "1\nTOPIC\n3\n") }
        t.repo.checkout("main")
        t.commitFiles("main edit", "a" to "1\nMAIN\n3\n")
        val o = t.repo.cherryPick("topic") as CherryPickOutcome.Conflicted
        assertEquals(1, o.conflicts.size)
        assertEquals(OperationKind.CHERRY_PICK, t.repo.mergeState()!!.kind)
        assertTrue(t.read("a").contains("<<<<<<< HEAD") && t.read("a").contains(">>>>>>> "))
        t.repo.abortMerge()
        assertEquals("1\nMAIN\n3\n", t.read("a"))
        t.repo.cherryPick("topic")
        t.write("a", "1\nBOTH\n3\n"); t.repo.add(listOf("a"))
        val id = t.repo.continueMerge()
        assertEquals(1, t.repo.db.readCommit(id).parents.size)
        assertEquals("topic edit\n", t.repo.db.readCommit(id).message)
    }

    @Test fun `cherry pick of an already applied change is empty and leaves nothing behind`() {
        val t = TestRepo()
        t.commitFiles("base", "a" to "1\n")
        t.branchFrom("topic")
        t.on("topic") { t.commitFiles("add b", "b" to "b\n") }
        t.repo.checkout("main")
        t.commitFiles("add b too", "b" to "b\n")
        assertEquals(CherryPickOutcomeKind.EMPTY, t.repo.cherryPick("topic").kind)
        assertNull(t.repo.mergeState()); assertFalse(t.repo.hasState(StateFiles.CHERRY_PICK_HEAD))
    }

    @Test fun `cherry picking a merge commit is refused`() {
        val t = fork(listOf("a" to "1\n"), listOf("x" to "x\n"), listOf("y" to "y\n"))
        val m = (t.repo.merge("topic") as MergeOutcome.Clean).commit!!
        t.branchFrom("other", "main~1")
        t.repo.checkout("other")
        expect(GitErrorKind.UNSUPPORTED) { t.repo.cherryPick(m.hex) }
    }

    // ------------------------------------------------------------------ criss-cross

    @Test fun `criss cross history merges via a virtual base`() {
        val t = TestRepo()
        t.commitFiles("base", "f" to "1\n2\n3\n4\n5\n6\n7\n8\n9\n")
        t.branchFrom("b")
        val a1 = t.commitFiles("a1", "a1" to "a1\n")
        t.on("b") { t.commitFiles("b1", "b1" to "b1\n") }
        val b1 = t.repo.resolve("b")
        t.repo.checkout("main")
        // each side merges the other's tip: the two merge commits cross
        t.repo.merge("b", MergeOptions(fastForward = FastForward.NEVER))          // main: merge(a1, b1)
        t.repo.checkout("b")
        t.repo.merge(a1.hex, MergeOptions(fastForward = FastForward.NEVER))      // b: merge(b1, a1)
        t.repo.checkout("main")
        t.commitFiles("main edit", "f" to "ONE\n2\n3\n4\n5\n6\n7\n8\n9\n")
        t.on("b") { t.commitFiles("b edit", "f" to "1\n2\n3\n4\n5\n6\n7\n8\nNINE\n") }
        t.repo.checkout("main")
        val bases = t.repo.mergeBases(t.repo.resolve("main"), t.repo.resolve("b"))
        assertEquals(setOf(a1, b1), t.repo.mergeBases(t.repo.resolve("main~1"), t.repo.resolve("b~1")).toSet())
        assertTrue(bases.isNotEmpty())
        val bt = t.repo.baseTreeFor(t.repo.resolve("main~1"), t.repo.resolve("b~1"))
        assertTrue("two best bases must give a virtual base", bt.virtual)
        assertEquals(2, bt.commits.size)
        val o = t.repo.merge("b")
        assertEquals(MergeOutcomeKind.CLEAN, o.kind)
        assertEquals("ONE\n2\n3\n4\n5\n6\n7\n8\nNINE\n", t.read("f"))
        assertEquals("a1\n", t.read("a1")); assertEquals("b1\n", t.read("b1"))
    }

    @Test fun `merge bases of linear and diverged histories`() {
        val t = TestRepo()
        val c1 = t.commitFiles("c1", "a" to "1\n")
        t.branchFrom("x")
        val c2 = t.commitFiles("c2", "a" to "2\n")
        t.on("x") { t.commitFiles("c3", "b" to "3\n") }
        assertEquals(listOf(c1), t.repo.mergeBases(t.repo.resolve("main"), t.repo.resolve("x")))
        assertEquals(listOf(c2), t.repo.mergeBases(c2, t.repo.resolve("main")))
    }
}
