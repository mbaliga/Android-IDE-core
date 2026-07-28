package dev.aarso.domain.prompt

/**
 * Instant, model-free prompt linting (handoff §6a): as-you-type heuristics only —
 * length, vague pronouns, missing role/format/constraints — plus variable-span
 * detection for templatization. Must be instant, so this is pure string work with
 * no model and no I/O. The on-demand model rewrite (§6b) is a separate, later path.
 */
enum class LintSeverity { SUGGESTION, INFO }

data class LintFinding(val severity: LintSeverity, val message: String)

/** A span that looks like a fill-in slot, for "extract into a reusable variable". */
data class VariableSpan(val start: Int, val end: Int, val text: String)

data class LintResult(
    val findings: List<LintFinding>,
    val variables: List<VariableSpan>,
) {
    val isClean: Boolean get() = findings.isEmpty()
}

object PromptLinter {

    private val ROLE_CUES = listOf("you are", "act as", "as a ", "your role")
    private val FORMAT_CUES = listOf(
        "format", "list", "bullet", "json", "table", "steps", "step-by-step",
        "step by step", "markdown", "outline",
    )
    private val CONSTRAINT_CUES = listOf(
        "word", "sentence", "paragraph", "concise", "brief", "short", "long",
        "tone", "formal", "casual", "under ", "at most", "no more than",
    )
    private val VAGUE_OPENERS = setOf("it", "this", "that", "these", "those", "they")

    // {{var}} / {var} / [VAR] / <var>, and bare ALL_CAPS tokens (NAME, TOPIC).
    private val DELIMITED = Regex("""\{\{[^}]+\}\}|\{[^}]+\}|\[[^\]]+\]|<[^>]+>""")
    private val ALL_CAPS = Regex("""\b[A-Z][A-Z0-9_]{2,}\b""")

    fun lint(text: String): LintResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return LintResult(emptyList(), emptyList())

        val findings = mutableListOf<LintFinding>()
        val lower = trimmed.lowercase()
        val words = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }

        if (words.size < 4) {
            findings += LintFinding(LintSeverity.SUGGESTION, "Very short — add detail about what you want.")
        }

        val firstWord = words.firstOrNull()?.trimEnd('.', ',', ':', ';')?.lowercase()
        if (firstWord in VAGUE_OPENERS) {
            findings += LintFinding(LintSeverity.SUGGESTION, "Opens with a vague pronoun (\"$firstWord\") — name the subject.")
        }

        // "Missing X" notes only once the prompt looks like a real instruction.
        if (words.size >= 6) {
            if (ROLE_CUES.none { lower.contains(it) }) {
                findings += LintFinding(LintSeverity.INFO, "No role set (e.g. \"You are a…\").")
            }
            if (FORMAT_CUES.none { lower.contains(it) }) {
                findings += LintFinding(LintSeverity.INFO, "No output format specified (list, JSON, steps…).")
            }
            val hasNumber = trimmed.any { it.isDigit() }
            if (!hasNumber && CONSTRAINT_CUES.none { lower.contains(it) }) {
                findings += LintFinding(LintSeverity.INFO, "No constraints (length, tone…).")
            }
        }

        val variables = detectVariables(text)
        if (variables.isNotEmpty()) {
            findings += LintFinding(
                LintSeverity.SUGGESTION,
                "Detected ${variables.size} variable slot(s) — templatize for reuse?",
            )
        }

        return LintResult(findings, variables)
    }

    private fun detectVariables(text: String): List<VariableSpan> {
        val spans = mutableListOf<VariableSpan>()
        for (m in DELIMITED.findAll(text)) spans += VariableSpan(m.range.first, m.range.last + 1, m.value)
        for (m in ALL_CAPS.findAll(text)) spans += VariableSpan(m.range.first, m.range.last + 1, m.value)
        return spans.sortedBy { it.start }
    }
}
