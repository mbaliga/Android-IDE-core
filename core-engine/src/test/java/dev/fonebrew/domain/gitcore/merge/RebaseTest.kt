package dev.fonebrew.domain.gitcore.merge

import dev.fonebrew.domain.gitcore.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RebaseTest {
    private class Crash(point: String) : RuntimeException("simulated process death at $point")

    @After fun clearHook() { RebaseTestHooks.hook = null }

    private fun expect(kind: GitErrorKind, block: () -> Unit): GitException {
        try { block() } catch (e: GitException) { assertEquals(e.message, kind, e.kind); return e }
        fail("expected $kind"); throw IllegalStateException()
    }

    /** main: base, m1.  topic: base, t1, t2, t3 (three independent files). */
    private fun diverged(conflictOnT2: Boolean = false): TestRepo {
        val t = TestRepo()
        t.commitFiles("base", "shared" to "a\nb\nc\n", "k" to "k\n")
        t.branchFrom("topic")
        t.commitFiles("m1", "m1" to "m1\n", "shared" to if (conflictOnT2) "a\nMAIN\nc\n" else "a\nb\nc\n")
        t.on("topic") {
            t.commitFiles("t1", "t1" to "t1\n")
            t.commitFiles("t2", "shared" to if (conflictOnT2) "a\nTOPIC\nc\n" else "a\nb\nC\n")
            t.commitFiles("t3", "t3" to "t3\n")
        }
        return t   // topic checked out
    }

    private fun messages(t: TestRepo, rev: String = "HEAD", n: Int) = t.repo.log(rev).take(n).map { it.commit.message.trim() }

    @Test fun `linear rebase replays every commit onto the new base`() {
        val t = diverged()
        val origTopic = t.repo.headCommitId()!!
        val o = t.repo.rebase("main") as RebaseOutcome.Completed
        assertEquals(3, o.applied)
        assertEquals("topic", t.repo.currentBranch())
        assertEquals(listOf("t3", "t2", "t1", "m1"), messages(t, n = 4))
        assertEquals(t.repo.resolve("topic"), o.head)
        assertTrue(t.file("m1").exists() && t.file("t1").exists() && t.file("t3").exists())
        assertEquals("a\nb\nC\n", t.read("shared"))
        assertEquals(origTopic, t.repo.readStateId(StateFiles.ORIG_HEAD))
        assertNull(t.repo.rebaseState()); assertFalse(t.file(".git/rebase-merge").exists())
        assertTrue(t.repo.status().isClean)
        // authors and original messages preserved
        assertEquals(t.repo.db.readCommit(origTopic).author, t.repo.db.readCommit(o.head).author)
    }

    @Test fun `rebase up to date fast forward and unsupported shapes`() {
        val t = TestRepo()
        t.commitFiles("c1", "a" to "1\n")
        t.branchFrom("topic")
        assertEquals(RebaseOutcomeKind.UP_TO_DATE, t.repo.rebase("topic").kind)
        t.commitFiles("c2", "b" to "2\n")                       // main moves, topic has nothing of its own
        t.repo.checkout("topic")
        val ff = t.repo.rebase("main")
        assertEquals(RebaseOutcomeKind.FAST_FORWARD, ff.kind)
        assertEquals(t.repo.resolve("main"), t.repo.resolve("topic"))
        assertEquals("2\n", t.read("b"))

        // a merge commit in the range is refused, not flattened
        val m = TestRepo()
        m.commitFiles("c1", "a" to "1\n")
        m.branchFrom("side")
        m.commitFiles("c2", "b" to "2\n")
        m.on("side") { m.commitFiles("s1", "s" to "s\n") }
        m.repo.checkout("main")
        m.repo.merge("side", MergeOptions(fastForward = FastForward.NEVER))
        m.branchFrom("base2", "main~2")
        m.commitFiles("c3", "c" to "3\n")
        m.repo.checkout("base2"); m.commitFiles("other", "o" to "o\n")
        m.repo.checkout("main")
        expect(GitErrorKind.UNSUPPORTED) { m.repo.rebase("base2") }
        assertNull(m.repo.rebaseState())
    }

    @Test fun `rebase refuses a dirty tree`() {
        val t = diverged()
        t.write("t1", "edited\n")
        val e = expect(GitErrorKind.DIRTY_TREE) { t.repo.rebase("main") }
        assertEquals(listOf("t1"), e.paths)
        assertNull(t.repo.rebaseState())
    }

    @Test fun `conflict stops the rebase with git's layout and continue finishes it`() {
        val t = diverged(conflictOnT2 = true)
        val o = t.repo.rebase("main") as RebaseOutcome.Stopped
        assertEquals(2, o.step); assertEquals(3, o.total)
        assertEquals("shared", o.conflicts.single().path)
        val st = t.repo.rebaseState()!!
        assertEquals("topic", st.branch); assertEquals(1, st.done); assertEquals(3, st.total); assertEquals(o.commit, st.stoppedAt)
        assertEquals(listOf("shared"), st.unresolved)
        assertNull(t.repo.refs.headBranch())                              // detached while replaying
        assertTrue(t.file(".git/rebase-merge/git-rebase-todo").readText().trim().endsWith("t3"))
        assertTrue(t.file(".git/rebase-merge/done").readText().lines().filter { it.isNotBlank() }.size == 2)
        assertEquals(o.commit.hex, t.file(".git/REBASE_HEAD").readText().trim())
        assertTrue(t.read("shared").contains("<<<<<<< HEAD") && t.read("shared").contains("(t2)"))
        // continue refuses while unresolved, then with markers
        val e = expect(GitErrorKind.INVALID_ARGUMENT) { t.repo.rebaseContinue() }
        assertEquals(listOf("shared"), e.paths)
        t.repo.add(listOf("shared"))
        expect(GitErrorKind.INVALID_ARGUMENT) { t.repo.rebaseContinue() }     // markers staged
        t.write("shared", "a\nMERGED\nc\n"); t.repo.add(listOf("shared"))
        val done = t.repo.rebaseContinue() as RebaseOutcome.Completed
        assertEquals(listOf("t3", "t2", "t1", "m1"), messages(t, n = 4))
        assertEquals("a\nMERGED\nc\n", t.read("shared"))
        assertEquals("topic", t.repo.currentBranch()); assertEquals(done.head, t.repo.resolve("topic"))
        assertNull(t.repo.rebaseState()); assertTrue(t.repo.status().isClean)
    }

    @Test fun `abort restores branch tree and index and refuses to discard later edits`() {
        val t = diverged(conflictOnT2 = true)
        val origHead = t.repo.headCommitId()!!
        val snap = t.snapshot()
        t.repo.rebase("main") as RebaseOutcome.Stopped
        t.write("shared", "a\nmy resolution\nc\n")                          // user edit after the stop
        val e = expect(GitErrorKind.DIRTY_TREE) { t.repo.rebaseAbort() }
        assertEquals(listOf("shared"), e.paths)
        assertNotNull(t.repo.rebaseState())
        assertEquals("a\nmy resolution\nc\n", t.read("shared"))
        t.repo.rebaseAbort(discardLocalChanges = true)
        assertEquals("topic", t.repo.currentBranch()); assertEquals(origHead, t.repo.headCommitId())
        assertEquals(snap, t.snapshot()); assertNull(t.repo.rebaseState()); assertTrue(t.repo.status().isClean)
        // a plain abort with nothing edited just works
        t.repo.rebase("main") as RebaseOutcome.Stopped
        t.repo.rebaseAbort()
        assertEquals(snap, t.snapshot()); assertEquals(origHead, t.repo.resolve("topic"))
    }

    @Test fun `skip drops the stopped commit`() {
        val t = diverged(conflictOnT2 = true)
        t.repo.rebase("main") as RebaseOutcome.Stopped
        val o = t.repo.rebaseSkip() as RebaseOutcome.Completed
        assertEquals(listOf("t3", "t1", "m1"), messages(t, n = 3))
        assertEquals("a\nMAIN\nc\n", t.read("shared"))
        assertEquals(o.head, t.repo.resolve("topic"))
    }

    @Test fun `continue with nothing staged is refused with a hint to skip`() {
        val t = diverged(conflictOnT2 = true)
        t.repo.rebase("main") as RebaseOutcome.Stopped
        t.write("shared", "a\nMAIN\nc\n"); t.repo.add(listOf("shared"))       // resolved to exactly the base: no change
        val e = expect(GitErrorKind.INVALID_ARGUMENT) { t.repo.rebaseContinue() }
        assertTrue(e.message!!.contains("skip"))
    }

    // ------------------------------------------------------------------ resumability

    private fun runWithCrash(point: String, occurrence: Int, conflict: Boolean = false): TestRepo {
        val t = diverged(conflictOnT2 = conflict)
        var seen = 0
        RebaseTestHooks.hook = { p -> if (p == point && ++seen == occurrence) throw Crash(p) }
        var crashed = false
        try { t.repo.rebase("main") } catch (e: Crash) { crashed = true }
        RebaseTestHooks.hook = null
        assertTrue("hook $point #$occurrence never fired", crashed)
        return t
    }

    @Test fun `a crash at any point between two writes resumes to the same result without losing or repeating a step`() {
        val reference = diverged().also { it.repo.rebase("main") }
        val refTree = reference.repo.headTreeId()
        val refMsgs = messages(reference, n = 4)
        val points = listOf("started-state", "after-marker", "after-plan", "after-commit-object", "after-head-move", "after-commit-point")
        var scenarios = 0
        for (p in points) {
            val occurrences = if (p == "started-state") listOf(1) else listOf(1, 2, 3)
            for (n in occurrences) {
                val t = runWithCrash(p, n)
                assertNotNull("$p#$n: state must still exist", t.repo.rebaseState())
                val o = t.repo.rebaseContinue()
                assertEquals("$p#$n", RebaseOutcomeKind.COMPLETED, o.kind)
                assertEquals("$p#$n: tree", refTree, t.repo.headTreeId())
                assertEquals("$p#$n: commits", refMsgs, messages(t, n = 4))
                assertEquals("$p#$n: branch", "topic", t.repo.currentBranch())
                assertTrue("$p#$n: clean", t.repo.status().isClean)
                assertNull("$p#$n: state removed", t.repo.rebaseState())
                // no commit is applied twice
                assertEquals("$p#$n: history length", 6 /* base + m1 + 3 + ... */ - 1, t.repo.log("HEAD").size)
                scenarios++
            }
        }
        assertTrue(scenarios >= 16)
    }

    @Test fun `a crash while recording a conflict stop still resumes as stopped`() {
        val t = runWithCrash("before-stop-cleanup", 1, conflict = true)
        val st = t.repo.rebaseState()!!
        assertNotNull(st.stoppedAt)
        assertEquals(listOf("shared"), st.unresolved)
        t.write("shared", "a\nMERGED\nc\n"); t.repo.add(listOf("shared"))
        assertEquals(RebaseOutcomeKind.COMPLETED, t.repo.rebaseContinue().kind)
        assertEquals("a\nMERGED\nc\n", t.read("shared"))
    }

    @Test fun `a crash mid conflict stop after the plan was written can be aborted cleanly`() {
        val t = runWithCrash("after-plan", 2, conflict = true)       // second step is the conflicting one
        val origBranchTip = t.repo.resolve("topic")
        t.repo.rebaseAbort()
        assertEquals(origBranchTip, t.repo.headCommitId()); assertEquals("topic", t.repo.currentBranch())
        assertTrue(t.repo.status().isClean); assertNull(t.repo.rebaseState())
    }

    @Test fun `resume refuses when HEAD was moved behind the engine's back`() {
        val t = runWithCrash("after-commit-point", 1)
        t.repo.checkout("main")                                          // detached state abandoned by hand
        val e = expect(GitErrorKind.INVALID_ARGUMENT) { t.repo.rebaseContinue() }
        assertTrue(e.message!!.contains("refusing to guess"))
    }

    @Test fun `the branch is not overwritten if it moved during the rebase`() {
        val t = diverged(conflictOnT2 = true)
        t.repo.rebase("main") as RebaseOutcome.Stopped
        t.repo.refs.update("refs/heads/topic", t.repo.resolve("main"))     // someone moves the branch
        t.write("shared", "a\nMERGED\nc\n"); t.repo.add(listOf("shared"))
        val e = expect(GitErrorKind.REF_CONFLICT) { t.repo.rebaseContinue() }
        assertTrue(e.message!!.contains("moved during the rebase"))
    }
}
