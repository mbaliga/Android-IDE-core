package dev.fonebrew.domain.layout

import dev.fonebrew.domain.layout.LayoutNode.Companion.leaf
import dev.fonebrew.domain.layout.LayoutNode.Companion.split
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PaneLayoutTest {
    private val big = SizeDp(2560f, 720f)
    private val ED1 = PaneId(PaneKind.EDITOR, 1)
    private val ED2 = PaneId(PaneKind.EDITOR, 2)
    private val TERM = PaneId(PaneKind.TERMINAL)
    private val TREE = PaneId(PaneKind.TREE)

    private fun two() = PaneLayout(split(Axis.ROW, leaf(PaneKind.TREE) to 0.3f, leaf(PaneKind.EDITOR) to 0.7f), ED1)
    private fun applied(e: LayoutEdit) = (e as LayoutEdit.Applied).layout
    private fun assertSizesRespectMinimums(l: PaneLayout, c: SizeDp) {
        val a = l.arrange(c, honourMaximised = false)
        assertTrue("fits", a.fits)
        for ((p, r) in a.rects) {
            assertTrue("$p width ${r.width}", r.width >= p.kind.minWidthDp - 0.02f)
            assertTrue("$p height ${r.height}", r.height >= p.kind.minHeightDp - 0.02f)
            assertTrue(r.x >= -0.01f && r.y >= -0.01f && r.right <= c.width + 0.02f && r.bottom <= c.height + 0.02f)
        }
    }

    @Test fun `arrange tiles the container exactly`() {
        val l = PaneLayoutPresets.ultrawideCoding.layout
        val a = l.arrange(big)
        val area = a.rects.values.sumOf { (it.width * it.height).toDouble() }
        assertEquals(2560.0 * 720.0, area, 2560.0 * 720.0 * 1e-4)
        assertEquals(l.panes().toSet(), a.rects.keys)
    }

    @Test fun `solver pins to minimum and re-shares the rest`() {
        val s = PaneLayout.solveSizes(1000f, listOf(300f, 100f), listOf(0.1f, 0.9f))
        assertEquals(300f, s[0], 0.01f)
        assertEquals(700f, s[1], 0.01f)
        assertEquals(1000f, s.sum(), 0.01f)
    }

    @Test fun `solver cascades pins`() {
        // 0.05 -> pinned to 200, then 0.25 of the remaining weight falls below 250 too.
        val s = PaneLayout.solveSizes(1000f, listOf(200f, 250f, 100f), listOf(0.05f, 0.15f, 0.8f))
        assertEquals(200f, s[0], 0.01f); assertEquals(250f, s[1], 0.01f); assertEquals(550f, s[2], 0.01f)
    }

    @Test fun `split halves the target and adds the new pane after it`() {
        val l = applied(two().split(ED1, TERM, Axis.COLUMN, true, big))
        assertEquals(listOf(TREE, ED1, TERM), l.panes())
        assertEquals(TERM, l.focused)
        assertSizesRespectMinimums(l, big)
    }

    @Test fun `split along the parent's axis flattens instead of nesting`() {
        val l = applied(two().split(ED1, ED2, Axis.ROW, true, big))
        val root = l.root as LayoutNode.Split
        assertEquals(3, root.children.size)
        assertEquals(1f, root.fractions.sum(), 1e-3f)
    }

    @Test fun `split is refused when it would break a minimum`() {
        val tight = SizeDp(600f, 400f) // tree 160 + editor 280 = 440 wide; a second editor would need 720
        val r = two().split(ED1, ED2, Axis.ROW, true, tight)
        assertEquals(LayoutEdit.Rejected(RejectReason.WOULD_BREAK_MINIMUM), r)
    }

    @Test fun `split refuses duplicates, unknown targets and second singletons`() {
        assertEquals(LayoutEdit.Rejected(RejectReason.DUPLICATE_PANE), two().split(ED1, TREE, Axis.ROW, true, big))
        assertEquals(LayoutEdit.Rejected(RejectReason.NO_SUCH_PANE), two().split(TERM, ED2, Axis.ROW, true, big))
        val withTerm = applied(two().split(ED1, TERM, Axis.COLUMN, true, big))
        assertEquals(LayoutEdit.Rejected(RejectReason.SINGLETON_KIND_OPEN), withTerm.split(ED1, PaneId(PaneKind.TERMINAL, 2), Axis.COLUMN, true, big))
    }

    @Test fun `close collapses a single-child split and moves focus`() {
        val l = two()
        val closed = applied(l.close(ED1, big))
        assertEquals(LayoutNode.Leaf(TREE), closed.root)
        assertEquals(TREE, closed.focused)
        assertEquals(LayoutEdit.Rejected(RejectReason.LAST_PANE), closed.close(TREE, big))
        assertEquals(LayoutEdit.Rejected(RejectReason.NO_SUCH_PANE), l.close(TERM, big))
    }

    @Test fun `close then undo restores the structure exactly`() {
        val start = PaneLayoutPresets.ultrawideCoding.layout
        for (victim in start.panes()) {
            val h = LayoutHistory(start)
            h.apply { it.close(victim, big) }
            assertFalse(victim in h.current.panes())
            assertTrue(h.undo())
            assertEquals(start, h.current)
            assertTrue(h.redo())
            assertFalse(victim in h.current.panes())
        }
    }

    @Test fun `rejected edit leaves history untouched`() {
        val h = LayoutHistory(two())
        h.apply { it.split(ED1, ED2, Axis.ROW, true, SizeDp(500f, 300f)) }
        assertFalse(h.canUndo)
    }

    @Test fun `closing the maximised pane restores`() {
        val l = applied(two().maximise(ED1))
        assertEquals(ED1, l.maximised)
        assertEquals(null, applied(l.close(ED1, big)).maximised)
    }

    @Test fun `move re-parents beside the target`() {
        val l = applied(two().split(ED1, TERM, Axis.COLUMN, true, big)) // tree | (ed over term)
        val moved = applied(l.move(TERM, TREE, Edge.BOTTOM, big))
        assertEquals(setOf(TREE, ED1, TERM), moved.panes().toSet())
        val a = moved.arrange(big)
        assertTrue(a.rects[TERM]!!.y >= a.rects[TREE]!!.bottom - 0.02f)
        assertSizesRespectMinimums(moved, big)
        assertEquals(LayoutEdit.Rejected(RejectReason.NO_OP), l.move(TERM, TERM, Edge.LEFT, big))
    }

    @Test fun `resize moves the divider and clamps at the neighbour minimum`() {
        val l = two()
        val grown = applied(l.resizePane(TREE, 100f, big))
        val a = grown.arrange(big).rects
        assertEquals(0.3f * 2560f + 100f, a[TREE]!!.width, 0.5f)
        val slammed = applied(l.resizePane(TREE, 100000f, big))
        val b = slammed.arrange(big).rects
        assertEquals(PaneKind.EDITOR.minWidthDp, b[ED1]!!.width, 0.05f)
        val shrunk = applied(l.resizePane(TREE, -100000f, big))
        assertEquals(PaneKind.TREE.minWidthDp, shrunk.arrange(big).rects[TREE]!!.width, 0.05f)
        assertSizesRespectMinimums(slammed, big)
        assertSizesRespectMinimums(shrunk, big)
    }

    @Test fun `resizing the last child uses the divider before it`() {
        val grown = applied(two().resizePane(ED1, 50f, big))
        assertEquals(0.7f * 2560f + 50f, grown.arrange(big).rects[ED1]!!.width, 0.5f)
    }

    @Test fun `resize rejects bad paths and non-finite deltas and a too-small container`() {
        assertEquals(LayoutEdit.Rejected(RejectReason.NO_SUCH_SPLIT), two().resizeDivider(listOf(5), 0, 10f, big))
        assertEquals(LayoutEdit.Rejected(RejectReason.NO_SUCH_SPLIT), two().resizeDivider(emptyList(), 3, 10f, big))
        assertEquals(LayoutEdit.Rejected(RejectReason.NO_OP), two().resizeDivider(emptyList(), 0, Float.NaN, big))
        assertEquals(LayoutEdit.Rejected(RejectReason.WOULD_BREAK_MINIMUM), two().resizeDivider(emptyList(), 0, 10f, SizeDp(100f, 100f)))
    }

    @Test fun `maximise and restore`() {
        val l = applied(two().maximise(TREE))
        val a = l.arrange(big)
        assertEquals(setOf(TREE), a.rects.keys)
        assertEquals(RectDp(0f, 0f, 2560f, 720f), a.rects[TREE])
        val back = l.restore()
        assertEquals(2, back.arrange(big).rects.size)
        assertEquals(back, applied(l.toggleMaximise(TREE)))
        assertEquals(LayoutEdit.Rejected(RejectReason.NO_SUCH_PANE), l.maximise(TERM))
    }

    @Test fun `focus next and previous visit every pane exactly once and wrap`() {
        for (preset in PaneLayoutPresets.all) {
            val n = preset.layout.panes().size
            var l = preset.layout
            val seen = ArrayList<PaneId>()
            repeat(n) { seen += l.focused; l = l.focusNext() }
            assertEquals(preset.id.name, n, seen.toSet().size)
            assertEquals(preset.layout.focused, l.focused) // wrapped back
            var p = preset.layout
            val seenBack = HashSet<PaneId>()
            repeat(n) { seenBack += p.focused; p = p.focusPrevious() }
            assertEquals(n, seenBack.size)
            assertEquals(preset.layout.focused, p.focused)
        }
    }

    @Test fun `focus direction follows geometry`() {
        val l = PaneLayoutPresets.desktopCoding.layout.let { it.copy(focused = TREE) }
        val c = SizeDp(1400f, 800f)
        assertEquals(ED1, l.focusDirection(Direction.RIGHT, c).focused)
        val ed = l.focusDirection(Direction.RIGHT, c)
        assertEquals(TERM, ed.focusDirection(Direction.DOWN, c).focused)
        assertEquals(ED1, ed.focusDirection(Direction.DOWN, c).focusDirection(Direction.UP, c).focused)
        assertEquals(TREE, ed.focusDirection(Direction.LEFT, c).focused)
        assertEquals(TREE, l.focusDirection(Direction.LEFT, c).focused) // nothing there: unchanged
        assertEquals(TREE, l.focusDirection(Direction.UP, c).focused)
    }

    @Test fun `directional focus can reach every pane of the ultrawide preset`() {
        val l = PaneLayoutPresets.ultrawideCoding.layout
        val reached = HashSet<PaneId>(); reached += l.focused
        val frontier = ArrayDeque(listOf(l))
        while (frontier.isNotEmpty()) {
            val cur = frontier.removeFirst()
            for (d in Direction.entries) {
                val nxt = cur.focusDirection(d, big)
                if (reached.add(nxt.focused)) frontier.addLast(nxt)
            }
        }
        assertEquals(l.panes().toSet(), reached)
    }

    @Test fun `focus while maximised moves the maximised pane`() {
        val l = applied(two().maximise(TREE)).focusNext()
        assertEquals(ED1, l.focused); assertEquals(ED1, l.maximised)
    }

    @Test fun `every preset is a placeholder and fits the smallest container of its class`() {
        assertTrue(PaneLayoutPresets.PRESETS_ARE_PLACEHOLDERS)
        val floors = mapOf(
            ScreenClass.ULTRAWIDE to SizeDp(ScreenClassThresholds.ULTRAWIDE_MIN_WIDTH_DP, 400f),
            ScreenClass.DESKTOP to SizeDp(ScreenClassThresholds.DESKTOP_MIN_WIDTH_DP, 400f),
            ScreenClass.TABLET to SizeDp(ScreenClassThresholds.TABLET_MIN_SMALLEST_DP, 600f),
            ScreenClass.PHONE to SizeDp(320f, 480f),
        )
        for (p in PaneLayoutPresets.all) {
            assertTrue("${p.id} placeholder", p.placeholder)
            assertSizesRespectMinimums(p.layout, floors[p.screenClass]!!)
            assertEquals(p.screenClass, PaneLayoutPresets.forClass(p.screenClass).screenClass)
        }
        assertEquals(setOf(PaneKind.RAIL, PaneKind.TREE, PaneKind.EDITOR, PaneKind.TERMINAL, PaneKind.ASSISTANT),
            PaneLayoutPresets.ultrawideCoding.layout.panes().map { it.kind }.toSet())
        assertEquals(2, PaneLayoutPresets.ultrawideCoding.layout.panes().count { it.kind == PaneKind.EDITOR })
        assertEquals(setOf(PaneKind.GIT, PaneKind.TERMINAL), PaneLayoutPresets.ultrawideReview.layout.panes().map { it.kind }.toSet())
        assertEquals(1, PaneLayoutPresets.phoneStack.layout.panes().size)
    }

    @Test fun `random edit sequences never produce a sub-minimum or negative size`() {
        val rnd = Random(42)
        val spare = listOf(
            PaneId(PaneKind.GIT), PaneId(PaneKind.SEARCH), PaneId(PaneKind.OUTPUT), PaneId(PaneKind.ASSISTANT),
            PaneId(PaneKind.EDITOR, 2), PaneId(PaneKind.EDITOR, 3),
        )
        repeat(60) { run ->
            val c = SizeDp(900f + rnd.nextInt(2500), 450f + rnd.nextInt(900))
            var l = PaneLayout(leaf(PaneKind.EDITOR), ED1)
            repeat(40) {
                val panes = l.panes()
                val edit = when (rnd.nextInt(5)) {
                    0 -> l.split(panes.random(rnd), spare.random(rnd), Axis.entries.random(rnd), rnd.nextBoolean(), c)
                    1 -> l.close(panes.random(rnd), c)
                    2 -> l.move(panes.random(rnd), panes.random(rnd), Edge.entries.random(rnd), c)
                    3 -> l.resizePane(panes.random(rnd), (rnd.nextFloat() - 0.5f) * 3000f, c)
                    else -> l.maximise(panes.random(rnd))
                }
                if (edit is LayoutEdit.Applied) l = edit.layout
                if (l.maximised == null) assertSizesRespectMinimums(l, c)
                assertEquals(l.panes().size, l.panes().toSet().size)
            }
        }
    }

    @Test fun `undersized container reports fits=false instead of pretending`() {
        val a = PaneLayoutPresets.ultrawideCoding.layout.arrange(SizeDp(400f, 300f))
        assertFalse(a.fits)
        assertTrue(a.rects.values.all { it.width > 0f && it.height > 0f })
    }

    @Test fun `split constructor rejects bad fractions`() {
        val bad = runCatching { LayoutNode.Split(Axis.ROW, listOf(leaf(PaneKind.TREE), leaf(PaneKind.EDITOR)), listOf(0.5f, 0.2f)) }
        assertTrue(bad.isFailure)
        assertTrue(runCatching { PaneLayout(leaf(PaneKind.TREE), ED1) }.isFailure) // focus not in layout
    }

    @Test fun `pane id token round trips`() {
        for (k in PaneKind.entries) for (i in 1..3) assertEquals(PaneId(k, i), PaneId.parse(PaneId(k, i).token))
        assertEquals(null, PaneId.parse("EDITOR#0")); assertEquals(null, PaneId.parse("NOPE#1")); assertEquals(null, PaneId.parse(null))
    }
}
