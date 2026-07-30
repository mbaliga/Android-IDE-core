package dev.aarso.data.search

import dev.aarso.domain.MessageNode
import dev.aarso.domain.Role
import dev.aarso.domain.ledger.LedgerEntry
import dev.aarso.domain.search.Segmenter
import dev.aarso.domain.tree.Conversations
import dev.aarso.domain.tree.MessageTree

/**
 * Flattens the message tree into search-index rows (Doc `FONEBREW_SEARCH_SPEC.md` §3, WP5).
 * Pure Kotlin — no Room, no SQLDelight, no Android — following this repo's existing presenter
 * convention ([dev.aarso.ui.state.ConversationsPresenter], [dev.aarso.domain.library.ConversationProjection]):
 * a stateless transform from domain facts to a plain data class, JVM-tested without any of the
 * plumbing that produces those facts at runtime. [dev.aarso.data.search.SearchIndexer] (WP6) is
 * what actually writes [Row]s into [SearchDatabase], chunked and resumable.
 *
 * One [Row] per conversation (root), matching Doc §3.1: "the projection model stays
 * conversation-level... a hit means *this conversation* matched, which is the right unit for a
 * chat list." Message-level granularity is resolved lazily by in-chat find (§9), not indexed.
 */
object SearchProjector {

    /** Bump when the projection logic changes shape — `index_state.projection_version` uses
     *  this to trigger a resumable rebuild (WP6) rather than silently indexing stale rows. */
    const val PROJECTION_VERSION = 1L

    /** Characters of the first assistant (or user, if none) turn kept as the raw snippet. */
    private const val SNIPPET_CHARS = 220

    /** One row of both `conv_projection` and `conv_facets` — [SearchIndexer] splits it back
     *  into the two SQLDelight insert calls; kept as one type here because both halves are
     *  produced from the same conversation in one pass. */
    data class Row(
        val convId: String,
        val title: String,
        val snippet: String,
        val body: String,
        val titleRaw: String,
        val snippetRaw: String,
        val bodyRaw: String,
        val updatedAt: Long,
        val createdAt: Long,
        val projectionVersion: Long,
        val starred: Boolean,
        val archived: Boolean,
        val projectId: String?,
        val modelIds: String,
        val turnCount: Long,
        val branchCount: Long,
        val hasImage: Boolean,
        val hasCode: Boolean,
        val costMinor: Long,
        val lastUsedAt: Long?,
    )

    /**
     * Projects every conversation in [tree]. The facet inputs are the same session/ledger facts
     * [dev.aarso.data.ConversationsStore] already combines for the (unwired) Chats list —
     * [starredRoots]/[projects] from `SessionStore`, [ledgerByConversation] from `LedgerStore`
     * grouped by `chatId`. [archivedRoots] is new (a `SessionStore` addition, same shape as
     * `bookmarkedRoots`) — `is:archived` isn't in either of those existing flows yet.
     */
    fun project(
        tree: MessageTree,
        starredRoots: Set<String>,
        archivedRoots: Set<String>,
        projects: Map<String, String>,
        ledgerByConversation: Map<String, List<LedgerEntry>>,
    ): List<Row> =
        Conversations.summarize(tree).map { summary ->
            projectOne(
                tree = tree,
                summary = summary,
                starred = summary.rootId in starredRoots,
                archived = summary.rootId in archivedRoots,
                projectId = projects[summary.rootId],
                ledgerEntries = ledgerByConversation[summary.rootId].orEmpty(),
            )
        }

    private fun projectOne(
        tree: MessageTree,
        summary: Conversations.Summary,
        starred: Boolean,
        archived: Boolean,
        projectId: String?,
        ledgerEntries: List<LedgerEntry>,
    ): Row {
        val nodes = subtreeNodes(tree, summary.rootId)
        val bodyRaw = nodes.joinToString("\n\n") { it.content }.trim()
        val snippetRaw = (nodes.firstOrNull { it.role == Role.ASSISTANT } ?: nodes.firstOrNull { it.role == Role.USER })
            ?.content?.take(SNIPPET_CHARS).orEmpty()
        val titleRaw = summary.title

        return Row(
            convId = summary.rootId,
            title = Segmenter.tokenizeForIndex(titleRaw),
            snippet = Segmenter.tokenizeForIndex(snippetRaw),
            body = Segmenter.tokenizeForIndex(bodyRaw),
            titleRaw = titleRaw,
            snippetRaw = snippetRaw,
            bodyRaw = bodyRaw,
            updatedAt = summary.lastUpdatedAt,
            createdAt = summary.createdMillis,
            projectionVersion = PROJECTION_VERSION,
            starred = starred,
            archived = archived,
            projectId = projectId,
            modelIds = summary.modelIds.filter { it.isNotBlank() }.distinct().sorted().joinToString(","),
            turnCount = summary.nodeCount.toLong(),
            branchCount = summary.branchCount.toLong(),
            hasImage = summary.hasImage,
            // Cheap, zero-new-domain-surface signal: does this conversation's raw text contain
            // a markdown code fence. Not a parser — a real fence pair vs. a stray "```" in prose
            // both count, same tradeoff §3.2's other has:* signals would make if they existed.
            hasCode = bodyRaw.contains("```"),
            costMinor = ledgerEntries.sumOf { it.estCostMinor },
            // No per-open timestamp exists (SessionStore.conversationOpens is a count, not a
            // clock) — last activity is the closest honest proxy for "last used".
            lastUsedAt = summary.lastUpdatedAt,
        )
    }

    /** Every node in the subtree rooted at [rootId], via [MessageTree.childrenOf] — there is no
     *  public "whole subtree" accessor on [MessageTree] itself (only [Conversations] has a
     *  private one), so this is the same walk, done locally. Order doesn't matter for [Row]'s
     *  concatenated body text. */
    private fun subtreeNodes(tree: MessageTree, rootId: String): List<MessageNode> {
        val root = tree.node(rootId) ?: return emptyList()
        val out = ArrayList<MessageNode>()
        val queue = ArrayDeque<MessageNode>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            out.add(node)
            queue.addAll(tree.childrenOf(node.id))
        }
        return out
    }
}
