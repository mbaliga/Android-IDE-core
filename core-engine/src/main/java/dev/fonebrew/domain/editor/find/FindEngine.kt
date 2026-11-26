package dev.fonebrew.domain.editor.find

import dev.fonebrew.domain.editor.edit.WordBreaks
import dev.fonebrew.domain.workspace.grep.BudgetCharSequence
import dev.fonebrew.domain.workspace.grep.RegexBudgetExceeded
import dev.fonebrew.domain.workspace.grep.RegexLint
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

enum class FindMode { LITERAL, REGEX }

/**
 * What to look for. Regex patterns run with MULTILINE (`^`/`$` per line). [allowRisky] overrides the
 * [RegexLint] screen only; the execution budget always applies.
 */
data class FindQuery(
    val pattern: String,
    val mode: FindMode = FindMode.LITERAL,
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
    val allowRisky: Boolean = false,
)

/** A match as `[start, end)` in UTF-16 units. */
data class Match(val start: Int, val end: Int) {
    val length: Int get() = end - start
}

sealed interface FindOutcome {
    /** [truncated] is true when the cap was hit: the list is a prefix, not everything. */
    data class Found(val matches: List<Match>, val truncated: Boolean) : FindOutcome
    data class Invalid(val message: String) : FindOutcome

    /** The lint screen flagged the pattern; re-run with `allowRisky = true` to override (still budgeted). */
    data class Risky(val reason: String) : FindOutcome

    /** The execution budget ran out (typed, never a hang). */
    object Timeout : FindOutcome
    object EmptyPattern : FindOutcome
}

object FindEngine {
    /** Character reads the regex engine may make in one call before [FindOutcome.Timeout]. */
    const val DEFAULT_BUDGET: Long = 50_000_000L
    const val DEFAULT_MAX_MATCHES: Int = 10_000

    private const val WORD = "[\\p{L}\\p{N}_]"

    private fun compile(q: FindQuery): Pattern {
        var flags = Pattern.MULTILINE
        if (!q.caseSensitive) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        val body = if (q.mode == FindMode.LITERAL) Pattern.quote(q.pattern) else q.pattern
        val full = if (q.wholeWord) "(?<!$WORD)(?:$body)(?!$WORD)" else body
        return Pattern.compile(full, flags)
    }

    /** All matches fully inside `[from, to)`, at most [maxMatches]. */
    fun findAll(
        text: String, q: FindQuery, from: Int = 0, to: Int = text.length,
        maxMatches: Int = DEFAULT_MAX_MATCHES, budget: Long = DEFAULT_BUDGET,
    ): FindOutcome {
        if (q.pattern.isEmpty()) return FindOutcome.EmptyPattern
        val lo = from.coerceIn(0, text.length)
        val hi = to.coerceIn(lo, text.length)
        if (q.mode == FindMode.LITERAL) return literal(text, q, lo, hi, maxMatches)
        if (!q.allowRisky) RegexLint.risk(q.pattern)?.let { return FindOutcome.Risky(it) }
        val p = try { compile(q) } catch (e: PatternSyntaxException) {
            return FindOutcome.Invalid(e.description ?: "invalid pattern")
        }
        val out = ArrayList<Match>()
        try {
            val m = p.matcher(BudgetCharSequence(text, budget))
            m.region(lo, hi)
            m.useTransparentBounds(true)
            m.useAnchoringBounds(false)
            while (m.find()) {
                if (out.size >= maxMatches) return FindOutcome.Found(out, truncated = true)
                out += Match(m.start(), m.end())
            }
        } catch (e: RegexBudgetExceeded) {
            return FindOutcome.Timeout
        } catch (e: StackOverflowError) {
            return FindOutcome.Timeout
        }
        return FindOutcome.Found(out, truncated = false)
    }

    private fun literal(text: String, q: FindQuery, lo: Int, hi: Int, max: Int): FindOutcome {
        val pat = q.pattern
        val out = ArrayList<Match>()
        var i = lo
        while (i + pat.length <= hi) {
            val at = if (q.caseSensitive) text.indexOf(pat, i).let { if (it < 0 || it + pat.length > hi) -1 else it }
            else scanIgnoreCase(text, pat, i, hi)
            if (at < 0) break
            if (!q.wholeWord || wordOk(text, at, at + pat.length)) {
                if (out.size >= max) return FindOutcome.Found(out, truncated = true)
                out += Match(at, at + pat.length)
                i = at + pat.length
            } else i = at + 1
        }
        return FindOutcome.Found(out, truncated = false)
    }

    private fun scanIgnoreCase(text: String, pat: String, from: Int, hi: Int): Int {
        var i = from
        while (i + pat.length <= hi) {
            if (text.regionMatches(i, pat, 0, pat.length, ignoreCase = true)) return i
            i++
        }
        return -1
    }

    private fun wordOk(text: String, s: Int, e: Int): Boolean {
        if (s < e && WordBreaks.isWordChar(Character.codePointAt(text, s)) && s > 0 &&
            WordBreaks.isWordChar(Character.codePointBefore(text, s))
        ) return false
        if (s < e && WordBreaks.isWordChar(Character.codePointBefore(text, e)) && e < text.length &&
            WordBreaks.isWordChar(Character.codePointAt(text, e))
        ) return false
        return true
    }

    /** First match starting at or after [from]; null if none. Searches in growing windows. */
    fun findNext(text: String, q: FindQuery, from: Int, budget: Long = DEFAULT_BUDGET): FindResult {
        return firstOf(text, q, from, text.length, budget, last = false)
    }

    /** Last match ending at or before [before]; null if none. */
    fun findPrevious(text: String, q: FindQuery, before: Int, budget: Long = DEFAULT_BUDGET): FindResult {
        var w = 4096
        while (true) {
            val lo = maxOf(0, before - w)
            val r = firstOf(text, q, lo, before, budget, last = true)
            if (r !is FindResult.None || lo == 0) return r
            w *= 16
        }
    }

    sealed interface FindResult {
        data class Hit(val match: Match) : FindResult
        object None : FindResult
        data class Failed(val outcome: FindOutcome) : FindResult
    }

    private fun firstOf(text: String, q: FindQuery, lo: Int, hi: Int, budget: Long, last: Boolean): FindResult =
        when (val o = findAll(text, q, lo, hi, maxMatches = if (last) 100_000 else 1, budget = budget)) {
            is FindOutcome.Found -> (if (last) o.matches.lastOrNull() else o.matches.firstOrNull())
                ?.let { FindResult.Hit(it) } ?: FindResult.None
            else -> FindResult.Failed(o)
        }

    /**
     * The text that replaces [m]. Literal mode: [replacement] verbatim. Regex mode: `$0`/`$&` whole
     * match, `$1`.. groups, `\n` `\t` `\\` `\$` escapes. A reference to a missing group is a failure.
     */
    fun expandReplacement(text: String, q: FindQuery, m: Match, replacement: String): Result<String> {
        if (q.mode == FindMode.LITERAL) return Result.success(replacement)
        return try {
            val p = compile(q)
            val mt = p.matcher(text)
            mt.region(m.start, m.end)
            mt.useTransparentBounds(true)
            mt.useAnchoringBounds(false)
            if (!mt.matches()) return Result.failure(IllegalStateException("text at the match changed"))
            val sb = StringBuilder()
            var i = 0
            while (i < replacement.length) {
                val c = replacement[i]
                if (c == '\\' && i + 1 < replacement.length) {
                    when (val n = replacement[i + 1]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); else -> sb.append(n)
                    }
                    i += 2
                } else if (c == '$' && i + 1 < replacement.length && replacement[i + 1] == '&') {
                    sb.append(mt.group()); i += 2
                } else if (c == '$' && i + 1 < replacement.length && replacement[i + 1].isDigit()) {
                    var g = replacement[i + 1] - '0'
                    var j = i + 2
                    while (j < replacement.length && replacement[j].isDigit() && g * 10 + (replacement[j] - '0') <= mt.groupCount()) {
                        g = g * 10 + (replacement[j] - '0'); j++
                    }
                    if (g > mt.groupCount()) return Result.failure(IllegalArgumentException("no group $g in the pattern"))
                    sb.append(mt.group(g) ?: "")
                    i = j
                } else { sb.append(c); i++ }
            }
            Result.success(sb.toString())
        } catch (e: PatternSyntaxException) {
            Result.failure(e)
        }
    }
}
