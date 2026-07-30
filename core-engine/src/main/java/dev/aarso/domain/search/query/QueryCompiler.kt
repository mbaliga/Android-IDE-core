package dev.aarso.domain.search.query

/**
 * [QueryNode] → text, in two different shapes: [compileFts] for FTS5's `MATCH` syntax, and
 * [renderCanonical] for round-trippable query-box text (chips, saved searches).
 */
object QueryCompiler {

    /**
     * Compiles the lexical parts of [node] into an FTS5 `MATCH` expression. `null` when there's
     * no lexical text to search (a pure facet/semantic-only query — an honest "no FTS
     * constraint", not an error).
     *
     * [QueryNode.Facet] never contributes text here (facets are SQL predicates, not FTS text —
     * WP8's scope is producing them, not yet executing them; see `ParsedQuery.facets`).
     * [QueryNode.Regex] never contributes either: FTS5 has no regex syntax, and per this
     * project's D3 deviation (no trigram sidecar yet) regex runs as a Kotlin-side scan over the
     * facet-prefiltered candidate set instead — a future `SearchQuery` concern, not this
     * compiler's. [QueryNode.Semantic] degrades to a plain prefix term for the FTS expression
     * (semantic search itself needs the AI layer, M5, not available here) — the parser already
     * records this via [QueryNode.Semantic] surfacing separately as `ParsedQuery.semanticText`,
     * so a caller can still show "semantic search unavailable" without losing the term as a
     * plain-lexical fallback (hard rule 1: the raw query still runs).
     */
    fun compileFts(node: QueryNode?): String? = node?.let(::renderFts)?.takeIf { it.isNotBlank() }

    private fun renderFts(node: QueryNode): String? = when (node) {
        is QueryNode.Term -> quotedPrefix(node.text, node.prefix)
        is QueryNode.Phrase -> quoted(node.text)
        is QueryNode.Semantic -> quotedPrefix(node.text, prefix = true)
        is QueryNode.Regex -> null
        is QueryNode.Facet -> null
        is QueryNode.Or -> {
            val parts = node.children.mapNotNull(::renderFts)
            if (parts.isEmpty()) null else "(" + parts.joinToString(" OR ") + ")"
        }
        is QueryNode.And -> {
            val positive = node.children.filterNot { it is QueryNode.Not }.mapNotNull(::renderFts)
            val negative = node.children.filterIsInstance<QueryNode.Not>().mapNotNull { renderFts(it.child) }
            when {
                positive.isEmpty() -> null // nothing lexical to anchor a NOT against — see class KDoc
                negative.isEmpty() -> positive.joinToString(" ")
                else -> positive.joinToString(" ") + " NOT (" + negative.joinToString(" OR ") + ")"
            }
        }
        // A lone/top-level NOT has no positive left-hand side — FTS5's NOT is binary (`a NOT b`),
        // not expressible as a standalone negation. Combined inside an And (above) it works;
        // bare, it's dropped from the FTS expression rather than emitting invalid syntax.
        is QueryNode.Not -> null
    }

    private fun quoted(text: String) = "\"" + text.replace("\"", "\"\"") + "\""
    private fun quotedPrefix(text: String, prefix: Boolean) = quoted(text) + if (prefix) "*" else ""
}

/** Round-trippable canonical text for one node — what a chip's `text` is built from, and what
 *  gets reparsed. Not FTS syntax (see [QueryCompiler.compileFts] for that). */
internal fun renderCanonical(node: QueryNode): String = when (node) {
    is QueryNode.Term -> node.text + if (node.prefix) "*" else ""
    is QueryNode.Phrase -> "\"" + node.text + "\""
    is QueryNode.Regex -> "/" + node.pattern + "/" + if (node.ignoreCase) "i" else ""
    is QueryNode.Semantic -> "?" + if (node.text.contains(' ')) "\"${node.text}\"" else node.text
    is QueryNode.Facet -> {
        val opText = when (node.op) {
            Op.EQ, Op.RANGE -> ""
            Op.GT -> ">"
            Op.GTE -> ">="
            Op.LT -> "<"
            Op.LTE -> "<="
        }
        val valueText = if (node.value.contains(' ')) "\"${node.value}\"" else node.value
        "${node.field.key}:$opText$valueText"
    }
    is QueryNode.Not -> "-" + renderCanonical(node.child)
    is QueryNode.And -> node.children.joinToString(" ") { renderCanonical(it) }
    is QueryNode.Or -> "(" + node.children.joinToString(" OR ") { renderCanonical(it) } + ")"
}

/** Builds the UI's editable-chip row from a parsed tree: each top-level AND child is one chip
 *  (a nested OR/NOT group collapses into a single removable [ChipKind.GROUP] chip, matching how
 *  a user thinks about "one thing I typed", not the tree's internal shape). */
object ChipBuilder {
    fun build(root: QueryNode?): List<QueryChip> {
        if (root == null) return emptyList()
        val parts = if (root is QueryNode.And) root.children else listOf(root)
        return parts.map(::toChip)
    }

    private fun toChip(node: QueryNode): QueryChip = when (node) {
        is QueryNode.Term -> QueryChip(renderCanonical(node), ChipKind.TERM)
        is QueryNode.Phrase -> QueryChip(renderCanonical(node), ChipKind.PHRASE)
        is QueryNode.Regex -> QueryChip(renderCanonical(node), ChipKind.REGEX)
        is QueryNode.Semantic -> QueryChip(renderCanonical(node), ChipKind.SEMANTIC)
        is QueryNode.Facet -> QueryChip(renderCanonical(node), ChipKind.FACET, field = node.field)
        is QueryNode.Not -> toChip(node.child).let { it.copy(text = "-" + it.text, negated = true) }
        is QueryNode.And, is QueryNode.Or -> QueryChip(renderCanonical(node), ChipKind.GROUP)
    }
}
