package dev.fonebrew.domain.gitcore.merge

import dev.fonebrew.domain.gitcore.*
import dev.fonebrew.domain.gitcore.GitTestSupport.git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Cherry-pick and rebase against the real git CLI: the same history is cloned twice, git does one
 * and the engine the other; clean results must have the same tree ids, conflicted ones the same
 * stages/status/files, and git must be able to abort - and to CONTINUE - a rebase the engine
 * stopped (the state directory is git-compatible).
 */
class PickRebaseInteropTest {
    @Before fun requireGit() = assumeTrue("git CLI not available", GitTestSupport.gitAvailable())

    private fun w(d: File, p: String, t: String) { File(d, p).parentFile.mkdirs(); File(d, p).writeText(t) }
    private fun commit(d: File, m: String) { git(d, "add", "-A"); git(d, "commit", "-q", "-m", m) }
    private fun repoWith(build: (File) -> Unit): File {
        val d = GitTestSupport.tempDir("g2pr")
        git(d, "init", "-q", "-b", "main"); git(d, "config", "user.name", "Eng Test"); git(d, "config", "user.email", "eng@test")
        build(d); return d
    }
    private fun copy(src: File): File {
        val dst = GitTestSupport.tempDir("g2prc")
        ProcessBuilder("cp", "-a", "${src.absolutePath}/.", dst.absolutePath).redirectErrorStream(true).start().let { it.inputStream.readBytes(); check(it.waitFor() == 0) }
        return dst
    }
    private fun open(d: File) = Repository.open(JvmFileSystem(), d.absolutePath, clock = { 1_700_000_000L })
    private fun workTree(d: File): Map<String, String> {
        val out = sortedMapOf<String, String>()
        d.walkTopDown().onEnter { it.name != ".git" }.forEach { f -> if (f.isFile) out[f.relativeTo(d).path] = f.readText() }
        return out
    }
    private fun unmerged(d: File) = git(d, "ls-files", "-u").lines().filter { it.isNotBlank() }
    private fun status(d: File) = git(d, "status", "--porcelain").lines().filter { it.isNotBlank() }
    private fun msgs(d: File, n: Int) = git(d, "log", "--format=%s|%an", "-n$n").lines().filter { it.isNotBlank() }

    // ------------------------------------------------------------------ cherry-pick

    private val pickBase: (File) -> Unit = { d ->
        w(d, "a", "1\n2\n3\n4\n5\n6\n7\n8\n9\n"); w(d, "k", "k\n"); commit(d, "base")
        git(d, "checkout", "-q", "-b", "topic")
        w(d, "a", "1\n2\nTHREE\n4\n5\n6\n7\n8\n9\n"); w(d, "n", "new\n"); commit(d, "feature one")
        w(d, "a", "1\n2\nTHREE\n4\n5\n6\n7\n8\nNINE\n"); commit(d, "feature two")
        git(d, "checkout", "-q", "main")
        w(d, "m", "m\n"); commit(d, "main work")
    }

    @Test fun `cherry pick clean matches git tree and message`() {
        val src = repoWith(pickBase)
        val a = copy(src); val b = copy(src)
        git(a, "cherry-pick", "topic")
        val o = open(b).cherryPick("topic")
        assertEquals(CherryPickOutcomeKind.CLEAN, o.kind)
        assertEquals(git(a, "rev-parse", "HEAD^{tree}").trim(), git(b, "rev-parse", "HEAD^{tree}").trim())
        assertEquals(git(a, "log", "-1", "--format=%an|%ae|%at|%s|%b"), git(b, "log", "-1", "--format=%an|%ae|%at|%s|%b"))
        assertEquals(emptyList<String>(), status(b))
    }

    @Test fun `cherry pick conflict leaves the same state as git and git can abort and continue it`() {
        val src = repoWith { d -> pickBase(d); w(d, "a", "1\n2\nMAIN3\n4\n5\n6\n7\n8\n9\n"); commit(d, "main edit a") }
        val a = copy(src); val b = copy(src); val c = copy(src)
        git(a, "cherry-pick", "topic~1", "-x".takeIf { false } ?: "--no-edit", allowFail = true)
        val o = open(b).cherryPick("topic~1")
        assertEquals(CherryPickOutcomeKind.CONFLICTED, o.kind)
        assertEquals(unmerged(a), unmerged(b)); assertEquals(status(a), status(b))
        assertEquals(workTree(a), workTree(b))
        assertEquals(File(a, ".git/CHERRY_PICK_HEAD").readText(), File(b, ".git/CHERRY_PICK_HEAD").readText())
        assertEquals(File(a, ".git/MERGE_MSG").readText(), File(b, ".git/MERGE_MSG").readText())
        git(b, "cherry-pick", "--abort")                               // git aborts the engine's state
        assertEquals(emptyList<String>(), status(b)); assertEquals(git(src, "rev-parse", "HEAD"), git(b, "rev-parse", "HEAD"))
        // git continues what the engine started
        val rc = open(c); rc.cherryPick("topic~1")
        w(c, "a", "1\n2\nRESOLVED\n4\n5\n6\n7\n8\n9\n"); git(c, "add", "a")
        git(c, "-c", "core.editor=true", "cherry-pick", "--continue")
        val rd = copy(src); val engine = open(rd); engine.cherryPick("topic~1")
        w(rd, "a", "1\n2\nRESOLVED\n4\n5\n6\n7\n8\n9\n"); engine.add(listOf("a")); engine.continueMerge()
        assertEquals(git(c, "rev-parse", "HEAD^{tree}").trim(), git(rd, "rev-parse", "HEAD^{tree}").trim())
        assertEquals(git(c, "log", "-1", "--format=%an|%s"), git(rd, "log", "-1", "--format=%an|%s"))
    }

    // ------------------------------------------------------------------ rebase

    private fun rebaseHistory(conflict: Boolean): (File) -> Unit = { d ->
        w(d, "shared", "a\nb\nc\n"); w(d, "k", "k\n"); commit(d, "base")
        git(d, "checkout", "-q", "-b", "topic")
        w(d, "t1", "t1\n"); commit(d, "t1")
        w(d, "shared", if (conflict) "a\nTOPIC\nc\n" else "a\nb\nC\n"); commit(d, "t2")
        w(d, "t3", "t3\n"); commit(d, "t3")
        git(d, "checkout", "-q", "main")
        w(d, "m1", "m1\n"); if (conflict) w(d, "shared", "a\nMAIN\nc\n"); commit(d, "m1")
        git(d, "checkout", "-q", "topic")
    }

    @Test fun `clean rebase gives git's tree and commit list`() {
        val src = repoWith(rebaseHistory(false))
        val a = copy(src); val b = copy(src)
        git(a, "rebase", "main")
        val o = open(b).rebase("main")
        assertEquals(RebaseOutcomeKind.COMPLETED, o.kind)
        assertEquals(git(a, "rev-parse", "HEAD^{tree}").trim(), git(b, "rev-parse", "HEAD^{tree}").trim())
        assertEquals(msgs(a, 5), msgs(b, 5))
        assertEquals(git(a, "symbolic-ref", "HEAD").trim(), git(b, "symbolic-ref", "HEAD").trim())
        assertEquals(emptyList<String>(), status(b))
        git(b, "fsck", "--strict", "--no-dangling")
    }

    @Test fun `stopped rebase looks like git's to git status and git can abort it`() {
        val src = repoWith(rebaseHistory(true))
        val a = copy(src); val b = copy(src)
        git(a, "rebase", "main", allowFail = true)
        val o = open(b).rebase("main")
        assertEquals(RebaseOutcomeKind.STOPPED, o.kind)
        assertEquals(unmerged(a), unmerged(b)); assertEquals(status(a), status(b)); assertEquals(workTree(a), workTree(b))
        for (f in listOf("head-name", "onto", "orig-head", "stopped-sha", "end", "msgnum")) {
            assertEquals(f, File(a, ".git/rebase-merge/$f").readText(), File(b, ".git/rebase-merge/$f").readText())
        }
        assertEquals(File(a, ".git/rebase-merge/done").readText(), File(b, ".git/rebase-merge/done").readText())
        assertEquals(File(a, ".git/rebase-merge/git-rebase-todo").readText(), File(b, ".git/rebase-merge/git-rebase-todo").readText())
        assertTrue(git(b, "status").contains("rebase in progress"))
        git(b, "rebase", "--abort")
        assertEquals(git(src, "rev-parse", "topic"), git(b, "rev-parse", "topic").trim().let { it + "\n" }.let { git(b, "rev-parse", "HEAD") })
        assertEquals(emptyList<String>(), status(b)); assertEquals("topic", git(b, "symbolic-ref", "--short", "HEAD").trim())
    }

    @Test fun `git continues a rebase the engine stopped and the engine continues one git stopped`() {
        val src = repoWith(rebaseHistory(true))
        val fix = "a\nFIXED\nc\n"
        // (1) engine stops, git continues
        val e1 = copy(src); open(e1).rebase("main")
        w(e1, "shared", fix); git(e1, "add", "shared")
        git(e1, "-c", "core.editor=true", "rebase", "--continue")
        // (2) git stops, engine continues
        val g1 = copy(src); git(g1, "rebase", "main", allowFail = true)
        w(g1, "shared", fix); git(g1, "add", "shared")
        val o = open(g1).rebaseContinue()
        assertEquals(RebaseOutcomeKind.COMPLETED, o.kind)
        // (3) engine all the way
        val e2 = copy(src); val eng = open(e2); eng.rebase("main")
        w(e2, "shared", fix); eng.add(listOf("shared")); eng.rebaseContinue()
        val tree = git(e1, "rev-parse", "HEAD^{tree}").trim()
        assertEquals(tree, git(g1, "rev-parse", "HEAD^{tree}").trim())
        assertEquals(tree, git(e2, "rev-parse", "HEAD^{tree}").trim())
        assertEquals(msgs(e1, 4), msgs(e2, 4)); assertEquals(msgs(g1, 4), msgs(e2, 4))
        for (d in listOf(e1, g1, e2)) assertEquals(emptyList<String>(), status(d))
    }
}
