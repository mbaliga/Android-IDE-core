package dev.fonebrew.domain.loop

/**
 * The CLOSED set of gateway conditions a loop can evaluate (docs/design/loop-tools.md,
 * "Conditions"). Deliberately not an expression language and deliberately **no regular
 * expressions**: every case is a literal `indexOf`/`startsWith`/length comparison, linear in the
 * (capped) input, so there is no backtracking and no ReDoS surface. A real expression language is
 * a separate owner decision.
 *
 * Case sensitivity is explicit per condition: the plain names are case-sensitive, the `-ci`
 * variants are case-insensitive. Pure; JVM-tested.
 */
sealed interface LoopCondition {
    /** `approved` — the critic convention: output begins with APPROVE (case-insensitive). */
    data object Approved : LoopCondition
    /** `!approved` (alias `not approved`). */
    data object NotApproved : LoopCondition
    data class Contains(val text: String, val ignoreCase: Boolean, val negated: Boolean) : LoopCondition
    data class Equals(val text: String, val ignoreCase: Boolean) : LoopCondition
    data class StartsWith(val text: String, val ignoreCase: Boolean) : LoopCondition
    data class LengthGreater(val n: Int) : LoopCondition
    data class LengthLess(val n: Int) : LoopCondition
    data object IsEmpty : LoopCondition
    data object IsNotEmpty : LoopCondition
}

object ConditionSyntax {
    /** Longest literal a condition may carry. */
    const val MAX_TEXT = 200

    /** Largest N a length condition accepts (above the input cap it could never be true). */
    const val MAX_LENGTH_N = ConditionEvaluator.INPUT_CAP

    /** The structured choices the editor offers, as `(label, template)`; `text`/`N` are filled in. */
    val NAMES: List<String> = listOf(
        "approved", "!approved",
        "contains", "!contains", "contains-ci", "!contains-ci",
        "equals", "equals-ci", "startsWith", "startsWith-ci",
        "length>", "length<", "is-empty", "is-not-empty",
    )

    /** Parse a saved condition string; `null` when it is not one of the closed set (never throws). */
    fun parse(raw: String?): LoopCondition? {
        if (raw == null) return null
        val s = raw.trim()
        when (s.lowercase()) {
            "approved" -> return LoopCondition.Approved
            "!approved", "not approved" -> return LoopCondition.NotApproved
            "is-empty" -> return LoopCondition.IsEmpty
            "is-not-empty" -> return LoopCondition.IsNotEmpty
        }
        if (s.startsWith("length>") || s.startsWith("length<")) {
            val digits = s.substring(7)
            if (digits.isEmpty() || digits.length > 9 || !digits.all { it in '0'..'9' }) return null
            val n = digits.toLong()
            if (n > MAX_LENGTH_N) return null
            return if (s[6] == '>') LoopCondition.LengthGreater(n.toInt()) else LoopCondition.LengthLess(n.toInt())
        }
        val open = s.indexOf('(')
        if (open <= 0 || !s.endsWith(")")) return null
        val name = s.substring(0, open)
        val text = s.substring(open + 1, s.length - 1)
        if (text.isEmpty() || text.length > MAX_TEXT) return null
        return when (name) {
            "contains" -> LoopCondition.Contains(text, ignoreCase = false, negated = false)
            "contains-ci" -> LoopCondition.Contains(text, ignoreCase = true, negated = false)
            "!contains" -> LoopCondition.Contains(text, ignoreCase = false, negated = true)
            "!contains-ci" -> LoopCondition.Contains(text, ignoreCase = true, negated = true)
            "equals" -> LoopCondition.Equals(text, ignoreCase = false)
            "equals-ci" -> LoopCondition.Equals(text, ignoreCase = true)
            "startsWith" -> LoopCondition.StartsWith(text, ignoreCase = false)
            "startsWith-ci" -> LoopCondition.StartsWith(text, ignoreCase = true)
            else -> null
        }
    }

    /** Canonical saved form; `parse(format(c)) == c` for every valid condition. */
    fun format(c: LoopCondition): String = when (c) {
        LoopCondition.Approved -> "approved"
        LoopCondition.NotApproved -> "!approved"
        is LoopCondition.Contains -> (if (c.negated) "!" else "") + "contains" + (if (c.ignoreCase) "-ci" else "") + "(${c.text})"
        is LoopCondition.Equals -> "equals" + (if (c.ignoreCase) "-ci" else "") + "(${c.text})"
        is LoopCondition.StartsWith -> "startsWith" + (if (c.ignoreCase) "-ci" else "") + "(${c.text})"
        is LoopCondition.LengthGreater -> "length>${c.n}"
        is LoopCondition.LengthLess -> "length<${c.n}"
        LoopCondition.IsEmpty -> "is-empty"
        LoopCondition.IsNotEmpty -> "is-not-empty"
    }

    /** Words for the editor chip and the run log — glyph-led by the caller, never colour. */
    fun describe(c: LoopCondition): String = when (c) {
        LoopCondition.Approved -> "output is approved"
        LoopCondition.NotApproved -> "output is not approved"
        is LoopCondition.Contains ->
            "output ${if (c.negated) "does not contain" else "contains"} \"${c.text}\" (${cs(c.ignoreCase)})"
        is LoopCondition.Equals -> "output equals \"${c.text}\" (${cs(c.ignoreCase)})"
        is LoopCondition.StartsWith -> "output starts with \"${c.text}\" (${cs(c.ignoreCase)})"
        is LoopCondition.LengthGreater -> "output is longer than ${c.n} characters"
        is LoopCondition.LengthLess -> "output is shorter than ${c.n} characters"
        LoopCondition.IsEmpty -> "output is empty"
        LoopCondition.IsNotEmpty -> "output is not empty"
    }

    private fun cs(ignoreCase: Boolean) = if (ignoreCase) "ignoring case" else "case-sensitive"
}

object ConditionEvaluator {
    /** Input beyond this many UTF-16 units is ignored: evaluation is bounded, and says so. */
    const val INPUT_CAP = 65_536

    /** Total and deterministic: never throws, same input -> same answer. */
    fun evaluate(c: LoopCondition, lastOutput: String): Boolean {
        val out = if (lastOutput.length > INPUT_CAP) lastOutput.substring(0, INPUT_CAP) else lastOutput
        return when (c) {
            LoopCondition.Approved -> out.trimStart().startsWith("APPROVE", ignoreCase = true)
            LoopCondition.NotApproved -> !out.trimStart().startsWith("APPROVE", ignoreCase = true)
            is LoopCondition.Contains -> out.contains(c.text, ignoreCase = c.ignoreCase) != c.negated
            is LoopCondition.Equals -> out.trim().equals(c.text, ignoreCase = c.ignoreCase)
            is LoopCondition.StartsWith -> out.trimStart().startsWith(c.text, ignoreCase = c.ignoreCase)
            is LoopCondition.LengthGreater -> out.codePointCount(0, out.length) > c.n
            is LoopCondition.LengthLess -> out.codePointCount(0, out.length) < c.n
            LoopCondition.IsEmpty -> out.isBlank()
            LoopCondition.IsNotEmpty -> out.isNotBlank()
        }
    }
}
