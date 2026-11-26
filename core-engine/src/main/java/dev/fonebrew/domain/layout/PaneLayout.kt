package dev.fonebrew.domain.layout

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * What a pane shows. [RAIL] is the thin launcher strip of the ultrawide coding preset (the brief's
 * "rail"); GIT hosts the diff view. Minimum sizes are PROPOSED, in dp, and exclude the divider
 * thickness (the later pane UI draws dividers inside the fractions). Only [multi] kinds may have
 * more than one instance (two editors side by side).
 */
enum class PaneKind(val minWidthDp: Float, val minHeightDp: Float, val multi: Boolean = false) {
    RAIL(56f, 120f),
    TREE(160f, 160f),
    EDITOR(280f, 160f, multi = true),
    TERMINAL(240f, 120f),
    ASSISTANT(280f, 200f),
    GIT(240f, 160f),
    SEARCH(200f, 160f),
    OUTPUT(200f, 100f),
}

/** A pane instance: a [kind] plus a 1-based [index] (only >1 for [PaneKind.multi] kinds). */
data class PaneId(val kind: PaneKind, val index: Int = 1) {
    init { require(index >= 1) { "pane index must be >= 1" } }
    val token: String get() = "${kind.name}#$index"

    companion object {
        fun parse(token: String?): PaneId? {
            if (token == null) return null
            val at = token.indexOf('#')
            if (at <= 0) return null
            val kind = PaneKind.entries.firstOrNull { it.name == token.substring(0, at) } ?: return null
            val n = token.substring(at + 1).toIntOrNull() ?: return null
            return if (n >= 1) PaneId(kind, n) else null
        }
    }
}

/** ROW lays children left to right (they share the width); COLUMN top to bottom (share the height). */
enum class Axis { ROW, COLUMN }

enum class Direction { LEFT, RIGHT, UP, DOWN }

/** Which side of a target pane [PaneLayout.move] drops onto. */
enum class Edge(val axis: Axis, val after: Boolean) {
    LEFT(Axis.ROW, false), RIGHT(Axis.ROW, true), TOP(Axis.COLUMN, false), BOTTOM(Axis.COLUMN, true)
}

data class SizeDp(val width: Float, val height: Float)
data class RectDp(val x: Float, val y: Float, val width: Float, val height: Float) {
    val right: Float get() = x + width
    val bottom: Float get() = y + height
}

sealed interface LayoutNode {
    data class Leaf(val pane: PaneId) : LayoutNode

    /** [fractions] are positive, one per child, summing to 1 (checked). */
    data class Split(val axis: Axis, val children: List<LayoutNode>, val fractions: List<Float>) : LayoutNode {
        init {
            require(children.size >= 2) { "a split needs at least two children" }
            require(fractions.size == children.size) { "one fraction per child" }
            require(fractions.all { it.isFinite() && it > 0f }) { "fractions must be finite and positive" }
            require(abs(fractions.sum() - 1f) < 1e-3f) { "fractions must sum to 1" }
        }
    }

    companion object {
        /** Builds a split from arbitrary positive [weights], normalising them to sum to 1. */
        fun split(axis: Axis, vararg parts: Pair<LayoutNode, Float>): Split {
            val total = parts.sumOf { it.second.toDouble() }.toFloat()
            return Split(axis, parts.map { it.first }, parts.map { it.second / total })
        }
        fun leaf(kind: PaneKind, index: Int = 1): Leaf = Leaf(PaneId(kind, index))
    }
}

enum class RejectReason { NO_SUCH_PANE, NO_SUCH_SPLIT, DUPLICATE_PANE, SINGLETON_KIND_OPEN, WOULD_BREAK_MINIMUM, LAST_PANE, NO_OP }

sealed interface LayoutEdit {
    data class Applied(val layout: PaneLayout) : LayoutEdit
    data class Rejected(val reason: RejectReason) : LayoutEdit
}

data class Arranged(val rects: Map<PaneId, RectDp>, val fits: Boolean)

/**
 * The docked pane layout: an immutable split tree plus focus and an optional maximised pane.
 * Every edit takes the container size in dp and either returns a layout in which every pane is
 * at least its kind's minimum, or is [LayoutEdit.Rejected] - sizes are never clamped into
 * something below a minimum. (When the *window itself* later shrinks below the layout's total
 * minimum, [arrange] says `fits = false` and the caller should fall back to a smaller preset.)
 */
data class PaneLayout(
    val root: LayoutNode,
    val focused: PaneId,
    val maximised: PaneId? = null,
) {
    init {
        val all = panes()
        require(all.size == all.toSet().size) { "duplicate pane in layout" }
        require(focused in all) { "focused pane is not in the layout" }
        require(maximised == null || maximised in all) { "maximised pane is not in the layout" }
        require(all.groupBy { it.kind }.all { (k, v) -> k.multi || v.size == 1 }) { "singleton pane kind used twice" }
    }

    /** Panes in depth-first, left-to-right / top-to-bottom order. */
    fun panes(): List<PaneId> = leaves(root)

    // ---- geometry ---------------------------------------------------------------------------

    fun arrange(container: SizeDp, honourMaximised: Boolean = true): Arranged {
        val fits = container.width >= minWidth(root) - EPS && container.height >= minHeight(root) - EPS
        val max = maximised
        if (honourMaximised && max != null) {
            val fitsOne = container.width >= max.kind.minWidthDp - EPS && container.height >= max.kind.minHeightDp - EPS
            return Arranged(mapOf(max to RectDp(0f, 0f, container.width, container.height)), fitsOne)
        }
        val out = LinkedHashMap<PaneId, RectDp>()
        place(root, RectDp(0f, 0f, container.width, container.height), out)
        return Arranged(out, fits)
    }

    // ---- edits ------------------------------------------------------------------------------

    fun split(target: PaneId, newPane: PaneId, axis: Axis, placeAfter: Boolean, container: SizeDp): LayoutEdit {
        if (target !in panes()) return LayoutEdit.Rejected(RejectReason.NO_SUCH_PANE)
        if (newPane in panes()) return LayoutEdit.Rejected(RejectReason.DUPLICATE_PANE)
        if (!newPane.kind.multi && panes().any { it.kind == newPane.kind }) return LayoutEdit.Rejected(RejectReason.SINGLETON_KIND_OPEN)
        val inserted = insertBeside(root, target, newPane, axis, placeAfter)
        return commit(copy(root = inserted, focused = newPane, maximised = null), container)
    }

    fun close(pane: PaneId, container: SizeDp): LayoutEdit {
        val order = panes()
        val at = order.indexOf(pane)
        if (at < 0) return LayoutEdit.Rejected(RejectReason.NO_SUCH_PANE)
        if (order.size == 1) return LayoutEdit.Rejected(RejectReason.LAST_PANE)
        val remaining = canonical(removeLeaf(root, pane)!!)
        val rest = leaves(remaining)
        val nextFocus = if (focused != pane) focused else rest[min(at, rest.lastIndex)]
        val nextMax = if (maximised == pane) null else maximised
        // Closing only ever frees space, so it is settled when it fits and left proportional otherwise.
        return LayoutEdit.Applied(settle(PaneLayout(remaining, nextFocus, nextMax), container))
    }

    fun move(pane: PaneId, target: PaneId, edge: Edge, container: SizeDp): LayoutEdit {
        val order = panes()
        if (pane !in order || target !in order) return LayoutEdit.Rejected(RejectReason.NO_SUCH_PANE)
        if (pane == target) return LayoutEdit.Rejected(RejectReason.NO_OP)
        val without = removeLeaf(root, pane) ?: return LayoutEdit.Rejected(RejectReason.LAST_PANE)
        val inserted = insertBeside(without, target, pane, edge.axis, edge.after)
        return commit(copy(root = inserted, focused = pane, maximised = null), container)
    }

    /**
     * Moves the divider after child [dividerIndex] of the split at [splitPath] (child indices from
     * the root) by [deltaDp] along its axis, clamped so both neighbours keep their minimum. Other
     * children keep their sizes. A delta that cannot move at all returns the layout unchanged.
     */
    fun resizeDivider(splitPath: List<Int>, dividerIndex: Int, deltaDp: Float, container: SizeDp): LayoutEdit {
        if (!deltaDp.isFinite()) return LayoutEdit.Rejected(RejectReason.NO_OP)
        if (!arrange(container, honourMaximised = false).fits) return LayoutEdit.Rejected(RejectReason.WOULD_BREAK_MINIMUM)
        val settled = settle(this, container)
        var node = settled.root
        var rect = RectDp(0f, 0f, container.width, container.height)
        for (i in splitPath) {
            val s = node as? LayoutNode.Split ?: return LayoutEdit.Rejected(RejectReason.NO_SUCH_SPLIT)
            if (i !in s.children.indices) return LayoutEdit.Rejected(RejectReason.NO_SUCH_SPLIT)
            rect = childRects(s, rect)[i]
            node = s.children[i]
        }
        val split = node as? LayoutNode.Split ?: return LayoutEdit.Rejected(RejectReason.NO_SUCH_SPLIT)
        if (dividerIndex !in 0 until split.children.lastIndex) return LayoutEdit.Rejected(RejectReason.NO_SUCH_SPLIT)
        val rects = childRects(split, rect)
        val total = if (split.axis == Axis.ROW) rect.width else rect.height
        val sizes = rects.map { if (split.axis == Axis.ROW) it.width else it.height }.toMutableList()
        val minA = minAlong(split.children[dividerIndex], split.axis)
        val minB = minAlong(split.children[dividerIndex + 1], split.axis)
        val delta = deltaDp.coerceIn(-(sizes[dividerIndex] - minA).coerceAtLeast(0f), (sizes[dividerIndex + 1] - minB).coerceAtLeast(0f))
        sizes[dividerIndex] += delta
        sizes[dividerIndex + 1] -= delta
        val replaced = LayoutNode.Split(split.axis, split.children, normalise(sizes.map { it / total }))
        val newRoot = replaceAt(settled.root, splitPath, replaced)
        return LayoutEdit.Applied(settle(settled.copy(root = newRoot), container))
    }

    /** Grows [pane] by [deltaDp] along its parent split's axis (negative shrinks) via the nearest divider. */
    fun resizePane(pane: PaneId, deltaDp: Float, container: SizeDp): LayoutEdit {
        val path = pathTo(root, pane) ?: return LayoutEdit.Rejected(RejectReason.NO_SUCH_PANE)
        if (path.isEmpty()) return LayoutEdit.Rejected(RejectReason.NO_OP)
        val index = path.last()
        val parent = path.dropLast(1)
        val split = nodeAt(root, parent) as LayoutNode.Split
        return if (index < split.children.lastIndex) resizeDivider(parent, index, deltaDp, container)
        else resizeDivider(parent, index - 1, -deltaDp, container)
    }

    fun maximise(pane: PaneId): LayoutEdit =
        if (pane !in panes()) LayoutEdit.Rejected(RejectReason.NO_SUCH_PANE)
        else LayoutEdit.Applied(copy(focused = pane, maximised = pane))

    fun restore(): PaneLayout = copy(maximised = null)

    fun toggleMaximise(pane: PaneId): LayoutEdit =
        if (maximised == pane) LayoutEdit.Applied(restore()) else maximise(pane)

    // ---- focus ------------------------------------------------------------------------------

    fun focusNext(): PaneLayout = focusStep(+1)
    fun focusPrevious(): PaneLayout = focusStep(-1)

    private fun focusStep(step: Int): PaneLayout {
        val order = panes()
        val to = order[((order.indexOf(focused) + step) % order.size + order.size) % order.size]
        return withFocus(to)
    }

    /** Nearest pane in [dir] that overlaps the focused one on the cross axis; unchanged if none. */
    fun focusDirection(dir: Direction, container: SizeDp): PaneLayout {
        val rects = arrange(container, honourMaximised = false).rects
        val cur = rects[focused] ?: return this
        val order = panes()
        var best: PaneId? = null
        var bestGap = Float.MAX_VALUE
        var bestOverlap = -1f
        for (p in order) {
            if (p == focused) continue
            val r = rects[p] ?: continue
            val gap = when (dir) {
                Direction.RIGHT -> r.x - cur.right
                Direction.LEFT -> cur.x - r.right
                Direction.DOWN -> r.y - cur.bottom
                Direction.UP -> cur.y - r.bottom
            }
            if (gap < -EPS) continue
            val overlap = when (dir) {
                Direction.LEFT, Direction.RIGHT -> min(cur.bottom, r.bottom) - max(cur.y, r.y)
                Direction.UP, Direction.DOWN -> min(cur.right, r.right) - max(cur.x, r.x)
            }
            if (overlap <= EPS) continue
            if (gap < bestGap - EPS || (abs(gap - bestGap) <= EPS && overlap > bestOverlap + EPS)) {
                best = p; bestGap = gap; bestOverlap = overlap
            }
        }
        return best?.let { withFocus(it) } ?: this
    }

    private fun withFocus(to: PaneId): PaneLayout = copy(focused = to, maximised = if (maximised != null) to else null)

    // ---- internals --------------------------------------------------------------------------

    private fun commit(candidate: PaneLayout, container: SizeDp): LayoutEdit {
        val canon = candidate.copy(root = canonical(candidate.root))
        if (!canon.arrange(container, honourMaximised = false).fits) return LayoutEdit.Rejected(RejectReason.WOULD_BREAK_MINIMUM)
        return LayoutEdit.Applied(settle(canon, container))
    }

    companion object {
        internal const val EPS = 0.01f

        internal fun leaves(n: LayoutNode): List<PaneId> = when (n) {
            is LayoutNode.Leaf -> listOf(n.pane)
            is LayoutNode.Split -> n.children.flatMap { leaves(it) }
        }

        internal fun minWidth(n: LayoutNode): Float = when (n) {
            is LayoutNode.Leaf -> n.pane.kind.minWidthDp
            is LayoutNode.Split -> if (n.axis == Axis.ROW) n.children.sumOf { minWidth(it).toDouble() }.toFloat() else n.children.maxOf { minWidth(it) }
        }

        internal fun minHeight(n: LayoutNode): Float = when (n) {
            is LayoutNode.Leaf -> n.pane.kind.minHeightDp
            is LayoutNode.Split -> if (n.axis == Axis.COLUMN) n.children.sumOf { minHeight(it).toDouble() }.toFloat() else n.children.maxOf { minHeight(it) }
        }

        internal fun minAlong(n: LayoutNode, axis: Axis): Float = if (axis == Axis.ROW) minWidth(n) else minHeight(n)

        /**
         * Shares [total] among children by [fractions] but never below [mins]: anything that would
         * fall short is pinned at its minimum and the rest re-share what is left. If the minimums
         * alone exceed [total] the layout cannot fit; sizes are then the minimums scaled down
         * proportionally (callers learn this from [Arranged.fits]).
         */
        fun solveSizes(total: Float, mins: List<Float>, fractions: List<Float>): List<Float> {
            val n = mins.size
            val minSum = mins.sum()
            if (minSum >= total) return if (minSum <= 0f) List(n) { total / n } else mins.map { it * total / minSum }
            val pinned = BooleanArray(n)
            while (true) {
                val pinnedSum = (0 until n).filter { pinned[it] }.sumOf { mins[it].toDouble() }.toFloat()
                val free = (0 until n).filter { !pinned[it] }
                val weight = free.sumOf { fractions[it].toDouble() }.toFloat()
                val room = total - pinnedSum
                val violators = free.filter { room * fractions[it] / weight < mins[it] }
                if (violators.isEmpty()) {
                    return List(n) { if (pinned[it]) mins[it] else room * fractions[it] / weight }
                }
                violators.forEach { pinned[it] = true }
            }
        }

        private fun normalise(w: List<Float>): List<Float> { val s = w.sum(); return w.map { it / s } }

        private fun childRects(s: LayoutNode.Split, rect: RectDp): List<RectDp> {
            val row = s.axis == Axis.ROW
            val sizes = solveSizes(
                if (row) rect.width else rect.height,
                s.children.map { minAlong(it, s.axis) },
                s.fractions,
            )
            var at = if (row) rect.x else rect.y
            return sizes.map { sz ->
                val r = if (row) RectDp(at, rect.y, sz, rect.height) else RectDp(rect.x, at, rect.width, sz)
                at += sz
                r
            }
        }

        private fun place(n: LayoutNode, rect: RectDp, out: MutableMap<PaneId, RectDp>) {
            when (n) {
                is LayoutNode.Leaf -> out[n.pane] = rect
                is LayoutNode.Split -> childRects(n, rect).forEachIndexed { i, r -> place(n.children[i], r, out) }
            }
        }

        /** Rewrites stored fractions to the solved sizes, so they stay true to the container. */
        internal fun settle(l: PaneLayout, container: SizeDp): PaneLayout {
            if (!l.arrange(container, honourMaximised = false).fits) return l
            fun go(n: LayoutNode, rect: RectDp): LayoutNode = when (n) {
                is LayoutNode.Leaf -> n
                is LayoutNode.Split -> {
                    val rects = childRects(n, rect)
                    val total = if (n.axis == Axis.ROW) rect.width else rect.height
                    LayoutNode.Split(
                        n.axis,
                        n.children.mapIndexed { i, c -> go(c, rects[i]) },
                        normalise(rects.map { (if (n.axis == Axis.ROW) it.width else it.height) / total }),
                    )
                }
            }
            return l.copy(root = go(l.root, RectDp(0f, 0f, container.width, container.height)))
        }

        /** Single-child splits collapse; a same-axis split nested in its parent merges into it. */
        internal fun canonical(n: LayoutNode): LayoutNode = when (n) {
            is LayoutNode.Leaf -> n
            is LayoutNode.Split -> {
                val kids = ArrayList<LayoutNode>()
                val fr = ArrayList<Float>()
                n.children.forEachIndexed { i, raw ->
                    val c = canonical(raw)
                    if (c is LayoutNode.Split && c.axis == n.axis) {
                        c.children.forEachIndexed { j, cc -> kids += cc; fr += n.fractions[i] * c.fractions[j] }
                    } else { kids += c; fr += n.fractions[i] }
                }
                if (kids.size == 1) kids[0] else LayoutNode.Split(n.axis, kids, normalise(fr))
            }
        }

        private fun insertBeside(n: LayoutNode, target: PaneId, add: PaneId, axis: Axis, after: Boolean): LayoutNode {
            val fresh = LayoutNode.Leaf(add)
            fun go(x: LayoutNode): LayoutNode = when (x) {
                is LayoutNode.Leaf ->
                    if (x.pane != target) x
                    else LayoutNode.Split(axis, if (after) listOf(x, fresh) else listOf(fresh, x), listOf(0.5f, 0.5f))
                is LayoutNode.Split -> {
                    val idx = x.children.indexOfFirst { it is LayoutNode.Leaf && it.pane == target }
                    if (idx >= 0 && x.axis == axis) {
                        val kids = x.children.toMutableList()
                        val fr = x.fractions.toMutableList()
                        val half = fr[idx] / 2f
                        fr[idx] = half
                        kids.add(if (after) idx + 1 else idx, fresh)
                        fr.add(if (after) idx + 1 else idx, half)
                        LayoutNode.Split(x.axis, kids, normalise(fr))
                    } else LayoutNode.Split(x.axis, x.children.map { go(it) }, x.fractions)
                }
            }
            return go(n)
        }

        private fun removeLeaf(n: LayoutNode, pane: PaneId): LayoutNode? = when (n) {
            is LayoutNode.Leaf -> if (n.pane == pane) null else n
            is LayoutNode.Split -> {
                val kids = ArrayList<LayoutNode>()
                val fr = ArrayList<Float>()
                n.children.forEachIndexed { i, c -> removeLeaf(c, pane)?.let { kids += it; fr += n.fractions[i] } }
                when (kids.size) {
                    0 -> null
                    1 -> kids[0]
                    else -> LayoutNode.Split(n.axis, kids, normalise(fr))
                }
            }
        }

        private fun pathTo(n: LayoutNode, pane: PaneId): List<Int>? = when (n) {
            is LayoutNode.Leaf -> if (n.pane == pane) emptyList() else null
            is LayoutNode.Split -> n.children.indices.firstNotNullOfOrNull { i -> pathTo(n.children[i], pane)?.let { listOf(i) + it } }
        }

        private fun nodeAt(n: LayoutNode, path: List<Int>): LayoutNode =
            path.fold(n) { cur, i -> (cur as LayoutNode.Split).children[i] }

        private fun replaceAt(n: LayoutNode, path: List<Int>, with: LayoutNode): LayoutNode {
            if (path.isEmpty()) return with
            val s = n as LayoutNode.Split
            return s.copy(children = s.children.mapIndexed { i, c -> if (i == path[0]) replaceAt(c, path.drop(1), with) else c })
        }
    }
}

/** Undo/redo over immutable [PaneLayout] snapshots, so "close then undo" restores structure exactly. */
class LayoutHistory(initial: PaneLayout, private val limit: Int = 50) {
    var current: PaneLayout = initial
        private set
    private val undoStack = ArrayDeque<PaneLayout>()
    private val redoStack = ArrayDeque<PaneLayout>()
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Applies [edit] to the current layout; records history only when it is applied. */
    fun apply(edit: (PaneLayout) -> LayoutEdit): LayoutEdit {
        val r = edit(current)
        if (r is LayoutEdit.Applied && r.layout != current) {
            undoStack.addLast(current)
            if (undoStack.size > limit) undoStack.removeFirst()
            redoStack.clear()
            current = r.layout
        }
        return r
    }

    fun undo(): Boolean {
        val prev = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(current); current = prev; return true
    }

    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(current); current = next; return true
    }
}
