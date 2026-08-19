package dev.fonebrew.domain.search

/**
 * The small, transport-free input/output types for **sovereign on-device conversation
 * search** (Doc 02 §5). These are deliberately tiny value types: the `domain/search/`
 * seam is pure Kotlin, JVM-tested, and knows nothing about Room, Android, or the network.
 *
 * Sovereignty by construction: search is **100% on-device, lexical, no embeddings, no
 * network**. A [SearchDoc] is the *projection of a conversation* the caller chooses to
 * index — the data layer flattens a message tree into a title + a short snippet + the
 * body text it is willing to match against, and search never reaches back into storage.
 *
 * The matching/ranking lives in [LexicalSearch]; this file is only the shapes.
 */

/** What kind of conversation a [SearchDoc] projects — mirrors the Conversations filter tabs. */
enum class SearchKind {
    /** A text-only conversation. */
    TEXT,

    /** An image-generation conversation. */
    IMAGE,

    /** A conversation that mixes text turns and generated images. */
    MIXED,
}

/**
 * One indexable conversation projection. The three text fields form a **two-tier** match
 * surface (Doc 02 §5): [title] + [snippet] are the cheap, high-signal metadata tier that
 * a UI can match first for instant results, and [body] is the fuller content tier matched
 * when a deeper pass is wanted. [LexicalSearch] treats [title] as the title field and
 * `snippet + body` as the content field.
 *
 * @property id stable conversation id; also the final, deterministic tie-breaker in ranking.
 * @property title the conversation's display title (the title-tier field).
 * @property snippet a short preview/metadata line — matched as content, shown in results.
 * @property body the fuller searchable text of the conversation (the content-tier field).
 * @property lastActivityMillis epoch-millis of the last activity; drives the recency boost
 *   and the secondary sort. Passed in by the caller — search itself never reads the clock.
 * @property kind which Conversations bucket this projection belongs to.
 */
data class SearchDoc(
    val id: String,
    val title: String,
    val snippet: String,
    val body: String,
    val lastActivityMillis: Long,
    val kind: SearchKind,
)

/** Which field a hit matched in — title matches are weighted higher and surface first. */
enum class MatchedIn {
    /** At least one query term was found in the [SearchDoc.title]. */
    TITLE,

    /** No title match; the hit came from the content tier (`snippet + body`). */
    CONTENT,
}

/**
 * One ranked search result. [highlights] are character ranges **into the field named by
 * [matchedIn]** — title ranges when [matchedIn] is [MatchedIn.TITLE], otherwise ranges into
 * the content text the UI is expected to display. The ranges are over the *original* field
 * text (see [LexicalSearch.findMatches] for the one normalization-length caveat) so a UI
 * can bold the matched substrings directly.
 *
 * @property score the legible relevance score from [LexicalSearch.score] — higher is better.
 * @property matchedIn the field the highlights index into.
 * @property highlights matched character ranges, in ascending start order, possibly empty.
 * @property explanation the term-by-term arithmetic behind [score] — only populated when the
 *   caller opts in via `explain = true` (see [LexicalSearch.search]); `null` otherwise so every
 *   existing caller/test is unaffected.
 */
data class SearchHit(
    val doc: SearchDoc,
    val score: Double,
    val matchedIn: MatchedIn,
    val highlights: List<IntRange>,
    val explanation: MatchExplanation? = null,
)

/** Which tier a [TermContribution] was found in — mirrors [MatchedIn] but per-term. */
enum class ExplainField {
    TITLE,
    CONTENT,
}

/**
 * One query term's share of a [MatchExplanation]. [weight] is the doc-level field weight that
 * applied to this term ([MatchExplanation.titleWeight] or [MatchExplanation.contentWeight] —
 * see [LexicalSearch.score]'s KDoc for why the weight is doc-level, not per-occurrence), and
 * [subtotal] is this term's even share of `termCoverage * weight`. [rawCount] is how many times
 * the (normalized) term occurs in the field it matched, shown for legibility only — it does not
 * factor into [subtotal], because the underlying formula scores term *coverage*, not term
 * *frequency*.
 */
data class TermContribution(
    val term: String,
    val field: ExplainField,
    val rawCount: Int,
    val weight: Double,
    val subtotal: Double,
)

/**
 * The arithmetic behind one [SearchHit.score], for a "why this matched" panel. Additive-only
 * (Doc `FONEBREW_SEARCH_SPEC.md` §3.3) — it never changes what [LexicalSearch.score] returns,
 * it just shows the work. [termContributions] plus [recencyFactor] sum to [lexicalScore]
 * (== the owning [SearchHit.score]) within `1e-9`.
 *
 * @property titleWeight echoed [LexicalSearch.TITLE_WEIGHT] so the UI never hardcodes it.
 * @property contentWeight echoed [LexicalSearch.CONTENT_WEIGHT].
 * @property recencyFactor the recency boost actually added on top of the term contributions.
 * @property lexicalScore equal to the owning [SearchHit.score].
 */
data class MatchExplanation(
    val termContributions: List<TermContribution>,
    val titleWeight: Double,
    val contentWeight: Double,
    val recencyFactor: Double,
    val lexicalScore: Double,
)
