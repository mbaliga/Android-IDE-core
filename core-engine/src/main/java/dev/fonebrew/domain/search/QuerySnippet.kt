package dev.fonebrew.domain.search

import java.util.Locale

/**
 * Builds the **query-centred** preview line for one search result.
 *
 * ### What was wrong
 * Every result row used to show `conv_projection.snippet_raw` — the first 220 characters of a
 * conversation's first assistant turn, chosen by [dev.fonebrew.data.search.SearchProjector] at
 * *index* time, before any query existed. So a hit forty turns deep showed a preview that did not
 * contain the searched-for word, and the user had to open the conversation to find out why it was
 * a result at all. Ranking was real; the evidence for it wasn't shown.
 *
 * ### Where the text comes from, in order
 * 1. **A window of the raw body around the first matched term.** Preferred because it is the
 *    *original* text — real casing, real punctuation — so it reads like the conversation.
 * 2. **FTS5's own `snippet()`** over the indexed column (passed in as [ftsSnippet]; see
 *    `Search.sq`'s `searchCandidatesWithFacets`), *and only when it actually contains a query
 *    term*. The index stores **NFKC-normalized** text ([Segmenter]), so a conversation written
 *    with a ligature (`oﬃce`) is indexed — and matched — as `office`, which is not a substring
 *    of the raw body at all; a `lowercase()` that changes length (`İ`) does the same. FTS5 knows
 *    where its own match landed when a Kotlin `indexOf` cannot. The text it returns is the
 *    normalized form — lower-case, punctuation dropped, degraded to look at, but query-centred.
 *
 *    The "contains a term" test is what keeps this from *hurting*: for a hit that matched in the
 *    **title**, `snippet()` over the body column returns that column's leading text, which is a
 *    worse-looking version of what step 3 already says. So a snippet with no term in it is
 *    treated as no snippet.
 * 3. **The projected snippet** — the old behaviour, kept as the last resort, as the answer for a
 *    title-only match, and as the honest answer for a facet-only query (`is:starred`), which has
 *    no term to centre on.
 *
 * Note what is deliberately *not* on that list: CJK. Segmentation changes which tokens the index
 * matches, but a matched token is normally still a literal substring of the raw text, so step 1
 * handles `東京駅` fine — no special case needed, and claiming one would be inventing a reason.
 *
 * Pure and clock-free like the rest of `domain/search/`; JVM-tested.
 */
object QuerySnippet {

    /** Characters of context shown. Sized for the two-line result row the overlay renders. */
    const val WINDOW_CHARS = 180

    /** How much of the window sits *before* the match, so the hit isn't flush against the edge. */
    const val LEAD_CHARS = 48

    private const val ELLIPSIS = "…"

    private val WHITESPACE_RUN = Regex("\\s+")

    /**
     * @param bodyRaw the conversation's original concatenated text (`conv_projection.body_raw`).
     * @param projected the index-time snippet (`snippet_raw`) — the fallback, and the whole
     *   answer when there is nothing to centre on.
     * @param ftsSnippet FTS5 `snippet()` over the indexed body column, or `null` when the query
     *   had no `MATCH` (the facet-only path) or the column wasn't selected.
     * @param queryTerms already-normalized terms, as produced by [LexicalSearch.tokenizeQuery].
     */
    fun centered(
        bodyRaw: String,
        projected: String,
        ftsSnippet: String?,
        queryTerms: List<String>,
    ): String {
        if (queryTerms.isNotEmpty()) {
            // Collapse whitespace only — the body is turns joined by blank lines, and a raw
            // window would otherwise render as ragged fragments in a two-line row. Case is
            // preserved here (unlike LexicalSearch.normalize) precisely because this text is for
            // reading, not matching.
            val display = bodyRaw.replace(WHITESPACE_RUN, " ").trim()
            val haystack = display.lowercase(Locale.ROOT)
            // A handful of code points change length when lower-cased (İ → i + combining dot).
            // When that happens the indices below would point at the wrong characters, so this
            // path steps aside for FTS5's rather than mis-quoting the conversation.
            if (haystack.length == display.length) {
                val at = queryTerms
                    .filter { it.isNotEmpty() }
                    .map { haystack.indexOf(it) }
                    .filter { it >= 0 }
                    .minOrNull()
                if (at != null) return window(display, at)
            }
        }
        val fts = ftsSnippet?.trim().orEmpty()
        // Only if FTS5's window genuinely contains what was searched for. For a title-only hit
        // snippet() falls back to the body column's leading text, and that lower-cased,
        // punctuation-stripped opening is strictly worse than the projected snippet below.
        if (fts.isNotBlank()) {
            val lowered = fts.lowercase(Locale.ROOT)
            if (queryTerms.any { it.isNotEmpty() && lowered.contains(it) }) return fts
        }
        return projected.ifBlank { clip(bodyRaw) }
    }

    /** [WINDOW_CHARS] of [text] around [matchStart], snapped to word boundaries and marked with
     *  an ellipsis on whichever side was actually cut. */
    private fun window(text: String, matchStart: Int): String {
        var start = (matchStart - LEAD_CHARS).coerceIn(0, text.length)
        if (start > 0) {
            // Snap forward to the next space so the window never opens mid-word. Bounded: if the
            // next space is far away (an unbroken run, e.g. a URL or a CJK sentence), keep the
            // hard offset rather than skipping past the match itself.
            val space = text.indexOf(' ', start)
            if (space in start until minOf(start + WORD_SNAP_CHARS, matchStart)) start = space + 1
        }
        var end = (start + WINDOW_CHARS).coerceAtMost(text.length)
        if (end < text.length) {
            val space = text.lastIndexOf(' ', end)
            if (space > matchStart) end = space
        }
        val prefix = if (start > 0) ELLIPSIS else ""
        val suffix = if (end < text.length) ELLIPSIS else ""
        return prefix + text.substring(start, end).trim() + suffix
    }

    /** How far [window] will look for a word boundary before giving up and cutting mid-word. */
    private const val WORD_SNAP_CHARS = 24

    private fun clip(text: String): String {
        val collapsed = text.replace(WHITESPACE_RUN, " ").trim()
        return if (collapsed.length <= WINDOW_CHARS) collapsed else collapsed.take(WINDOW_CHARS) + ELLIPSIS
    }
}
