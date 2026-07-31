package dev.aarso.domain.search.query

/**
 * "Search within results" (Doc `FONEBREW_SEARCH_SPEC.md` §7): scopes stack rather than replace
 * — each level's candidate set is the previous level's result set. The UI shows this as a
 * breadcrumb; `Esc` pops one level. Capped at [MAX_DEPTH] (the spec's own number) and each level
 * is independently nameable → "save as smart collection" ([nameableSnapshot]).
 *
 * Pure, JVM-tested, no DB dependency — persisting a [SavedSearchDraft] into the `saved_search`
 * table is whichever surface first needs it (WP10/WP11), kept decoupled here on purpose.
 */
data class ScopeStack(val levels: List<ParsedQuery> = emptyList()) {

    val depth: Int get() = levels.size
    val isEmpty: Boolean get() = levels.isEmpty()
    val current: ParsedQuery? get() = levels.lastOrNull()
    val isFull: Boolean get() = levels.size >= MAX_DEPTH

    /** Pushes a new scope level. A no-op past [MAX_DEPTH] — never throws, never silently drops
     *  an earlier level either; a caller offering the "scope to this" gesture is expected to
     *  check [isFull] first (and the UI hides/disables the gesture at that point, per §7). */
    fun push(query: ParsedQuery): ScopeStack = if (isFull) this else copy(levels = levels + query)

    /** Pops the most recent level (`Esc`). A no-op on an already-empty stack. */
    fun pop(): ScopeStack = if (levels.isEmpty()) this else copy(levels = levels.dropLast(1))

    /** Pops every level at once — leaving the overlay's base "all conversations" state. */
    fun clear(): ScopeStack = ScopeStack()

    /** One label per level, in push order, for the breadcrumb. Falls back to an ellipsis for a
     *  level whose raw text is blank (a pure-facet query, e.g.) rather than an empty chip. */
    fun breadcrumb(): List<String> = levels.map { it.rawText.ifBlank { "…" } }

    /** Every level, base-first — "this level's candidate set is the previous level's result
     *  set" (§7) means a search executor applies these in order, each over the prior's results,
     *  not independently. */
    fun asOrderedQueries(): List<ParsedQuery> = levels

    /**
     * Names the scope through [index] (inclusive) for "save as smart collection" (§7). A saved
     * search is one standalone query, not a multi-level stack, so every level up to [index] is
     * flattened into a single combined query string — reflecting exactly what "search within
     * results" already means semantically (each level narrows the one before it, which is what
     * AND-ing their raw text together reproduces for a fresh, single-shot query).
     *
     * Returns `null` for an out-of-range [index] or a blank [name] — never throws.
     */
    fun nameableSnapshot(index: Int, name: String): SavedSearchDraft? {
        if (index !in levels.indices || name.isBlank()) return null
        val combined = levels.subList(0, index + 1).joinToString(" ") { it.rawText }.trim()
        return SavedSearchDraft(name = name.trim(), query = combined)
    }

    companion object {
        const val MAX_DEPTH = 4
    }
}

/** What [ScopeStack.nameableSnapshot] produces — ready to become one `saved_search` row. */
data class SavedSearchDraft(val name: String, val query: String)
