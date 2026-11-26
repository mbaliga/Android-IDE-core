package dev.fonebrew.domain.workspace.ignore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.TimeUnit

/**
 * INTEROP PROOF: [IgnoreMatcher] must agree with the real `git check-ignore` on every path of
 * randomised trees with randomised nested `.gitignore` files. Skipped by assumption when no git
 * CLI is installed; when it runs, the number of trees / paths compared is printed and written to
 * `build/ignore-interop-count.txt` so a verifier can see it was not vacuous.
 */
class GitCheckIgnoreInteropTest {

    private val gitEnv = mapOf(
        "GIT_CONFIG_GLOBAL" to "/dev/null",
        "GIT_CONFIG_NOSYSTEM" to "1",
        "GIT_TERMINAL_PROMPT" to "0",
    )

    private fun git(dir: File, stdin: ByteArray?, vararg args: String): Pair<Int, ByteArray> {
        val pb = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(false)
        pb.environment().putAll(gitEnv)
        pb.environment()["HOME"] = dir.absolutePath
        val p = pb.start()
        if (stdin != null) p.outputStream.use { it.write(stdin) } else p.outputStream.close()
        val out = p.inputStream.readBytes()
        p.errorStream.readBytes()
        if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); error("git timed out") }
        return p.exitValue() to out
    }

    private fun gitAvailable(): Boolean = try {
        val p = ProcessBuilder("git", "--version").redirectErrorStream(true).start()
        p.inputStream.readBytes(); p.waitFor() == 0
    } catch (_: Exception) { false }

    private val dirNames = listOf("a", "b", "ab", "sub", "sp y", "build")
    private val fileNames = listOf("a2", "b2", "ab.txt", "c.log", "d.tmp", "keep", "x y", "A.txt", "#h", "!bang", "a.log", "z")
    private val segs = listOf(
        "a", "b", "ab", "sub", "*", "?", "**", "*.log", "*.txt", "a*", "*b", "[ab]", "[!a]", "[a-b]",
        "c.log", "keep", "x\\ y", "\\#h", "\\!bang", "build", "*.tm?", "[[:alpha:]]", "z",
    )

    private fun randomLine(r: Random): String {
        val roll = r.nextInt(20)
        if (roll == 0) return ""
        if (roll == 1) return "# comment"
        if (roll == 2) return "keep   "          // trailing spaces dropped
        if (roll == 3) return "x\\ y\\ "          // escaped trailing space kept
        val sb = StringBuilder()
        if (r.nextInt(4) == 0) sb.append('!')
        if (r.nextInt(4) == 0) sb.append('/')
        val n = 1 + r.nextInt(3)
        for (i in 0 until n) { if (i > 0) sb.append('/'); sb.append(segs[r.nextInt(segs.size)]) }
        if (r.nextInt(4) == 0) sb.append('/')
        return sb.toString()
    }

    private fun buildTree(root: File, r: Random): List<Pair<String, Boolean>> {
        val all = ArrayList<Pair<String, Boolean>>()
        fun fill(dir: File, rel: String, depth: Int) {
            val files = fileNames.filter { r.nextInt(3) == 0 }
            for (f in files) { File(dir, f).writeText("x"); all.add((if (rel.isEmpty()) f else "$rel/$f") to false) }
            if (depth < 3) for (d in dirNames) {
                if (r.nextInt(3) != 0) continue
                val sub = File(dir, d); sub.mkdirs()
                val subRel = if (rel.isEmpty()) d else "$rel/$d"
                all.add(subRel to true)
                fill(sub, subRel, depth + 1)
            }
        }
        fill(root, "", 0)
        // ignore files in the root and in a few random existing directories
        val dirs = listOf("") + all.filter { it.second }.map { it.first }.filter { r.nextInt(2) == 0 }
        for (d in dirs) {
            val lines = (0 until 1 + r.nextInt(6)).joinToString("\n") { randomLine(r) } + "\n"
            File(File(root, d), ".gitignore").writeText(lines)
        }
        return all
    }

    @Test
    fun `matcher agrees with git check-ignore on randomised trees`() {
        assumeTrue("git CLI not installed", gitAvailable())
        var trees = 0
        var compared = 0
        var ignoredCount = 0
        val mismatches = ArrayList<String>()
        val base = Files.createTempDirectory("ignore-interop").toFile()
        for (seed in 1..120) {
            val root = File(base, "t$seed").also { it.mkdirs() }
            val (rc, _) = git(root, null, "init", "-q")
            assertEquals(0, rc)
            val paths = buildTree(root, Random(seed.toLong()))
            if (paths.isEmpty()) continue
            val stdin = paths.joinToString("") { it.first + "\u0000" }.toByteArray()
            val (_, out) = git(root, stdin, "check-ignore", "-z", "--stdin", "--no-index")
            val gitIgnored = String(out).split('\u0000').filter { it.isNotEmpty() }.toSet()
            val matcher = IgnoreMatcher(IgnoreSource { dir ->
                val f = File(File(root, dir), ".gitignore")
                if (f.isFile) f.readText() else null
            })
            for ((p, isDir) in paths) {
                val mine = matcher.isIgnored(p, isDir)
                val theirs = p in gitIgnored
                compared++
                if (theirs) ignoredCount++
                if (mine != theirs) {
                    val d = matcher.explain(p, isDir)
                    mismatches.add("seed=$seed path=$p dir=$isDir git=$theirs mine=$mine why=$d\n" +
                        root.walkTopDown().filter { it.name == ".gitignore" }.joinToString("\n") {
                            "  [" + it.relativeTo(root).path + "]\n" + it.readText().prependIndent("    ")
                        })
                }
            }
            trees++
        }
        val summary = "trees=$trees pathsCompared=$compared gitIgnored=$ignoredCount mismatches=${mismatches.size}"
        println("IGNORE-INTEROP $summary")
        File("build").mkdirs()
        File("build/ignore-interop-count.txt").writeText(summary + "\n")
        base.deleteRecursively()
        assertTrue("interop never ran", compared > 1000 && ignoredCount > 100)
        assertTrue(mismatches.take(5).joinToString("\n\n"), mismatches.isEmpty())
    }
}
