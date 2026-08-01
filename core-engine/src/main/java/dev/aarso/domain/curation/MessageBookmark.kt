package dev.aarso.domain.curation

/**
 * A message- (or code-block-) level bookmark (STUDIO_UX_SPEC.md §4.3/§5.1).
 *
 * Deliberately a *different* concept from [dev.aarso.domain.tree.Bookmarks], which is
 * conversation-level ("Starred," a `Set<String>` of root ids). This is the missing finer-grained
 * tier underneath it — pinning one message, or one code block within a message — and the two
 * coexist without conflict: a conversation can be starred, and independently carry zero or more
 * message-level bookmarks.
 *
 * @property ref what's pinned: a whole message, or (when [MessageRef.blockIndex] is set) one
 *   fenced code block within it, addressed by its ordinal position in the rendered message.
 * @property kind the bookmark's purpose — auto-suggested by an on-device classifier per the
 *   spec, always human-editable.
 * @property note optional free-text annotation.
 * @property label optional short display label (defaults to a kind-derived label in the UI when
 *   absent).
 */
data class MessageBookmark(
    val id: String,
    val ref: MessageRef,
    val kind: BookmarkKind,
    val note: String? = null,
    val label: String? = null,
    val at: Long,
)

/** What a [MessageBookmark] (or a compaction anchor) points at. */
data class MessageRef(
    val msgId: String,
    /** Ordinal index of a fenced code block within the message; null pins the whole message. */
    val blockIndex: Int? = null,
)

enum class BookmarkKind {
    REFERENCE,
    DECISION,
    SNIPPET,
    REVISIT,
}

/** Pure bookmark-set operations, kept separate from persistence. */
object MessageBookmarks {

    /** Add-wins merge for sync (STUDIO_UX_SPEC.md §5.1): union by [MessageBookmark.id], newest wins on an id collision (shouldn't happen across devices given UUID ids, but keeps the merge total). */
    fun mergeAddWins(existing: List<MessageBookmark>, incoming: List<MessageBookmark>): List<MessageBookmark> {
        val byId = LinkedHashMap<String, MessageBookmark>()
        for (b in existing) byId[b.id] = b
        for (b in incoming) {
            val prior = byId[b.id]
            if (prior == null || b.at >= prior.at) byId[b.id] = b
        }
        return byId.values.toList()
    }

    /** Bookmarks pinned to [msgId] (whole-message or any of its blocks), newest first. */
    fun forMessage(bookmarks: List<MessageBookmark>, msgId: String): List<MessageBookmark> =
        bookmarks.filter { it.ref.msgId == msgId }.sortedByDescending { it.at }
}
