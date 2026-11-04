package dev.fonebrew.ui.search

import dev.fonebrew.domain.format.LocaleFormat
import dev.fonebrew.domain.search.LexicalSearch
import dev.fonebrew.domain.search.MatchExplanation
import dev.fonebrew.domain.search.MatchedIn
import dev.fonebrew.domain.search.SearchHit
import dev.fonebrew.domain.search.SearchKind
import java.time.ZoneId
import java.util.Locale

/**
 * Pure derivation of the search overlay's result rows from raw [SearchHit]s — no Android, no
 * clock, no I/O, following this repo's presenter convention
 * ([dev.fonebrew.ui.state.ConversationsPresenter]/[dev.fonebrew.ui.state.ChatThreadPresenter]).
 * [SearchViewModel] supplies the live inputs (query results, clock, locale); this function does
 * the actual formatting/derivation work, so it's the only part of the search-overlay UI stack
 * that's unit-testable without a device (Compose rendering itself is owner-verify, same as the
 * rest of this repo).
 */
object SearchResultsPresenter {

    /** One rendered result row. [titleHighlights]/[snippetHighlights] are character ranges into
     *  [title]/[snippet] respectively. Title ranges come straight from [SearchHit.highlights]
     *  (which index the title exactly when [matchedIn] is [MatchedIn.TITLE]); snippet ranges are
     *  recomputed against [snippet] itself — see [present]'s `queryText` for why they can't be
     *  taken from the hit. */
    data class ResultRow(
        /** The opened record's id — a conversation's root id for [SearchKind.TEXT]/[IMAGE]/
         *  [MIXED] (the field's original, narrower meaning), a loop id for [SearchKind.LOOP],
         *  a task id for [SearchKind.TASK]. Kept as `convId` rather than renamed: it is also the
         *  `LazyColumn` item key and the argument to [dev.fonebrew.ui.search.SearchViewModel]'s
         *  `toggleExplain`/`onResultOpened`, and none of those care which kind of id it is. */
        val convId: String,
        val title: String,
        val snippet: String,
        val relativeTime: String,
        val matchedIn: MatchedIn,
        val titleHighlights: List<IntRange>,
        val snippetHighlights: List<IntRange>,
        val explanation: MatchExplanation?,
        /** What [convId] actually identifies — see its own KDoc. Lets the overlay open a hit the
         *  right way: a conversation into chat, a loop into LoopRoom, a task into the Project
         *  room ([dev.fonebrew.ui.search.SearchOverlay]'s `onOpenLoop`/`onOpenTask`). */
        val kind: SearchKind = SearchKind.TEXT,
    )

    /**
     * @param queryText the plain lexical text of the query (facet/regex syntax stripped —
     *   `QueryCompiler.lexicalText`). Supplied so snippet highlights can be computed against the
     *   snippet **as drawn**. [SearchHit.highlights] can't serve that: for a content match they
     *   index `snippet + " " + body` (see [dev.fonebrew.domain.search.LexicalSearch.search]),
     *   which is not the string this row renders — ranges past the snippet's end were silently
     *   dropped, and ranges straddling its end highlighted the wrong characters. Now that
     *   [dev.fonebrew.data.search.SearchQuery] replaces the snippet with a query-centred window,
     *   recomputing is also the only way the highlight lands on the term that window exists to
     *   show. Defaults to blank, which keeps the old (hit-supplied) behaviour for any caller that
     *   has no query text to give.
     */
    fun present(
        hits: List<SearchHit>,
        nowMillis: Long,
        zone: ZoneId,
        locale: Locale,
        queryText: String = "",
    ): List<ResultRow> {
        val terms = LexicalSearch.tokenizeQuery(queryText)
        return hits.map { hit ->
            val snippet = hit.doc.snippet.ifBlank { hit.doc.body.take(SNIPPET_FALLBACK_CHARS) }
            ResultRow(
                convId = hit.doc.id,
                title = hit.doc.title,
                snippet = snippet,
                relativeTime = LocaleFormat.relativeOrAbsolute(hit.doc.lastActivityMillis, nowMillis, zone, locale),
                matchedIn = hit.matchedIn,
                titleHighlights = if (hit.matchedIn == MatchedIn.TITLE) hit.highlights else emptyList(),
                snippetHighlights = when {
                    terms.isNotEmpty() -> LexicalSearch.findMatches(snippet, terms)
                    hit.matchedIn == MatchedIn.CONTENT -> hit.highlights
                    else -> emptyList()
                },
                explanation = hit.explanation,
                kind = hit.doc.kind,
            )
        }
    }

    private const val SNIPPET_FALLBACK_CHARS = 160
}
