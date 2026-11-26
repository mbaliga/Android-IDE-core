package dev.fonebrew.domain.editor.buffer

/**
 * Extended-grapheme-cluster boundaries, a self-contained simplification of UAX #29 so the cursor
 * behaves the same on the JVM and on Android (platform `BreakIterator`s differ between ICU and the
 * JDK). Covered: CR LF, controls, Extend / ZWJ / spacing marks, emoji ZWJ sequences, emoji
 * modifiers, regional-indicator pairs, Hangul L/V/T. Not modelled: Prepend and Indic conjunct
 * clusters (they segment per the simple rules). "Extended pictographic" is approximated by block
 * ranges. Offsets are UTF-16 units and always land on code point boundaries.
 */
object GraphemeBreaks {
    private enum class T { CR, LF, CONTROL, EXTEND, ZWJ, L, V, T, LV, LVT, RI, PICT, OTHER }

    /** How far back [previous] looks for a safe place to start segmenting (bounded work). */
    private const val BACK_WINDOW = 256

    private fun typeOf(cp: Int): T {
        if (cp == 0x0D) return T.CR
        if (cp == 0x0A) return T.LF
        if (cp == 0x200D) return T.ZWJ
        if (cp in 0xAC00..0xD7A3) return if ((cp - 0xAC00) % 28 == 0) T.LV else T.LVT
        if (cp in 0x1100..0x115F || cp in 0xA960..0xA97C) return T.L
        if (cp in 0x1160..0x11A7 || cp in 0xD7B0..0xD7C6) return T.V
        if (cp in 0x11A8..0x11FF || cp in 0xD7CB..0xD7FB) return T.T
        if (cp in 0x1F1E6..0x1F1FF) return T.RI
        if (cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F || cp == 0x200C) return T.EXTEND
        when (Character.getType(cp).toByte()) {
            Character.CONTROL, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> return T.CONTROL
            Character.FORMAT -> return T.CONTROL
            Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> return T.EXTEND
        }
        if (cp in 0x1F000..0x1FFFD || cp in 0x2600..0x27BF || cp in 0x2300..0x23FF || cp in 0x2B00..0x2BFF ||
            cp == 0xA9 || cp == 0xAE || cp == 0x203C || cp == 0x2049 || cp == 0x2122 || cp == 0x2139 ||
            cp in 0x2194..0x21AA || cp == 0x24C2 || cp in 0x25AA..0x25FE
        ) return T.PICT
        return T.OTHER
    }

    /** End of the cluster that starts at [offset] (== [offset] boundary assumed). */
    fun next(text: CharSequence, offset: Int): Int {
        val n = text.length
        if (offset >= n) return n
        var i = offset
        var cp = Character.codePointAt(text, i)
        i += Character.charCount(cp)
        var prev = typeOf(cp)
        var ri = if (prev == T.RI) 1 else 0
        var pictChain = prev == T.PICT
        var pictZwj = false
        while (i < n) {
            cp = Character.codePointAt(text, i)
            val t = typeOf(cp)
            val join = when {
                prev == T.CR && t == T.LF -> true
                prev == T.CR || prev == T.LF || prev == T.CONTROL -> false
                t == T.CR || t == T.LF || t == T.CONTROL -> false
                prev == T.L && (t == T.L || t == T.V || t == T.LV || t == T.LVT) -> true
                (prev == T.LV || prev == T.V) && (t == T.V || t == T.T) -> true
                (prev == T.LVT || prev == T.T) && t == T.T -> true
                t == T.EXTEND || t == T.ZWJ -> true
                pictZwj && t == T.PICT -> true
                prev == T.RI && t == T.RI && ri % 2 == 1 -> true
                else -> false
            }
            if (!join) break
            when {
                t == T.RI -> ri++
                else -> ri = 0
            }
            when {
                t == T.PICT -> { pictChain = true; pictZwj = false }
                t == T.EXTEND && pictChain -> {}
                t == T.ZWJ && pictChain -> { pictChain = false; pictZwj = true }
                else -> { pictChain = false; pictZwj = false }
            }
            prev = t
            i += Character.charCount(cp)
        }
        return i
    }

    /** Start of the cluster that ends at or before [offset] (the previous boundary strictly before it). */
    fun previous(text: CharSequence, offset: Int): Int {
        if (offset <= 0) return 0
        var s = maxOf(0, offset - BACK_WINDOW)
        s = TextUnits.floorCodePoint(text, s)
        // Start no later than a line terminator boundary: segmentation restarts there by rule.
        var b = s
        var last = s
        while (b < offset) {
            last = b
            b = next(text, b)
        }
        return if (last < offset) last else s
    }

    /** True if [offset] is a cluster boundary (judged from a bounded look-back). */
    fun isBoundary(text: CharSequence, offset: Int): Boolean {
        if (offset <= 0 || offset >= text.length) return offset in 0..text.length
        return floor(text, offset) == offset
    }

    /** Largest boundary <= [offset]. */
    fun floor(text: CharSequence, offset: Int): Int {
        if (offset <= 0) return 0
        if (offset >= text.length) return text.length
        var b = TextUnits.floorCodePoint(text, maxOf(0, offset - BACK_WINDOW))
        while (true) {
            val nx = next(text, b)
            if (nx > offset) return b
            b = nx
            if (b == offset) return b
        }
    }
}
