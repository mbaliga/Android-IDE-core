package dev.fonebrew.domain.gitcore.textmerge

import dev.fonebrew.domain.gitcore.GitTestSupport
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Text-merge interop proof: a table of scenarios, each merged by this engine and by
 * `git merge-file -p` (labels ours/base/theirs) in all three marker styles. The merged bytes must
 * be identical and the conflict count must equal git's exit status. Skipped by assumption when
 * git is not installed; the JUnit report counts the executed versus skipped tests.
 */
class TextMergeInteropTest {
    @Before fun requireGit() = assumeTrue("git CLI not available", GitTestSupport.gitAvailable())

    private class S(val name: String, val base: String, val ours: String, val theirs: String)

    private val scenarios: List<S> = listOf(
        S("clean: edits far apart", "1\n2\n3\n4\n5\n6\n7\n8\n", "1\nA\n3\n4\n5\n6\n7\n8\n", "1\n2\n3\n4\n5\n6\nG\n8\n"),
        S("clean: only ours changes", "a\nb\nc\n", "a\nB\nc\n", "a\nb\nc\n"),
        S("clean: only theirs changes", "a\nb\nc\n", "a\nb\nc\n", "a\nb\nC\n"),
        S("clean: identical edit", "a\nb\nc\n", "a\nX\nc\n", "a\nX\nc\n"),
        S("clean: identical delete", "a\nb\nc\n", "a\nc\n", "a\nc\n"),
        S("clean: insert at top and bottom", "m\n", "top\nm\n", "m\nbottom\n"),
        S("clean: both sides unchanged", "same\n", "same\n", "same\n"),
        S("clean: empty everything", "", "", ""),
        S("clean: add to empty on one side", "", "new\n", ""),
        S("clean: delete everything on one side", "a\nb\n", "", "a\nb\n"),
        S("conflict: same line edited differently", "a\nb\nc\n", "a\nX\nc\n", "a\nY\nc\n"),
        S("conflict: adjacent lines", "a\nb\nc\nd\n", "a\nB\nc\nd\n", "a\nb\nC\nd\n"),
        S("conflict: delete versus modify", "a\nb\nc\n", "a\nc\n", "a\nB\nc\n"),
        S("conflict: modify versus delete", "a\nb\nc\n", "a\nB\nc\n", "a\nc\n"),
        S("conflict: both insert at the same point", "a\nb\n", "a\nX\nb\n", "a\nY\nb\n"),
        S("conflict: add/add with empty base", "", "x\n", "y\n"),
        S("conflict: add/add multi line", "", "x\n1\n", "y\n1\n"),
        S("conflict: ours deleted everything, theirs edited", "a\nb\n", "", "a\nB\n"),
        S("conflict: two separate conflicts far apart", "a\nb\nc\nc2\nc3\nc4\nd\ne\n", "a\nB\nc\nc2\nc3\nc4\nD\ne\n", "a\nBB\nc\nc2\nc3\nc4\nDD\ne\n"),
        S("conflict: two conflicts one common line apart", "a\nb\nc\nd\ne\n", "a\nB\nc\nD\ne\n", "a\nBB\nc\nDD\ne\n"),
        S("conflict: common run of three joins", "a\nb\nc\n", "a\nX\nm1\nm2\nm3\nY\nc\n", "a\nZ\nm1\nm2\nm3\nW\nc\n"),
        S("conflict: common run of four splits", "a\nb\nc\n", "a\nX\nm1\nm2\nm3\nm4\nY\nc\n", "a\nZ\nm1\nm2\nm3\nm4\nW\nc\n"),
        S("conflict: braces only run joins", "a\nb\nc\n", "a\nX\n}\n}\n}\n}\n}\nY\nc\n", "a\nZ\n}\n}\n}\n}\n}\nW\nc\n"),
        S("conflict: shared prefix and suffix inside", "a\nb\nc\n", "a\nq\nX\nr\nc\n", "a\nq\nY\nr\nc\n"),
        S("conflict: one side empties the region", "a\nb\nc\n", "a\nc\n", "a\nb2\nc\n"),
        S("conflict: whole file replaced differently", "1\n2\n3\n", "a\nb\n", "x\ny\nz\n"),
        S("conflict: clean change between two conflicts", "a\nb\nm\nc\nd\ne\n", "a\nB\nm\nc\nD\ne\n", "a\nBB\nm\nc\nDD\ne\n"),
        S("conflict: one side also edits elsewhere", "a\nb\nc\nd\ne\nf\ng\nh\n", "a\nB\nc\nd\ne\nf\nG\nh\n", "a\nBB\nc\nd\ne\nf\ng\nh\n"),
        S("eol: crlf conflict", "a\r\nb\r\n", "a\r\nX\r\n", "a\r\nY\r\n"),
        S("eol: crlf clean", "a\r\nb\r\nc\r\n", "A\r\nb\r\nc\r\n", "a\r\nb\r\nC\r\n"),
        S("eol: ours crlf theirs lf", "a\r\nb\r\n", "a\r\nX\r\n", "a\r\nY\n"),
        S("eol: ours lf theirs crlf", "a\nb\n", "a\nX\n", "a\r\nY\r\n"),
        S("eol: crlf conflict at file start", "b\r\nc\r\n", "c\r\n", "B\r\nc\r\n"),
        S("eol: ours missing final newline", "a\nb\n", "a\nX", "a\nY\n"),
        S("eol: both missing final newline", "a\nb", "a\nX", "a\nY"),
        S("eol: theirs missing final newline only", "a\nb\n", "a\nX\n", "a\nY"),
        S("eol: clean merge keeps missing final newline", "a\nb", "a\nB", "a\nb"),
        S("eol: newline added at end by one side", "a\nb", "a\nb\n", "a\nb"),
        S("eol: crlf conflict without final newline", "a\r\nb", "a\r\nX", "a\r\nY"),
        S("eol: empty theirs with crlf ours", "a\r\nb\r\n", "X\r\n", ""),
        S("shape: duplicate lines shifted", "a\nb\nb\nc\n", "a\nb\nc\n", "a\nb\nb\nb\nc\n"),
        S("shape: reorder on one side", "a\nb\nc\nd\n", "a\nc\nb\nd\n", "a\nb\nc\nd\nE\n"),
        S("shape: function moved and edited", "f1\nf2\nf3\n\ng1\ng2\n", "g1\ng2\n\nf1\nf2\nf3\n", "f1\nF2\nf3\n\ng1\ng2\n"),
        S("shape: blank line churn", "a\n\n\nb\n", "a\n\nb\n", "a\n\n\n\nb\n"),
        S("shape: unicode text", "cafÃ©\nnaÃ¯ve\n", "cafÃ©\nNAÃ¯VE\n", "CAFÃ©\nnaÃ¯ve\n"),
        S("shape: long file two distant edits", (1..200).joinToString("") { "line $it\n" }, (1..200).joinToString("") { if (it == 10) "TEN\n" else "line $it\n" }, (1..200).joinToString("") { if (it == 190) "NINETY\n" else "line $it\n" }),
        S("shape: long file same line conflict", (1..200).joinToString("") { "line $it\n" }, (1..200).joinToString("") { if (it == 100) "OURS\n" else "line $it\n" }, (1..200).joinToString("") { if (it == 100) "THEIRS\n" else "line $it\n" }),
    )

    private fun gitMerge(s: S, style: String, dir: File): Pair<String, Int> {
        File(dir, "base").writeBytes(s.base.toByteArray(Charsets.ISO_8859_1))
        File(dir, "ours").writeBytes(s.ours.toByteArray(Charsets.ISO_8859_1))
        File(dir, "theirs").writeBytes(s.theirs.toByteArray(Charsets.ISO_8859_1))
        val cmd = mutableListOf("git", "merge-file", "-p", "-L", "ours", "-L", "base", "-L", "theirs")
        if (style.isNotEmpty()) cmd.add(style)
        cmd.addAll(listOf("ours", "base", "theirs"))
        val pb = ProcessBuilder(cmd).directory(dir)
        pb.environment()["GIT_CONFIG_NOSYSTEM"] = "1"; pb.environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
        val p = pb.start()
        val out = p.inputStream.readBytes()
        val rc = p.waitFor()
        return String(out, Charsets.ISO_8859_1) to rc
    }

    @Test fun `at least forty scenarios and every one agrees with git merge-file in all three styles`() {
        check(scenarios.size >= 40) { "scenario table too small: ${scenarios.size}" }
        val dir = GitTestSupport.tempDir("mfinterop")
        var compared = 0
        for (s in scenarios) {
            for ((flag, style) in listOf("" to MarkerStyle.MERGE, "--diff3" to MarkerStyle.DIFF3, "--zdiff3" to MarkerStyle.ZDIFF3)) {
                val (gitOut, rc) = gitMerge(s, flag, dir)
                val mine = TextMerge.merge(
                    s.base.toByteArray(Charsets.ISO_8859_1), s.ours.toByteArray(Charsets.ISO_8859_1), s.theirs.toByteArray(Charsets.ISO_8859_1),
                    TextMergeOptions(style),
                )
                assertEquals("[${s.name} / ${flag.ifEmpty { "merge" }}] merged bytes", gitOut, String(mine.render(), Charsets.ISO_8859_1))
                assertEquals("[${s.name} / ${flag.ifEmpty { "merge" }}] conflict count", rc, mine.conflictCount)
                compared++
            }
        }
        assertEquals(scenarios.size * 3, compared)
    }

    @Test fun `randomised inputs agree with git merge-file except where several minimal diffs tie`() {
        // A seeded fuzz over small inputs. Ties between equally minimal diffs can still place a hunk
        // differently than git on duplicate-heavy inputs, so this test bounds the disagreement rate
        // instead of claiming zero; the table above is the exact-equality proof.
        val dir = GitTestSupport.tempDir("mffuzz")
        val rnd = java.util.Random(20260926)
        var total = 0; var differ = 0
        repeat(120) {
            val base = List(3 + rnd.nextInt(8)) { "l${rnd.nextInt(30)}\n" }
            fun mut(src: List<String>): List<String> {
                val o = src.toMutableList()
                repeat(1 + rnd.nextInt(3)) {
                    when (rnd.nextInt(3)) {
                        0 -> if (o.isNotEmpty()) o[rnd.nextInt(o.size)] = "m${rnd.nextInt(30)}\n"
                        1 -> o.add(rnd.nextInt(o.size + 1), "n${rnd.nextInt(30)}\n")
                        else -> if (o.isNotEmpty()) o.removeAt(rnd.nextInt(o.size))
                    }
                }
                return o
            }
            val s = S("fuzz", base.joinToString(""), mut(base).joinToString(""), mut(base).joinToString(""))
            for ((flag, style) in listOf("" to MarkerStyle.MERGE, "--diff3" to MarkerStyle.DIFF3, "--zdiff3" to MarkerStyle.ZDIFF3)) {
                val (g, _) = gitMerge(s, flag, dir)
                val mine = String(TextMerge.merge(s.base.toByteArray(), s.ours.toByteArray(), s.theirs.toByteArray(), TextMergeOptions(style)).render())
                total++; if (g != mine) differ++
            }
        }
        check(differ * 100 <= total) { "fuzz disagreement $differ of $total exceeds 1 percent" }
    }
}
