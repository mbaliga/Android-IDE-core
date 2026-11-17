// SPDX-License-Identifier: Apache-2.0
package dev.fonebrew.apple

import dev.fonebrew.domain.diff.LineDiff
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cross-implementation golden vectors for the Ubuntu Touch web shell (ubuntu-touch/www/linediff.js).
 *
 * The Kotlin [LineDiff] is the truth. This test renders every case below to JSON and requires the checked-in
 * file ubuntu-touch/golden/linediff.v1.json to match it BYTE FOR BYTE; the JS port is then tested against the
 * same file (ubuntu-touch/tests/linediff.test.cjs). To regenerate after an intentional Kotlin change:
 * `UPDATE_GOLDEN=1 ./gradlew :shared:jvmTest --tests '*LineDiffGoldenTest*'` (from apple/).
 */
class LineDiffGoldenTest {

    private class Case(val name: String, val old: String, val new: String, val context: Int)

    private val cases = listOf(
        Case("identical", "a\nb\nc\n", "a\nb\nc\n", 3),
        Case("single-change-ctx1", "a\nb\nc\n", "a\nB\nc\n", 1),
        Case("single-change-ctx0", "1\n2\n3\n4\n5\n", "1\n2\n3x\n4\n5\n", 0),
        Case("single-change-ctx1-mid", "1\n2\n3\n4\n5\n", "1\n2\n3x\n4\n5\n", 1),
        Case("new-file", "", "x\ny\n", 3),
        Case("deleted-file", "x\ny\n", "", 3),
        Case("both-empty", "", "", 3),
        Case(
            "two-far-hunks",
            (1..20).joinToString("\n") { "L$it" } + "\n",
            (1..20).joinToString("\n") { if (it == 2 || it == 18) "L${it}x" else "L$it" } + "\n",
            2,
        ),
        Case(
            "two-near-hunks-merge",
            (1..10).joinToString("\n") { "n$it" } + "\n",
            (1..10).joinToString("\n") { if (it == 3 || it == 6) "n${it}x" else "n$it" } + "\n",
            2,
        ),
        Case("no-trailing-newline", "a\nb", "a\nc", 3),
        Case("trailing-newline-only-difference", "a\nb\n", "a\nb", 3),
        Case("duplicate-lines", "x\nx\nx\ny\n", "x\ny\nx\nx\n", 1),
        Case("insert-in-middle", "a\nb\nd\ne\n", "a\nb\nc\nd\ne\n", 1),
        Case("delete-in-middle", "a\nb\nc\nd\ne\n", "a\nb\nd\ne\n", 1),
        Case("unicode-and-escapes", "café — \"q\"\n\ttab\\slash\n", "café — \"q\"\n\ttab\\slash!\n日本語\n", 3),
        Case("blank-lines", "a\n\nb\n\n\nc\n", "a\n\nB\n\nc\n", 1),
        Case("replace-all", "a\nb\nc\n", "x\ny\nz\n", 3),
    )

    private fun esc(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch.code < 0x20 -> sb.append("\\u%04x".format(ch.code))
                else -> sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }

    private fun render(): String {
        val sb = StringBuilder()
        sb.append("{\n  \"schema\": \"fonebrew.linediff.golden.v1\",\n  \"cases\": [\n")
        cases.forEachIndexed { ci, c ->
            val hunks = LineDiff.diff(c.old, c.new, c.context)
            val stat = LineDiff.stat(c.old, c.new)
            sb.append("    {\n")
            sb.append("      \"name\": ").append(esc(c.name)).append(",\n")
            sb.append("      \"old\": ").append(esc(c.old)).append(",\n")
            sb.append("      \"new\": ").append(esc(c.new)).append(",\n")
            sb.append("      \"context\": ").append(c.context).append(",\n")
            sb.append("      \"stat\": {\"added\": ").append(stat.added).append(", \"removed\": ").append(stat.removed).append("},\n")
            sb.append("      \"unified\": ").append(esc(LineDiff.unified(c.old, c.new, "a/f.txt", "b/f.txt", c.context))).append(",\n")
            sb.append("      \"hunks\": [")
            hunks.forEachIndexed { hi, h ->
                sb.append(if (hi == 0) "\n" else ",\n")
                sb.append("        {\"oldStart\": ${h.oldStart}, \"oldCount\": ${h.oldCount}, \"newStart\": ${h.newStart}, \"newCount\": ${h.newCount}, \"lines\": [")
                sb.append(h.lines.joinToString(", ") { l ->
                    val tag = when (l) {
                        is LineDiff.Line.Context -> " "
                        is LineDiff.Line.Add -> "+"
                        is LineDiff.Line.Remove -> "-"
                    }
                    "[" + esc(tag) + ", " + esc(l.text) + "]"
                })
                sb.append("]}")
            }
            sb.append(if (hunks.isEmpty()) "]\n" else "\n      ]\n")
            sb.append("    }").append(if (ci == cases.size - 1) "\n" else ",\n")
        }
        sb.append("  ]\n}\n")
        return sb.toString()
    }

    @Test
    fun goldenFileMatchesKotlinLineDiffByteForByte() {
        val golden = File("../../ubuntu-touch/golden/linediff.v1.json")
        val actual = render()
        if (System.getenv("UPDATE_GOLDEN") == "1") {
            golden.parentFile.mkdirs()
            golden.writeText(actual, Charsets.UTF_8)
        }
        assertTrue(golden.isFile, "golden file missing: ${golden.canonicalPath} (run with UPDATE_GOLDEN=1)")
        assertEquals(golden.readText(Charsets.UTF_8), actual, "golden vectors are stale; regenerate with UPDATE_GOLDEN=1")
    }
}
