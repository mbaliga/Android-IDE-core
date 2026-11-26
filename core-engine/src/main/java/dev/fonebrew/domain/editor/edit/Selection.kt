package dev.fonebrew.domain.editor.edit

/** A selection: [anchor] is where it started, [head] is the moving end (the caret). Offsets are UTF-16 units. */
data class Selection(val anchor: Int, val head: Int) {
    val start: Int get() = minOf(anchor, head)
    val end: Int get() = maxOf(anchor, head)
    val isEmpty: Boolean get() = anchor == head
    val length: Int get() = end - start
    fun collapsedToHead() = Selection(head, head)

    companion object { fun caret(at: Int) = Selection(at, at) }
}

/**
 * One primary selection plus any number of additional ones (multi-cursor is designed in; the v1 UI
 * uses one). Always normalised: sorted, non-overlapping, identical carets merged. [primaryIndex]
 * points into [ranges].
 */
class SelectionSet private constructor(val ranges: List<Selection>, val primaryIndex: Int) {
    val primary: Selection get() = ranges[primaryIndex]
    val size: Int get() = ranges.size
    val isSingle: Boolean get() = ranges.size == 1

    fun withPrimary(sel: Selection): SelectionSet =
        of(ranges.mapIndexed { i, r -> if (i == primaryIndex) sel else r }, primaryIndex)

    fun onlyPrimary(): SelectionSet = single(primary)

    fun map(f: (Selection) -> Selection): SelectionSet = of(ranges.map(f), primaryIndex)

    override fun equals(other: Any?) = other is SelectionSet && ranges == other.ranges && primaryIndex == other.primaryIndex
    override fun hashCode() = ranges.hashCode() * 31 + primaryIndex
    override fun toString() = "SelectionSet($ranges, primary=$primaryIndex)"

    companion object {
        fun single(sel: Selection) = SelectionSet(listOf(sel), 0)
        fun caret(at: Int) = single(Selection.caret(at))

        /** Sort, merge overlapping/identical ranges; the merged range holding [primaryIndex] stays primary. */
        fun of(sels: List<Selection>, primaryIndex: Int = 0): SelectionSet {
            require(sels.isNotEmpty()) { "a selection set needs at least one range" }
            val p = sels[primaryIndex.coerceIn(sels.indices)]
            val sorted = sels.sortedWith(compareBy({ it.start }, { it.end }))
            val out = ArrayList<Selection>()
            var primaryOut = -1
            for (s in sorted) {
                val last = out.lastOrNull()
                // overlap (or same caret) merges; touching non-empty ranges stay separate
                if (last != null && (s.start < last.end || (s.start == last.end && (s.isEmpty || last.isEmpty)))) {
                    val forward = last.head >= last.anchor
                    val merged = if (forward) Selection(last.start, maxOf(last.end, s.end)) else Selection(maxOf(last.end, s.end), last.start)
                    out[out.size - 1] = merged
                } else out += s
                if (s === p || s == p) primaryOut = out.size - 1
            }
            return SelectionSet(out, primaryOut.coerceAtLeast(0))
        }
    }
}
