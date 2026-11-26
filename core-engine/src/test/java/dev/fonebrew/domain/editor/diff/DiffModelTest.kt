package dev.fonebrew.domain.editor.diff

import dev.fonebrew.domain.diff.Decision
import dev.fonebrew.domain.diff.LineDiff
import dev.fonebrew.domain.diff.ReviewSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DiffModelTest {
    private fun lines(s: String): List<String> = if (s.isEmpty()) emptyList() else s.split('\n').let { if (it.last().isEmpty()) it.dropLast(1) else it }

    @Test fun `identical texts have no hunks and all context rows`() {
        val m = DiffModel.of("a\nb\n", "a\nb\n")
        assertTrue(m.isIdentical)
        assertEquals(2, m.unified.size)
        assertTrue(m.unified.all { it.kind == RowKind.CONTEXT })
    }

    @Test fun `a replaced line pairs side by side with word-level spans`() {
        val m = DiffModel.of("a\nthe quick brown fox\nz\n", "a\nthe slow brown fox\nz\n")
        assertEquals(1, m.hunks.size)
        val row = m.side[1]
        assertEquals("the quick brown fox", row.left!!.text)
        assertEquals("the slow brown fox", row.right!!.text)
        assertEquals(listOf(Span(4, 9)), row.left!!.spans)    // "quick"
        assertEquals(listOf(Span(4, 8)), row.right!!.spans)   // "slow"
        assertEquals(RowKind.REMOVE, row.left!!.kind); assertEquals(RowKind.ADD, row.right!!.kind)
    }

    @Test fun `unpaired lines get blank filler cells`() {
        val m = DiffModel.of("a\nb\nc\n", "a\nX\nY\nZ\nc\n")
        val rows = m.side.filter { it.hunkId != null }
        assertEquals(3, rows.size)
        assertEquals("b", rows[0].left!!.text); assertEquals("X", rows[0].right!!.text)
        assertNull(rows[1].left); assertEquals("Y", rows[1].right!!.text)
        assertNull(rows[2].left); assertEquals("Z", rows[2].right!!.text)
    }

    @Test fun `gutters number both sides and skip the absent side`() {
        val m = DiffModel.of("a\nb\n", "a\nc\nd\n")
        val u = m.unified
        assertEquals(listOf(1 to 1, 2 to null, null to 2, null to 3), u.map { it.oldNo to it.newNo })
        assertEquals(1 to 1, m.gutterWidths())
    }

    @Test fun `rows carry a glyph and label as well as a kind`() {
        assertEquals('+', RowKind.ADD.glyph); assertEquals("added", RowKind.ADD.label)
        assertEquals('-', RowKind.REMOVE.glyph); assertEquals("removed", RowKind.REMOVE.label)
        assertEquals("unchanged", RowKind.CONTEXT.label)
    }

    @Test fun `hunk ids are stable across context changes and unrelated edits`() {
        val old = "1\n2\n3\n4\n5\n6\n7\n8\n9\n"
        val a = DiffModel.of(old, "1\n2\nX\n4\n5\n6\n7\n8\n9\n")
        val b = DiffModel.of(old, "1\n2\nX\n4\n5\n6\n7\nY\n9\n")
        assertEquals(a.hunks[0].id, b.hunks[0].id)
        assertEquals(2, b.hunks.size)
        assertNotEquals(b.hunks[0].id, b.hunks[1].id)
        val c = DiffModel.of(old, "1\n2\nX\n4\n5\n6\n7\n8\n9\n")
        assertEquals(a.hunks.map { it.id }, c.hunks.map { it.id })
    }

    @Test fun `identical changes in two places get distinct ids`() {
        val m = DiffModel.of("a\nx\nb\nx\nc\n", "a\ny\nb\ny\nc\n")
        assertEquals(2, m.hunks.map { it.id }.toSet().size)
    }

    @Test fun `hunks line up one to one with a zero-context review session`() {
        val old = "a\nb\nc\nd\ne\nf\n"
        val new = "a\nB\nc\nd\nE1\nE2\nf\n"
        val m = DiffModel.of(old, new)
        assertTrue(m.matches(ReviewSession("p", old, new)))
        val pureInsert = DiffModel.of("a\nb\n", "a\nNEW\nb\n")
        assertTrue(pureInsert.matches(ReviewSession("p", "a\nb\n", "a\nNEW\nb\n")))
        val pureDelete = DiffModel.of("a\nb\nc\n", "a\nc\n")
        assertTrue(pureDelete.matches(ReviewSession("p", "a\nb\nc\n", "a\nc\n")))
    }

    @Test fun `stage and discard intents drive the review session decisions`() {
        val old = "a\nb\nc\nd\ne\n"
        val new = "a\nB\nc\nD\ne\n"
        val m = DiffModel.of(old, new)
        val session = ReviewSession("f", old, new)
        val unknown = HunkIntents.applyTo(session, m, listOf(
            HunkIntent(m.hunks[0].id, HunkAction.STAGE),
            HunkIntent(m.hunks[1].id, HunkAction.DISCARD),
            HunkIntent("nope", HunkAction.STAGE),
        ))
        assertEquals(listOf("nope"), unknown)
        assertEquals(Decision.APPROVED, session.decision(0))
        assertEquals(Decision.REJECTED, session.decision(1))
        assertTrue(HunkAction.REVERT.destructive)
        assertFalse(HunkAction.STAGE.destructive || HunkAction.DISCARD.destructive)
        assertEquals("a\nB\nc\nd\ne\n", HunkIntents.textAfter(m, listOf(
            HunkIntent(m.hunks[0].id, HunkAction.STAGE), HunkIntent(m.hunks[1].id, HunkAction.DISCARD))))
    }

    @Test fun `ReviewSession applied keeps the final newline, matching this lane's model`() {
        val session = ReviewSession("p", "a\nb\n", "a\nB\n")
        session.approveAll()
        assertEquals("a\nB\n", session.applied())
        assertEquals("a\nB\n", DiffModel.of("a\nb\n", "a\nB\n").applyAll())
        // a file without a final newline stays without one, and rejecting everything returns the original exactly
        val noNl = ReviewSession("p", "a\nb", "a\nB"); noNl.approveAll(); assertEquals("a\nB", noNl.applied())
        val rej = ReviewSession("p", "a\nb\n", "a\nB\n"); rej.rejectAll(); assertEquals("a\nb\n", rej.applied())
        // a change that adds the final newline follows the proposal only when every hunk is approved
        val add = ReviewSession("p", "a\nb", "a\nB\n"); add.approveAll(); assertEquals("a\nB\n", add.applied())
    }

    @Test fun `revert puts one hunk back and keeps the rest`() {
        val m = DiffModel.of("a\nb\nc\nd\ne\n", "a\nB\nc\nD\ne\n")
        assertEquals("a\nb\nc\nD\ne\n", m.revert(m.hunks[0].id))
        assertEquals("a\nB\nc\nd\ne\n", m.revert(m.hunks[1].id))
    }

    @Test fun `final newline changes are reported and applied`() {
        val m = DiffModel.of("a\nb", "a\nb\n")
        assertTrue(m.eofNewlineChanged)
        assertEquals("a\nb\n", m.applyAll())
        assertEquals("a\nB\n", DiffModel.of("a\nb", "a\nB\n").applyAll())
        assertEquals("a\nb", DiffModel.of("a\nb", "a\nB\n").apply(emptyMap()))
    }

    @Test fun `empty to text and text to empty`() {
        assertEquals("x\ny\n", DiffModel.of("", "x\ny\n").applyAll())
        assertEquals("", DiffModel.of("x\ny\n", "").applyAll())
        assertEquals("x\ny\n", DiffModel.of("x\ny\n", "").apply(emptyMap()))
        assertTrue(DiffModel.of("", "").isIdentical)
    }

    @Test fun `navigation goes next and previous with wrap`() {
        val m = DiffModel.of("1\n2\n3\n4\n5\n6\n7\n", "1\nA\n3\n4\n5\nB\n7\n")
        val h0 = m.hunks[0]; val h1 = m.hunks[1]
        assertEquals(h0.id, HunkNavigator.next(m, -1)!!.hunk.id)
        assertEquals(h1.id, HunkNavigator.next(m, h0.firstRow)!!.hunk.id)
        val wrapped = HunkNavigator.next(m, h1.lastRow)!!
        assertEquals(h0.id, wrapped.hunk.id); assertTrue(wrapped.wrapped)
        assertNull(HunkNavigator.next(m, h1.lastRow, wrap = false))
        assertEquals(h0.id, HunkNavigator.previous(m, h1.firstRow)!!.hunk.id)
        assertEquals(h1.id, HunkNavigator.previous(m, h0.firstRow)!!.hunk.id)
        assertNull(DiffModel.of("a", "a").let { HunkNavigator.next(it, 0) })
    }

    // ---- folding ---------------------------------------------------------------------------------------

    @Test fun `unchanged runs fold beyond the context and expand more on request`() {
        val old = (1..40).joinToString("\n") { "l$it" } + "\n"
        val new = old.replace("l20\n", "CHANGED\n")
        val m = DiffModel.of(old, new)
        val items = m.foldedUnified(context = 3)
        val folds = items.filterIsInstance<FoldItem.Fold>()
        assertEquals(2, folds.size)
        assertEquals(16, folds[0].hiddenCount)       // lines 1..16 hidden, 17..19 shown as context
        assertEquals(17, folds[1].hiddenCount)
        val more = FoldState.EMPTY.expand(folds[0].id, 5, FoldState.Direction.UP)
        val items2 = m.foldedUnified(3, more)
        assertEquals(11, items2.filterIsInstance<FoldItem.Fold>()[0].hiddenCount)
        val all = FoldState.EMPTY.expand(folds[0].id, 1, FoldState.Direction.ALL)
        assertEquals(1, m.foldedUnified(3, all).filterIsInstance<FoldItem.Fold>().size)
    }

    @Test fun `folded rows plus hidden rows account for every row exactly once`() {
        val rnd = Random(3)
        repeat(100) {
            val old = (0 until 60).joinToString("\n") { "r$it" } + "\n"
            val new = old.split("\n").mapIndexed { i, s -> if (rnd.nextInt(15) == 0) "chg$i" else s }.joinToString("\n")
            val m = DiffModel.of(old, new)
            assertEquals(m.unified.size, m.foldedUnified(2).sumOf { if (it is FoldItem.Show) 1 else (it as FoldItem.Fold).hiddenCount })
            assertEquals(m.side.size, m.foldedSide(2).sumOf { if (it is FoldItem.Show) 1 else (it as FoldItem.Fold).hiddenCount })
        }
    }

    @Test fun `a one-line gap is shown not folded`() {
        val m = DiffModel.of("a\nb\nc\nd\ne\nf\ng\nh\n", "A\nb\nc\nd\ne\nf\ng\nH\n")
        assertEquals(0, m.foldedUnified(3).count { it is FoldItem.Fold })
        assertEquals(1, m.foldedUnified(2).count { it is FoldItem.Fold })
    }

    // ---- properties ------------------------------------------------------------------------------------------

    @Test fun `applying all hunks reproduces the target and none reproduces the source - random texts`() {
        val rnd = Random(2026)
        val vocab = listOf("a", "b", "c", "x", "foo bar", "", "  indented", "z z")
        repeat(1500) { n ->
            fun doc() = (0 until rnd.nextInt(14)).joinToString("\n") { vocab[rnd.nextInt(vocab.size)] } + if (rnd.nextBoolean()) "\n" else ""
            val old = doc(); val new = doc()
            val m = DiffModel.of(old, new)
            assertEquals("all hunks #$n\nold=[$old]\nnew=[$new]", new, m.applyAll())
            assertEquals("no hunks #$n", old, m.apply(emptyMap()))
            // the alignment never drops or duplicates a line
            assertEquals("old lines #$n", lines(old), m.oldFromRows())
            assertEquals("new lines #$n", lines(new), m.newFromRows())
            assertEquals(lines(old), m.side.mapNotNull { it.left }.map { it.text })
            assertEquals(lines(new), m.side.mapNotNull { it.right }.map { it.text })
            // gutter numbers count up by one per side
            assertEquals((1..lines(old).size).toList(), m.unified.mapNotNull { it.oldNo })
            assertEquals((1..lines(new).size).toList(), m.unified.mapNotNull { it.newNo })
            // agrees with LineDiff's own stat
            val stat = LineDiff.stat(old, new)
            assertEquals(stat.added, m.unified.count { it.kind == RowKind.ADD })
            assertEquals(stat.removed, m.unified.count { it.kind == RowKind.REMOVE })
            // and with the review session
            assertTrue("session match #$n", m.matches(ReviewSession("p", old, new)))
            // any subset of hunks approved gives text that still decomposes into old/new lines in order
            val subset = m.hunks.filter { rnd.nextBoolean() }.associate { it.id to Decision.APPROVED }
            val partial = lines(m.apply(subset))
            assertEquals(lines(old).size + m.hunks.filter { it.id in subset }.sumOf { it.newCount - it.oldCount }, partial.size)
        }
    }

    @Test fun `intra-line spans only ever cover differing text and stay inside the line`() {
        val rnd = Random(8)
        val words = listOf("alpha", "beta", " ", "(", ")", "12", "x_y", "  ", "é")
        repeat(500) {
            fun s() = (0 until rnd.nextInt(8)).joinToString("") { words[rnd.nextInt(words.size)] }
            val a = s(); val b = s()
            val (sa, sb) = IntraLineDiff.spans(a, b)
            for (x in sa) assertTrue(x.start in 0 until x.end && x.end <= a.length)
            for (x in sb) assertTrue(x.start in 0 until x.end && x.end <= b.length)
            if (a == b) { assertTrue(sa.isEmpty() && sb.isEmpty()) } else assertTrue(sa.isNotEmpty() || sb.isNotEmpty())
            // removing the changed spans from both sides leaves identical shared text
            fun rest(t: String, sp: List<Span>): String { val sbd = StringBuilder(); var p = 0; for (x in sp) { sbd.append(t, p, x.start); p = x.end }; sbd.append(t, p, t.length); return sbd.toString() }
            if (sa.size != 1 || sa[0] != Span(0, a.length)) assertEquals("a=[$a] b=[$b]", rest(a, sa), rest(b, sb))
        }
    }

    @Test fun `huge lines fall back to a whole-line span`() {
        val a = (0 until 2000).joinToString(" ") { "w$it" }
        val b = a + " tail"
        val (sa, sb) = IntraLineDiff.spans(a, b)
        assertEquals(listOf(Span(0, a.length)), sa)
        assertEquals(listOf(Span(0, b.length)), sb)
    }

    @Test fun `past LineDiff's safety valve a huge changed region becomes one whole-region hunk (inherited limit)`() {
        val old = (0 until 3000).joinToString("\n") { "line $it" } + "\n"
        val new = (0 until 3000).joinToString("\n") { if (it % 50 == 0) "changed $it" else "line $it" } + "\n"
        val m = DiffModel.of(old, new)
        assertEquals(1, m.hunks.size)                      // 2950 x 2950 lines exceeds LineDiff's 8M-cell table
        assertEquals(new, m.applyAll())                    // still lossless, just coarse
        assertEquals(old, m.apply(emptyMap()))
    }

    @Test fun `a large change set builds quickly`() {
        val old = (0 until 2000).joinToString("\n") { "line $it" } + "\n"
        val new = (0 until 2000).joinToString("\n") { if (it % 50 == 0) "changed $it" else "line $it" } + "\n"
        val t0 = System.nanoTime()
        val m = DiffModel.of(old, new)
        assertEquals(40, m.hunks.size)
        assertNotNull(m.foldedSide())
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 10_000)
    }

    // ---- three-column ----------------------------------------------------------------------------------------

    @Test fun `three column rows align ours base theirs with fillers and per-column numbers`() {
        val regions = ThreeWaySource {
            listOf(
                ThreeWayRegion.Stable(listOf("head")),
                ThreeWayRegion.Conflict("c1", ours = listOf("o1", "o2"), base = listOf("b1"), theirs = listOf("t1", "t2", "t3")),
                ThreeWayRegion.Stable(listOf("tail")),
            )
        }
        val rows = ThreeWayDisplay.rows(regions)
        assertEquals(5, rows.size)
        assertEquals(listOf(false, true, true, true, false), rows.map { it.conflicted })
        assertEquals("o1", rows[1].ours!!.text); assertEquals("b1", rows[1].base!!.text); assertEquals("t1", rows[1].theirs!!.text)
        assertNull(rows[2].base); assertEquals("t2", rows[2].theirs!!.text)
        assertNull(rows[3].ours); assertNull(rows[3].base); assertEquals("t3", rows[3].theirs!!.text)
        assertEquals(listOf(1, 2, 3), listOf(rows[0].ours!!.lineNo, rows[1].ours!!.lineNo, rows[2].ours!!.lineNo))
        assertEquals(4, rows[4].ours!!.lineNo); assertEquals(3, rows[4].base!!.lineNo); assertEquals(5, rows[4].theirs!!.lineNo)
        assertEquals("conflict", rows[1].stateLabel); assertEquals("!", rows[1].marker)
        assertEquals("unchanged", rows[0].stateLabel)
        assertEquals(Triple("Ours", "Base", "Theirs"), ThreeWayDisplay.COLUMN_LABELS)
    }

    @Test fun `a two-way conflict leaves the base column empty`() {
        val rows = ThreeWayDisplay.rows { listOf(ThreeWayRegion.Conflict("c", listOf("a"), null, listOf("b"))) }
        assertNull(rows.single().base)
    }
}
