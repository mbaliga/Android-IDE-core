package dev.aarso.domain.library

import dev.aarso.domain.tree.Conversations

/**
 * Pure projection from the message tree's [Conversations.Summary] (the spine's derived view of a
 * chat) plus the on-device session facts (stars, project label, open count) into the
 * [ConversationSummary] the Conversations room ([dev.aarso.ui.state.ConversationsViewModel])
 * renders. JVM-tested; no Android, no clock, no I/O.
 *
 * **Honesty (binding rule 6).** Every field comes from a real source: the timestamps and the
 * branch count are computed from the tree by [Conversations.summarize]; the star, project, and
 * open count come from the user's own on-device session store. Nothing is fabricated — a chat
 * with no recorded opens reports `useCount = 0` (it sorts last under MOST_USED), never a guess.
 *
 * **Kinds (contains-semantics).** A chat that holds both text and image turns is *both*
 * [ConvKind.TEXT] and [ConvKind.IMAGE], so it shows under Text **and** Image **and** All. A chat
 * with neither flag (an empty root) still lists under Text so it never falls out of every tab.
 */
object ConversationProjection {

    /** Map one tree summary + its session facts into the room's row model. */
    fun from(
        summary: Conversations.Summary,
        starred: Boolean,
        projectId: String?,
        useCount: Int,
    ): ConversationSummary = ConversationSummary(
        id = summary.rootId,
        title = summary.title,
        lastActivityMillis = summary.lastUpdatedAt,
        createdMillis = summary.createdMillis,
        projectId = projectId,
        kinds = buildSet {
            if (summary.hasText) add(ConvKind.TEXT)
            if (summary.hasImage) add(ConvKind.IMAGE)
            if (isEmpty()) add(ConvKind.TEXT)
        },
        starred = starred,
        branchCount = summary.branchCount,
        useCount = useCount.coerceAtLeast(0),
    )

    /**
     * Map a whole list of tree summaries, resolving each chat's session facts through the given
     * lookups. The order of [summaries] is preserved (the room re-sorts via
     * [ConversationsPresenter]); this is a straight per-row projection.
     */
    fun fromAll(
        summaries: List<Conversations.Summary>,
        starredRoots: Set<String>,
        projects: Map<String, String>,
        opens: Map<String, Int>,
    ): List<ConversationSummary> = summaries.map { s ->
        from(
            summary = s,
            starred = s.rootId in starredRoots,
            projectId = projects[s.rootId],
            useCount = opens[s.rootId] ?: 0,
        )
    }
}
