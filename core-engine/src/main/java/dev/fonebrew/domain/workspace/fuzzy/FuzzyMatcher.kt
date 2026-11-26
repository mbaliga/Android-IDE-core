package dev.fonebrew.domain.workspace.fuzzy

/** One whitespace-separated term of a query. Smart case: any uppercase letter makes the term case-sensitive. */
class FuzzyTerm internal constructor(val text: String) {
    val caseSensitive: Boolean = text.any { it.isUpperCase() }
    internal val chars: CharArray = text.toCharArray()
    internal val lower: CharArray = CharArray(text.length) { Character.toLowerCase(text[it]) }
}

/** A parsed query: terms are ANDed, each must match somewhere in the candidate. */
class FuzzyQuery private constructor(val terms: List<FuzzyTerm>) {
    val isEmpty: Boolean get() = terms.isEmpty()

    companion object {
        fun parse(raw: String): FuzzyQuery =
            FuzzyQuery(raw.split(' ', '\t').filter { it.isNotEmpty() }.map { FuzzyTerm(it) })
    }
}

/** [positions] are ascending indices into the candidate string, for highlighting. */
class FuzzyMatch(val score: Int, val positions: IntArray) {
    override fun equals(other: Any?) = other is FuzzyMatch && other.score == score && other.positions.contentEquals(positions)
    override fun hashCode() = 31 * score + positions.contentHashCode()
    override fun toString() = "FuzzyMatch($score, ${positions.toList()})"
}

/**
 * fzf-style subsequence scorer, written from the published description of the idea (a dynamic
 * programme over query x text with per-position bonuses), not from any source. Deterministic and
 * allocation-light (per-thread scratch arrays); the best-scoring alignment is found exactly, not
 * greedily.
 *
 * Scoring (integers):
 *  - each matched char +16;
 *  - bonus when the char starts a path segment (after `/`) +9, starts a word (after a space or
 *    `_ - . :` or any other non-alphanumeric) +8, starts a camel hump (lower to Upper, letter to
 *    digit) +7; the FIRST query char's bonus counts double, so where the match begins matters most;
 *  - a char directly after the previous matched char gets +max(its own bonus, 4, the bonus of the
 *    char that started the run) (consecutive run);
 *  - a gap costs 3 to open and 1 per further skipped char; the distance before the first match
 *    costs 1 per char (capped at 12);
 *  - path awareness: every matched char inside the basename gets +2, and a match lying ENTIRELY in
 *    the basename gets +30 (leading distance is then counted from the basename start), so `mnact` finds `MainActivity.kt` before `my/nice/action/tool.kt`;
 *  - a tiny length term (-length/16) prefers the shorter of otherwise equal paths.
 * Ranking order everywhere is: score descending, then shorter text, then lexicographic: a total
 * order, so results never depend on hash or insertion order.
 */
object FuzzyMatcher {
    private const val MATCH = 16
    private const val GAP_START = -3
    private const val GAP_EXT = -1
    private const val BONUS_BOUNDARY = 8
    private const val BONUS_PATHSEP = 9
    private const val BONUS_CAMEL = 7
    private const val BONUS_CONSEC = 4
    private const val FIRST_MULT = 2
    private const val LEADING_CAP = 12
    private const val BASENAME_CHAR = 2
    private const val BASENAME_ALL = 30
    private const val NEG = Int.MIN_VALUE / 4
    const val MAX_TEXT = 4096

    private class Scratch {
        var h = IntArray(0)
        var p = IntArray(0)
        var f = IntArray(0)
        var bonus = IntArray(0)
        fun ensure(cells: Int, n: Int) {
            if (h.size < cells) { h = IntArray(cells); p = IntArray(cells); f = IntArray(cells) }
            if (bonus.size < n) bonus = IntArray(n)
        }
    }

    private val scratch = ThreadLocal.withInitial { Scratch() }

    /** Null when some term is not a subsequence of [text]. */
    fun match(query: FuzzyQuery, text: String): FuzzyMatch? {
        if (query.isEmpty || text.length > MAX_TEXT) return null
        var total = 0
        val all = ArrayList<Int>()
        for (t in query.terms) {
            if (!isSubsequence(t, text)) return null
        }
        for (t in query.terms) {
            val r = matchTerm(t, text) ?: return null
            total += r.score
            r.positions.forEach { all.add(it) }
        }
        total -= text.length / 16
        return FuzzyMatch(total, all.distinct().sorted().toIntArray())
    }

    fun match(query: String, text: String): FuzzyMatch? = match(FuzzyQuery.parse(query), text)

    /** The total order used by every ranking in this package. */
    fun compare(aScore: Int, aText: String, bScore: Int, bText: String): Int {
        if (aScore != bScore) return bScore.compareTo(aScore)
        if (aText.length != bText.length) return aText.length.compareTo(bText.length)
        return aText.compareTo(bText)
    }

    private fun isSubsequence(t: FuzzyTerm, text: String): Boolean {
        var i = 0
        val m = t.chars.size
        for (c in text) {
            if (i < m && (if (t.caseSensitive) c == t.chars[i] else Character.toLowerCase(c) == t.lower[i])) i++
        }
        return i == m
    }

    private fun charClassBonus(prev: Char, cur: Char): Int = when {
        prev == '/' -> BONUS_PATHSEP
        !prev.isLetterOrDigit() -> BONUS_BOUNDARY
        prev.isLowerCase() && cur.isUpperCase() -> BONUS_CAMEL
        prev.isLetter() && cur.isDigit() -> BONUS_CAMEL
        else -> 0
    }

    private class TermResult(val score: Int, val positions: IntArray)

    private fun matchTerm(t: FuzzyTerm, text: String): TermResult? {
        val n = text.length
        val m = t.chars.size
        val s = scratch.get()
        s.ensure(m * n, n)
        val baseStart = text.lastIndexOf('/') + 1
        val bonus = s.bonus
        for (j in 0 until n) {
            var b = if (j == 0) BONUS_PATHSEP else charClassBonus(text[j - 1], text[j])
            if (j >= baseStart) b += BASENAME_CHAR
            bonus[j] = b
        }
        // No slash: the whole text IS the basename, so any match lies entirely in it.
        if (baseStart == 0) return run(t, text, 0, s)?.let { TermResult(it.score + BASENAME_ALL, it.positions) }
        var best: TermResult? = null
        val inBase = run(t, text, baseStart, s)
        if (inBase != null) best = TermResult(inBase.score + BASENAME_ALL, inBase.positions)
        val full = run(t, text, 0, s)
        if (full != null && (best == null || full.score > best.score)) best = full
        return best
    }

    private fun run(t: FuzzyTerm, text: String, s0: Int, s: Scratch): TermResult? {
        val n = text.length
        val m = t.chars.size
        val h = s.h
        val p = s.p
        val f = s.f
        val bonus = s.bonus
        val cs = t.caseSensitive
        fun eq(j: Int, i: Int): Boolean = if (cs) text[j] == t.chars[i] else Character.toLowerCase(text[j]) == t.lower[i]

        for (j in 0 until n) { h[j] = NEG; p[j] = -1 }
        for (j in s0 until n) if (eq(j, 0)) { h[j] = MATCH + bonus[j] * FIRST_MULT - minOf(j - s0, LEADING_CAP); f[j] = bonus[j] }
        for (i in 1 until m) {
            val row = i * n
            val prev = row - n
            for (j in 0 until n) { h[row + j] = NEG; p[row + j] = -1 }
            var g = NEG
            var gk = -1
            for (j in s0 until n) {
                if (j - 2 >= s0) {
                    val ext = if (g > NEG) g + GAP_EXT else NEG
                    val open = if (h[prev + j - 2] > NEG) h[prev + j - 2] + GAP_START else NEG
                    if (open > ext) { g = open; gk = j - 2 } else g = ext
                }
                if (!eq(j, i)) continue
                var bestV = NEG
                var from = -1
                var bestFb = 0
                if (j - 1 >= s0 && h[prev + j - 1] > NEG) {
                    // A run of consecutive matches inherits the bonus of the char that started it
                    // (typing `abc` should favour `abc` over `a_b_c`), unless this char opens a
                    // stronger boundary of its own, which starts a new run.
                    val fb = f[prev + j - 1]
                    val b = bonus[j]
                    val eff: Int
                    if (b >= BONUS_BOUNDARY && b > fb) { eff = b; bestFb = b } else { eff = maxOf(b, BONUS_CONSEC, fb); bestFb = fb }
                    bestV = h[prev + j - 1] + MATCH + eff; from = j - 1
                }
                if (g > NEG) {
                    val c = g + MATCH + bonus[j]
                    if (c > bestV) { bestV = c; from = gk; bestFb = bonus[j] }
                }
                if (from >= 0) { h[row + j] = bestV; p[row + j] = from; f[row + j] = bestFb }
            }
        }
        val last = (m - 1) * n
        var bj = -1
        var bv = NEG
        for (j in s0 until n) if (h[last + j] > bv) { bv = h[last + j]; bj = j }
        if (bj < 0) return null
        val pos = IntArray(m)
        var j = bj
        for (i in m - 1 downTo 0) {
            pos[i] = j
            j = p[i * n + j]
        }
        return TermResult(bv, pos)
    }
}
