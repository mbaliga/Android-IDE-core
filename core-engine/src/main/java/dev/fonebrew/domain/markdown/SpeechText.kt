package dev.fonebrew.domain.markdown

/**
 * Markdown -> speech-sensible plain text, for TurnActionsSheet's "Read aloud" row (lane A3's
 * honest missing-list update — see `ChatScreen.kt`'s TurnActionsSheet KDoc).
 * [dev.fonebrew.service.OnDeviceReadAloud] hands the *result* of [plain] to Android's on-device
 * `TextToSpeech` (binding rule 2: no network TTS, ever — this file imports no Android class at
 * all, so that constraint holds by construction, not by discipline).
 *
 * Deliberately conservative, same "keep the surface small" stance as this package's
 * [StreamingMarkdown]: strips exactly the marks that would otherwise be read aloud literally —
 * a heading `#`, a bullet `-`, `**` around emphasis, a link's `(url)` — and collapses a fenced
 * code block to a single spoken "Code block." rather than reading source punctuation character
 * by character (which is worse than a short, honest summary of what's there). Tables, out of
 * scope for [StreamingMarkdown] entirely, get a light touch here: a separator row is dropped and
 * a data row's cells are read as a comma list rather than silently losing their delimiters.
 *
 * Pure text transform — no Android, no clock, no randomness. Never throws; blank input yields
 * `""`.
 */
object SpeechText {

    /**
     * [markdown] reduced to one flowing, speech-ready string: fenced code collapsed to "Code
     * block.", inline code/emphasis/link/image markup unwrapped to their visible text,
     * heading/blockquote/list/horizontal-rule/table-separator line markers stripped, and every
     * remaining non-blank line joined with a single space (a line break reads as a pause, not
     * silence — and joining, rather than keeping raw newlines, is what keeps this "one utterance"
     * rather than a string [android.speech.tts.TextToSpeech] would read with odd internal gaps).
     */
    fun plain(markdown: String): String {
        if (markdown.isBlank()) return ""
        val spoken = mutableListOf<String>()
        var inFence = false
        var fenceChar = '`'
        for (rawLine in markdown.split("\n")) {
            val trimmed = rawLine.trim()
            if (inFence) {
                if (isFenceLine(trimmed, fenceChar)) inFence = false
                continue // code content itself is never read
            }
            val openChar = fenceCharOf(trimmed)
            if (openChar != null) {
                inFence = true
                fenceChar = openChar
                spoken += "Code block."
                continue
            }
            val line = lineToSpeech(trimmed)
            if (line.isNotBlank()) spoken += line
        }
        return spoken.joinToString(" ").replace(WHITESPACE_RUN, " ").trim()
    }

    // ---- internals (module-visible: dev.fonebrew.domain.tasks.TaskFromTurn's title derivation
    // reuses the exact same single-line unwrap so a task title and a spoken turn agree on what
    // "plain" means for one line, rather than maintaining two divergent strippers). ----

    /**
     * Structural handling (heading/blockquote/list/horizontal-rule/table-row) plus inline markup
     * unwrap, for one already-trimmed line that is known not to be a fence-toggle line itself.
     * `""` for a line that carries no speakable content at all (a horizontal rule, a table
     * separator row).
     */
    internal fun lineToSpeech(trimmedLine: String): String {
        if (trimmedLine.isEmpty()) return ""
        if (isHorizontalRule(trimmedLine)) return ""
        if (isTableSeparatorRow(trimmedLine)) return ""

        var line = trimmedLine
        line = BLOCKQUOTE_MARKER.replace(line, "")
        line = HEADING_MARKER.replaceFirst(line, "")
        line = ORDERED_LIST_MARKER.replaceFirst(line, "")
        line = UNORDERED_LIST_MARKER.replaceFirst(line, "")

        if (line.startsWith("|")) {
            // A GFM data row: pipes read naturally as a comma-separated list rather than
            // vanishing along with the delimiters.
            line = line.trim('|').split("|").joinToString(", ") { it.trim() }
        }

        return unwrapInline(line).trim()
    }

    /** Images/links reduced to their visible text, inline code unwrapped, emphasis markers
     *  stripped — in that order, so `` ![alt](url) `` never gets treated as a bracketed link
     *  once its leading `!` is already consumed. */
    internal fun unwrapInline(text: String): String {
        var out = text
        out = IMAGE_MARKUP.replace(out) { it.groupValues[1] }
        out = LINK_MARKUP.replace(out) { it.groupValues[1] }
        out = INLINE_CODE.replace(out) { it.groupValues[1] }
        out = BOLD_ITALIC_STAR.replace(out) { it.groupValues[1] }
        out = BOLD_ITALIC_UNDERSCORE.replace(out) { it.groupValues[1] }
        out = BOLD_STAR.replace(out) { it.groupValues[1] }
        out = BOLD_UNDERSCORE.replace(out) { it.groupValues[1] }
        out = ITALIC_STAR.replace(out) { it.groupValues[1] }
        out = ITALIC_UNDERSCORE.replace(out) { it.groupValues[1] }
        return out
    }

    /** True when [trimmedLine] closes the fence opened with [expected] (a run of only that
     *  char, length >= 3 — the same "any length >= the opener" leniency CommonMark allows). */
    private fun isFenceLine(trimmedLine: String, expected: Char): Boolean {
        if (trimmedLine.length < 3) return false
        return trimmedLine.all { it == expected }
    }

    /** The fence char ('`' or '~') if [trimmedLine] opens a fenced block (>= 3 of it at the
     *  start), else `null`. Any info string (language tag) after the run is ignored — unlike
     *  [StreamingMarkdown], nothing downstream needs it, the whole block becomes "Code block." */
    private fun fenceCharOf(trimmedLine: String): Char? {
        if (trimmedLine.length < 3) return null
        val c = trimmedLine[0]
        if (c != '`' && c != '~') return null
        val run = trimmedLine.takeWhile { it == c }
        return if (run.length >= 3) c else null
    }

    /** A line of 3+ identical `-`/`*`/`_` and nothing else. */
    private fun isHorizontalRule(trimmedLine: String): Boolean {
        if (trimmedLine.length < 3) return false
        val c = trimmedLine[0]
        if (c != '-' && c != '*' && c != '_') return false
        return trimmedLine.all { it == c }
    }

    /** A GFM separator row: pipes plus only `-`, `:`, spaces between them (e.g. `|---|:--:|`) —
     *  same rule [StreamingMarkdown.isSeparatorRow] uses, reproduced here since that one is
     *  file-private and this file's scope (a *complete* turn, not a streaming partial) doesn't
     *  need the rest of that class's streaming-specific machinery. */
    private fun isTableSeparatorRow(trimmedLine: String): Boolean {
        if (!trimmedLine.startsWith("|")) return false
        val inner = trimmedLine.trim('|')
        if (inner.isBlank()) return false
        return inner.all { it == '-' || it == ':' || it == '|' || it == ' ' } && inner.contains('-')
    }

    private val WHITESPACE_RUN = Regex("\\s+")
    private val BLOCKQUOTE_MARKER = Regex("^(>\\s?)+")
    private val HEADING_MARKER = Regex("^#{1,6}\\s+")
    private val ORDERED_LIST_MARKER = Regex("^\\d+[.)]\\s+")
    private val UNORDERED_LIST_MARKER = Regex("^[-*+]\\s+")
    private val IMAGE_MARKUP = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
    private val LINK_MARKUP = Regex("\\[([^\\]]*)]\\([^)]*\\)")
    private val INLINE_CODE = Regex("`([^`]*)`")
    private val BOLD_ITALIC_STAR = Regex("\\*\\*\\*([^*]+)\\*\\*\\*")
    private val BOLD_ITALIC_UNDERSCORE = Regex("___([^_]+)___")
    private val BOLD_STAR = Regex("\\*\\*([^*]+)\\*\\*")
    private val BOLD_UNDERSCORE = Regex("__([^_]+)__")
    private val ITALIC_STAR = Regex("\\*([^*]+)\\*")
    private val ITALIC_UNDERSCORE = Regex("_([^_]+)_")
}
