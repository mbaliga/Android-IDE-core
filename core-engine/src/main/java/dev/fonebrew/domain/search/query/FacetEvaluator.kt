package dev.fonebrew.domain.search.query

import dev.fonebrew.domain.search.SearchKind
import java.time.ZoneId

/**
 * Everything about one indexed record that a facet can be asked about — the `conv_facets` row
 * plus the `updated_at` the date facets compare against, lifted out of the data layer so the
 * actual decision logic stays pure and JVM-testable.
 *
 * Every field through [updatedAtMillis] is conversation-shaped and defaulted for the two record
 * kinds that aren't conversations at all: [recordKind]/[stateValue] are what a loop or task
 * candidate actually carries (see [dev.fonebrew.data.search.LoopSearchProjector]/
 * [dev.fonebrew.data.search.TaskSearchProjector]) — a loop or task subject leaves every
 * conversation-only field at its default, so `is:starred loop:` correctly matches nothing (a
 * loop is never starred) rather than something undefined.
 */
data class FacetSubject(
    val starred: Boolean = false,
    val archived: Boolean = false,
    val projectId: String? = null,
    val modelIds: List<String> = emptyList(),
    val turnCount: Long = 0L,
    val branchCount: Long = 0L,
    val hasImage: Boolean = false,
    val hasCode: Boolean = false,
    val costMinor: Long = 0L,
    val updatedAtMillis: Long,
    /** Which kind of record this is — [SearchKind.TEXT] (the default) for every existing
     *  conversation candidate, [SearchKind.LOOP]/[SearchKind.TASK] for the two new corpora. This
     *  is what `loop:`/`task:` actually test — see [matchesFacet]. */
    val recordKind: SearchKind = SearchKind.TEXT,
    /** `LoopState`/`TaskState` name (e.g. "UNUSED", "DONE") for a loop/task subject; `null` for
     *  a conversation, which has no such state. What a `loop:<value>`/`task:<value>` facet value
     *  compares against, case-insensitively. */
    val stateValue: String? = null,
)

/**
 * Decides whether one indexed conversation satisfies a parsed query's **facet** constraints
 * (Doc `FONEBREW_SEARCH_SPEC.md` §6.1). This is what closes the gap M2 left open: the parser
 * produced [QueryNode.Facet] nodes and the UI round-tripped them as chips, but nothing evaluated
 * them against real results.
 *
 * ### Why Kotlin and not a dynamic SQL `WHERE`
 * SQLDelight's whole value here is compile-time-checked static SQL; a dynamically-assembled
 * predicate string would throw that away (and this file would then be the *second* place in this
 * module bypassing the analyzer, after the FTS5 trigger workaround in
 * [dev.fonebrew.data.search.SearchDriverFactory]). Evaluating the parsed tree directly in Kotlin
 * also handles arbitrary boolean nesting (`is:starred (has:code OR has:image) -is:archived`)
 * exactly, which a flat SQL clause built by string-joining would not. The candidate set is
 * bounded before this runs (see [dev.fonebrew.data.search.SearchQuery]), so this is a walk over
 * hundreds of rows, not the whole index.
 *
 * ### Non-facet leaves evaluate to `true`
 * Terms/phrases/regex/semantic nodes are *not* this function's job — the FTS5 `MATCH` already
 * applied the lexical constraint upstream, so here they're neutral. For an all-AND query (the
 * overwhelmingly common shape) that composes to exactly the right answer: FTS-match AND facets.
 *
 * **The one documented lossy case** is a facet OR'd with text — `gradle OR is:starred`. True
 * semantics would be "matches gradle, or is starred"; this pipeline computes
 * `FTS(gradle) AND (true OR starred)` = FTS(gradle), quietly dropping the starred-but-not-
 * matching-gradle branch. It never returns a *wrong* row, only too few, and only for that
 * mixed-disjunction shape. Pinned by test rather than left to be discovered.
 */
object FacetEvaluator {

    /**
     * Detects the one shape this pipeline evaluates lossily: an `OR` with a facet on one side and
     * lexical text on the other (`gradle OR is:starred`). See the class KDoc — the result is
     * too-few rows, never wrong ones. Exposed so the UI can say so out loud instead of silently
     * under-returning.
     */
    fun hasLossyDisjunction(node: QueryNode?): Boolean = when (node) {
        null -> false
        is QueryNode.Or -> {
            val anyFacet = node.children.any(::containsFacet)
            val anyLexical = node.children.any(::containsLexical)
            (anyFacet && anyLexical) || node.children.any(::hasLossyDisjunction)
        }
        is QueryNode.And -> node.children.any(::hasLossyDisjunction)
        is QueryNode.Not -> hasLossyDisjunction(node.child)
        else -> false
    }

    private fun containsFacet(node: QueryNode): Boolean = when (node) {
        is QueryNode.Facet -> true
        is QueryNode.And -> node.children.any(::containsFacet)
        is QueryNode.Or -> node.children.any(::containsFacet)
        is QueryNode.Not -> containsFacet(node.child)
        else -> false
    }

    private fun containsLexical(node: QueryNode): Boolean = when (node) {
        is QueryNode.Term, is QueryNode.Phrase, is QueryNode.Semantic -> true
        is QueryNode.And -> node.children.any(::containsLexical)
        is QueryNode.Or -> node.children.any(::containsLexical)
        is QueryNode.Not -> containsLexical(node.child)
        else -> false
    }

    fun matches(subject: FacetSubject, node: QueryNode?, nowMillis: Long, zone: ZoneId): Boolean =
        when (node) {
            null -> true
            is QueryNode.And -> node.children.all { matches(subject, it, nowMillis, zone) }
            is QueryNode.Or -> node.children.any { matches(subject, it, nowMillis, zone) }
            // Negation only applies to a subtree this evaluator actually has an opinion about.
            // Without the guard, `-maven` (a negated *term*, already handled by FTS5's own NOT)
            // would negate the neutral `true` into `false` and silently reject every row.
            is QueryNode.Not ->
                if (containsFacet(node.child)) !matches(subject, node.child, nowMillis, zone) else true
            is QueryNode.Facet -> matchesFacet(subject, node, nowMillis, zone)
            // Lexical leaves: already applied by FTS5 upstream — see class KDoc.
            is QueryNode.Term, is QueryNode.Phrase, is QueryNode.Regex, is QueryNode.Semantic -> true
        }

    private fun matchesFacet(subject: FacetSubject, facet: QueryNode.Facet, nowMillis: Long, zone: ZoneId): Boolean {
        // An unbacked facet is an honest zero, never a silent pass — the parser has already
        // emitted Diagnostic.UnindexedFacet so the UI can say why nothing matched.
        if (!isBacked(facet.field, facet.value)) return false
        val value = facet.value.trim()
        return when (facet.field) {
            Field.IS -> when (value.lowercase()) {
                "starred" -> subject.starred
                "archived" -> subject.archived
                "orphan" -> subject.projectId.isNullOrBlank()
                else -> false
            }
            Field.HAS -> when (value.lowercase()) {
                "image" -> subject.hasImage
                "code" -> subject.hasCode
                else -> false
            }
            Field.IN, Field.PROJECT -> subject.projectId?.equals(value, ignoreCase = true) == true
            // Model ids are namespaced ("cloud:anthropic/claude-…", "local:…"), so a user typing
            // `model:claude` means "any model whose id mentions claude" — substring, not equality.
            Field.MODEL -> subject.modelIds.any { it.contains(value, ignoreCase = true) }
            Field.BEFORE -> RelativeDate.resolvePoint(value, nowMillis, zone)
                ?.let { subject.updatedAtMillis < it } ?: false
            Field.AFTER -> RelativeDate.resolvePoint(value, nowMillis, zone)
                ?.let { subject.updatedAtMillis >= it } ?: false
            Field.DURING -> RelativeDate.resolveRange(value, nowMillis, zone)
                ?.let { subject.updatedAtMillis in it } ?: false
            Field.TURNS -> compareNumeric(subject.turnCount, facet.op, value)
            Field.BRANCH -> compareNumeric(subject.branchCount, facet.op, value)
            Field.COST -> compareNumeric(subject.costMinor, facet.op, value)
            // A bare `loop:`/`task:` selects the kind alone; a value additionally requires the
            // LoopState/TaskState name to match. Retrieval (SearchQuery.candidates) only ever
            // hands this evaluator loop-kind subjects for a loop:-scoped query (and likewise for
            // task:), so the recordKind check here is a second, cheap confirmation, not the
            // primary filter — but it is what makes `loop:` correctly reject every conversation
            // subject on the (unusual) path where a facet is OR'd across kinds.
            Field.LOOP -> subject.recordKind == SearchKind.LOOP &&
                (value.isBlank() || subject.stateValue.equals(value, ignoreCase = true))
            Field.TASK -> subject.recordKind == SearchKind.TASK &&
                (value.isBlank() || subject.stateValue.equals(value, ignoreCase = true))
            Field.TAG, Field.ROOM, Field.FILE, Field.TOOL, Field.BUILD, Field.LANG -> false
        }
    }

    /** `turns:>5`, `cost:10..50`, `branch:2`. An unparseable number matches nothing (honest zero)
     *  rather than throwing — same hard rule the parser itself follows. */
    private fun compareNumeric(actual: Long, op: Op, raw: String): Boolean = when (op) {
        Op.RANGE -> {
            val bounds = raw.split("..", limit = 2)
            val low = bounds.getOrNull(0)?.trim()?.toLongOrNull()
            val high = bounds.getOrNull(1)?.trim()?.toLongOrNull()
            if (low == null || high == null) false else actual in low..high
        }
        else -> raw.toLongOrNull()?.let { n ->
            when (op) {
                Op.EQ -> actual == n
                Op.GT -> actual > n
                Op.GTE -> actual >= n
                Op.LT -> actual < n
                Op.LTE -> actual <= n
                Op.RANGE -> false // handled above
            }
        } ?: false
    }
}
