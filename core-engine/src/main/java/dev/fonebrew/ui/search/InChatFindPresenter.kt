package dev.fonebrew.ui.search

/**
 * Find-in-chat (Doc `FONEBREW_SEARCH_SPEC.md` §9, S9): pure, JVM-tested matching over the
 * **active thread's rendered text** (each [dev.fonebrew.ui.state.ChatThreadPresenter.ThreadRow]'s
 * `renderText`) — no Android, no clock.
 *
 * One deliberate scope cut from a literal reading of "reuse the [dev.fonebrew.domain.search
 * .Segmenter]/[dev.fonebrew.domain.search.LexicalSearch] pipeline": that pipeline is a *document*
 * scorer (title/snippet/body → ranked hits with a relevance score), and find-in-chat needs the
 * opposite shape — every literal/regex occurrence across an ordered list of rows, precisely
 * positioned for a "n of m" ribbon and (row-level) highlighting. Kotlin's own `Regex` already
 * does correct Unicode-aware case folding and word-boundary matching for this; routing it
 * through the doc-scoring pipeline would add indirection without adding correctness. What *is*
 * shared with the rest of search: the same never-throws-on-bad-input ethos as
 * [dev.fonebrew.domain.search.query.QueryParser] — an invalid `/regex/` degrades to
 * [FindResult.error], never a crash.
 */
object InChatFindPresenter {

    data class FindOptions(
        val caseSensitive: Boolean = false,
        val wholeWord: Boolean = false,
        val useRegex: Boolean = false,
    )

    /** One occurrence. [range] is a character range into the row's own text. */
    data class Hit(val rowIndex: Int, val nodeId: String, val range: IntRange)

    data class FindResult(
        val hits: List<Hit>,
        val error: String?,
    ) {
        val highlightsByNode: Map<String, List<IntRange>> get() = hits.groupBy({ it.nodeId }, { it.range })
    }

    /** [rows] is (nodeId, renderText) in thread order. A blank [query] finds nothing (not an
     *  error — the empty starting state). An unmatchable/invalid regex degrades to
     *  [FindResult.error] rather than throwing, mirroring the rest of search's hard rule. */
    fun find(rows: List<Pair<String, String>>, query: String, options: FindOptions): FindResult {
        if (query.isBlank()) return FindResult(emptyList(), null)
        val regex = try {
            buildRegex(query, options)
        } catch (e: Exception) {
            return FindResult(emptyList(), "Invalid pattern: ${e.message ?: query}")
        }
        val hits = mutableListOf<Hit>()
        rows.forEachIndexed { rowIndex, (nodeId, text) ->
            regex.findAll(text).forEach { m ->
                if (m.range.isEmpty() && m.value.isEmpty()) return@forEach
                hits.add(Hit(rowIndex, nodeId, m.range))
            }
        }
        return FindResult(hits, null)
    }

    /** Clamped next/previous index into [FindResult.hits], wrapping both directions; -1 (no
     *  hits) maps to -1 regardless of direction. */
    fun step(hitCount: Int, currentIndex: Int, forward: Boolean): Int {
        if (hitCount == 0) return -1
        val base = if (currentIndex in 0 until hitCount) currentIndex else 0
        return if (forward) (base + 1) % hitCount else (base - 1 + hitCount) % hitCount
    }

    private fun buildRegex(query: String, options: FindOptions): Regex {
        val flags = if (options.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        val pattern = when {
            options.useRegex -> query
            options.wholeWord -> "\\b${Regex.escape(query)}\\b"
            else -> Regex.escape(query)
        }
        return Regex(pattern, flags)
    }
}
