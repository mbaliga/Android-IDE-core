package dev.fonebrew.domain.gitcore.merge

import dev.fonebrew.domain.gitcore.*
import dev.fonebrew.domain.gitcore.GitTestSupport.git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Tree-merge interop proof. Each scenario builds ONE repository with the real git CLI (so object
 * ids are git's own), copies it twice, lets git merge one copy and this engine the other, and
 * compares: the outcome; for a clean merge the resulting TREE ID, the parents and a clean status;
 * for a conflict the `git ls-files -u` stages, `git status --porcelain` and `git diff` as reported
 * BY GIT on the engine-written repository, every work-tree file byte for byte, and MERGE_HEAD /
 * ORIG_HEAD / MERGE_MSG. git must also be able to `merge --abort` the state the engine left.
 * Each scenario is its own test, so the JUnit report shows exactly how many ran versus were
 * skipped (the whole class is skipped by assumption when git is missing).
 */
@RunWith(Parameterized::class)
class MergeInteropTest(private val s: Scn) {
    sealed class Op {
        class W(val path: String, val text: String, val exec: Boolean = false) : Op()
        class Bin(val path: String, val bytes: ByteArray) : Op()
        class Rm(val path: String) : Op()
        class Mv(val from: String, val to: String, val newText: String? = null) : Op()
        class Chmod(val path: String, val exec: Boolean) : Op()
        class Sym(val path: String, val target: String) : Op()
    }

    class Scn(
        val name: String,
        val base: List<Op> = emptyList(),
        val ours: List<Op> = emptyList(),
        val theirs: List<Op> = emptyList(),
        val custom: ((File) -> Unit)? = null,
        val mergeArgs: List<String> = emptyList(),
        val options: MergeOptions = MergeOptions(),
        val gitConfig: Map<String, String> = emptyMap(),
    ) { override fun toString() = name }

    @Before fun requireGit() = assumeTrue("git CLI not available", GitTestSupport.gitAvailable())

    companion object {
        private fun W(p: String, t: String, exec: Boolean = false) = Op.W(p, t, exec)
        private val ten = (1..10).joinToString("") { "line $it\n" }
        private val tenPlus = (1..10).joinToString("") { "line $it\n" } + "extra\n"
        private val base9 = "a\nb\nc\nd\ne\nf\ng\nh\ni\n"

        private fun crissCross(withConflict: Boolean): (File) -> Unit = { d ->
            fun c(m: String) { git(d, "add", "-A"); git(d, "commit", "-q", "-m", m, "--allow-empty") }
            File(d, "f").writeText(base9); File(d, "k").writeText("k\n"); c("base")
            git(d, "branch", "theirs")
            File(d, "a1").writeText("a1\n"); c("a1"); val a1 = git(d, "rev-parse", "HEAD").trim()
            git(d, "checkout", "-q", "theirs")
            File(d, "b1").writeText("b1\n"); c("b1"); val b1 = git(d, "rev-parse", "HEAD").trim()
            git(d, "merge", "-q", "--no-ff", "-m", "M2", a1)
            git(d, "checkout", "-q", "main")
            git(d, "merge", "-q", "--no-ff", "-m", "M1", b1)
            File(d, "f").writeText(base9.replace("a\n", "A\n")); if (withConflict) File(d, "k").writeText("main k\n"); c("main edit")
            git(d, "checkout", "-q", "theirs")
            File(d, "f").writeText(base9.replace("i\n", "I\n")); if (withConflict) File(d, "k").writeText("theirs k\n"); c("theirs edit")
            git(d, "checkout", "-q", "main")
        }

        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun scenarios(): List<Array<Any>> = listOf(
            // ---------------- clean merges
            Scn("clean: disjoint edits in one file", listOf(W("f", base9)), listOf(W("f", base9.replace("a\n", "A\n"))), listOf(W("f", base9.replace("i\n", "I\n")))),
            Scn("clean: both add different files", listOf(W("k", "k\n")), listOf(W("a", "a\n")), listOf(W("b", "b\n"))),
            Scn("clean: identical edit on both sides", listOf(W("f", "1\n2\n")), listOf(W("f", "1\nX\n")), listOf(W("f", "1\nX\n"))),
            Scn("clean: delete on one side", listOf(W("f", "1\n"), W("g", "g\n")), listOf(W("g", "g2\n")), listOf(Op.Rm("f"))),
            Scn("clean: delete on both sides", listOf(W("f", "1\n"), W("k", "k\n")), listOf(Op.Rm("f")), listOf(Op.Rm("f"))),
            Scn("clean: add identical file on both sides", listOf(W("k", "k\n")), listOf(W("n", "same\n")), listOf(W("n", "same\n"))),
            Scn("clean: exec bit on theirs only", listOf(W("s.sh", "echo\n")), listOf(W("o", "o\n")), listOf(Op.Chmod("s.sh", true))),
            Scn("clean: exec bit on both sides", listOf(W("s.sh", "echo\n")), listOf(Op.Chmod("s.sh", true)), listOf(Op.Chmod("s.sh", true))),
            Scn("clean: exec on one side content on the other", listOf(W("s.sh", "echo 1\n")), listOf(W("s.sh", "echo 2\n")), listOf(Op.Chmod("s.sh", true))),
            Scn("clean: exact rename on ours edit on theirs", listOf(W("old.txt", ten)), listOf(Op.Mv("old.txt", "new.txt")), listOf(W("old.txt", ten.replace("line 5", "FIVE")))),
            Scn("clean: exact rename on theirs edit on ours", listOf(W("old.txt", ten)), listOf(W("old.txt", ten.replace("line 5", "FIVE"))), listOf(Op.Mv("old.txt", "new.txt"))),
            Scn("clean: inexact rename 80 percent with edit on the other side", listOf(W("old.txt", ten)), listOf(Op.Mv("old.txt", "new.txt", ten.replace("line 9", "nine").replace("line 10", "ten"))), listOf(W("old.txt", ten.replace("line 2", "TWO")))),
            Scn("clean: move into a subdirectory", listOf(W("a.txt", ten), W("k", "k\n")), listOf(Op.Mv("a.txt", "sub/dir/a.txt")), listOf(W("a.txt", ten.replace("line 1\n", "ONE\n")))),
            Scn("clean: both rename to the same name with edits", listOf(W("old.txt", ten)), listOf(Op.Mv("old.txt", "n.txt", ten.replace("line 1\n", "ONE\n"))), listOf(Op.Mv("old.txt", "n.txt", ten.replace("line 10", "TEN")))),
            Scn("clean: edits in nested directories", listOf(W("a/b/c.txt", "1\n2\n3\n4\n5\n6\n7\n"), W("a/d.txt", "d\n")), listOf(W("a/b/c.txt", "ONE\n2\n3\n4\n5\n6\n7\n")), listOf(W("a/d.txt", "D\n"))),
            Scn("clean: binary changed on one side only", listOf(Op.Bin("bin", byteArrayOf(0, 1, 2))), listOf(Op.Bin("bin", byteArrayOf(0, 1, 3))), listOf(W("k", "k\n"))),
            Scn("clean: symlink added on one side", listOf(W("t", "t\n")), listOf(Op.Sym("link", "t")), listOf(W("u", "u\n"))),
            Scn("clean: symlink retargeted on one side", listOf(W("t", "t\n"), Op.Sym("link", "t")), listOf(W("o", "o\n")), listOf(Op.Sym("link", "elsewhere"))),
            Scn("clean: empty file added", listOf(W("k", "k\n")), listOf(W("empty", "")), listOf(W("b", "b\n"))),
            Scn("clean: file emptied on one side", listOf(W("f", "1\n2\n")), listOf(W("f", "")), listOf(W("g", "g\n"))),
            Scn("clean: whole directory added on each side", listOf(W("k", "k\n")), listOf(W("x/1", "1\n"), W("x/2", "2\n")), listOf(W("y/1", "1\n"), W("y/2", "2\n"))),
            Scn("clean: unicode file name", listOf(W("k", "k\n")), listOf(W("café.txt", "ours\n")), listOf(W("naïve.txt", "theirs\n"))),
            Scn("clean: crlf file edited far apart", listOf(W("f", base9.replace("\n", "\r\n"))), listOf(W("f", base9.replace("a\n", "A\n").replace("\n", "\r\n"))), listOf(W("f", base9.replace("i\n", "I\n").replace("\n", "\r\n")))),
            // ---------------- fast forward / up to date
            Scn("fast-forward", listOf(W("k", "k\n")), emptyList(), listOf(W("b", "b\n"))),
            Scn("up to date", listOf(W("k", "k\n")), listOf(W("o", "o\n")), emptyList()),
            Scn("no-ff merge of a fast-forwardable branch", listOf(W("k", "k\n")), emptyList(), listOf(W("b", "b\n")), mergeArgs = listOf("--no-ff"), options = MergeOptions(fastForward = FastForward.NEVER)),
            // ---------------- conflicts
            Scn("conflict: same line edited differently", listOf(W("f", "a\nb\nc\n")), listOf(W("f", "a\nX\nc\n")), listOf(W("f", "a\nY\nc\n"))),
            Scn("conflict: two regions in one file", listOf(W("f", "1\n2\n3\n4\n5\n6\n7\n8\n9\n")), listOf(W("f", "1\nO\n3\n4\n5\n6\n7\nO8\n9\n")), listOf(W("f", "1\nT\n3\n4\n5\n6\n7\nT8\n9\n"))),
            Scn("conflict: ours modifies theirs deletes", listOf(W("f", "a\nb\nc\n"), W("k", "k\n")), listOf(W("f", "a\nB\nc\n")), listOf(Op.Rm("f"))),
            Scn("conflict: ours deletes theirs modifies", listOf(W("f", "a\nb\nc\n"), W("k", "k\n")), listOf(Op.Rm("f")), listOf(W("f", "a\nB\nc\n"))),
            Scn("conflict: add add with different content", listOf(W("k", "k\n")), listOf(W("n", "ours\n")), listOf(W("n", "theirs\n"))),
            Scn("conflict: add add multi line similar", listOf(W("k", "k\n")), listOf(W("n", "1\n2\n3\n")), listOf(W("n", "1\nTWO\n3\n"))),
            Scn("conflict: binary changed on both sides", listOf(Op.Bin("bin", byteArrayOf(0, 1, 2))), listOf(Op.Bin("bin", byteArrayOf(0, 1, 3))), listOf(Op.Bin("bin", byteArrayOf(0, 1, 4)))),
            Scn("conflict: rename on theirs delete on ours", listOf(W("old.txt", ten), W("k", "k\n")), listOf(Op.Rm("old.txt")), listOf(Op.Mv("old.txt", "new.txt"))),
            Scn("conflict: rename on ours delete on theirs", listOf(W("old.txt", ten), W("k", "k\n")), listOf(Op.Mv("old.txt", "new.txt")), listOf(Op.Rm("old.txt"))),
            Scn("conflict: rename to two different names", listOf(W("old.txt", ten), W("k", "k\n")), listOf(Op.Mv("old.txt", "g.txt")), listOf(Op.Mv("old.txt", "h.txt"))),
            Scn("conflict: rename with conflicting edits at the new name", listOf(W("old.txt", ten)), listOf(Op.Mv("old.txt", "n.txt", ten.replace("line 3", "OURS"))), listOf(Op.Mv("old.txt", "n.txt", ten.replace("line 3", "THEIRS")))),
            Scn("conflict: file on ours directory on theirs", listOf(W("k", "k\n")), listOf(W("a", "file\n")), listOf(W("a/b", "dir\n"))),
            Scn("conflict: directory on ours file on theirs", listOf(W("k", "k\n")), listOf(W("a/b", "dir\n")), listOf(W("a", "file\n"))),
            Scn("conflict: conflicts in three files with clean files between", listOf(W("a", "1\n2\n3\n"), W("b", "x\n"), W("c", "1\n2\n3\n"), W("d", "1\n2\n3\n")), listOf(W("a", "1\nO\n3\n"), W("b", "x2\n"), W("c", "1\nO\n3\n"), W("d", "1\nO\n3\n")), listOf(W("a", "1\nT\n3\n"), W("c", "1\nT\n3\n"), W("d", "1\nT\n3\n"), W("e", "e\n"))),
            Scn("conflict: crlf file", listOf(W("f", "a\r\nb\r\nc\r\n")), listOf(W("f", "a\r\nX\r\nc\r\n")), listOf(W("f", "a\r\nY\r\nc\r\n"))),
            Scn("conflict: one side empties the file", listOf(W("f", "a\nb\nc\n")), listOf(W("f", "")), listOf(W("f", "a\nB\nc\n"))),
            Scn("conflict: nested path", listOf(W("p/q/f", "a\nb\nc\n")), listOf(W("p/q/f", "a\nX\nc\n")), listOf(W("p/q/f", "a\nY\nc\n"))),
            Scn("conflict: delete modify in a subdirectory with siblings", listOf(W("d/f", "1\n2\n"), W("d/g", "g\n")), listOf(W("d/f", "1\nTWO\n")), listOf(Op.Rm("d/f"))),
            Scn("conflict: unicode path", listOf(W("café.txt", "a\nb\nc\n")), listOf(W("café.txt", "a\nX\nc\n")), listOf(W("café.txt", "a\nY\nc\n"))),
            Scn("conflict: missing final newline on both", listOf(W("f", "a\nb")), listOf(W("f", "a\nX")), listOf(W("f", "a\nY"))),
            Scn("conflict: adjacent line edits", listOf(W("f", "a\nb\nc\nd\n")), listOf(W("f", "a\nB\nc\nd\n")), listOf(W("f", "a\nb\nC\nd\n"))),
            // ---------------- criss-cross
            Scn("diff3 style: content conflict shows the base abbreviation", listOf(W("f", "a\nb\nc\n")), listOf(W("f", "a\nX\nc\n")), listOf(W("f", "a\nY\nc\n")), gitConfig = mapOf("merge.conflictStyle" to "diff3")),
            Scn("zdiff3 style: shared lines are moved out", listOf(W("f", "a\nb\nc\n")), listOf(W("f", "a\nq\nX\nr\nc\n")), listOf(W("f", "a\nq\nY\nr\nc\n")), gitConfig = mapOf("merge.conflictStyle" to "zdiff3")),
            Scn("diff3 style: criss-cross labels the base as merged common ancestors", custom = crissCross(true), gitConfig = mapOf("merge.conflictStyle" to "diff3")),
            Scn("criss-cross: clean via virtual base", custom = crissCross(false)),
            Scn("criss-cross: with a conflict on top", custom = crissCross(true)),
        ).map { arrayOf<Any>(it) }
    }

    // ------------------------------------------------------------------ harness

    private fun build(): File {
        val d = GitTestSupport.tempDir("g2merge")
        git(d, "init", "-q", "-b", "main")
        git(d, "config", "user.name", "Eng Test"); git(d, "config", "user.email", "eng@test")
        git(d, "config", "core.autocrlf", "false")
        for ((k, v) in s.gitConfig) git(d, "config", k, v)
        if (s.custom != null) { s.custom.invoke(d); return d }
        fun apply(ops: List<Op>) {
            for (op in ops) when (op) {
                is Op.W -> { val f = File(d, op.path); f.parentFile.mkdirs(); f.writeBytes(op.text.toByteArray()); f.setExecutable(op.exec) }
                is Op.Bin -> { val f = File(d, op.path); f.parentFile.mkdirs(); f.writeBytes(op.bytes) }
                is Op.Rm -> File(d, op.path).delete()
                is Op.Mv -> { File(d, op.to).parentFile.mkdirs(); git(d, "mv", op.from, op.to); op.newText?.let { File(d, op.to).writeBytes(it.toByteArray()) } }
                is Op.Chmod -> File(d, op.path).setExecutable(op.exec)
                is Op.Sym -> { val p = File(d, op.path).toPath(); Files.deleteIfExists(p); Files.createSymbolicLink(p, Paths.get(op.target)) }
            }
            git(d, "add", "-A")
        }
        fun commit(m: String) = git(d, "commit", "-q", "-m", m, "--allow-empty")
        apply(s.base); commit("base")
        git(d, "branch", "theirs")
        if (s.ours.isNotEmpty()) { apply(s.ours); commit("ours") }
        git(d, "checkout", "-q", "theirs")
        if (s.theirs.isNotEmpty()) { apply(s.theirs); commit("theirs") }
        git(d, "checkout", "-q", "main")
        return d
    }

    private fun copy(src: File): File {
        val dst = GitTestSupport.tempDir("g2copy")
        ProcessBuilder("cp", "-a", "${src.absolutePath}/.", dst.absolutePath).redirectErrorStream(true).start().let { it.inputStream.readBytes(); check(it.waitFor() == 0) }
        return dst
    }

    private fun workTree(d: File): Map<String, String> {
        val out = sortedMapOf<String, String>()
        d.walkTopDown().onEnter { it.name != ".git" }.forEach { f ->
            val rel = f.relativeTo(d).path
            if (Files.isSymbolicLink(f.toPath())) out[rel] = "-> " + Files.readSymbolicLink(f.toPath())
            else if (f.isFile) out[rel] = String(f.readBytes(), Charsets.ISO_8859_1) + if (f.canExecute()) " [x]" else ""
        }
        return out
    }

    private fun lsUnmerged(d: File) = git(d, "ls-files", "-u").lines().filter { it.isNotBlank() }
    private fun statusOf(d: File) = git(d, "status", "--porcelain").lines().filter { it.isNotBlank() }

    @Test fun `engine and git agree`() {
        val src = build()
        val gitDir = copy(src); val engDir = copy(src)
        val mergeOut = git(gitDir, "merge", "theirs", "--no-edit", *s.mergeArgs.toTypedArray(), allowFail = true)
        val gitInProgress = File(gitDir, ".git/MERGE_HEAD").exists()
        val gitKind = when {
            gitInProgress -> MergeOutcomeKind.CONFLICTED
            mergeOut.contains("Already up to date") -> MergeOutcomeKind.UP_TO_DATE
            mergeOut.contains("Fast-forward") -> MergeOutcomeKind.FAST_FORWARD
            else -> MergeOutcomeKind.CLEAN
        }
        val repo = Repository.open(JvmFileSystem(), engDir.absolutePath, clock = { 1_700_000_000L })
        val out = repo.merge("theirs", s.options)
        if (System.getenv("MERGE_DEBUG") != null) println("SCN ${s.name} -> git=$gitKind engine=${out.kind} unmerged=${lsUnmerged(gitDir).size}")
        assertEquals("outcome kind\n--- git said:\n$mergeOut", gitKind, out.kind)

        // whatever the outcome, git must find the engine's repository sound
        val fsck = git(engDir, "fsck", "--strict", "--no-dangling", allowFail = true)
        assertTrue("fsck: $fsck", !fsck.contains("error") && !fsck.contains("missing") && !fsck.contains("broken"))

        when (gitKind) {
            MergeOutcomeKind.UP_TO_DATE, MergeOutcomeKind.FAST_FORWARD ->
                assertEquals("HEAD", git(gitDir, "rev-parse", "HEAD").trim(), git(engDir, "rev-parse", "HEAD").trim())
            MergeOutcomeKind.CLEAN -> {
                assertEquals("merged tree id", git(gitDir, "rev-parse", "HEAD^{tree}").trim(), git(engDir, "rev-parse", "HEAD^{tree}").trim())
                assertEquals("parent count", git(gitDir, "rev-list", "--parents", "-n1", "HEAD").trim().split(' ').size, git(engDir, "rev-list", "--parents", "-n1", "HEAD").trim().split(' ').size)
                assertEquals("git's own view of the engine's result", emptyList<String>(), statusOf(engDir))
                assertEquals("work tree files", workTree(gitDir), workTree(engDir))
            }
            MergeOutcomeKind.CONFLICTED -> {
                assertEquals("git ls-files -u", lsUnmerged(gitDir), lsUnmerged(engDir))
                assertEquals("git status --porcelain", statusOf(gitDir), statusOf(engDir))
                assertEquals("git diff (combined)", git(gitDir, "diff"), git(engDir, "diff"))
                assertEquals("work tree files", workTree(gitDir), workTree(engDir))
                for (f in listOf("MERGE_HEAD", "ORIG_HEAD")) assertEquals(f, File(gitDir, ".git/$f").readText(), File(engDir, ".git/$f").readText())
                assertEquals("MERGE_MSG", File(gitDir, ".git/MERGE_MSG").readText(), File(engDir, ".git/MERGE_MSG").readText())
                // git can take the engine's conflicted state apart again, back to the pre-merge tree
                val before = workTree(src)
                git(engDir, "merge", "--abort")
                assertEquals("after git merge --abort on the engine's state", emptyList<String>(), statusOf(engDir))
                assertEquals(before, workTree(engDir))
            }
        }
    }
}
