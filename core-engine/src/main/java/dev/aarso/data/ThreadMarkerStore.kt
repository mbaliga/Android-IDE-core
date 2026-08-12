package dev.aarso.data

import dev.aarso.data.dao.ThreadMarkerDao
import dev.aarso.data.entity.ThreadMarkerEntity
import dev.aarso.domain.thread.ThreadMarker
import dev.aarso.domain.thread.ThreadMarkerKind
import dev.aarso.domain.thread.ThreadMarkerSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * THREAD_TOPOLOGY_PLAN.md WP1's data gateway for [dev.aarso.domain.thread.ThreadMarker] — chapter
 * boundaries, session starts, and (once WP2/WP3 land) compaction-run/lineage-source markers.
 * Fronts [ThreadMarkerDao] with one store, same "thin adapter, plain-class DI" shape
 * [dev.aarso.data.CurationStore] uses for [dev.aarso.domain.curation.Version]/[dev.aarso.domain.curation.MessageBookmark]
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
