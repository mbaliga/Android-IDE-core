package dev.fonebrew.domain.editor.edit

import dev.fonebrew.domain.editor.buffer.TextUnits

/**
 * Word motion over code points. Classes: word (letter/digit/underscore), punctuation run, blank
 * (space/tab) and newline. A line terminator is always its own stop, so word-delete never eats a
 * newline together with neighbouring text.
 */
object WordBreaks {
    private enum class K { WORD, PUNCT, BLANK, NEWLINE }

    private fun kind(cp: Int): K = when {
        cp == '\n'.code || cp == '\r'.code -> K.NEWLINE
        cp == ' '.code || cp == '\t'.code || (Character.isWhitespace(cp) && cp != 0x0A) -> K.BLANK
        Character.isLetterOrDigit(cp) || cp == '_'.code -> K.WORD
        else -> K.PUNCT
    }

    fun isWordChar(cp: Int) = kind(cp) == K.WORD

    /** Offset reached by moving one word to the right of [offset]. */
    fun nextWord(t: CharSequence, offset: Int): Int {
        var i = offset.coerceIn(0, t.length)
        if (i >= t.length) return t.length
        if (kind(Character.codePointAt(t, i)) == K.NEWLINE) return crlfAware(t, i)
        while (i < t.length && kind(Character.codePointAt(t, i)) == K.BLANK) i = TextUnits.nextCodePoint(t, i)
        if (i >= t.length || kind(Character.codePointAt(t, i)) == K.NEWLINE) return i
        val k = kind(Character.codePointAt(t, i))
        while (i < t.length && kind(Character.codePointAt(t, i)) == k) i = TextUnits.nextCodePoint(t, i)
        return i
    }

    /** Offset reached by moving one word to the left of [offset]. */
    fun previousWord(t: CharSequence, offset: Int): Int {
        var i = offset.coerceIn(0, t.length)
        if (i <= 0) return 0
        if (kind(Character.codePointBefore(t, i)) == K.NEWLINE) {
            return if (i >= 2 && t[i - 1] == '\n' && t[i - 2] == '\r') i - 2 else i - 1
        }
        while (i > 0 && kind(Character.codePointBefore(t, i)) == K.BLANK) i = TextUnits.previousCodePoint(t, i)
        if (i <= 0 || kind(Character.codePointBefore(t, i)) == K.NEWLINE) return i
        val k = kind(Character.codePointBefore(t, i))
        while (i > 0 && kind(Character.codePointBefore(t, i)) == k) i = TextUnits.previousCodePoint(t, i)
        return i
    }

    private fun crlfAware(t: CharSequence, i: Int): Int =
        if (t[i] == '\r' && i + 1 < t.length && t[i + 1] == '\n') i + 2 else i + 1

    /** The word (or punctuation run) containing or touching [offset]; null on blanks/newlines/empty. */
    fun wordRange(t: CharSequence, offset: Int): IntRange? {
        val o = offset.coerceIn(0, t.length)
        val atCp = if (o < t.length) kind(Character.codePointAt(t, o)) else null
        val beforeCp = if (o > 0) kind(Character.codePointBefore(t, o)) else null
        val k = when {
            atCp == K.WORD || atCp == K.PUNCT -> atCp
            beforeCp == K.WORD || beforeCp == K.PUNCT -> beforeCp
            else -> return null
        }
        var s = o
        var e = o
        // when the offset sits at the end of a word, anchor inside it
        if (!(atCp == k)) { /* e stays; s extends below */ }
        while (s > 0 && kind(Character.codePointBefore(t, s)) == k) s = TextUnits.previousCodePoint(t, s)
        while (e < t.length && kind(Character.codePointAt(t, e)) == k) e = TextUnits.nextCodePoint(t, e)
        return if (s == e) null else s until e
    }
}
