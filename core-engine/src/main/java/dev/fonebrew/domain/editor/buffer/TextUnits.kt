package dev.fonebrew.domain.editor.buffer

/** A zero-based line and a column counted in UTF-16 code units (see [TextUnits] for other units). */
data class Position(val line: Int, val column: Int)

/**
 * Explicit conversions between the three ways a "character" can be counted. The buffer's native unit
 * is the UTF-16 code unit (what `String` indexes by); code points and grapheme clusters are derived
 * views, never mixed implicitly.
 */
object TextUnits {
    fun isHighSurrogate(c: Char) = Character.isHighSurrogate(c)
    fun isLowSurrogate(c: Char) = Character.isLowSurrogate(c)

    /** True if [offset] does not fall between the halves of a surrogate pair. */
    fun isCodePointBoundary(text: CharSequence, offset: Int): Boolean {
        if (offset <= 0 || offset >= text.length) return offset in 0..text.length
        return !(Character.isHighSurrogate(text[offset - 1]) && Character.isLowSurrogate(text[offset]))
    }

    /** Largest code point boundary <= [offset]. */
    fun floorCodePoint(text: CharSequence, offset: Int): Int {
        val o = offset.coerceIn(0, text.length)
        return if (isCodePointBoundary(text, o)) o else o - 1
    }

    fun nextCodePoint(text: CharSequence, offset: Int): Int {
        if (offset >= text.length) return text.length
        return offset + Character.charCount(Character.codePointAt(text, offset))
    }

    fun previousCodePoint(text: CharSequence, offset: Int): Int {
        if (offset <= 0) return 0
        return offset - Character.charCount(Character.codePointBefore(text, offset))
    }

    /** Number of code points in `[0, offset)`; a lone surrogate counts as one. */
    fun codePointsBefore(text: CharSequence, offset: Int): Int =
        Character.codePointCount(text, 0, offset.coerceIn(0, text.length))

    /** UTF-16 offset of the [index]th code point (clamped to the text length). */
    fun offsetOfCodePoint(text: CharSequence, index: Int): Int {
        var o = 0
        var k = 0
        while (k < index && o < text.length) { o = nextCodePoint(text, o); k++ }
        return o
    }

    /** Number of grapheme clusters in `[0, offset)`. */
    fun graphemesBefore(text: CharSequence, offset: Int): Int {
        var o = 0
        var k = 0
        val end = offset.coerceIn(0, text.length)
        while (o < end) { o = GraphemeBreaks.next(text, o); k++ }
        return k
    }

    /** UTF-16 offset of the [index]th grapheme boundary (clamped to the text length). */
    fun offsetOfGrapheme(text: CharSequence, index: Int): Int {
        var o = 0
        var k = 0
        while (k < index && o < text.length) { o = GraphemeBreaks.next(text, o); k++ }
        return o
    }

    fun positionOf(rope: Rope, offset: Int): Position {
        val line = rope.lineOfOffset(offset)
        return Position(line, offset - rope.lineStart(line))
    }

    /** Offset of (line, column), clamped into the line's content so it never lands inside a terminator. */
    fun offsetOf(rope: Rope, line: Int, column: Int): Int {
        val l = line.coerceIn(0, rope.lineBreaks)
        val s = rope.lineStart(l)
        return minOf(s + column.coerceAtLeast(0), rope.lineContentEnd(l))
    }
}
