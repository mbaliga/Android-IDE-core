package dev.aarso.domain.tree

/**
 * Conversation bookmarks (§5). The append-only tree never mutates a node, so a
 * "starred" flag can't live on it; instead we keep a small set of bookmarked
 * root ids alongside the session. Pure set algebra here; persistence is in
 * [dev.aarso.data.SessionStore].
 */
object Bookmarks {

    /** Toggle [id] in [current], returning a new set. */
    fun toggle(current: Set<String>, id: String): Set<String> =
        if (id in current) current - id else current + id

    /** The subset of [summaries] whose root is bookmarked, order preserved. */
    fun filter(summaries: List<Conversations.Summary>, ids: Set<String>): List<Conversations.Summary> =
        summaries.filter { it.rootId in ids }
}
