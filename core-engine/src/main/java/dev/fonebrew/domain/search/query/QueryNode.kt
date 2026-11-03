package dev.fonebrew.domain.search.query

/** The parsed query AST (Doc `FONEBREW_SEARCH_SPEC.md` §6.2's `QueryNode` contract, verbatim
 *  shape). Boolean structure ([And]/[Or]/[Not]) composes freely with every leaf kind. */
sealed interface QueryNode {
    data class And(val children: List<QueryNode>) : QueryNode
    data class Or(val children: List<QueryNode>) : QueryNode
    data class Not(val child: QueryNode) : QueryNode
    data class Term(val text: String, val prefix: Boolean) : QueryNode
    data class Phrase(val text: String) : QueryNode
    data class Regex(val pattern: String, val ignoreCase: Boolean) : QueryNode
    data class Semantic(val text: String) : QueryNode

    /** [value] is the raw, unparsed facet value text (post-operator-stripping for [Op.GT] etc.,
     *  and post-`..`-detection for [Op.RANGE], but not yet split/typed further — WP8's scope is
     *  parsing and round-tripping, not executing predicates against SQL; that lands when the UI
     *  layer needs it, WP10/WP11). */
    data class Facet(val field: Field, val op: Op, val value: String) : QueryNode
}

enum class Op { EQ, GT, GTE, LT, LTE, RANGE }

/** For the UI's editable-chip row — see [dev.fonebrew.domain.search.query.ChipBuilder]. [text] is
 *  the chip's canonical round-trip text (`chips.joinToString(" ") { it.text }` reparses to an
 *  equivalent tree — see `QueryParserTest`'s round-trip suite). */
data class QueryChip(
    val text: String,
    val kind: ChipKind,
    val field: Field? = null,
    val negated: Boolean = false,
)

enum class ChipKind { TERM, PHRASE, REGEX, SEMANTIC, FACET, GROUP }

/** Malformed/degraded input never throws (Doc §6.2's hard rule) — it produces a diagnostic
 *  instead, surfaced inline in the UI, while the rest of the query still runs. */
sealed interface Diagnostic {
    data class UnknownField(val typed: String, val suggestion: String?) : Diagnostic
    data class UnindexedFacet(val field: Field, val value: String) : Diagnostic
    data class UnterminatedQuote(val at: Int) : Diagnostic
    data class UnterminatedRegex(val at: Int) : Diagnostic
    data class InvalidDate(val field: Field, val raw: String) : Diagnostic
    data class SemanticUnavailable(val text: String) : Diagnostic
}

/**
 * The full result of parsing one query string (Doc §6.2). [ftsExpression] is `null` when there
 * is no lexical text to search (a pure facet/semantic query) — an honest "no FTS constraint",
 * not an error. [facets] is every [QueryNode.Facet] found anywhere in the tree (flattened,
 * regardless of AND/OR/NOT nesting) for a caller that just wants "what facets did the user
 * type" without walking the tree itself.
 */
data class ParsedQuery(
    val rawText: String,
    val root: QueryNode?,
    val ftsExpression: String?,
    val facets: List<QueryNode.Facet>,
    val semanticText: String?,
    val diagnostics: List<Diagnostic>,
    val chips: List<QueryChip>,
)

/** The inverse of parsing: chips back to one query string. Reparsing this text reproduces an
 *  equivalent tree to the one the chips were built from (see [ChipBuilder]/`QueryParserTest`). */
fun List<QueryChip>.toQueryText(): String = joinToString(" ") { it.text }
