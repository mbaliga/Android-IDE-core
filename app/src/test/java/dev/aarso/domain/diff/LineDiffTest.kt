package dev.aarso.domain.diff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LineDiffTest {

    @Test fun `identical input produces no hunks, empty unified, and a zero stat`() {
        val text = "a\nb\nc\n"
        assertTrue(LineDiff.diff(text, text).isEmpty())
        assertEquals("", LineDiff.unified(text, text))
        val stat = LineDiff.stat(text, text)
        assertEquals(LineDiff.Stat(0, 0), stat)
        assertFalse(stat.changed)
    }

    @Test fun `a single changed line yields one hunk with the exact standard header`() {
        val old = "a\nb\nc\n"
        val new = "a\nB\nc\n"
        assertEquals(
            """
            --- a
            +++ b
            @@ -1,3 +1,3 @@
             a
            -b
            +B
             c

            """.trimIndent(),
            LineDiff.unified(old, new, context = 1),
        )
        assertEquals(LineDiff.Stat(1, 1), LineDiff.stat(old, new))
    }

    @Test fun `context parameter bounds how many unchanged lines surround a change`() {
        val old = "1\n2\n3\n4\n5\n"
        val new = "1\n2\n3x\n4\n5\n"
        // context 0 = just the change
        val c0 = LineDiff.diff(old, new, context = 0).single()
        assertEquals(listOf<LineDiff.Line>(LineDiff.Line.Remove("3"), LineDiff.Line.Add("3x")), c0.lines)
        assertEquals(3, c0.oldStart)
        // context 1 = one line either side
        val c1 = LineDiff.diff(old, new, context = 1).single()
        assertEquals(4, c1.lines.size) // 2,3->3x,4
        assertEquals(2, c1.oldStart)
    }

    @Test fun `two far-apart changes are two hunks while two near ones merge into one`() {
        // build from line lists so a change lands on exactly one line (no substring traps)
        val baseL = (1..20).map { "L$it" }
        val farL = baseL.toMutableList().apply { this[1] = "L2x"; this[17] = "L18x" }
        // lines 2 and 18: 15 unchanged between them > 2*context -> two hunks
        assertEquals(2, LineDiff.diff(baseL.joinToString("\n"), farL.joinToString("\n"), context = 2).size)

        val nearL = (1..10).map { "n$it" }
        val nearEditL = nearL.toMutableList().apply { this[2] = "n3x"; this[5] = "n6x" }
        // lines 3 and 6: only 2 unchanged between them <= 2*context -> one merged hunk
        assertEquals(1, LineDiff.diff(nearL.joinToString("\n"), nearEditL.joinToString("\n"), context = 2).size)
    }

    @Test fun `a brand-new file is a pure insertion against an empty old side`() {
        val u = LineDiff.unified("", "x\ny\n")
        assertTrue(u, u.contains("@@ -0,0 +1,2 @@"))
        assertEquals(LineDiff.Stat(2, 0), LineDiff.stat("", "x\ny\n"))
        val h = LineDiff.diff("", "x\ny\n").single()
        assertEquals(0, h.oldStart)
        assertEquals(0, h.oldCount)
        assertEquals(2, h.newCount)
        assertTrue(h.lines.all { it is LineDiff.Line.Add })
    }

    @Test fun `a deleted file is a pure deletion against an empty new side`() {
        val u = LineDiff.unified("x\ny\n", "")
        assertTrue(u, u.contains("@@ -1,2 +0,0 @@"))
        assertEquals(LineDiff.Stat(0, 2), LineDiff.stat("x\ny\n", ""))
    }

    @Test fun `insert-only and delete-only hunks carry the right counts`() {
        // insert a line between b and c
        val ins = LineDiff.diff("a\nb\nc\n", "a\nb\nNEW\nc\n", context = 0).single()
        assertEquals(listOf<LineDiff.Line>(LineDiff.Line.Add("NEW")), ins.lines)
        assertEquals(0, ins.oldCount)
        assertEquals(1, ins.newCount)
        // delete b
        val del = LineDiff.diff("a\nb\nc\n", "a\nc\n", context = 0).single()
        assertEquals(listOf<LineDiff.Line>(LineDiff.Line.Remove("b")), del.lines)
        assertEquals(1, del.oldCount)
        assertEquals(0, del.newCount)
    }

    @Test fun `trailing newline is not treated as an extra empty line`() {
        // "a" vs "a\n" are the same single line -> no change
        assertTrue(LineDiff.diff("a", "a\n").isEmpty())
        // a genuine blank line at the end IS a change
        assertEquals(LineDiff.Stat(1, 0), LineDiff.stat("a\n", "a\n\n"))
    }

    @Test fun `unified output is well-formed standard diff`() {
        val old = "alpha\nbeta\ngamma\ndelta\n"
        val new = "alpha\nBETA\ngamma\ndelta\nepsilon\n"
        val u = LineDiff.unified(old, new, oldPath = "a/f.txt", newPath = "b/f.txt")
        val lines = u.trimEnd('\n').split('\n')
        assertEquals("--- a/f.txt", lines[0])
        assertEquals("+++ b/f.txt", lines[1])
        // every body line carries a valid prefix
        for (l in lines.drop(2)) {
            assertTrue("bad line: '$l'", l.startsWith("@@") || l.startsWith(" ") || l.startsWith("+") || l.startsWith("-"))
        }
        assertEquals(LineDiff.Stat(2, 1), LineDiff.stat(old, new))
    }

    @Test fun `hunk lines reconstruct both sides of the changed region`() {
        val old = "k1\nk2\nold-a\nold-b\nk3\nk4\n"
        val new = "k1\nk2\nnew-a\nk3\nk4\n"
        val h = LineDiff.diff(old, new, context = 1).single()
        // context + removed == the old slice; context + added == the new slice
        val oldSide = h.lines.filter { it !is LineDiff.Line.Add }.map { it.text }
        val newSide = h.lines.filter { it !is LineDiff.Line.Remove }.map { it.text }
        assertEquals(listOf("k2", "old-a", "old-b", "k3"), oldSide)
        assertEquals(listOf("k2", "new-a", "k3"), newSide)
        assertEquals(oldSide.size, h.oldCount)
        assertEquals(newSide.size, h.newCount)
    }

    @Test fun `a large unchanged file with one edit stays a single tiny hunk (prefix-suffix trim)`() {
        val base = (1..2000).joinToString("\n") { "line $it" }
        val edited = base.replace("line 1000", "line 1000 EDITED")
        val hunks = LineDiff.diff(base + "\n", edited + "\n", context = 3)
        assertEquals(1, hunks.size)
        // 3 context each side + the swap = 8 lines, not 2000
        assertEquals(8, hunks.single().lines.size)
        assertEquals(LineDiff.Stat(1, 1), LineDiff.stat(base, edited))
    }

    @Test fun `unified diff re-diffs to nothing once applied conceptually (idempotence of stat)`() {
        // applying new over old means stat(new,new) is clean
        val old = "x\ny\nz\n"
        val new = "x\nY\nz\nW\n"
        assertEquals(LineDiff.Stat(2, 1), LineDiff.stat(old, new))
        assertEquals(LineDiff.Stat(0, 0), LineDiff.stat(new, new))
    }
}
