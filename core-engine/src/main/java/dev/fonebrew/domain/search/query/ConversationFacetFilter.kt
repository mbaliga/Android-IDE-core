package dev.fonebrew.domain.search.query

import dev.fonebrew.domain.tree.Conversations

/**
 * Evaluates a small, deterministic subset of [QueryNode.Facet] clauses against a
 * [Conversations.Summary] (the tree-layer projection `ChatsRoom` already renders from,
 * synchronously — no index backfill needed). This is **not** the SQLite/FTS5 facet path
 * ([dev.fonebrew.data.search.SearchQuery], M1) — that path needs the index populated, which nothing
 * triggers automatically yet (WP6's `SearchIndexer` has no app-start hook). This evaluator is
 * how Doc `FONEBREW_SEARCH_SPEC.md` §6.4 ("the existing tabs become presets") is actually
 * deliverable today: real facet text, evaluated in-memory over data already in scope.
 *
 * Scope is deliberately narrow — only the facets [ChatsPreset]'s five tab-equivalent queries
 * ever use: `is:starred`, `is:orphan`, `has:image`, and their `AND`/`OR`/`NOT` composition.
 * [Conversations.Summary] itself has no `starred`/`projectId` fields (those live in
 * `SessionStore`'s separate flows, joined by the caller — see `ChatsRoom.kt`), so they're
 * parameters here, not read off the summary.
 */
object ConversationFacetFilter {

    /** `node == null` (a blank query, e.g. the "All" preset) always matches — no constraint. */
    fun matches(summary: Conversations.Summary, starred: Boolean, projectId: String?, node: QueryNode?): Boolean {
        if (node == null) return true
        return when (node) {
            is QueryNode.And -> node.children.all { matches(summary, starred, projectId, it) }
            is QueryNode.Or -> node.children.any { matches(summary, starred, projectId, it) }
            is QueryNode.Not -> !matches(summary, starred, projectId, node.child)
            is QueryNode.Facet -> matchesFacet(summary, starred, projectId, node)
            // Term/Phrase/Regex/Semantic have no in-memory equivalent here — matching free text
            // is the FTS index's job, not this evaluator's. None of ChatsPreset's queries
            // contain one; treated as "no constraint" so a future preset with a stray term
            // degrades to over-matching rather than silently hiding every conversation.
            else -> true
        }
    }

    private fun matchesFacet(summary: Conversations.Summary, starred: Boolean, projectId: String?, facet: QueryNode.Facet): Boolean =
        when (facet.field) {
            Field.IS -> when (facet.value.lowercase()) {
                "starred" -> starred
                "orphan" -> projectId == null
                else -> false // archived/unread/etc: not backed on this tree-layer summary
            }
            Field.HAS -> when (facet.value.lowercase()) {
                "image" -> summary.hasImage
                else -> false
            }
            else -> false
        }
}

/**
 * The Chats room's five tabs, re-expressed as saved facet-query presets (Doc §6.4) — same
 * meaning, now defined in the query language instead of a bespoke per-tab enum. [ALL], [TEXT],
 * and [STARRED] are genuinely equivalent boolean filters over the same data the tabs already
 * rendered from, and `ChatsRoom.kt` uses [ConversationFacetFilter] to evaluate them. [IMAGE]
 * (a distinct node-browse mode, not a conversation-list filter) and [PROJECTS] (a grouped view
 * that includes an "Unassigned" bucket, not a filtered-out one) keep their existing, different
 * rendering paths — their query strings here are the documented spec mapping, not (yet) wired
 * to an evaluator, since forcing them into a boolean filter would be a real behavior change.
 */
enum class ChatsPreset(val label: String, val query: String) {
    ALL("All", ""),
    TEXT("Text", "-has:image"),
    IMAGE("Image", "has:image"),
    STARRED("Starred", "is:starred"),
    PROJECTS("Projects", "-is:orphan"),
}
