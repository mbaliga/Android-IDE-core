package dev.aarso.domain.remote.term

/**
 * The terminal screen as a pure model (docs/build-plan.md, Sprint 2). A remote PTY emits a
 * byte stream; [VtParser] folds it into this grid of [Cell]s + a [Cursor]. Modelling the
 * terminal purely means the whole thing is JVM-testable and the Compose view (design-gated)
 * is a thin renderer over [rows] — the terminal stays a *watched object* showing the remote's
 * literal output, never our paraphrase.
 *
 * Scope: a single screen with optional scrollback. Common VT100/ANSI control + CSI handling
 * (cursor moves, SGR, erase) lives in [VtParser]; this class just holds and mutates the grid.
 * Pure Kotlin; JVM-tested.
 */

/** Text attributes for a cell. A small, honest subset of SGR. */
data class Sgr(
    val fg: Int? = null,          // 0..7 standard ANSI palette index, or null = default
    val bg: Int? = null,
    val bold: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
) {
    companion object { val DEFAULT = Sgr() }
}

/** One screen cell: a single character and its attributes. */
data class Cell(val char: Char = ' ', val sgr: Sgr = Sgr.DEFAULT)

/** Cursor position (0-based row/col). */
data class Cursor(val row: Int = 0, val col: Int = 0)

class ScreenBuffer(
    var rows: Int = 24,
    var cols: Int = 80,
    val maxScrollback: Int = 1000,
) {
    private var grid: Array<Array<Cell>> = blank(rows, cols)
    private val scrollback = ArrayDeque<Array<Cell>>()

    var cursor: Cursor = Cursor()
        private set
    var pen: Sgr = Sgr.DEFAULT

    private fun blank(r: Int, c: Int) = Array(r) { Array(c) { Cell() } }

    /** A snapshot of the visible grid as immutable rows (what a renderer draws). */
    fun rowsSnapshot(): List<List<Cell>> = grid.map { it.toList() }

    /** The visible row [r] as a plain string (trailing blanks trimmed) — handy for tests/logs. */
    fun lineText(r: Int): String = grid[r].joinToString("") { it.char.toString() }.trimEnd()

    /** Scrollback lines, oldest first. */
    fun scrollbackSnapshot(): List<List<Cell>> = scrollback.map { it.toList() }

    fun setCursor(row: Int, col: Int) {
        cursor = Cursor(row.coerceIn(0, rows - 1), col.coerceIn(0, cols - 1))
    }

    fun moveCursor(dRow: Int, dCol: Int) = setCursor(cursor.row + dRow, cursor.col + dCol)

    /** Write one printable char at the cursor, advancing (wrapping + scrolling as needed). */
    fun put(ch: Char) {
        if (cursor.col >= cols) { carriageReturn(); lineFeed() }
        grid[cursor.row][cursor.col] = Cell(ch, pen)
        cursor = cursor.copy(col = cursor.col + 1)
    }

    fun carriageReturn() { cursor = cursor.copy(col = 0) }

    fun backspace() { if (cursor.col > 0) cursor = cursor.copy(col = cursor.col - 1) }

    /** Tab to the next 8-column stop. */
    fun tab() { setCursor(cursor.row, ((cursor.col / 8) + 1) * 8) }

    /** Line feed: down a row, scrolling the top line into scrollback at the bottom edge. */
    fun lineFeed() {
        if (cursor.row >= rows - 1) scrollUp() else cursor = cursor.copy(row = cursor.row + 1)
    }

    private fun scrollUp() {
        val top = grid.first()
        scrollback.addLast(top)
        while (scrollback.size > maxScrollback) scrollback.removeFirst()
        for (r in 0 until rows - 1) grid[r] = grid[r + 1]
        grid[rows - 1] = Array(cols) { Cell(sgr = pen) }
    }

    /** Erase from the cursor to end of line (CSI K, mode 0). */
    fun eraseToLineEnd() { for (c in cursor.col until cols) grid[cursor.row][c] = Cell(sgr = pen) }

    /** Erase the whole current line (CSI K, mode 2). */
    fun eraseLine() { for (c in 0 until cols) grid[cursor.row][c] = Cell(sgr = pen) }

    /** Erase from the cursor to end of screen (CSI J, mode 0). */
    fun eraseToScreenEnd() {
        eraseToLineEnd()
        for (r in cursor.row + 1 until rows) for (c in 0 until cols) grid[r][c] = Cell(sgr = pen)
    }

    /** Clear the whole screen (CSI J, mode 2); cursor unchanged. */
    fun clear() { grid = blank(rows, cols) }

    /** Resize the grid, preserving overlapping content; cursor clamped. */
    fun resize(newRows: Int, newCols: Int) {
        val ng = Array(newRows) { r -> Array(newCols) { c ->
            if (r < rows && c < cols) grid[r][c] else Cell()
        } }
        grid = ng; rows = newRows; cols = newCols
        setCursor(cursor.row, cursor.col)
    }
}
