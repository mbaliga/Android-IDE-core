package dev.fonebrew.data

import dev.fonebrew.data.dao.ThreadMarkerDao
import dev.fonebrew.data.entity.ThreadMarkerEntity
import dev.fonebrew.domain.thread.ThreadMarker
import dev.fonebrew.domain.thread.ThreadMarkerKind
import dev.fonebrew.domain.thread.ThreadMarkerSource
import dev.fonebrew.domain.tree.TreeFork
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.util.UUID

/**
 * THREAD_TOPOLOGY_PLAN.md WP1's data gateway for [dev.fonebrew.domain.thread.ThreadMarker] — chapter
 * boundaries, session starts, and (once WP2/WP3 land) compaction-run/lineage-source markers.
 * Fronts [ThreadMarkerDao] with one store, same "thin adapter, plain-class DI" shape
 * [dev.fonebrew.data.CurationStore] uses for [dev.fonebrew.domain.curation.Version]/[dev.fonebrew.domain.curation.MessageBookmark]
 * — a ThreadMarker is exactly the same kind of RETROACTIVE fact those are (THREAD_TOPOLOGY_PLAN.md
 * binding constraint 5: "metadata minted at insert, never edited; retroactive facts -> Room
 * tables" — this store never writes to `MessageNode.metadata`).
 */
class ThreadMarkerStore(private val dao: ThreadMarkerDao) {

    val markers: Flow<List<ThreadMarker>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun forRoot(rootId: String): List<ThreadMarker> = dao.forRoot(rootId).map { it.toDomain() }

    suspend fun forAnchor(msgId: String): List<ThreadMarker> = dao.forAnchor(msgId).map { it.toDomain() }

    /** TurnActionsSheet's "Mark chapter here…": names a chapter boundary anchored at [anchorMsgId]. */
    suspend fun markChapter(
        rootId: String,
        anchorMsgId: String,
        label: String,
        note: String? = null,
        now: Long = System.currentTimeMillis(),
    ): ThreadMarker {
        val marker = ThreadMarker(
            id = UUID.randomUUID().toString(),
            rootId = rootId,
            anchorMsgId = anchorMsgId,
            kind = ThreadMarkerKind.CHAPTER,
            label = label,
            note = note,
            at = now,
            source = ThreadMarkerSource.USER,
        )
        dao.insert(marker.toEntity())
        return marker
    }

    /** Renames (and optionally re-notes) an existing chapter marker in place — the marker ROW, never the anchored MessageNode. */
    suspend fun renameChapter(marker: ThreadMarker, label: String, note: String? = marker.note) {
        require(marker.kind == ThreadMarkerKind.CHAPTER) { "renameChapter only applies to CHAPTER markers, got ${marker.kind}." }
        dao.update(marker.copy(label = label, note = note).toEntity())
    }

    /** Removes a marker (chapter, session-start, or any other kind) outright. */
    suspend fun removeChapter(marker: ThreadMarker) = dao.delete(marker.toEntity())

    /**
     * TurnActionsSheet's "Start fresh session here": marks [anchorMsgId] as the point a new
     * session officially begins for [rootId]. Unlike [markChapter], a session-start marker
     * carries no label — SESSION_START is self-describing by `kind` alone.
     */
    suspend fun markSessionStart(
        rootId: String,
        anchorMsgId: String? = null,
        now: Long = System.currentTimeMillis(),
    ): ThreadMarker {
        val marker = ThreadMarker(
            id = UUID.randomUUID().toString(),
            rootId = rootId,
            anchorMsgId = anchorMsgId,
            kind = ThreadMarkerKind.SESSION_START,
            at = now,
            source = ThreadMarkerSource.USER,
        )
        dao.insert(marker.toEntity())
        return marker
    }

    /**
     * THREAD_TOPOLOGY_PLAN.md WP2's fork/spawn writer: a SYSTEM-sourced [ThreadMarkerKind.LINEAGE_SRC]
     * marker on the newly created conversation, pointing back to where it came from. `anchorMsgId`
     * is deliberately null — per this table's own schema doc, LINEAGE_SRC "points into
     * `payloadJson` for its own reference shape" rather than anchoring a message *in this new
     * conversation* (there is no such message; the source lives in a different root entirely for
     * a Fork/Spawn, unlike CHAPTER's same-conversation anchor).
     */
    /**
     * THREAD_TOPOLOGY_PLAN.md WP3's compaction-run writer: a SYSTEM-sourced
     * [ThreadMarkerKind.COMPACTION_RUN] marker anchored at the newest message a run compacted.
     * [dev.fonebrew.domain.curation.CompactionBoundary] reads these back (per-path "newest applicable
     * anchor wins") so later turns reconstruct the prompt above this point from the
     * [dev.fonebrew.domain.curation.Receipt] at [receiptObjectId] (looked up via
     * [dev.fonebrew.data.ReceiptStore.forObjectId]) instead of resending the verbatim originals.
     * [payloadJson]-carried [receiptObjectId] is deliberately just a back-reference, not the
     * receipt itself — the receipt is the durable, byte-comparable record; this marker only
     * points to it.
     */
    suspend fun markCompactionRun(
        rootId: String,
        anchorMsgId: String,
        receiptObjectId: String,
        now: Long = System.currentTimeMillis(),
    ): ThreadMarker {
        val payload = JSONObject().put("receiptObjectId", receiptObjectId).toString()
        val marker = ThreadMarker(
            id = UUID.randomUUID().toString(),
            rootId = rootId,
            anchorMsgId = anchorMsgId,
            kind = ThreadMarkerKind.COMPACTION_RUN,
            at = now,
            source = ThreadMarkerSource.SYSTEM,
            payloadJson = payload,
        )
        dao.insert(marker.toEntity())
        return marker
    }

    suspend fun markLineageSource(
        newRootId: String,
        srcRootId: String,
        srcNodeId: String,
        lineageKind: TreeFork.LineageKind,
        now: Long = System.currentTimeMillis(),
    ): ThreadMarker {
        val payload = JSONObject()
            .put("srcRootId", srcRootId)
            .put("srcNodeId", srcNodeId)
            .put("lineageKind", lineageKind.name)
            .toString()
        val marker = ThreadMarker(
            id = UUID.randomUUID().toString(),
            rootId = newRootId,
            anchorMsgId = null,
            kind = ThreadMarkerKind.LINEAGE_SRC,
            at = now,
            source = ThreadMarkerSource.SYSTEM,
            payloadJson = payload,
        )
        dao.insert(marker.toEntity())
        return marker
    }
}

private fun ThreadMarkerEntity.toDomain() = ThreadMarker(
    id = id,
    rootId = rootId,
    anchorMsgId = anchorMsgId,
    kind = kind,
    label = label,
    note = note,
    at = at,
    source = source,
    payloadJson = payloadJson,
)

private fun ThreadMarker.toEntity() = ThreadMarkerEntity(
    id = id,
    rootId = rootId,
    anchorMsgId = anchorMsgId,
    kind = kind,
    label = label,
    note = note,
    at = at,
    source = source,
    payloadJson = payloadJson,
)
