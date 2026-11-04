package dev.fonebrew.domain.curation

import dev.fonebrew.domain.MessageNode

/**
 * Pure, agent-free preview of what a compaction run WOULD do to a set of messages — the
 * Compaction Preview sheet's per-row fidelity/fate badge (STUDIO_UX_SPEC.md §5.2), resolved the
 * exact same way [CompactionEngine.run] resolves it internally, but without calling a model:
 * nothing here produces text, so [Row] carries only an excerpt of the ORIGINAL content plus the
 * fidelity/fate a real run would apply — never a generated gist/faithful/tombstone. That's
 * [dev.fonebrew.ui.ChatViewModel.runCompaction]'s job, the follow-on action once the user has seen
 * this preview and chosen to actually run it.
 */
object CompactionPreviewPresenter {

    /** One row of the preview: what a real run would do to [msgId], nothing has been generated yet. */
    data class Row(
        val msgId: String,
        val excerpt: String,
        val resolution: ResolvedFidelity,
        val fate: MessageFate,
    )

    /**
     * @param messages the messages a run would consider, in any order (mirrors
     *   [CompactionEngine.run]'s own parameter — verdicts/bookmarks/versionSpine are looked up by
     *   id, not position); rows come back in [messages]' own order.
     */
    fun build(
        messages: List<MessageNode>,
        directives: Map<String, CompactionDirective>,
        verdicts: Map<String, Verdict>,
        bookmarkedIds: Set<String>,
        versionSpineIds: Set<String>,
    ): List<Row> = messages.map { message ->
        val resolution = CompactionContract.resolve(
            msgId = message.id,
            directive = directives[message.id],
            verdict = verdicts[message.id],
            isBookmarked = message.id in bookmarkedIds,
            isOnVersionSpine = message.id in versionSpineIds,
        )
        Row(
            msgId = message.id,
            excerpt = message.content.take(EXCERPT_LENGTH),
            resolution = resolution,
            fate = Fates.forResolution(resolution),
        )
    }

    private const val EXCERPT_LENGTH = 120
}
