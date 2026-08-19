package dev.fonebrew.ui.search

import dev.fonebrew.domain.format.LocaleFormat
import dev.fonebrew.domain.search.MatchExplanation
import dev.fonebrew.domain.search.MatchedIn
import dev.fonebrew.domain.search.SearchHit
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
     *  [title]/[snippet] respectively — whichever [matchedIn] didn't hit gets an empty list,
     *  since [SearchHit.highlights] only ever indexes the one field it actually matched in. */
    data class ResultRow(
        val convId: String,
        val title: String,
        val snippet: String,
        val relativeTime: String,
        val matchedIn: MatchedIn,
        val titleHighlights: List<IntRange>,
        val snippetHighlights: List<IntRange>,
        val explanation: MatchExplanation?,
    )

    fun present(hits: List<SearchHit>, nowMillis: Long, zone: ZoneId, locale: Locale): List<ResultRow> =
        hits.map { hit ->
            ResultRow(
                convId = hit.doc.id,
                title = hit.doc.title,
                snippet = hit.doc.snippet.ifBlank { hit.doc.body.take(SNIPPET_FALLBACK_CHARS) },
                relativeTime = LocaleFormat.relativeOrAbsolute(hit.doc.lastActivityMillis, nowMillis, zone, locale),
                matchedIn = hit.matchedIn,
                titleHighlights = if (hit.matchedIn == MatchedIn.TITLE) hit.highlights else emptyList(),
                snippetHighlights = if (hit.matchedIn == MatchedIn.CONTENT) hit.highlights else emptyList(),
                explanation = hit.explanation,
            )
        }

    private const val SNIPPET_FALLBACK_CHARS = 160
}
