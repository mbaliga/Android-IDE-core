package dev.fonebrew.domain.workspace.grep

/**
 * A cheap, engine-independent screen for the classic catastrophic-backtracking shapes, run BEFORE
 * a regex is ever executed: a group that is itself repeated without an upper bound (`*`, `+`,
 * `{n,}`) and that either contains another unbounded repeat (`(a+)+`, `(x*y*)*`) or an
 * alternation (`(a|aa)+`). It is deliberately conservative (it will also flag harmless patterns
 * such as `(\d+,)+`), which is why a flagged search returns [SearchOutcome.RiskyPattern] and the
 * caller may re-run with `allowRiskyRegex = true`, still protected by the per-line budget.
 *
 * Why both layers: [BudgetCharSequence] stops a runaway match by counting the engine's character
 * reads, which is proven on the JVM; on Android `java.util.regex` hands the text to a native
 * engine (the platform copies the input), so the budget is NOT guaranteed there. This lint does
 * not depend on the engine and so protects the phone too. Neither layer claims to catch every
 * pathological pattern (backreference tricks, for one); that residual risk is stated in the
 * design note.
 */
object RegexLint {
    /** Null if nothing risky was found, else a short human reason. */
    fun risk(pattern: String): String? {
        class Group(var hasRepeat: Boolean = false, var hasAlt: Boolean = false)
        val stack = ArrayDeque<Group>()
        var i = 0
        var inClass = false
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> { i += 2; continue }
                inClass -> { if (c == ']') inClass = false }
                c == '[' -> inClass = true
                c == '(' -> stack.addLast(Group())
                c == '|' -> stack.lastOrNull()?.hasAlt = true
                c == ')' -> {
                    val g = stack.removeLastOrNull()
                    if (g != null) {
                        val q = quantifierAt(pattern, i + 1)
                        if (q != null && q.unbounded) {
                            if (g.hasRepeat) return "a repeated group contains another unbounded repeat (nested quantifiers)"
                            if (g.hasAlt) return "a repeated group contains an alternation"
                        }
                        // the group as a whole counts as a repeat inside its parent when it is unbounded-repeated
                        if (q != null && q.unbounded) stack.lastOrNull()?.hasRepeat = true
                        if (g.hasRepeat) stack.lastOrNull()?.hasRepeat = true
                    }
                }
                c == '*' || c == '+' -> stack.lastOrNull()?.hasRepeat = true
                c == '{' -> { val q = quantifierAt(pattern, i); if (q != null && q.unbounded) stack.lastOrNull()?.hasRepeat = true }
            }
            i++
        }
        return null
    }

    private class Quant(val unbounded: Boolean)

    private fun quantifierAt(p: String, at: Int): Quant? {
        if (at >= p.length) return null
        return when (p[at]) {
            '*', '+' -> Quant(true)
            '{' -> {
                val end = p.indexOf('}', at)
                if (end < 0) return null
                val body = p.substring(at + 1, end)
                if (!body.matches(Regex("\\d+(,\\d*)?"))) return null
                Quant(body.endsWith(",") )
            }
            else -> null
        }
    }
}
