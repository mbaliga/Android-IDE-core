package dev.fonebrew.domain.search.query

import dev.fonebrew.domain.search.Stemmer

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
     *
     * ### Stemming (symmetric with the indexer)
     * A [QueryNode.Term] with [QueryNode.Term.prefix] `false`, and every word inside a
     * [QueryNode.Phrase], are run through [Stemmer.maybeStem] before being quoted — the same
     * transform [dev.fonebrew.data.search.SearchProjector]/[dev.fonebrew.data.search.LoopSearchProjector]/
     * [dev.fonebrew.data.search.TaskSearchProjector] apply to indexed text, so "running" in a
     * query and "runs" in a document land on the identical stemmed FTS5 token. This applies
     * unconditionally, not just when [prefixBareTerms] is set: the index itself now stores
     * stemmed text, so an *unstemmed* exact-token `MATCH` (no `prefixBareTerms`, no explicit `*`)
     * against it would simply never hit — stemming the query is what keeps a non-prefix `MATCH`
     * meaningful at all, not only an as-you-type convenience.
     *
     * An **explicit** user-typed prefix ([QueryNode.Term.prefix] `true`, i.e. the user typed
     * `word*`) is the one exception: it is never stemmed. Typing a literal prefix glob is a
     * request for exactly that spelling — stemming it would silently change what the user asked
     * for (e.g. `caresses*` must search for that literal prefix, not the stem `caress*`).
     * [QueryNode.Semantic] is stemmed the same way bare terms are: its `prefix = true` in the
     * compiled expression comes from the *compiler's* semantic-fallback strategy, not from the
     * user typing `*`, so the "explicit prefix is literal" exception does not apply to it.
     */
    /**
     * @param prefixBareTerms when true, a term the user did *not* explicitly suffix with `*` is
     *   still compiled as an FTS5 prefix match. This is what an as-you-type search box needs
     *   (spec S3): mid-word, `gradl` must already find `gradle`, or results only appear once the
     *   user finishes typing a whole token. Off by default so [ParsedQuery.ftsExpression] stays a
     *   faithful rendering of exactly what was typed, prefix-wise — the retrieval layer
     *   ([dev.fonebrew.data.search.SearchQuery]) opts in, a caller inspecting the parse does not.
     *   Stemming (see above) is unaffected by this flag either way.
     */
    fun compileFts(node: QueryNode?, prefixBareTerms: Boolean = false): String? =
        node?.let { renderFts(it, prefixBareTerms) }?.takeIf { it.isNotBlank() }

    private fun renderFts(node: QueryNode, prefixBare: Boolean): String? = when (node) {
        is QueryNode.Term -> {
            val text = if (node.prefix) node.text else Stemmer.maybeStem(node.text)
            quotedPrefix(text, node.prefix || prefixBare)
        }
        is QueryNode.Phrase -> quoted(Stemmer.stemJoined(node.text))
        // node.text can be multi-word (?"gradle build cache" parses to one Semantic node whose
        // text has a space in it) — stemJoined, not maybeStem, so every word gets stemmed
        // independently rather than the whole phrase silently skipping isStemmable's ASCII-letter
        // check over a string that contains a space.
        is QueryNode.Semantic -> quotedPrefix(Stemmer.stemJoined(node.text), prefix = true)
        is QueryNode.Regex -> null
        is QueryNode.Facet -> null
        is QueryNode.Or -> {
            val parts = node.children.mapNotNull { renderFts(it, prefixBare) }
            if (parts.isEmpty()) null else "(" + parts.joinToString(" OR ") + ")"
        }
        is QueryNode.And -> {
            val positive = node.children.filterNot { it is QueryNode.Not }.mapNotNull { renderFts(it, prefixBare) }
            val negative = node.children.filterIsInstance<QueryNode.Not>().mapNotNull { renderFts(it.child, prefixBare) }
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

    /**
     * The plain words the user typed, with facet/regex syntax stripped — everything that is
     * genuinely *text to find*. Two callers need exactly this: relevance re-scoring (feeding
     * `is:starred` to a lexical scorer would rank documents for containing the words "is" and
     * "starred"), and find-in-chat continuity (opening a result from a search for
     * `gradle is:starred` should look for `gradle` inside the conversation, not the facet).
     *
     * **Deliberately NOT stemmed, unlike [compileFts].** Both of [lexicalText]'s callers feed
     * straight into [dev.fonebrew.domain.search.LexicalSearch], which matches by plain substring
     * containment over the *raw*, un-stemmed conversation text and is explicitly never modified
     * (see that class's own KDoc and [dev.fonebrew.data.search.SearchQuery]'s "never modified or
     * replaced" — pinned by `SearchQueryTest`'s golden-ordering test, which asserts
     * `SearchQuery.search`'s scores are byte-for-byte what calling `LexicalSearch.search` directly
     * over the same raw documents would produce). Stemming this text would materially change
     * those scores and visibly shorten every on-screen highlight span (a query for "gradle" would
     * only highlight "gradl" — the stem — inside the displayed word), for the sake of surfacing a
     * narrow class of relevance matches. So: [compileFts]'s FTS5 stemming widens *candidate
     * generation* — a document spelled "runs" is now a real candidate for a query typed
     * "running" — but that candidate only survives into [dev.fonebrew.data.search.SearchQuery]'s
     * final ranked results if the raw query text also has some literal-substring relationship to
     * the raw document text (true for the large majority of real inflection pairs — the shorter
     * spelling is usually a literal prefix of the longer one, e.g. `cache`⊂`caches`,
     * `test`⊂`tests` — but not for every pair, e.g. `running`/`runs` share no such raw-substring
     * relationship at all). That is a real, named boundary of this two-stage design — not
     * something this lane silently worked around — see `StemmingSearchIntegrationTest`'s own
     * comment on its "running finds runs" case, which is verified at the FTS5 candidate layer
     * this lane owns rather than through the full re-scored pipeline for exactly this reason.
     *
     * Negated terms are excluded — `-maven` is a thing to *avoid*, never a thing to highlight.
     */
    fun lexicalText(node: QueryNode?): String = buildList { collectLexical(node, this) }.joinToString(" ")

    private fun collectLexical(node: QueryNode?, into: MutableList<String>) {
        when (node) {
            null -> Unit
            is QueryNode.Term -> into.add(node.text)
            is QueryNode.Phrase -> into.add(node.text)
            is QueryNode.Semantic -> into.add(node.text)
            is QueryNode.And -> node.children.forEach { collectLexical(it, into) }
            is QueryNode.Or -> node.children.forEach { collectLexical(it, into) }
            is QueryNode.Not -> Unit
            is QueryNode.Regex, is QueryNode.Facet -> Unit
        }
    }
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
