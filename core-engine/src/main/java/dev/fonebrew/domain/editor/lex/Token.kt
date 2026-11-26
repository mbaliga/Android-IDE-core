package dev.fonebrew.domain.editor.lex

/** Semantic category of a token. PLAIN and PROPERTY go beyond the ten named categories (see design note). */
enum class TokenCategory { PLAIN, KEYWORD, STRING, COMMENT, NUMBER, TYPE, FUNCTION, OPERATOR, PUNCTUATION, ESCAPE, PROPERTY, INVALID }

/** Bold / italic / underline / strike as a bit set: meaning that survives without colour. */
@JvmInline
value class StyleHint(val bits: Int) {
    infix fun or(o: StyleHint) = StyleHint(bits or o.bits)
    fun has(flag: StyleHint) = bits and flag.bits == flag.bits && flag.bits != 0
    val isNone: Boolean get() = bits == 0
    override fun toString(): String = buildList {
        if (has(BOLD)) add("bold"); if (has(ITALIC)) add("italic"); if (has(UNDERLINE)) add("underline"); if (has(STRIKE)) add("strike")
    }.joinToString("+").ifEmpty { "none" }

    companion object {
        val NONE = StyleHint(0)
        val BOLD = StyleHint(1)
        val ITALIC = StyleHint(2)
        val UNDERLINE = StyleHint(4)
        val STRIKE = StyleHint(8)
    }
}

/**
 * The colour-independent half of the highlighting contract. The owner is red-green colorblind, so a
 * category either carries a style hint here, or is listed in [glyphIntrinsic] because the glyphs
 * already identify it (quotes, digits, operators, brackets, `name(` calls, `key:`). A test fails if a
 * category is in neither set, or if two hinted categories share a hint.
 */
object TokenStyle {
    val glyphIntrinsic: Set<TokenCategory> = setOf(
        TokenCategory.PLAIN, TokenCategory.STRING, TokenCategory.NUMBER, TokenCategory.FUNCTION,
        TokenCategory.OPERATOR, TokenCategory.PUNCTUATION, TokenCategory.ESCAPE, TokenCategory.PROPERTY,
    )

    fun defaultFor(c: TokenCategory): StyleHint = when (c) {
        TokenCategory.KEYWORD -> StyleHint.BOLD
        TokenCategory.COMMENT -> StyleHint.ITALIC
        TokenCategory.TYPE -> StyleHint.BOLD or StyleHint.ITALIC
        TokenCategory.INVALID -> StyleHint.UNDERLINE
        else -> StyleHint.NONE
    }
}

/** A token within one line: `[start, end)` in UTF-16 units of the line text (no terminator). */
data class Token(val start: Int, val end: Int, val category: TokenCategory, val style: StyleHint = TokenStyle.defaultFor(category))

/**
 * The lexer's memory between lines: a small code, an int argument and an optional tag (heredoc
 * delimiter, fence marker). Equality drives incremental re-lexing: it stops when a line's end state
 * matches what was stored.
 */
data class LexState(val code: Int = 0, val arg: Int = 0, val tag: String? = null) {
    companion object { val INITIAL = LexState() }
}

/**
 * Collects a line's tokens in order. Gaps become PLAIN, overlaps are clipped, empty spans dropped, so
 * a lexer cannot produce a malformed list; adjacent tokens of the "run" categories are merged.
 */
class TokenSink(private val lineLength: Int) {
    private val out = ArrayList<Token>()
    private var pos = 0

    fun add(start: Int, end: Int, category: TokenCategory, style: StyleHint = TokenStyle.defaultFor(category)) {
        val s = maxOf(start, pos)
        val e = minOf(end, lineLength)
        if (e <= s) return
        if (s > pos) push(pos, s, TokenCategory.PLAIN, StyleHint.NONE)
        push(s, e, category, style)
        pos = e
    }

    private fun push(s: Int, e: Int, c: TokenCategory, st: StyleHint) {
        val last = out.lastOrNull()
        if (last != null && last.end == s && last.category == c && last.style == st && c in MERGE) {
            out[out.size - 1] = Token(last.start, e, c, st)
        } else out += Token(s, e, c, st)
    }

    fun build(): List<Token> {
        if (pos < lineLength) push(pos, lineLength, TokenCategory.PLAIN, StyleHint.NONE)
        return out
    }

    private companion object {
        val MERGE = setOf(
            TokenCategory.PLAIN, TokenCategory.STRING, TokenCategory.COMMENT, TokenCategory.OPERATOR,
            TokenCategory.PUNCTUATION, TokenCategory.INVALID,
        )
    }
}

/** One line at a time, state in, state out. Must advance on every iteration and never throw. */
interface LineLexer {
    val id: String
    fun lexLine(line: String, start: LexState, sink: TokenSink): LexState

    /** What the rest of an over-long line should be coloured as, given the state at the cut. */
    fun categoryAt(state: LexState): TokenCategory = TokenCategory.PLAIN
}
