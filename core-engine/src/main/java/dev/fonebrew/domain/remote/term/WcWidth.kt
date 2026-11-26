package dev.fonebrew.domain.remote.term

/**
 * Terminal cell width of a code point: 0 (combining / format - attaches to the previous cell),
 * 1, or 2 (East Asian Wide/Fullwidth, emoji presentation). C0/C1 controls are the caller's
 * business ([VtParser] never asks about them). The tables are generated from the public Unicode
 * Character Database (`scripts/gen-wcwidth.py`); a code point the tables do not know is one cell.
 *
 * Not modelled: grapheme clustering beyond combining marks (ZWJ emoji sequences render as their
 * parts), and VS16 emoji-presentation width promotion. See docs/design/terminal-engine.md.
 */
object WcWidth {
    fun of(cp: Int): Int = when {
        cp < 0x300 -> 1 // fast path: Latin, no combining marks below U+0300 (soft hyphen is 1)
        inTable(WcWidthTable.ZERO, cp) -> 0
        inTable(WcWidthTable.WIDE, cp) -> 2
        else -> 1
    }

    /** Binary search over sorted (first, last) pairs. */
    private fun inTable(t: IntArray, cp: Int): Boolean {
        var lo = 0
        var hi = t.size / 2 - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                cp < t[mid * 2] -> hi = mid - 1
                cp > t[mid * 2 + 1] -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }
}
