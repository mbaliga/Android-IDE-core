package dev.fonebrew.domain.gitcore.merge

import dev.fonebrew.domain.gitcore.*
import dev.fonebrew.domain.gitcore.GitTestSupport.git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Random

/**
 * Seeded differential fuzz of the tree merge against git: random small repositories where each side
 * adds, edits, deletes, renames and chmods a few files. For every case the engine and git must agree
 * on the outcome kind, and then on the merged TREE ID (clean) or on `git ls-files -u` and the set of
 * conflicted paths (conflicted). A disagreement fails the test and prints the seed.
 */
class MergeFuzzInteropTest {
    @Before fun requireGit() = assumeTrue("git CLI not available", GitTestSupport.gitAvailable())

    private fun lines(r: Random, n: Int, tag: String) = (1..n).joinToString("") { "$tag line ${it} ${r.nextInt(3)}\n" }

    private fun mutate(d: File, r: Random, files: MutableList<String>, tag: String) {
        repeat(1 + r.nextInt(3)) {
            when (r.nextInt(6)) {
                0 -> if (files.isNotEmpty()) { val f = files[r.nextInt(files.size)]; val l = File(d, f).readText().lines().filter { it.isNotEmpty() }.toMutableList(); if (l.isNotEmpty()) { l[r.nextInt(l.size)] = "$tag edit ${r.nextInt(2)}"; File(d, f).writeText(l.joinToString("\n") + "\n") } }
                1 -> { val f = "n${r.nextInt(4)}.txt"; File(d, f).writeText(lines(r, 3, tag)); if (f !in files) files.add(f) }
                2 -> if (files.size > 1) { val f = files.removeAt(r.nextInt(files.size)); File(d, f).delete() }
                3 -> if (files.isNotEmpty()) { val f = files.removeAt(r.nextInt(files.size)); val to = "d${r.nextInt(2)}/" + f.substringAfterLast('/') + (if (r.nextBoolean()) "" else "x"); File(d, to).parentFile.mkdirs(); File(d, f).renameTo(File(d, to)); files.add(to) }
                4 -> if (files.isNotEmpty()) { val f = File(d, files[r.nextInt(files.size)]); f.setExecutable(!f.canExecute()) }
                else -> if (files.isNotEmpty()) { val f = files[r.nextInt(files.size)]; File(d, f).appendText("$tag tail\n") }
            }
        }
    }

    private fun copy(src: File): File {
        val dst = GitTestSupport.tempDir("g2fz")
        ProcessBuilder("cp", "-a", "${src.absolutePath}/.", dst.absolutePath).redirectErrorStream(true).start().let { it.inputStream.readBytes(); check(it.waitFor() == 0) }
        return dst
    }

    @Test fun `random merges agree with git`() {
        var clean = 0; var conflicted = 0; var other = 0
        val failures = ArrayList<String>()
        val from = System.getenv("FUZZ_FROM")?.toLongOrNull() ?: 1L
        for (seed in from..(from + (System.getenv("FUZZ_SEEDS")?.toLongOrNull() ?: 80L) - 1)) {
            val r = Random(seed)
            val d = GitTestSupport.tempDir("g2fzs")
            git(d, "init", "-q", "-b", "main"); git(d, "config", "user.name", "E"); git(d, "config", "user.email", "e@e")
            val files = ArrayList<String>()
            for (i in 0 until 3 + r.nextInt(3)) { val f = "f$i.txt"; File(d, f).writeText(lines(r, 6 + r.nextInt(6), "b$i")); files.add(f) }
            git(d, "add", "-A"); git(d, "commit", "-q", "-m", "base")
            git(d, "branch", "theirs")
            val of = ArrayList(files); mutate(d, r, of, "ours"); git(d, "add", "-A"); git(d, "commit", "-q", "-m", "ours", "--allow-empty")
            git(d, "checkout", "-q", "theirs")
            val tf = ArrayList(files); mutate(d, r, tf, "theirs"); git(d, "add", "-A"); git(d, "commit", "-q", "-m", "theirs", "--allow-empty")
            git(d, "checkout", "-q", "main")
            val a = copy(d); val b = copy(d)
            val out = git(a, "merge", "theirs", "--no-edit", allowFail = true)
            val gitConflicted = File(a, ".git/MERGE_HEAD").exists()
            val repo = Repository.open(JvmFileSystem(), b.absolutePath, clock = { 1_700_000_000L })
            val res = try { repo.merge("theirs") } catch (e: GitException) { failures.add("seed $seed: engine threw ${e.kind}: ${e.message}"); other++; continue }
            val kind = when { gitConflicted -> MergeOutcomeKind.CONFLICTED; out.contains("Already up to date") -> MergeOutcomeKind.UP_TO_DATE; out.contains("Fast-forward") -> MergeOutcomeKind.FAST_FORWARD; else -> MergeOutcomeKind.CLEAN }
            if (kind != res.kind) { failures.add("seed $seed: git=$kind engine=${res.kind}\n$out"); continue }
            when (kind) {
                MergeOutcomeKind.CLEAN -> { clean++; val ga = git(a, "rev-parse", "HEAD^{tree}").trim(); val gb = git(b, "rev-parse", "HEAD^{tree}").trim(); if (ga != gb) failures.add("seed $seed: clean tree differs git=$ga engine=$gb") }
                MergeOutcomeKind.CONFLICTED -> {
                    conflicted++
                    val ua = git(a, "ls-files", "-u"); val ub = git(b, "ls-files", "-u")
                    if (ua != ub) failures.add("seed $seed: stages differ\n--- git\n$ua--- engine\n$ub")
                    val sa = git(a, "status", "--porcelain"); val sb = git(b, "status", "--porcelain")
                    if (sa != sb) failures.add("seed $seed: status differs\n--- git\n$sa--- engine\n$sb")
                }
                else -> other++
            }
        }
        println("FUZZ clean=$clean conflicted=$conflicted other=$other failures=${failures.size}")
        assertEquals("disagreements with git:\n" + failures.joinToString("\n\n"), emptyList<String>(), failures)
        assertTrue("fuzz produced too few interesting cases", clean + conflicted >= 40)
    }
}
