package dev.fonebrew.domain.remote.term

/**
 * Bounded ring of scrollback lines, oldest first. Bounded two ways: by line count and by a total
 * cell budget (the memory ceiling - see docs/design/terminal-engine.md). Pushing past either
 * bound evicts the oldest lines; nothing grows without limit. Storage is a plain circular array
 * that grows by doubling only up to [maxLines], so an idle terminal holds almost nothing.
 */
internal class LineRing<T>(maxLines: Int, maxCells: Int, private val cellsOf: (T) -> Int) {
    var maxLines: Int = maxLines
        private set
    private val maxCells = maxCells.coerceAtLeast(1)
    private var arr = arrayOfNulls<Any?>(minOf(maxLines.coerceAtLeast(1), 64))
    private var head = 0
    var size = 0
        private set
    var cells = 0
        private set

    @Suppress("UNCHECKED_CAST")
    operator fun get(i: Int): T {
        require(i in 0 until size) { "index $i size $size" }
        return arr[(head + i) % arr.size] as T
    }

    fun push(item: T) {
        if (maxLines == 0) return
        if (size == arr.size && arr.size < maxLines) grow()
        if (size == arr.size) dropOldest()
        arr[(head + size) % arr.size] = item
        size++
        cells += cellsOf(item)
        // The newest line is never evicted by the cell budget alone (a single huge line would
        // otherwise empty the ring); the line bound already caps it.
        while (cells > maxCells && size > 1) dropOldest()
    }

    fun clear() {
        arr.fill(null); head = 0; size = 0; cells = 0
    }

    fun setMaxLines(n: Int) {
        maxLines = n.coerceAtLeast(0)
        while (size > maxLines) dropOldest()
    }

    fun toList(): List<T> = (0 until size).map { get(it) }

    @Suppress("UNCHECKED_CAST")
    private fun dropOldest() {
        if (size == 0) return
        cells -= cellsOf(arr[head] as T)
        arr[head] = null
        head = (head + 1) % arr.size
        size--
    }

    private fun grow() {
        val n = arrayOfNulls<Any?>(minOf(arr.size * 2, maxLines))
        for (i in 0 until size) n[i] = arr[(head + i) % arr.size]
        arr = n
        head = 0
    }
}
