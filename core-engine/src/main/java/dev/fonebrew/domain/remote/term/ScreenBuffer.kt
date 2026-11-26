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

/**
 * One screen cell. [char] is the first UTF-16 unit and is what the original renderer reads;
 * [glyph] is the whole grapheme as drawn (a base character plus any combining marks, or a
 * supplementary-plane character). [width] is 1, 2 (left half of a wide character) or 0 (the
 * right half of a wide character - a renderer skips it).
 */
data class Cell(
    val char: Char = ' ',
    val sgr: Sgr = Sgr.DEFAULT,
    val width: Int = 1,
    val cluster: String? = null,
) {
    val glyph: String get() = cluster ?: char.toString()
}

/** Cursor position (0-based row/col). */
data class Cursor(val row: Int = 0, val col: Int = 0)

/** A line as exported to a renderer / copy: its cells and whether it soft-wrapped into the next. */
class TermLine(val cells: List<Cell>, val wrapped: Boolean)

/** Internal mutable row. [wrapped] = this row is continued by the next one (soft wrap). */
internal class Row(var cells: Array<Cell>, var wrapped: Boolean = false) {
    /** The last cell is only padding left because a wide character wrapped whole; not content. */
    var pad = false
    fun copy() = Row(cells.copyOf(), wrapped).also { it.pad = pad }
}

class ScreenBuffer(
    rows: Int = 24,
    cols: Int = 80,
    maxScrollback: Int = DEFAULT_SCROLLBACK,
    scrollbackCellBudget: Int = DEFAULT_SCROLLBACK_CELLS,
) {
    var rows: Int = rows.coerceAtLeast(1)
        private set
    var cols: Int = cols.coerceAtLeast(1)
        private set

    /** Effective scrollback line limit: the requested value clamped to [0, [HARD_MAX_SCROLLBACK]]. */
    var maxScrollback: Int = maxScrollback.coerceIn(0, HARD_MAX_SCROLLBACK)
        private set

    private var grid: Array<Row> = blank(this.rows, this.cols)
    private val scrollback = LineRing<Row>(this.maxScrollback, scrollbackCellBudget) { it.cells.size }

    var cursor: Cursor = Cursor()
        private set
    var pen: Sgr = Sgr.DEFAULT

    /**
     * DECTCEM (`CSI ?25h/l`). A full-screen app hides the cursor while it repaints; drawing our
     * inverted block anyway leaves a bright artefact sitting in the middle of vim's buffer.
     */
    var cursorVisible: Boolean = true

    /** DECAWM (`CSI ?7h/l`): wrap at the right margin (default) or overwrite the last cell. */
    var autoWrap: Boolean = true

    /**
     * The DECSTBM scroll region (0-based, inclusive), defaulting to the whole screen. Curses
     * apps set one and then rely on LF/RI scrolling *inside* it - without this, a pager's
     * status bar scrolls away with the text it is supposed to be pinned under.
     */
    var scrollTop: Int = 0
        private set
    var scrollBottom: Int = this.rows - 1
        private set

    /** True while the alternate screen is up (`CSI ?1049h` / `?47h`). See [enterAltScreen]. */
    var altScreen: Boolean = false
        private set

    private var altSavedGrid: Array<Row>? = null
    private var altSavedCursor: Cursor = Cursor()
    private var altSavedPen: Sgr = Sgr.DEFAULT

    // DECSC/DECRC (ESC 7 / ESC 8) and CSI s/u - a saved cursor *and* pen, which is the pair the
    // spec saves and what a prompt-repainting shell expects back.
    private var markCursor: Cursor? = null
    private var markPen: Sgr = Sgr.DEFAULT

    private fun blankCells(c: Int, pen: Sgr = Sgr.DEFAULT): Array<Cell> = Array(c) { Cell(sgr = pen) }
    private fun blankRow(pen: Sgr = Sgr.DEFAULT) = Row(blankCells(cols, pen))
    private fun blank(r: Int, c: Int): Array<Row> = Array(r) { Row(blankCells(c)) }

    // --- reading (synchronised: the reader thread mutates while the UI snapshots) ---------------

    /** A snapshot of the visible grid as immutable rows (what a renderer draws). */
    @Synchronized fun rowsSnapshot(): List<List<Cell>> = grid.map { it.cells.toList() }

    /** The visible grid with each row's soft-wrap flag. */
    @Synchronized fun visibleLines(): List<TermLine> = grid.map { TermLine(it.cells.toList(), it.wrapped) }

    /** The visible row [r] as plain text (trailing blanks trimmed) - handy for tests/logs. */
    @Synchronized fun lineText(r: Int): String = rowText(grid[r]).trimEnd()

    /**
     * Scrollback lines, oldest first, optionally only the newest [limit] of them. The limit
     * exists for the renderer: composing thousands of lines as text every time a byte arrives is
     * a phone-scale cost, and the newest few hundred are what a reader scrolls back through.
     * Stored lines may be shorter than [cols] (trailing blanks are trimmed to save memory).
     */
    @Synchronized fun scrollbackSnapshot(limit: Int = Int.MAX_VALUE): List<List<Cell>> =
        scrollbackLines(limit).map { it.cells }

    /** As [scrollbackSnapshot], with each line's soft-wrap flag (for correct copy). */
    @Synchronized fun scrollbackLines(limit: Int = Int.MAX_VALUE): List<TermLine> {
        val from = (scrollback.size - limit).coerceAtLeast(0)
        return (from until scrollback.size).map { scrollback[it].let { r -> TermLine(r.cells.toList(), r.wrapped) } }
    }

    /** Number of stored scrollback lines. */
    @Synchronized fun scrollbackSize(): Int = scrollback.size

    /** Total cells held by scrollback (the quantity the memory ceiling bounds). */
    @Synchronized fun scrollbackCells(): Int = scrollback.cells

    /**
     * The whole transcript - scrollback then the visible screen - as text, with soft-wrapped
     * rows joined (a copied long line is one line, not several broken at the window width) and
     * blank rows below the cursor dropped. Trailing blanks are trimmed per logical line.
     */
    @Synchronized fun transcriptText(): String {
        val all = ArrayList<Row>(scrollback.size + rows)
        for (i in 0 until scrollback.size) all.add(scrollback[i])
        val last = if (altScreen) -1 else lastContentRow()
        for (r in 0..last) all.add(grid[r])
        val sb = StringBuilder()
        val line = StringBuilder()
        for (row in all) {
            line.append(rowText(row))
            if (!row.wrapped) {
                sb.append(line.toString().trimEnd()).append('\n')
                line.setLength(0)
            }
        }
        if (line.isNotEmpty()) sb.append(line.toString().trimEnd()).append('\n')
        // Blank lines at the very end (the empty cursor row after a final newline) are not content.
        return sb.toString().trimEnd('\n').let { if (it.isEmpty()) it else it + "\n" }
    }

    private fun rowText(row: Row): String {
        val sb = StringBuilder()
        val n = if (row.pad && row.wrapped) row.cells.size - 1 else row.cells.size
        for (k in 0 until n) { val c = row.cells[k]; if (c.width != 0) sb.append(c.glyph) }
        return sb.toString()
    }

    /** Last grid row that is not blank, or the cursor row if that is lower. */
    private fun lastContentRow(): Int {
        var last = cursor.row
        for (r in rows - 1 downTo last + 1) if (!isBlankRow(grid[r])) { last = r; break }
        return last
    }

    private fun isBlankRow(row: Row) = row.cells.all { isBlankCell(it) }
    private fun isBlankCell(c: Cell) = c.char == ' ' && c.sgr == Sgr.DEFAULT && c.width == 1 && c.cluster == null

    // --- cursor ---------------------------------------------------------------------------------

    fun setCursor(row: Int, col: Int) {
        cursor = Cursor(row.coerceIn(0, rows - 1), col.coerceIn(0, cols - 1))
    }

    fun moveCursor(dRow: Int, dCol: Int) = setCursor(cursor.row + dRow, cursor.col + dCol)

    fun saveCursor() { markCursor = cursor; markPen = pen }

    fun restoreCursor() {
        markCursor?.let { setCursor(it.row, it.col); pen = markPen }
    }

    // --- writing --------------------------------------------------------------------------------

    /** Write one printable BMP char at the cursor. See [putCodePoint]. */
    fun put(ch: Char) = putCodePoint(ch.code)

    /**
     * Write one printable code point at the cursor, advancing (wrapping + scrolling as needed).
     * Wide characters take two cells (a width-2 lead and a width-0 continuation); zero-width
     * code points attach to the previous cell; a wide character that does not fit at the right
     * margin wraps whole.
     */
    fun putCodePoint(cp: Int) {
        val w = WcWidth.of(cp)
        if (w == 0) { attachCombining(cp); return }
        if (cursor.col + w > cols) {
            if (autoWrap) {
                grid[cursor.row].let { r ->
                    // A wide char that cannot fit leaves a blank in the last cell.
                    if (cursor.col < cols) r.cells[cursor.col] = Cell(sgr = pen)
                    r.wrapped = true
                    r.pad = cursor.col < cols && w == 2
                }
                carriageReturn(); lineFeed()
            } else {
                cursor = cursor.copy(col = (cols - w).coerceAtLeast(0))
            }
        }
        val row = grid[cursor.row]
        val c = cursor.col
        // Overwriting half of a wide character must not leave the other half behind.
        breakWideAt(row, c)
        if (w == 2 && c + 1 < cols) breakWideAt(row, c + 1)
        val glyph = if (cp > 0xFFFF) String(Character.toChars(cp)) else null
        val ch = if (cp > 0xFFFF) Character.highSurrogate(cp) else cp.toChar()
        if (w == 2 && c + 1 >= cols) {
            // 1-column terminal: a wide character cannot be shown; draw it narrow.
            row.cells[c] = Cell(ch, pen, 1, glyph)
        } else {
            row.cells[c] = Cell(ch, pen, w, glyph)
            if (w == 2) row.cells[c + 1] = Cell(' ', pen, 0)
        }
        cursor = cursor.copy(col = c + (if (w == 2 && c + 1 >= cols) 1 else w))
    }

    private fun breakWideAt(row: Row, c: Int) {
        if (c !in row.cells.indices) return
        val cell = row.cells[c]
        if (cell.width == 0 && c > 0) row.cells[c - 1] = Cell(sgr = row.cells[c - 1].sgr)
        if (cell.width == 2 && c + 1 < row.cells.size) row.cells[c + 1] = Cell(sgr = row.cells[c + 1].sgr)
    }

    private fun attachCombining(cp: Int) {
        var c = if (cursor.col >= cols) cols - 1 else cursor.col - 1
        if (c < 0) return // nothing to attach to; a lone combining mark is dropped
        val row = grid[cursor.row]
        if (row.cells[c].width == 0 && c > 0) c--
        val base = row.cells[c]
        val prev = base.cluster ?: base.char.toString()
        if (prev.length >= MAX_CLUSTER) return // bounded: a hostile stream cannot grow a cell
        row.cells[c] = base.copy(cluster = prev + String(Character.toChars(cp)))
    }

    /** After an insert/delete/erase, repair any wide character whose other half was removed. */
    private fun fixWide(row: Row) {
        val cs = row.cells
        for (i in cs.indices) {
            val cell = cs[i]
            if (cell.width == 2 && (i + 1 >= cs.size || cs[i + 1].width != 0)) cs[i] = Cell(sgr = cell.sgr)
            if (cell.width == 0 && (i == 0 || cs[i - 1].width != 2)) cs[i] = Cell(sgr = cell.sgr)
        }
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
            // and the alt screen is by definition not transcript - neither belongs in scrollback.
            if (scrollTop == 0 && !altScreen) pushScrollback(grid[0])
            for (r in scrollTop until scrollBottom) grid[r] = grid[r + 1]
            grid[scrollBottom] = blankRow(pen)
        }
    }

    /** Trim trailing default blanks of a non-wrapped row (memory), then bank it. */
    private fun pushScrollback(row: Row) {
        if (!row.wrapped) {
            var n = row.cells.size
            while (n > 0 && isBlankCell(row.cells[n - 1])) n--
            if (n < row.cells.size) row.cells = row.cells.copyOf(n).requireNoNulls()
        }
        scrollback.push(row)
    }

    /** Scroll the region down [n] lines (CSI T, and what RI does at the region's top). */
    fun scrollDown(n: Int = 1) {
        repeat(n.coerceIn(0, rows)) {
            for (r in scrollBottom downTo scrollTop + 1) grid[r] = grid[r - 1]
            grid[scrollTop] = blankRow(pen)
        }
    }

    /** Insert [n] blank lines at the cursor row, pushing the rest of the region down (CSI L). */
    fun insertLines(n: Int) {
        if (cursor.row < scrollTop || cursor.row > scrollBottom) return
        repeat(n.coerceIn(0, rows)) {
            for (r in scrollBottom downTo cursor.row + 1) grid[r] = grid[r - 1]
            grid[cursor.row] = blankRow(pen)
        }
    }

    /** Delete [n] lines at the cursor row, pulling the rest of the region up (CSI M). */
    fun deleteLines(n: Int) {
        if (cursor.row < scrollTop || cursor.row > scrollBottom) return
        repeat(n.coerceIn(0, rows)) {
            for (r in cursor.row until scrollBottom) grid[r] = grid[r + 1]
            grid[scrollBottom] = blankRow(pen)
        }
    }

    /** Insert [n] blanks at the cursor, shifting the rest of the line right (CSI @). */
    fun insertChars(n: Int) {
        val k = n.coerceIn(0, cols)
        if (k == 0 || cursor.col >= cols) return
        val row = grid[cursor.row].cells
        for (c in cols - 1 downTo cursor.col + k) row[c] = row[c - k]
        for (c in cursor.col until (cursor.col + k).coerceAtMost(cols)) row[c] = Cell(sgr = pen)
        fixWide(grid[cursor.row])
    }

    /** Delete [n] chars at the cursor, pulling the rest of the line left (CSI P). */
    fun deleteChars(n: Int) {
        val k = n.coerceIn(0, cols)
        if (k == 0 || cursor.col >= cols) return
        val row = grid[cursor.row].cells
        for (c in cursor.col until cols) row[c] = if (c + k < cols) row[c + k] else Cell(sgr = pen)
        fixWide(grid[cursor.row])
    }

    /** Blank [n] cells at the cursor without moving anything (CSI X). */
    fun eraseChars(n: Int) {
        val k = n.coerceIn(0, cols)
        if (cursor.col >= cols) return
        val row = grid[cursor.row].cells
        for (c in cursor.col until (cursor.col + k).coerceAtMost(cols)) row[c] = Cell(sgr = pen)
        fixWide(grid[cursor.row])
    }

    /** Erase from the cursor to end of line (CSI K, mode 0). The line no longer continues. */
    fun eraseToLineEnd() {
        val r = grid[cursor.row]
        for (c in cursor.col until cols) r.cells[c] = Cell(sgr = pen)
        r.wrapped = false; r.pad = false
        fixWide(r)
    }

    /** Erase from the start of the line through the cursor (CSI K, mode 1). */
    fun eraseToLineStart() {
        val r = grid[cursor.row]
        for (c in 0..cursor.col.coerceAtMost(cols - 1)) r.cells[c] = Cell(sgr = pen)
        fixWide(r)
    }

    /** Erase the whole current line (CSI K, mode 2). */
    fun eraseLine() {
        val r = grid[cursor.row]
        for (c in 0 until cols) r.cells[c] = Cell(sgr = pen)
        r.wrapped = false; r.pad = false
    }

    /** Erase from the cursor to end of screen (CSI J, mode 0). */
    fun eraseToScreenEnd() {
        eraseToLineEnd()
        for (r in cursor.row + 1 until rows) grid[r] = blankRow(pen)
    }

    /** Erase from the top of the screen through the cursor (CSI J, mode 1). */
    fun eraseToScreenStart() {
        for (r in 0 until cursor.row) grid[r] = blankRow(pen)
        eraseToLineStart()
    }

    /** Clear the whole screen (CSI J, mode 2); cursor unchanged. */
    fun clear() { grid = Array(rows) { blankRow(pen) } }

    /** Drop the scrollback only (CSI 3 J); the visible screen is untouched. */
    fun clearScrollback() { scrollback.clear() }

    /** Change the scrollback line limit at runtime (clamped to the hard cap); evicts the oldest. */
    fun setMaxScrollback(n: Int) {
        maxScrollback = n.coerceIn(0, HARD_MAX_SCROLLBACK)
        scrollback.setMaxLines(maxScrollback)
    }

    /**
     * Switch to the alternate screen (`CSI ?1049h`, `?47h`) - the separate, scrollback-free grid
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
        grid = saved ?: blank(rows, cols)
        pen = altSavedPen
        setCursor(altSavedCursor.row, altSavedCursor.col)
    }

    /** Full reset - grid, scrollback, and cursor - for switching to a different machine
     *  entirely, where the old session's history shouldn't linger (unlike [clear], which is
     *  the CSI J the remote/local shell itself sends and only ever touches the visible grid). */
    fun resetAll() {
        grid = blank(rows, cols)
        scrollback.clear()
        cursor = Cursor()
        pen = Sgr.DEFAULT
        cursorVisible = true
        autoWrap = true
        altScreen = false
        altSavedGrid = null
        markCursor = null
        resetScrollRegion()
    }

    // --- resize + reflow ------------------------------------------------------------------------

    /**
     * Resize to the window the phone is actually rendering, **reflowing soft-wrapped lines**.
     *
     * The main screen and its scrollback are re-laid out as one transcript: rows joined by the
     * soft-wrap flag form logical lines, which are re-broken at the new width (a wide character
     * is never split). The visible window then keeps the cursor's line on screen and prefers the
     * newest content, so opening the soft keyboard (fewer rows) pushes the *top* into
     * scrollback and closing it pulls lines back out. Hard line breaks are never joined.
     * The alternate screen is not reflowed (a full-screen app repaints on SIGWINCH): it is
     * cropped/padded, while the main screen saved behind it is reflowed so it is right on exit.
     * Lines below the visible window after a shrink are dropped only if they were below the
     * cursor and non-blank.
     */
    @Synchronized fun resize(newRows: Int, newCols: Int) {
        val r = newRows.coerceAtLeast(1)
        val c = newCols.coerceAtLeast(1)
        if (r == rows && c == cols) return
        if (altScreen) {
            grid = fit(grid, r, c)
            val (g, cur) = reflow(altSavedGrid!!, altSavedCursor, r, c)
            altSavedGrid = g
            altSavedCursor = cur
            cursor = Cursor(cursor.row.coerceIn(0, r - 1), cursor.col.coerceIn(0, c - 1))
        } else {
            val (g, cur) = reflow(grid, cursor, r, c)
            grid = g
            cursor = cur
        }
        rows = r
        cols = c
        resetScrollRegion()
        markCursor = markCursor?.let { Cursor(it.row.coerceIn(0, r - 1), it.col.coerceIn(0, c - 1)) }
        cursor = Cursor(cursor.row.coerceIn(0, r - 1), cursor.col.coerceIn(0, c))
    }

    /** Crop/pad without reflow (alternate screen). */
    private fun fit(src: Array<Row>, r: Int, c: Int): Array<Row> = Array(r) { rr ->
        val old = src.getOrNull(rr)
        Row(Array(c) { cc -> old?.cells?.getOrNull(cc) ?: Cell() }, false).also { fixWide(it) }
    }

    private fun reflow(mainGrid: Array<Row>, cur: Cursor, r: Int, c: Int): Pair<Array<Row>, Cursor> {
        // Content to carry: scrollback + the grid down to the last non-blank row / cursor row.
        var last = cur.row.coerceIn(0, mainGrid.size - 1)
        for (i in mainGrid.size - 1 downTo last + 1) if (!isBlankRow(mainGrid[i])) { last = i; break }
        val src = ArrayList<Row>(scrollback.size + last + 1)
        for (i in 0 until scrollback.size) src.add(scrollback[i])
        val cursorSrcIdx = src.size + cur.row.coerceIn(0, mainGrid.size - 1)
        for (i in 0..last) src.add(mainGrid[i])

        val out = ArrayList<Row>(src.size)
        var cursorAbs = 0
        var cursorCol = cur.col
        val line = ArrayList<Cell>()
        var cursorOffset = -1
        for ((idx, row) in src.withIndex()) {
            val isCursorRow = idx == cursorSrcIdx
            if (isCursorRow) cursorOffset = line.size + cur.col
            var n = row.cells.size
            if (row.pad && row.wrapped) n--
            if (!row.wrapped) {
                val keep = if (isCursorRow) cur.col.coerceAtMost(n) else 0
                while (n > keep && isBlankCell(row.cells[n - 1])) n--
            }
            for (k in 0 until n) line.add(row.cells[k])
            // A cursor beyond the stored cells (pending wrap, or trimmed row) pads with blanks.
            if (isCursorRow) while (line.size < cursorOffset.coerceAtMost(line.size + 4096)) line.add(Cell())
            if (!row.wrapped || idx == src.size - 1) {
                val start = out.size
                val cpos = layout(line, c, out, if (cursorOffset >= 0) cursorOffset else -1)
                if (cursorOffset >= 0) { cursorAbs = start + cpos.first; cursorCol = cpos.second; cursorOffset = -1 }
                line.clear()
            }
        }
        // Window: keep the cursor visible, prefer the newest content.
        val top = minOf(cursorAbs, (out.size - r).coerceAtLeast(0))
        scrollback.clear()
        for (i in 0 until top) pushScrollback(out[i])
        val newGrid = Array(r) { rr ->
            val row = out.getOrNull(top + rr)
            if (row == null) Row(blankCells(c)) else {
                val cells = if (row.cells.size >= c) row.cells.copyOf(c).requireNoNulls() else Array(c) { k -> row.cells.getOrNull(k) ?: Cell() }
                // A wrapped row that came out short (a wide character moved to the next row) is
                // padded to the window width; that filler is not content.
                Row(cells, row.wrapped).also { it.pad = row.pad || (row.wrapped && row.cells.size < c) }
            }
        }
        return newGrid to Cursor((cursorAbs - top).coerceIn(0, r - 1), cursorCol.coerceIn(0, c))
    }

    /**
     * Break one logical line into rows of width [c] appended to [out]; every row but the last
     * is flagged soft-wrapped. Returns (row index within this line, column) for [cursorOffset]
     * (or (0,0) if it is -1).
     */
    private fun layout(cells: List<Cell>, c: Int, out: MutableList<Row>, cursorOffset: Int): Pair<Int, Int> {
        if (cells.isEmpty()) { out.add(Row(arrayOf<Cell>())); return 0 to 0 }
        var pos = 0
        var chunkIdx = 0
        var result = 0 to 0
        while (pos < cells.size) {
            var end = minOf(pos + c, cells.size)
            if (end < cells.size && cells[end].width == 0 && end - 1 > pos) end-- // keep a wide char whole
            if (end == pos) end = pos + 1
            val padWide = end < cells.size && end - pos == c - 1 && cells[end].width == 2
            val chunk = Array(end - pos + if (padWide) 1 else 0) { if (it < end - pos) cells[pos + it] else Cell() }
            // A wide character cannot fit in a 1-column terminal: draw the lead narrow.
            if (c == 1 && chunk[0].width != 1) chunk[0] = chunk[0].copy(width = 1)
            val wrapped = end < cells.size
            out.add(Row(chunk, wrapped).also { it.pad = padWide })
            if (cursorOffset >= pos && (cursorOffset < end || !wrapped)) result = chunkIdx to (cursorOffset - pos)
            pos = end
            chunkIdx++
        }
        return result
    }

    companion object {
        const val DEFAULT_SCROLLBACK = 5_000
        /** Hard ceiling on the scrollback line setting. */
        const val HARD_MAX_SCROLLBACK = 100_000
        /** Cell budget per session (the memory ceiling; see docs/design/terminal-engine.md). */
        const val DEFAULT_SCROLLBACK_CELLS = 4_000_000
        private const val MAX_CLUSTER = 16
    }
}
