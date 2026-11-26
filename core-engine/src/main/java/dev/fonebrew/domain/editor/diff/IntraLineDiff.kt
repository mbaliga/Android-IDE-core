package dev.fonebrew.domain.editor.diff

/** A changed range `[start, end)` within one line's text (UTF-16 units). */
data class Span(val start: Int, val end: Int)

/**
 * Word-level diff of two paired lines: split both into word / whitespace / single-symbol tokens, LCS
 * the token lists, and report the tokens that are NOT shared as spans. Bounded: past [MAX_TOKENS]
 * tokens per side, or when nothing is shared, the whole line is one span (never a quadratic blow-up).
 */
object IntraLineDiff {
    const val MAX_TOKENS = 600

    private fun tokenize(s: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            var j = i + 1
            when {
                Character.isLetterOrDigit(c) || c == '_' -> while (j < s.length && (Character.isLetterOrDigit(s[j]) || s[j] == '_')) j++
                c.isWhitespace() -> while (j < s.length && s[j].isWhitespace()) j++
                Character.isHighSurrogate(c) && j < s.length && Character.isLowSurrogate(s[j]) -> j++
            }
            out += i until j
            i = j
        }
        return out
    }

    /** Spans of [a] and of [b] that differ. Identical lines give two empty lists. */
    fun spans(a: String, b: String): Pair<List<Span>, List<Span>> {
        if (a == b) return emptyList<Span>() to emptyList()
        val ta = tokenize(a)
        val tb = tokenize(b)
        val whole = listOf(Span(0, a.length)).filter { it.end > it.start } to listOf(Span(0, b.length)).filter { it.end > it.start }
        if (ta.size > MAX_TOKENS || tb.size > MAX_TOKENS) return whole
        val sa = ta.map { a.substring(it.first, it.last + 1) }
        val sb = tb.map { b.substring(it.first, it.last + 1) }
        val n = sa.size; val m = sb.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            dp[i][j] = if (sa[i] == sb[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        if (dp[0][0] == 0) return whole
        val keepA = BooleanArray(n); val keepB = BooleanArray(m)
        var i = 0; var j = 0
        while (i < n && j < m) {
            when {
                sa[i] == sb[j] -> { keepA[i] = true; keepB[j] = true; i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        return merge(ta, keepA) to merge(tb, keepB)
    }

    private fun merge(tokens: List<IntRange>, keep: BooleanArray): List<Span> {
        val out = ArrayList<Span>()
        for ((k, t) in tokens.withIndex()) {
            if (keep[k]) continue
            val last = out.lastOrNull()
            if (last != null && last.end == t.first) out[out.size - 1] = Span(last.start, t.last + 1)
            else out += Span(t.first, t.last + 1)
        }
        return out
    }
}
