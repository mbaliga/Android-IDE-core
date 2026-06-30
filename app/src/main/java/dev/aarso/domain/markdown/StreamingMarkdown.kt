package dev.aarso.domain.markdown

/**
 * Streaming-safe markdown reconciliation (Doc 01 §2.3 + §15): an assistant turn
 * arrives **token by token**, so at any instant the accumulated text may be *partial*
 * markdown — an unclosed code fence, a half-built table, a row written only halfway.
 * Handing that raw to a strict markdown renderer makes the layout **thrash**: the
 * renderer flips a paragraph into a code block and back as the closing fence lands,
 * tables jump as a separator row appears, emphasis flickers as a `*` finds its mate.
 *
 * This file is the *pure text* fix, not a renderer (the app uses a Compose markdown
 * lib). [reconcile] takes the partial text-so-far and returns a **render-safe** version
 * — e.g. with an open ``` fence *temporarily* closed — plus the structural facts about
 * what it had to complete or trim, so the caller can show a subtle "still streaming"
 * cue (a soft caret, a dimmed tail) instead of a jumping block.
 *
 * ## Contract (the whole point)
 * - **Idempotent on complete markdown.** If [partial] is already well-formed (even
 *   number of fences, no dangling table row), [reconcile] returns the text *unchanged*
 *   with every flag `false`. `reconcile(reconcile(x).text)` is stable for such input.
 * - **Monotonic enough.** Appending more tokens must not make the *rendered* structure
 *   jump backwards. Each call closes what is open with the minimal synthetic suffix and
 *   trims only the trailing incomplete table row; it never rewrites earlier text. So the
 *   structure the user already sees only *grows* — it doesn't reflow as tokens land.
 * - **Never throws.** Any input — empty, binary-ish, deeply nested — yields a value.
 *
 * ## Scope
 * - **Fenced code blocks** (``` and ~~~) are completed: an odd fence count means an
 *   open block, so a matching closing fence is appended on its own line.
 * - **GFM tables**: a trailing row that is mid-construction (a header with no `|---|`
 *   separator yet, or a half-written row after the separator) is dropped from the safe
 *   text and flagged, so the renderer never paints a torn row.
 * - **Out of scope by design:** inline code (single backtick), emphasis/link balancing,
 *   blockquotes, lists. Inline backticks are explicitly *not* treated as fences. Keeping
 *   the surface small keeps the monotonicity guarantee honest.
 *
 * Pure domain — no Android, no clock, no randomness. JVM-tested.
 */
data class SafeMarkdown(
    /** The render-safe text: [partial] with any open fence closed and any torn trailing table row trimmed. */
    val text: String,
    /** True when [partial] had an unclosed fenced code block and a closing fence was appended. */
    val openFence: Boolean,
    /**
     * The language tag of the open fence (e.g. `"kotlin"` from ```` ```kotlin ````), or
     * `null` when the fence carried no info string or no fence is open. Lets the caller
     * keep syntax highlighting stable across the close/reopen as more tokens arrive.
     */
    val openFenceLang: String?,
    /** True when a mid-construction trailing GFM table row was dropped to avoid a torn render. */
    val truncatedTable: Boolean,
)

/**
 * Pure reconciliation logic. Stateless; every function is a deterministic function of
 * its input string.
 */
object StreamingMarkdown {

    /**
     * Make [partial] safe to hand to a strict markdown renderer mid-stream.
     *
     * Algorithm (two conservative passes):
     * 1. **Fences.** Walk the lines, tracking whether we are inside a fenced block and
     *    which marker opened it (``` or ~~~). A line is a fence toggle when its trimmed
     *    start is the active marker (or, when closed, *any* fence marker of length ≥ 3).
     *    If we finish *inside* a block, append a closing fence of the opening marker on a
     *    fresh line and record [SafeMarkdown.openFence]/[SafeMarkdown.openFenceLang].
     * 2. **Tables.** Only when *not* inside a code block: if the last non-empty line is a
     *    partial GFM table — a `|`-led header with no separator row beneath it yet, or a
     *    half-written row whose cell structure is still forming — drop that trailing line
     *    from the safe text and set [SafeMarkdown.truncatedTable].
     *
     * Everything else is left byte-for-byte untouched. Never throws.
     */
    fun reconcile(partial: String): SafeMarkdown {
        val fence = scanFences(partial)

        // Pass 2 (tables) only runs on text that is *not* sitting inside an open code
        // block — a `|` line inside a fence is literal code, not a table.
        var text = partial
        var truncatedTable = false
        if (!fence.open) {
            val trimResult = trimPartialTableRow(text)
            if (trimResult != null) {
                text = trimResult
                truncatedTable = true
            }
        }

        if (fence.open) {
            // Close the open fence on its own line. Preserve a trailing newline boundary:
            // if the text does not already end with a newline, start the closer on a new
            // line; otherwise the closer follows directly.
            val sep = if (text.isEmpty() || text.endsWith("\n")) "" else "\n"
            text = text + sep + fence.marker
        }

        return SafeMarkdown(
            text = text,
            openFence = fence.open,
            openFenceLang = if (fence.open) fence.lang else null,
            truncatedTable = truncatedTable,
        )
    }

    /**
     * True when the partial text currently ends *inside* an unclosed fenced code block.
     * Convenience over [reconcile] for callers that only need the boolean (e.g. to decide
     * whether a stray `|` should be read as table syntax or literal code).
     */
    fun isInsideCodeFence(partial: String): Boolean = scanFences(partial).open

    // ---- internals ----

    /** Result of the fence scan: whether a block is open, its marker, and its info-string language. */
    private data class FenceState(val open: Boolean, val marker: String, val lang: String?)

    /**
     * Scan [text] line by line, toggling in/out of fenced code blocks. A fence line is a
     * line whose *trimmed* content starts with a run of ≥ 3 identical fence chars (``` or
     * ~~~). When closed, either marker can open a block; once open, only the *same* marker
     * char closes it (so ~~~ inside a ``` block is literal, matching CommonMark intent).
     */
    private fun scanFences(text: String): FenceState {
        var open = false
        var marker = "```"
        var lang: String? = null

        for (rawLine in text.split("\n")) {
            val line = rawLine.trim()
            val fenceChar = fenceCharOf(line) ?: continue
            if (!open) {
                // Opening fence: capture the marker run and the info string (language).
                val run = line.takeWhile { it == fenceChar }
                marker = run
                val info = line.substring(run.length).trim()
                // The language is the first whitespace-delimited token of the info string.
                lang = info.takeIf { it.isNotEmpty() }?.split(Regex("\\s+"))?.firstOrNull()?.takeIf { it.isNotEmpty() }
                open = true
            } else {
                // Closing fence must use the same fence char and be at least as long.
                if (line.all { it == fenceChar } && line.length >= marker.length) {
                    open = false
                    lang = null
                }
            }
        }
        return FenceState(open, marker, lang)
    }

    /**
     * Returns the fence character ('`' or '~') if [trimmedLine] is a fence line (≥ 3 of
     * that char at the start, with only that char up to any info string), else `null`.
     * Inline code like `` `code` `` never matches: it neither starts with three backticks
     * nor is a pure run.
     */
    private fun fenceCharOf(trimmedLine: String): Char? {
        if (trimmedLine.length < 3) return null
        val c = trimmedLine[0]
        if (c != '`' && c != '~') return null
        val run = trimmedLine.takeWhile { it == c }
        if (run.length < 3) return null
        // After the run, only an info string is allowed; an info string may not itself
        // contain backticks (CommonMark) — but we stay lenient: any tail is treated as
        // the info string. The run length check above already excludes inline `code`.
        return c
    }

    /**
     * If the last non-empty line of [text] is a *mid-construction* GFM table row, return
     * [text] with that trailing line removed; otherwise return `null` (no change).
     *
     * Conservative rules — we only trim when leaving the line in risks a torn render:
     * - The candidate line must start with `|` (after trimming) to be table-ish at all.
     * - **Header with no separator yet:** the candidate is the *first* `|`-row of the
     *   block and the line below it is absent (it is the last line) — a header alone
     *   renders as a one-row table that will reflow once `|---|` lands. Trim it.
     * - **Half-written row:** the candidate sits under an already-present separator (so a
     *   table is established) but is itself incomplete — it has no closing `|` and only a
     *   single cell boundary so far, i.e. it is still being typed. Trim it.
     *
     * A *complete* row (balanced leading+trailing `|`, or a row matching the established
     * column count) is left alone. Separator rows (`|---|`) are never trimmed.
     */
    private fun trimPartialTableRow(text: String): String? {
        if (text.isEmpty()) return null
        val lines = text.split("\n")

        // Find the last non-empty line and its index.
        var lastIdx = lines.lastIndex
        while (lastIdx >= 0 && lines[lastIdx].isBlank()) lastIdx--
        if (lastIdx < 0) return null

        val candidate = lines[lastIdx].trim()
        if (!candidate.startsWith("|")) return null
        if (isSeparatorRow(candidate)) return null // a `|---|` line is structurally complete

        // Look at the previous non-blank line to decide header-vs-body context.
        var prevIdx = lastIdx - 1
        while (prevIdx >= 0 && lines[prevIdx].isBlank()) prevIdx--
        val prev = if (prevIdx >= 0) lines[prevIdx].trim() else ""
        val prevIsTableRow = prev.startsWith("|")
        val prevIsSeparator = prevIsTableRow && isSeparatorRow(prev)

        val complete = isCompleteRow(candidate)

        val isPartial = when {
            // Header row sitting alone (no separator line yet) — will reflow when it lands.
            !prevIsTableRow -> true
            // First two lines are header + (not-yet-separator) — the second is mid-typing.
            prevIsTableRow && !prevIsSeparator && !complete -> true
            // Body row under an established separator, but the row itself is half-written.
            prevIsSeparator && !complete -> true
            else -> false
        }
        if (!isPartial) return null

        // Drop the candidate line; keep any blank lines that preceded it intact so the
        // text up to that point is byte-identical to the input prefix.
        val kept = lines.subList(0, lastIdx)
        return kept.joinToString("\n")
    }

    /** A GFM separator row: pipes plus only `-`, `:`, spaces between them (e.g. `|---|:--:|`). */
    private fun isSeparatorRow(trimmedLine: String): Boolean {
        if (!trimmedLine.startsWith("|")) return false
        val inner = trimmedLine.trim('|').trim()
        if (inner.isEmpty()) return false
        return inner.all { it == '-' || it == ':' || it == '|' || it == ' ' } && inner.contains('-')
    }

    /**
     * A row is "complete enough" not to thrash when it has both a leading and a trailing
     * `|` with at least one cell between them. A row with a leading `|` but no closing one
     * is still being typed (the tail cell is open), so it is *not* complete.
     */
    private fun isCompleteRow(trimmedLine: String): Boolean {
        // Need at least a leading and a distinct trailing `|` (length ≥ 2). A lone `|`
        // satisfies both startsWith and endsWith on the same char — guard before slicing.
        if (trimmedLine.length < 2) return false
        if (!trimmedLine.startsWith("|")) return false
        if (!trimmedLine.endsWith("|")) return false
        // `|a|b|` -> inner "a|b" non-empty; `||` (empty) is degenerate, treat as incomplete.
        val inner = trimmedLine.substring(1, trimmedLine.length - 1)
        return inner.isNotEmpty()
    }
}
