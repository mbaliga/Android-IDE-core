package dev.aarso.data.search

import dev.aarso.domain.search.LexicalSearch
import dev.aarso.domain.search.SearchDoc
import dev.aarso.domain.search.SearchHit
import dev.aarso.domain.search.SearchKind

/**
 * T0 candidate generation + final re-scoring (Doc `FONEBREW_SEARCH_SPEC.md` §5, WP7): the FTS5
 * `MATCH` query (already written, WP3/4) narrows the whole index down to a bounded candidate
 * set, then [LexicalSearch] — unchanged, per the spec's hard rule — does the actual ranking and
 * highlighting over that set with `explain = true`.
 *
 * No facet `WHERE` clause and no vector fusion here yet: facets are the query-language parser's
 * job (WP8, not landed), and vectors are M4 (out of scope for this pass). With `w_vec` effectively
 * 0 (spec §5.2: "0.0 when semantic is off, which reduces to pure lexical"), this **is** already
 * the semantic-off path M3 ships with — proven by [SearchQueryTest]'s golden-ordering test: FTS5
 * is purely a candidate generator here, never a second ranker, so its output re-scored by
 * [LexicalSearch] must land in exactly the order [LexicalSearch.search] would produce directly
 * over the same documents (Doc §13 test 12).
 */
object SearchQuery {

    /** Candidate-set size before re-scoring (spec §5.1's `LIMIT 400`). */
    const val CANDIDATE_LIMIT = 400L

    /** Results returned to the caller after re-scoring. */
    const val DEFAULT_RESULT_LIMIT = 50

    fun search(
        database: SearchDatabase,
        query: String,
        nowMillis: Long,
        resultLimit: Int = DEFAULT_RESULT_LIMIT,
    ): List<SearchHit> {
        val ftsQuery = compileFtsQuery(query) ?: return emptyList()
        val candidates = database.searchQueries.searchCandidates(ftsQuery, CANDIDATE_LIMIT).executeAsList()
        if (candidates.isEmpty()) return emptyList()

        val docs = candidates.map { row ->
            SearchDoc(
                id = row.conv_id,
                title = row.title_raw,
                snippet = row.snippet_raw,
                body = row.body_raw,
                lastActivityMillis = row.updated_at,
                // Facet-derived kind (TEXT/IMAGE/MIXED) isn't in the T0 candidate row and isn't
                // used by LexicalSearch's scoring — WP8's facet layer is what actually filters
                // has:image, not this field. Defaulting is harmless, not a silent lie.
                kind = SearchKind.TEXT,
            )
        }
        return LexicalSearch.search(docs, query, nowMillis, explain = true).take(resultLimit)
    }

    /**
     * Compiles a plain user query into an FTS5 `MATCH` expression: each whitespace-delimited,
     * NFKC-normalized term (via [LexicalSearch.tokenizeQuery] — same normalization the index
     * itself was built with, see [Segmenter][dev.aarso.domain.search.Segmenter]) becomes a
     * quoted prefix match, e.g. `gradle cache` -> `"gradle"* "cache"*`. Quoting every term as a
     * literal string is what keeps this safe against FTS5 query-syntax injection (a term like
     * `OR`, `-foo`, or `(bar` would otherwise be parsed as an FTS5 operator, not a literal).
     *
     * This is a stub ahead of WP8's real query-language parser (`QueryNode`/`ParsedQuery`) —
     * no quoted-phrase passthrough, no facet/regex/boolean operators yet, just term-AND with
     * per-term prefix. Returns `null` for a blank query, mirroring
     * [LexicalSearch.tokenizeQuery]'s own blank-query contract.
     */
    fun compileFtsQuery(query: String): String? {
        val terms = LexicalSearch.tokenizeQuery(query)
        if (terms.isEmpty()) return null
        return terms.joinToString(" ") { term -> "\"" + term.replace("\"", "\"\"") + "\"*" }
    }
}
