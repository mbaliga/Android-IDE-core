package dev.fonebrew.data.search

import dev.fonebrew.domain.search.LexicalSearch
import dev.fonebrew.domain.search.SearchDoc
import dev.fonebrew.domain.search.SearchHit
import dev.fonebrew.domain.search.SearchKind
import dev.fonebrew.domain.search.query.FacetEvaluator
import dev.fonebrew.domain.search.query.FacetSubject
import dev.fonebrew.domain.search.query.ParsedQuery
import dev.fonebrew.domain.search.query.QueryCompiler
import dev.fonebrew.domain.search.query.QueryNode
import dev.fonebrew.domain.search.query.QueryParser
import java.time.ZoneId

/**
 * The full retrieval pipeline (Doc `FONEBREW_SEARCH_SPEC.md` §5): parse → FTS5 candidate
 * generation → facet filter → regex filter → [LexicalSearch] re-scoring.
 *
 * [LexicalSearch] is never modified or replaced (the spec's hard rule) — FTS5 is purely a
 * *candidate generator* here, never a second ranker, and the facet/regex stages only ever
 * *remove* candidates. So the surviving set, re-scored, lands in exactly the order
 * [LexicalSearch.search] would produce over those same documents; [SearchQueryTest]'s
 * golden-ordering test pins that (Doc §13 test 12). With `w_vec` effectively 0 (§5.2: "0.0 when
 * semantic is off, which reduces to pure lexical"), this **is** the semantic-off path M3 ships.
 *
 * ### Three retrieval shapes
 * 1. **Text (± facets)** — `gradle`, `gradle is:starred`: FTS5 `MATCH` produces candidates,
 *    facets narrow them.
 * 2. **Facet-only** — `is:starred`, `after:-7d`: there is no lexical expression to `MATCH`, so
 *    candidates are the most-recent [CANDIDATE_LIMIT] conversations, then filtered. Without this
 *    path a pure-facet query returned nothing at all, which is why it exists.
 * 3. **Regex** — `/grad\w+/`: FTS5 has no regex syntax, so per this project's D3 deviation (no
 *    trigram sidecar) the pattern runs as a Kotlin scan over the *already-narrowed* candidate
 *    set, not the whole corpus. Correct, and bounded — but honestly slower per-candidate than an
 *    indexed lookup, and only as complete as the candidate set that reached it.
 *
 * ### The candidate cap is applied before facets
 * [CANDIDATE_LIMIT] bounds what FTS5 returns, and facets filter *after* that. A very broad term
 * combined with a very narrow facet (`the is:starred`) can therefore under-return: the 400
 * highest-bm25 rows for `the` may contain no starred conversation even though one exists further
 * down. This is the spec's own §5.1 design (a bounded candidate set is the point), noted here so
 * it's a known bound rather than a mystery.
 */
object SearchQuery {

    /** Candidate-set size before re-scoring (spec §5.1's `LIMIT 400`). */
    const val CANDIDATE_LIMIT = 400L

    /** Results returned to the caller after re-scoring. */
    const val DEFAULT_RESULT_LIMIT = 50

    /** One candidate row: display text plus everything the facet predicate needs. */
    private class Candidate(val doc: SearchDoc, val subject: FacetSubject)

    fun search(
        database: SearchDatabase,
        query: String,
        nowMillis: Long,
        resultLimit: Int = DEFAULT_RESULT_LIMIT,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<SearchHit> = search(database, QueryParser.parse(query, nowMillis, zone), nowMillis, resultLimit, zone)

    /**
     * The parsed-query entry point — preferred when the caller already parsed (the search
     * overlay parses on every keystroke to paint chips/diagnostics, so re-parsing here would be
     * pure waste, and a second parse could in principle disagree with the one the UI is showing).
     */
    fun search(
        database: SearchDatabase,
        parsed: ParsedQuery,
        nowMillis: Long,
        resultLimit: Int = DEFAULT_RESULT_LIMIT,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<SearchHit> {
        val candidates = candidates(database, parsed)
        if (candidates.isEmpty()) return emptyList()

        val surviving = candidates
            .filter { FacetEvaluator.matches(it.subject, parsed.root, nowMillis, zone) }
            .filter { matchesRegexNodes(it.doc, parsed.root) }
        if (surviving.isEmpty()) return emptyList()

        // Re-score with the *lexical* text only. A facet like `is:starred` is a filter, not a
        // relevance signal — feeding "is:starred" to LexicalSearch as query text would score
        // documents for containing the literal words "is" and "starred".
        val lexicalText = QueryCompiler.lexicalText(parsed.root)
        if (lexicalText.isBlank()) {
            // Facet-only query: nothing to rank by, so keep the SQL's recency order and report an
            // honest zero score rather than inventing a relevance number.
            return surviving.take(resultLimit).map { c ->
                SearchHit(doc = c.doc, score = 0.0, matchedIn = dev.fonebrew.domain.search.MatchedIn.CONTENT, highlights = emptyList())
            }
        }
        return LexicalSearch.search(surviving.map { it.doc }, lexicalText, nowMillis, explain = true).take(resultLimit)
    }

    private fun candidates(database: SearchDatabase, parsed: ParsedQuery): List<Candidate> {
        // Recompiled from the tree with prefixBareTerms=true rather than reusing
        // parsed.ftsExpression: as-you-type search needs `gradl` to already find `gradle`
        // (see QueryCompiler.compileFts's KDoc), while ParsedQuery.ftsExpression stays a
        // faithful rendering of exactly what the user typed.
        val fts = QueryCompiler.compileFts(parsed.root, prefixBareTerms = true)
        return if (fts == null) {
            // No lexical constraint. Only worth scanning recent conversations if there IS some
            // other constraint to apply — an entirely empty query means "show nothing", not
            // "show everything".
            if (parsed.root == null) return emptyList()
            database.searchQueries.selectRecentWithFacets(CANDIDATE_LIMIT).executeAsList().map { row ->
                Candidate(
                    doc = SearchDoc(row.conv_id, row.title_raw, row.snippet_raw, row.body_raw, row.updated_at, SearchKind.TEXT),
                    subject = toSubject(
                        row.starred, row.archived, row.project_id, row.model_ids,
                        row.turn_count, row.branch_count, row.has_image, row.has_code,
                        row.cost_minor, row.updated_at,
                    ),
                )
            }
        } else {
            database.searchQueries.searchCandidatesWithFacets(fts, CANDIDATE_LIMIT).executeAsList().map { row ->
                Candidate(
                    doc = SearchDoc(row.conv_id, row.title_raw, row.snippet_raw, row.body_raw, row.updated_at, SearchKind.TEXT),
                    subject = toSubject(
                        row.starred, row.archived, row.project_id, row.model_ids,
                        row.turn_count, row.branch_count, row.has_image, row.has_code,
                        row.cost_minor, row.updated_at,
                    ),
                )
            }
        }
    }

    /** LEFT JOIN nulls (a projection with no facets row) become "no facet data" defaults, so the
     *  conversation stays findable by text and simply matches no facet. */
    private fun toSubject(
        starred: Long?, archived: Long?, projectId: String?, modelIds: String?,
        turnCount: Long?, branchCount: Long?, hasImage: Long?, hasCode: Long?,
        costMinor: Long?, updatedAt: Long,
    ) = FacetSubject(
        starred = starred == 1L,
        archived = archived == 1L,
        projectId = projectId,
        modelIds = modelIds.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
        turnCount = turnCount ?: 0L,
        branchCount = branchCount ?: 0L,
        hasImage = hasImage == 1L,
        hasCode = hasCode == 1L,
        costMinor = costMinor ?: 0L,
        updatedAtMillis = updatedAt,
    )

    /**
     * Applies every [QueryNode.Regex] in the tree as a Kotlin scan over the candidate's text
     * (D3 deviation — see class KDoc). A negated regex is handled by the surrounding [QueryNode.Not]
     * here in the same tree walk, so `-/^draft/` excludes as expected. An invalid pattern matches
     * nothing rather than throwing (the parser has already flagged it).
     */
    private fun matchesRegexNodes(doc: SearchDoc, node: QueryNode?): Boolean = when (node) {
        null -> true
        is QueryNode.And -> node.children.all { matchesRegexNodes(doc, it) }
        is QueryNode.Or -> node.children.any { matchesRegexNodes(doc, it) }
        // Same guard as FacetEvaluator's: only negate a subtree this stage has an opinion about,
        // or `-is:archived` would negate the neutral `true` and reject every row.
        is QueryNode.Not -> if (containsRegex(node.child)) !matchesRegexNodes(doc, node.child) else true
        is QueryNode.Regex -> {
            val options = if (node.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
            val regex = try {
                Regex(node.pattern, options)
            } catch (_: Exception) {
                null
            }
            regex != null && (regex.containsMatchIn(doc.title) || regex.containsMatchIn(doc.body) || regex.containsMatchIn(doc.snippet))
        }
        // Facets and lexical leaves are other stages' business — neutral here, same rationale as
        // FacetEvaluator's non-facet leaves.
        is QueryNode.Facet, is QueryNode.Term, is QueryNode.Phrase, is QueryNode.Semantic -> true
    }

    private fun containsRegex(node: QueryNode): Boolean = when (node) {
        is QueryNode.Regex -> true
        is QueryNode.And -> node.children.any(::containsRegex)
        is QueryNode.Or -> node.children.any(::containsRegex)
        is QueryNode.Not -> containsRegex(node.child)
        else -> false
    }

    /**
     * Compiles a plain user query into an FTS5 `MATCH` expression: each whitespace-delimited,
     * NFKC-normalized term (via [LexicalSearch.tokenizeQuery] — same normalization the index
     * itself was built with, see [Segmenter][dev.fonebrew.domain.search.Segmenter]) becomes a
     * quoted prefix match, e.g. `gradle cache` -> `"gradle"* "cache"*`. Quoting every term as a
     * literal string is what keeps this safe against FTS5 query-syntax injection (a term like
     * `OR`, `-foo`, or `(bar` would otherwise be parsed as an FTS5 operator, not a literal).
     *
     * Returns `null` for a blank query, mirroring [LexicalSearch.tokenizeQuery]'s own blank-query
     * contract.
     */
    fun compileFtsQuery(query: String): String? {
        val terms = LexicalSearch.tokenizeQuery(query)
        if (terms.isEmpty()) return null
        return terms.joinToString(" ") { term -> "\"" + term.replace("\"", "\"\"") + "\"*" }
    }
}
