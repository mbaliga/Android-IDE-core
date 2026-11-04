package dev.fonebrew.domain.search.query

/**
 * The facet field vocabulary (Doc `FONEBREW_SEARCH_SPEC.md` §6.1). The parser recognizes every
 * field here — grammar completeness, so chips round-trip and nothing degrades to "unknown field"
 * that shouldn't — but [isBacked] decides whether a given (field, value) pair actually compiles
 * to a real filter or degrades to [dev.fonebrew.domain.search.query.Diagnostic.UnindexedFacet]
 * ("this filter isn't indexed yet", an honest zero — never a silent no-op).
 *
 * Per the build plan's facet-data reality check: `tool:`/`file:`/`build:`/`room:`/`tag:`/`lang:`
 * assume domain concepts (tool-call capture, file/build linkage, tags, language detection) that
 * don't exist anywhere in this codebase, so they're always not-yet-indexed. `is:`/`has:` are
 * split per-value: `starred`/`archived`/`orphan` and `image`/`code` are real (backed by
 * `SessionStore`/`ConversationsStore`/`SearchProjector`); `unread`/`failed` and
 * `attachment`/`artifact`/`error` are not.
 *
 * `loop:`/`task:` are the two exceptions to that reality check, not more of it: they select
 * which non-conversation corpus a query runs against, backed by real
 * [dev.fonebrew.data.LoopStore]/[dev.fonebrew.data.TaskStore] data
 * ([dev.fonebrew.data.search.LoopSearchProjector]/[dev.fonebrew.data.search.TaskSearchProjector]).
 * A bare `loop:`/`task:` matches any record of that kind; a value (`loop:unused`, `task:done`)
 * additionally requires that record's `LoopState`/`TaskState` name to match, case-insensitively —
 * see [dev.fonebrew.domain.search.query.FacetEvaluator.matchesFacet]. Presence of one of these
 * facets is also what switches [dev.fonebrew.data.search.SearchQuery]'s retrieval away from
 * conversations, so `loop:` and bare text together search loop text, not conversation text.
 *
 * Two more honesty seams live here rather than in the UI, so they're testable: [unbackedReason]
 * says why a given pair can't return anything *today* — which is a strictly wider question than
 * [isBacked], and catches `is:archived` — and [facetCaveat] says what a working-but-coarser-
 * than-its-name facet is actually measuring.
 */
enum class Field(val key: String) {
    IN("in"),
    IS("is"),
    HAS("has"),
    PROJECT("project"),
    MODEL("model"),
    TAG("tag"),
    ROOM("room"),
    FILE("file"),
    TOOL("tool"),
    BUILD("build"),
    BRANCH("branch"),
    TURNS("turns"),
    COST("cost"),
    BEFORE("before"),
    AFTER("after"),
    DURING("during"),
    LANG("lang"),
    LOOP("loop"),
    TASK("task"),
    ;

    companion object {
        private val byKey = entries.associateBy { it.key }

        fun fromKey(key: String): Field? = byKey[key.lowercase()]

        /** All recognized field keys, for "did you mean" suggestions on an unknown field. */
        val allKeys: List<String> = entries.map { it.key }
    }
}

/** `is:` values with a real column and a real predicate behind them. */
val BACKED_IS_VALUES = setOf("starred", "archived", "orphan")

/** `has:` values that match real data — see [facetCaveat] for how coarse `code` really is. */
val BACKED_HAS_VALUES = setOf("image", "code")

/**
 * Whether this exact (field, value) pair compiles to a real filter today. See [Field]'s KDoc.
 *
 * This is the **evaluation** gate ([FacetEvaluator] returns an honest `false` for anything not
 * backed), which is why `archived` is in [BACKED_IS_VALUES] even though nothing writes that
 * column yet: the predicate itself is correct, and dropping it would silently turn
 * `-is:archived` from "exclude archived conversations" into "no constraint at all" — a wrong
 * answer the day archiving gets wired up. "Can this ever return anything *today*" is a different
 * question, and it is [unbackedReason]'s.
 */
fun isBacked(field: Field, value: String): Boolean = when (field) {
    Field.IS -> value.lowercase() in BACKED_IS_VALUES
    Field.HAS -> value.lowercase() in BACKED_HAS_VALUES
    Field.IN, Field.PROJECT, Field.MODEL,
    Field.BEFORE, Field.AFTER, Field.DURING,
    Field.TURNS, Field.BRANCH, Field.COST,
    Field.LOOP, Field.TASK,
    -> true
    Field.TAG, Field.ROOM, Field.FILE, Field.TOOL, Field.BUILD, Field.LANG -> false
}

/**
 * Why this exact (field, value) pair **cannot return anything today** — the sentence the UI puts
 * after the facet in a [Diagnostic.UnindexedFacet] line, and the reason [QueryParser] emits that
 * diagnostic at all. `null` when the facet can genuinely match.
 *
 * Two different kinds of "no" collapse to one diagnostic here, deliberately, because the user's
 * situation is the same (this filter will return an empty set) while the *reason* is not:
 *  - **Not indexed** — `tool:`/`tag:`/`file:`…: no column, no domain concept behind it.
 *  - **Indexed but never written** — `is:archived`: `conv_facets.archived` exists,
 *    [SearchProjector][dev.fonebrew.data.search.SearchProjector] fills it, and [FacetEvaluator]
 *    reads it correctly, but the only thing that could ever set it — `SessionStore.toggleArchived`
 *    — has **zero call sites** in the app, so the column is `0` for every conversation. This was
 *    the one dead facet that produced no diagnostic at all: [isBacked] says yes (rightly — the
 *    predicate works), so the parser stayed quiet and the user got a silent, unexplained zero.
 *
 * Saying "isn't indexed yet" for the second case would send someone hunting an indexing bug that
 * doesn't exist, which is why the reasons are separate strings rather than one generic line.
 */
fun unbackedReason(field: Field, value: String): String? = when {
    field == Field.IS && value.lowercase() == "archived" ->
        "matches nothing — no surface in this build archives a conversation yet"
    isBacked(field, value) -> null
    field == Field.IS || field == Field.HAS -> "isn't a value this build records"
    else -> "isn't indexed yet"
}

/**
 * What a **backed** facet actually measures, when its name promises more than the data delivers.
 * Shown quietly next to the query (not as an error — these filters do work), because a filter
 * that silently means something narrower than its name is exactly the kind of hidden influence
 * this app exists not to have.
 *
 * `null` for every facet whose name and data agree.
 */
fun facetCaveat(field: Field, value: String): String? = when {
    // SearchProjector fills turn_count from Conversations.Summary.nodeCount — every message node
    // in the subtree, user and assistant alike (and every branch's nodes), not exchanges.
    field == Field.TURNS -> "turns: counts every message node — user and assistant, across branches"
    // SearchProjector: hasCode = bodyRaw.contains("```"). Not a parser: a real fenced block and a
    // stray ``` in prose are indistinguishable to it.
    field == Field.HAS && value.lowercase() == "code" ->
        "has:code means the text contains a ``` fence — it isn't a parsed code block"
    else -> null
}
