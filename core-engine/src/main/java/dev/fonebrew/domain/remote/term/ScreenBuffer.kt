package dev.fonebrew.domain.remote.term

/**
 * The terminal screen as a pure model (docs/build-plan.md, Sprint 2). A remote PTY emits a
 * byte stream; [VtParser] folds it into this grid of [Cell]s + a [Cursor]. Modelling the
 * terminal purely means the whole thing is JVM-testable and the Compose view is a thin renderer
 * over [rowsSnapshot] + [scrollbackSnapshot] — the terminal stays a *watched object* showing the
 * remote's literal output, never our paraphrase.
 *
 * Scope: one screen, a scrollback, an alternate screen, and a DECSTBM scroll region. Common
 * VT100/ANSI control + CSI handling lives in [VtParser]; this class holds and mutates the grid.
 * Pure Kotlin; JVM-tested.
 */

/**
 * A colour the pen can carry. This was a bare `Int?` palette index 0–7, which cannot represent
 * what real programs actually emit — xterm-256 (`ESC[38;5;n`) and 24-bit truecolour
 * (`ESC[38;2;r;g;b`). The old parser folded those sequences' sub-parameters as if each number
 * were a standalone SGR code, so `ESC[38;2;30;144;255m` executed `30` ("foreground black") and
 * the prompt rendered as near-black on the near-black terminal panel: invisible, not degraded.
 */
sealed interface TermColor {
    /** An xterm palette slot: 0–7 standard, 8–15 bright, 16–231 the 6×6×6 cube, 232–255 greys. */
    data class Indexed(val index: Int) : TermColor

    /** 24-bit truecolour, packed 0xRRGGBB. */
    data class Rgb(val rgb: Int) : TermColor
}

/** Text attributes for a cell. A small, honest subset of SGR. */
data class Sgr(
    val fg: TermColor? = null,    // null = the terminal's own default foreground
    val bg: TermColor? = null,
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
    rows: Int = 24,
    cols: Int = 80,
    val maxScrollback: Int = 1000,
) {
    var rows: Int = rows.coerceAtLeast(1)
        private set
    var cols: Int = cols.coerceAtLeast(1)
        private set

    private var grid: Array<Array<Cell>> = blank(this.rows, this.cols)
    private val scrollback = ArrayDeque<Array<Cell>>()

    var cursor: Cursor = Cursor()
        private set
    var pen: Sgr = Sgr.DEFAULT

    /**
     * DECTCEM (`CSI ?25h/l`). A full-screen app hides the cursor while it repaints; drawing our
     * inverted block anyway leaves a bright artefact sitting in the middle of vim's buffer.
     */
    var cursorVisible: Boolean = true

    /**
     * The DECSTBM scroll region (0-based, inclusive), defaulting to the whole screen. Curses
     * apps set one and then rely on LF/RI scrolling *inside* it — without this, a pager's
     * status bar scrolls away with the text it is supposed to be pinned under.
     */
    var scrollTop: Int = 0
        private set
    var scrollBottom: Int = this.rows - 1
        private set

    /** True while the alternate screen is up (`CSI ?1049h` / `?47h`). See [enterAltScreen]. */
    var altScreen: Boolean = false
        private set

    private var altSavedGrid: Array<Array<Cell>>? = null
    private var altSavedCursor: Cursor = Cursor()
    private var altSavedPen: Sgr = Sgr.DEFAULT

    // DECSC/DECRC (ESC 7 / ESC 8) and CSI s/u — a saved cursor *and* pen, which is the pair the
    // spec saves and what a prompt-repainting shell expects back.
    private var markCursor: Cursor? = null
    private var markPen: Sgr = Sgr.DEFAULT

    private fun blank(r: Int, c: Int) = Array(r) { Array(c) { Cell() } }

    /** A snapshot of the visible grid as immutable rows (what a renderer draws). */
    fun rowsSnapshot(): List<List<Cell>> = grid.map { it.toList() }

    /** The visible row [r] as a plain string (trailing blanks trimmed) — handy for tests/logs. */
    fun lineText(r: Int): String = grid[r].joinToString("") { it.char.toString() }.trimEnd()

    /**
     * Scrollback lines, oldest first, optionally only the newest [limit] of them. The limit
     * exists for the renderer: the buffer holds up to [maxScrollback] lines, but composing all
     * of them as text every time a byte arrives is a phone-scale cost, and the newest few
     * hundred are what a reader is ever scrolling back through.
     */
    fun scrollbackSnapshot(limit: Int = Int.MAX_VALUE): List<List<Cell>> {
        val from = (scrollback.size - limit).coerceAtLeast(0)
        return (from until scrollback.size).map { scrollback[it].toList() }
    }

    fun setCursor(row: Int, col: Int) {
        cursor = Cursor(row.coerceIn(0, rows - 1), col.coerceIn(0, cols - 1))
    }

    fun moveCursor(dRow: Int, dCol: Int) = setCursor(cursor.row + dRow, cursor.col + dCol)

    fun saveCursor() { markCursor = cursor; markPen = pen }

    fun restoreCursor() {
        markCursor?.let { setCursor(it.row, it.col); pen = markPen }
    }

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

    /** Line feed: down a row, scrolling at the *scroll region's* bottom (not just the screen's). */
    fun lineFeed() {
        when {
            cursor.row == scrollBottom -> scrollUp(1)
            cursor.row < rows - 1 -> cursor = cursor.copy(row = cursor.row + 1)
        }
    }

    /** Reverse index (ESC M): up a row, scrolling the region down at its top edge. */
    fun reverseIndex() {
        when {
            cursor.row == scrollTop -> scrollDown(1)
            cursor.row > 0 -> cursor = cursor.copy(row = cursor.row - 1)
        }
    }

    /**
     * Set the DECSTBM region (0-based, inclusive). An empty/inverted region resets to the whole
     * screen, which is what `CSI r` with no parameters means. DECSTBM homes the cursor.
     */
    fun setScrollRegion(top: Int, bottom: Int) {
        val t = top.coerceIn(0, rows - 1)
        val b = bottom.coerceIn(0, rows - 1)
        if (t >= b) resetScrollRegion() else { scrollTop = t; scrollBottom = b }
        setCursor(scrollTop, 0)
    }

    fun resetScrollRegion() { scrollTop = 0; scrollBottom = rows - 1 }

    /** Scroll the region up [n] lines (CSI S, and what LF does at the region's bottom). */
    fun scrollUp(n: Int = 1) {
        repeat(n.coerceIn(0, rows)) {
            // Only a line leaving the top of the WHOLE screen is transcript. A line pushed out
            // of a smaller DECSTBM region (a pager's text pane) never left the screen at all,
            // and the alt screen is by definition not transcript — neither belongs in scrollback.
            if (scrollTop == 0 && !altScreen) {
                scrollback.addLast(grid[0])
                while (scrollback.size > maxScrollback) scrollback.removeFirst()
            }
            for (r in scrollTop until scrollBottom) grid[r] = grid[r + 1]
            grid[scrollBottom] = Array(cols) { Cell(sgr = pen) }
        }
    }

    /** Scroll the region down [n] lines (CSI T, and what RI does at the region's top). */
    fun scrollDown(n: Int = 1) {
        repeat(n.coerceIn(0, rows)) {
            for (r in scrollBottom downTo scrollTop + 1) grid[r] = grid[r - 1]
            grid[scrollTop] = Array(cols) { Cell(sgr = pen) }
        }
    }

    /** Insert [n] blank lines at the cursor row, pushing the rest of the region down (CSI L). */
    fun insertLines(n: Int) {
        if (cursor.row < scrollTop || cursor.row > scrollBottom) return
        repeat(n.coerceIn(0, rows)) {
            for (r in scrollBottom downTo cursor.row + 1) grid[r] = grid[r - 1]
            grid[cursor.row] = Array(cols) { Cell(sgr = pen) }
        }
    }

    /** Delete [n] lines at the cursor row, pulling the rest of the region up (CSI M). */
    fun deleteLines(n: Int) {
        if (cursor.row < scrollTop || cursor.row > scrollBottom) return
        repeat(n.coerceIn(0, rows)) {
            for (r in cursor.row until scrollBottom) grid[r] = grid[r + 1]
            grid[scrollBottom] = Array(cols) { Cell(sgr = pen) }
        }
    }

    /** Insert [n] blanks at the cursor, shifting the rest of the line right (CSI @). */
    fun insertChars(n: Int) {
        val k = n.coerceIn(0, cols)
        if (k == 0 || cursor.col >= cols) return
        val row = grid[cursor.row]
        for (c in cols - 1 downTo cursor.col + k) row[c] = row[c - k]
        for (c in cursor.col until (cursor.col + k).coerceAtMost(cols)) row[c] = Cell(sgr = pen)
    }

    /** Delete [n] chars at the cursor, pulling the rest of the line left (CSI P). */
    fun deleteChars(n: Int) {
        val k = n.coerceIn(0, cols)
        if (k == 0 || cursor.col >= cols) return
        val row = grid[cursor.row]
        for (c in cursor.col until cols) row[c] = if (c + k < cols) row[c + k] else Cell(sgr = pen)
    }

    /** Blank [n] cells at the cursor without moving anything (CSI X). */
    fun eraseChars(n: Int) {
        val k = n.coerceIn(0, cols)
        if (cursor.col >= cols) return
        val row = grid[cursor.row]
        for (c in cursor.col until (cursor.col + k).coerceAtMost(cols)) row[c] = Cell(sgr = pen)
    }

    /** Erase from the cursor to end of line (CSI K, mode 0). */
    fun eraseToLineEnd() { for (c in cursor.col until cols) grid[cursor.row][c] = Cell(sgr = pen) }

    /** Erase from the start of the line through the cursor (CSI K, mode 1). */
    fun eraseToLineStart() {
        for (c in 0..cursor.col.coerceAtMost(cols - 1)) grid[cursor.row][c] = Cell(sgr = pen)
    }

    /** Erase the whole current line (CSI K, mode 2). */
    fun eraseLine() { for (c in 0 until cols) grid[cursor.row][c] = Cell(sgr = pen) }

    /** Erase from the cursor to end of screen (CSI J, mode 0). */
    fun eraseToScreenEnd() {
        eraseToLineEnd()
        for (r in cursor.row + 1 until rows) for (c in 0 until cols) grid[r][c] = Cell(sgr = pen)
    }

    /** Erase from the top of the screen through the cursor (CSI J, mode 1). */
    fun eraseToScreenStart() {
        for (r in 0 until cursor.row) for (c in 0 until cols) grid[r][c] = Cell(sgr = pen)
        eraseToLineStart()
    }

    /** Clear the whole screen (CSI J, mode 2); cursor unchanged. */
    fun clear() { grid = Array(rows) { Array(cols) { Cell(sgr = pen) } } }

    /**
     * Switch to the alternate screen (`CSI ?1049h`, `?47h`) — the separate, scrollback-free grid
     * vi/nano/htop draw into. Without it those apps paint their full-screen UI straight into the
     * transcript and leave the wreckage behind when they exit; with it, [exitAltScreen] puts the
     * shell's own screen back exactly as it was.
     */
    fun enterAltScreen() {
        if (altScreen) return
        altSavedGrid = grid
        altSavedCursor = cursor
        altSavedPen = pen
        grid = blank(rows, cols)
        altScreen = true
        resetScrollRegion()
        cursor = Cursor()
    }

    /** Return to the main screen, restoring the grid, pen and cursor the app displaced. */
    fun exitAltScreen() {
        if (!altScreen) return
        val saved = altSavedGrid
        altScreen = false
        altSavedGrid = null
        resetScrollRegion()
        // The window may have been resized while the app was up, so re-fit rather than trusting
        // the saved grid's own dimensions.
        grid = Array(rows) { r ->
            Array(cols) { c -> saved?.getOrNull(r)?.getOrNull(c) ?: Cell() }
        }
        pen = altSavedPen
        setCursor(altSavedCursor.row, altSavedCursor.col)
    }

    /** Full reset — grid, scrollback, and cursor — for switching to a different machine
     *  entirely, where the old session's history shouldn't linger (unlike [clear], which is
     *  the CSI J the remote/local shell itself sends and only ever touches the visible grid). */
    fun resetAll() {
        grid = blank(rows, cols)
        scrollback.clear()
        cursor = Cursor()
        pen = Sgr.DEFAULT
        cursorVisible = true
        altScreen = false
        altSavedGrid = null
        markCursor = null
        resetScrollRegion()
    }

    /**
     * Resize the grid to the window the phone is actually rendering.
     *
     * This used to be dead code (nothing ever called it) and it kept the *top-left* corner. Now
     * that the view measures itself and drives the real size in, shrinking is the common case —
     * the soft keyboard opening takes rows away — and keeping the top would scroll the live
     * prompt off the bottom, which is the one line the user needs. So rows are dropped from the
     * top instead, and never past the cursor's own row; the dropped lines are real transcript,
     * so they go to scrollback rather than the bin.
     */
    fun resize(newRows: Int, newCols: Int) {
        val r = newRows.coerceAtLeast(1)
        val c = newCols.coerceAtLeast(1)
        if (r == rows && c == cols) return

        val drop = (rows - r).coerceIn(0, cursor.row)
        if (!altScreen) {
            for (i in 0 until drop) {
                scrollback.addLast(grid[i])
                while (scrollback.size > maxScrollback) scrollback.removeFirst()
            }
        }
        val old = grid
        grid = Array(r) { rr -> Array(c) { cc -> old.getOrNull(rr + drop)?.getOrNull(cc) ?: Cell() } }
        val wasRow = cursor.row - drop
        val wasCol = cursor.col
        rows = r
        cols = c
        resetScrollRegion()
        setCursor(wasRow, wasCol)
    }
}
