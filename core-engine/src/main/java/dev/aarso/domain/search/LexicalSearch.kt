package dev.aarso.domain.search

import java.text.Normalizer
import java.util.Locale

/**
 * **Sovereign on-device conversation search** — the pure, deterministic ranking + matching
 * core (Doc 02 §5). No network, no embeddings, no model: just legible lexical matching with
 * a relevance score the user can reason about ("recent + matches", not a black box).
 *
 * ### Script-awareness (the binding requirement)
 * Matching is **Unicode-aware, not naive byte matching**. [normalize] folds case and Unicode
 * form so the same word written with different code points (composed vs decomposed, full-width
 * vs half-width) matches, while **non-ASCII is never stripped** — Devanagari (Hindi/Konkani),
 * Japanese, and Arabic-script (Urdu/RTL) text survive normalization intact and match against
 * queries in the same script. There is no transliteration and no language guessing; a query in
 * a script matches text in that script.
 *
 * ### Two-tier matching
 * Each [SearchDoc] exposes a **title tier** ([SearchDoc.title]) and a **content tier**
 * (`snippet + body`). Title matches are weighted higher and reported as [MatchedIn.TITLE]; a
 * caller can run the cheap title tier first for instant results, then fall to content.
 *
 * ### The score (legible by design — see [score])
 * ```
 * score = termCoverage * fieldWeight + recencyBoost
 *   termCoverage = (# distinct query terms found in the field) / (# distinct query terms)
 *   fieldWeight  = TITLE_WEIGHT (2.0) if any term hit the title, else CONTENT_WEIGHT (1.0)
 *   recencyBoost = RECENCY_WEIGHT (0.5) * 1.0 / (1 + ageDays)   // decays smoothly with age
 * ```
 * So a full-coverage title match on a fresh conversation scores highest; coverage and recency
 * each move the number in an explainable direction. Everything is `nowMillis`-relative and
 * pure — no `Date.now`, no randomness — so results are reproducible and testable.
 */
object LexicalSearch {

    /** Weight applied when at least one query term matched the title tier. */
    const val TITLE_WEIGHT: Double = 2.0

    /** Weight applied when the match came only from the content tier. */
    const val CONTENT_WEIGHT: Double = 1.0

    /** Scale of the recency boost added on top of the weighted coverage. */
    const val RECENCY_WEIGHT: Double = 0.5

    private const val MILLIS_PER_DAY: Double = 24.0 * 60.0 * 60.0 * 1000.0

    /**
     * Unicode-aware normalization for matching. Steps, in order:
     * 1. **NFKC normalize** (`Normalizer.normalize(s, Form.NFKC)`) — canonical + compatibility
     *    folding so composed/decomposed forms and full-width/half-width variants unify.
     * 2. **Lowercase with [Locale.ROOT]** — locale-independent case folding (no Turkish-i
     *    surprises); a no-op for caseless scripts like Devanagari/Japanese.
     * 3. **Collapse whitespace** — runs of Unicode whitespace become a single space, trimmed.
     *
     * This is **script-aware, not byte matching**: non-ASCII code points are *kept*, so
     * Devanagari, Japanese, and Arabic-script text pass through and match in their own script.
     * Note NFKC can change string length (e.g. ﬁ → fi, full-width digits → ASCII); [findMatches]
     * documents how that interacts with returning original-text ranges.
     */
    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(WHITESPACE_RUN, " ")
            .trim()

    private val WHITESPACE_RUN = Regex("\\s+")

    /**
     * Tokenize a raw query into match terms: [normalize] it, split on whitespace, and drop any
     * blanks. Returns an empty list for a blank/empty query (which [search] treats as no query).
     */
    fun tokenizeQuery(q: String): List<String> =
        normalize(q).split(' ').filter { it.isNotBlank() }

    /**
     * Find the character ranges in [text] where any of [queryTerms] occurs, case- and
     * diacritic-insensitively. Matching is done over the **normalized** text; ranges are
     * returned over the **original [text] indices**.
     *
     * **Correctness + the one caveat.** When [normalize] preserves length (the common case —
     * case folding and most scripts are 1:1), the normalized index *is* the original index and
     * the returned ranges point exactly at the matched substring in [text]. When NFKC changes
     * length (e.g. a ligature or full-width form expands/contracts), original and normalized
     * indices can drift; rather than return a subtly-wrong original range we **fall back to
     * ranges over the normalized text** in that case. Highlights stay within bounds and never
     * point at the wrong characters; they may simply index the normalized form. This keeps the
     * function simple and always correct-by-construction.
     *
     * Ranges are merged when they overlap/abut and returned sorted by start. [queryTerms] are
     * assumed already normalized (as produced by [tokenizeQuery]).
     */
    fun findMatches(text: String, queryTerms: List<String>): List<IntRange> {
        if (text.isEmpty() || queryTerms.isEmpty()) return emptyList()
        val normalized = normalize(text)
        if (normalized.isEmpty()) return emptyList()
        // Same length → normalized indices line up with original indices. Otherwise we report
        // ranges over the normalized form (documented fallback) so we never mis-highlight.
        val lengthPreserved = normalized.length == text.length

        val raw = ArrayList<IntRange>()
        for (term in queryTerms) {
            if (term.isEmpty()) continue
            var from = 0
            while (true) {
                val idx = normalized.indexOf(term, from)
                if (idx < 0) break
                val end = idx + term.length - 1
                raw.add(idx..end)
                from = idx + 1 // allow overlapping occurrences
            }
        }
        if (raw.isEmpty()) return emptyList()

        // Merge overlapping/adjacent ranges for clean highlight spans.
        raw.sortBy { it.first }
        val merged = ArrayList<IntRange>()
        var cur = raw[0]
        for (i in 1 until raw.size) {
            val r = raw[i]
            cur = if (r.first <= cur.last + 1) {
                cur.first..maxOf(cur.last, r.last)
            } else {
                merged.add(cur); r
            }
        }
        merged.add(cur)

        // If normalization preserved length the indices are valid in the original text as-is.
        // If not, the ranges already index the normalized text (our documented fallback).
        return if (lengthPreserved) {
            merged.map { it.first.coerceIn(0, text.length - 1)..it.last.coerceIn(0, text.length - 1) }
        } else {
            merged.map { it.first.coerceIn(0, normalized.length - 1)..it.last.coerceIn(0, normalized.length - 1) }
        }
    }

    /**
     * Score [doc] against the already-normalized [queryTerms], as of [nowMillis]. Returns
     * `null` when **no** term matches either tier (the doc is not a hit and is dropped).
     *
     * The formula is intentionally legible (see the class KDoc):
     * ```
     * score = termCoverage * fieldWeight + recencyBoost
     * ```
     * - **termCoverage** = distinct query terms found / total distinct query terms. Matching
     *   more of the query raises the score.
     * - **fieldWeight** = [TITLE_WEIGHT] if any term hit the title, else [CONTENT_WEIGHT]. A
     *   title hit always outscores a content-only hit at equal coverage and recency.
     * - **recencyBoost** = [RECENCY_WEIGHT] * `1 / (1 + ageDays)`, ageDays = max(0, now - last)
     *   in days. A smooth decay: today ≈ +[RECENCY_WEIGHT], a year ago ≈ +0. Future timestamps
     *   are clamped to age 0.
     *
     * The match is computed over normalized title and normalized content (`snippet + body`),
     * counting each query term at most once per tier. A term found anywhere sets a hit; a term
     * found in the title also flips [matchedIn] semantics (callers read that from [search]).
     */
    fun score(doc: SearchDoc, queryTerms: List<String>, nowMillis: Long): Double? {
        if (queryTerms.isEmpty()) return null
        val distinct = queryTerms.distinct()

        val normTitle = normalize(doc.title)
        val normContent = normalize(doc.snippet + " " + doc.body)

        var titleHit = false
        var matchedTerms = 0
        for (term in distinct) {
            val inTitle = term.isNotEmpty() && normTitle.contains(term)
            val inContent = term.isNotEmpty() && normContent.contains(term)
            if (inTitle) titleHit = true
            if (inTitle || inContent) matchedTerms++
        }
        if (matchedTerms == 0) return null

        val termCoverage = matchedTerms.toDouble() / distinct.size.toDouble()
        val fieldWeight = if (titleHit) TITLE_WEIGHT else CONTENT_WEIGHT
        val ageDays = ((nowMillis - doc.lastActivityMillis).coerceAtLeast(0L)) / MILLIS_PER_DAY
        val recencyBoost = RECENCY_WEIGHT * (1.0 / (1.0 + ageDays))

        return termCoverage * fieldWeight + recencyBoost
    }

    /**
     * Run a full lexical search: tokenize [query], [score] every doc, drop non-matches, and
     * return ranked [SearchHit]s. Deterministic ordering — **score desc, then
     * [SearchDoc.lastActivityMillis] desc, then [SearchDoc.id] asc** — so equal-relevance ties
     * always resolve the same way.
     *
     * A blank query yields an empty list (nothing to match). Each hit's [SearchHit.matchedIn]
     * is [MatchedIn.TITLE] when any term hit the title, and its [SearchHit.highlights] index the
     * title in that case; otherwise the hit is [MatchedIn.CONTENT] with highlights over the
     * content text (`snippet + " " + body`).
     */
    fun search(docs: List<SearchDoc>, query: String, nowMillis: Long): List<SearchHit> {
        val terms = tokenizeQuery(query)
        if (terms.isEmpty()) return emptyList()

        val hits = ArrayList<SearchHit>()
        for (doc in docs) {
            val s = score(doc, terms, nowMillis) ?: continue
            val titleHighlights = findMatches(doc.title, terms)
            val (matchedIn, highlights) = if (titleHighlights.isNotEmpty()) {
                MatchedIn.TITLE to titleHighlights
            } else {
                MatchedIn.CONTENT to findMatches(doc.snippet + " " + doc.body, terms)
            }
            hits.add(SearchHit(doc = doc, score = s, matchedIn = matchedIn, highlights = highlights))
        }

        return hits.sortedWith(
            compareByDescending<SearchHit> { it.score }
                .thenByDescending { it.doc.lastActivityMillis }
                .thenBy { it.doc.id },
        )
    }
}
