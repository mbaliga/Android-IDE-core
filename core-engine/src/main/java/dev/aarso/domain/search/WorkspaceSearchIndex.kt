package dev.aarso.domain.search

import dev.aarso.contracts.search.IndexFreshness
import dev.aarso.contracts.search.IndexSourceKind
import dev.aarso.contracts.search.ResultProvenance
import dev.aarso.contracts.search.SearchCancellation
import dev.aarso.contracts.workspace.BufferSnapshotEntry
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * WP-6's "minimal functional search surface" for the Workspace Kernel (brief: "final visuals
 * deferred to owner design" -- this is the functional core a future UI wires to). Deliberately a
 * plain in-memory index, NOT a new SQLDelight table: `CLAUDE.md`'s own build rules single out the
 * existing conversation FTS5 schema (`data/search/Search.sq`) as a "two SQL toolchains coexist...
 * do not unify" hazard zone, and this domain has no live human-editing UI yet to populate a
 * persistent index from (WP-3's own gate report §5 already flagged this — no interactive editor
 * surface exists in this codebase today). Wraps the same real [LexicalSearch]/[SearchDoc] engine
 * `data/search/` uses for conversations — see [WorkspaceSearchProjector].
 */
class WorkspaceSearchIndex {

    private data class Entry(val doc: SearchDoc, val provenance: ResultProvenance)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** Indexes (or re-indexes) one buffer snapshot's current content. Idempotent per `bufferId`. */
    fun index(snapshot: BufferSnapshotEntry, content: String, displayPath: String, now: Instant = Instant.now()) {
        val doc = WorkspaceSearchProjector.project(snapshot, content, displayPath, now.toEpochMilli())
        val provenance = ResultProvenance(
            sourceKind = IndexSourceKind.WORKSPACE_BUFFER,
            sourceId = snapshot.bufferId,
            freshness = WorkspaceSearchProjector.freshnessOf(snapshot, now),
        )
        entries[snapshot.bufferId] = Entry(doc, provenance)
    }

    /** Removes a buffer from the index (e.g. once its `DocumentBuffer` closes -- FB-RAT-WS-006, an index is disposable). */
    fun remove(bufferId: String) {
        entries.remove(bufferId)
    }

    fun size(): Int = entries.size

    /**
     * Searches all currently-indexed workspace buffers via the real [LexicalSearch] engine.
     * [cancellation] is polled between ranking and provenance-attachment so a caller can abandon
     * a large search without this function doing unnecessary work after the caller stopped
     * caring -- mirrors the execution domain's own cancellation-is-cooperative posture.
     */
    fun search(query: String, nowMillis: Long, cancellation: SearchCancellation = SearchCancellation.NEVER): List<WorkspaceSearchHit> {
        val docs = entries.values.map { it.doc }
        val hits = LexicalSearch.search(docs, query, nowMillis)
        if (cancellation.isCancelled()) return emptyList()
        return hits.mapNotNull { hit ->
            val provenance = entries[hit.doc.id]?.provenance ?: return@mapNotNull null
            WorkspaceSearchHit(hit, provenance)
        }
    }
}

/** One workspace search result, paired with where it came from and how fresh that projection is (FB-RAT-WS-006). */
data class WorkspaceSearchHit(val hit: SearchHit, val provenance: ResultProvenance) {
    val isFresh: Boolean get() = !provenance.freshness.isStale
}
