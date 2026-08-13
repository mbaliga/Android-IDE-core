package dev.aarso.domain.curation

import dev.aarso.domain.MessageNode
import dev.aarso.domain.thread.ThreadMarker
import dev.aarso.domain.thread.ThreadMarkerKind

/**
 * Turns a compaction run's [Receipt] into the prompt actually sent to the model on later turns —
 * THREAD_TOPOLOGY_PLAN.md WP3's "prompt truncation above newest boundary node." A
 * [ThreadMarkerKind.COMPACTION_RUN] marker anchored at message X means "everything at or before X
 * (root-ward — i.e. older) was compacted by the run recorded in this marker's payload." Later
 * turns replace those messages' content with that run's fate-appropriate output (or drop them
 * outright per [MessageFate.DROPPED]) instead of resending the verbatim originals; anything
 * strictly after X — newer, produced since that run — is untouched, since it was never part of
 * the run and has no [CompactedMessage] entry to draw from anyway.
 *
 * Pure and store-free on purpose, same split [CompactionContract] uses for its own inputs: the
 * caller ([dev.aarso.ui.ChatViewModel]) resolves the marker list and the winning [Receipt] from
 * Room/[dev.aarso.data.ReceiptStore], this object only decides — so both functions here are fully
 * JVM-testable without a database.
 */
object CompactionBoundary {

    /**
     * The anchor id of the COMPACTION_RUN marker that applies to [pathIds] — "newest" meaning
     * closest to the leaf (the highest index within [pathIds], a root-first path), not newest by
     * wall-clock [ThreadMarker.at]. A marker's `at` is when the run was recorded, which does not
     * have to correlate with how far down a given branch its anchor sits — especially once
     * branching lets more than one COMPACTION_RUN marker exist for the same root. Markers whose
     * anchor isn't even on [pathIds] (a run recorded against a since-abandoned sibling branch)
     * are ignored entirely rather than skewing the "newest" pick.
     */
    fun newestBoundaryAnchorId(markers: List<ThreadMarker>, pathIds: List<String>): String? =
        markers.asSequence()
            .filter { it.kind == ThreadMarkerKind.COMPACTION_RUN }
            .mapNotNull { it.anchorMsgId }
            .filter { it in pathIds }
            .maxByOrNull { pathIds.indexOf(it) }

    /**
     * @param path a root-first conversation path (the shape
     *   [dev.aarso.data.MessageTreeRepository.path] returns).
     * @param boundaryAnchorId the id [newestBoundaryAnchorId] resolved for this same [path], or
     *   null when no compaction run applies yet — [path] is returned unchanged in that case.
     * @param receiptEntries the [Receipt.entries] of the run [boundaryAnchorId]'s marker points
     *   at. A message at or before the boundary with no matching entry (shouldn't normally
     *   happen — the run covered the same path — but the append-only tree means an old receipt
     *   can outlive edits to what's reachable) is kept verbatim rather than silently dropped.
     */
    fun effectivePath(
        path: List<MessageNode>,
        boundaryAnchorId: String?,
        receiptEntries: List<CompactedMessage>,
    ): List<MessageNode> {
        if (boundaryAnchorId == null) return path
        val boundaryIndex = path.indexOfFirst { it.id == boundaryAnchorId }
        if (boundaryIndex < 0) return path
        val byMsgId = receiptEntries.associateBy { it.msgId }
        return path.mapIndexedNotNull { index, node ->
            if (index > boundaryIndex) return@mapIndexedNotNull node // newer than the boundary: untouched
            val entry = byMsgId[node.id] ?: return@mapIndexedNotNull node // no record for it: keep as-is
            when (entry.fate) {
                MessageFate.DROPPED -> null
                MessageFate.KEPT_VERBATIM -> node
                MessageFate.FAITHFUL, MessageFate.GIST, MessageFate.TOMBSTONE ->
                    node.copy(content = entry.text ?: node.content)
            }
        }
    }
}
