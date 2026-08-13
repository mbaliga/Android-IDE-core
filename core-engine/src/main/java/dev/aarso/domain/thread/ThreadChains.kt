package dev.aarso.domain.thread

import dev.aarso.domain.tree.Conversations
import dev.aarso.domain.tree.TreeFork
import org.json.JSONObject

/**
 * **Mega-thread** (THREAD_TOPOLOGY_PLAN.md WP6 — "Mega-thread + search facets"): stitches the
 * conversations (roots) a Fork/Spawn chain connects into one human-legible project thread, plus
 * the chapter/session/compaction markers logged against each root along the way.
 *
 * Pure, JVM-tested, Room/Android-independent — same "presenter over already-loaded domain facts"
 * shape [dev.aarso.domain.tree.Conversations] and [dev.aarso.domain.tree.SiblingCompares] use.
 * Inputs are exactly what [dev.aarso.ui.ChatViewModel] already has in scope: every conversation's
 * [Conversations.Summary] (one per root) and the full, unfiltered [ThreadMarker] list — this file
 * does its own `rootId`grouping rather than asking the caller to pre-group, so
 * [dev.aarso.data.search.SearchProjector] (WP6's other consumer, for the `conv_facets`
 * `lineage_parent`/`lineage_kind`/`chapter_count`/`compaction_count` columns) and
 * [dev.aarso.ui.ChatViewModel] both call this the same way.
 *
 * ### Reads Room stores, never the event log
 * Lineage comes from the queryable [ThreadMarkerKind.LINEAGE_SRC] marker
 * [dev.aarso.data.ThreadMarkerStore.markLineageSource] writes — never from
 * [dev.aarso.domain.mirror.AarsoEventLog], which THREAD_TOPOLOGY_PLAN.md binding constraint 3
 * (Issue #2) keeps write-only. [TreeFork] also stamps the same `lineage.*` keys directly onto a
 * forked root's [dev.aarso.domain.MessageNode.metadata], but that copy is for the tree layer's
 * own bookkeeping (immutable, insert-time); the marker is the one THREAD_TOPOLOGY_PLAN.md's Data
 * Model section names as the surface other rooms read back from, so this file reads that one.
 */
object ThreadChains {

    /** The [ThreadMarkerKind.LINEAGE_SRC] payload, decoded — the same shape
     *  [dev.aarso.data.ThreadMarkerStore.markLineageSource] writes (`srcRootId`/`srcNodeId`/
     *  `lineageKind`). Exposed (not `private`) so [dev.aarso.data.search.SearchProjector] can
     *  reuse [lineagePointer] instead of re-parsing the same JSON shape a second way. */
    data class LineagePointer(
        val srcRootId: String,
        val srcNodeId: String,
        val lineageKind: TreeFork.LineageKind?,
    )

    /** One conversation's place in a [Chain]: its own summary facts plus how it got here
     *  ([lineage], null for the chain's origin) and how many retroactive markers were logged
     *  against it. */
    data class ChainLink(
        val rootId: String,
        val title: String,
        val createdMillis: Long,
        val lastUpdatedAt: Long,
        val lineage: LineagePointer?,
        val chapterCount: Int,
        val sessionStartCount: Int,
        val compactionCount: Int,
    )

    /**
     * A connected family of conversations — one origin (no [LineagePointer], created first) plus
     * every Fork/Spawn descendant transitively reachable from it — ordered oldest first. A
     * conversation with no Fork/Spawn history of its own is still a [Chain], just a singleton one;
     * callers that only care about genuine mega-threads should filter on [isMultiRoot].
     */
    data class Chain(val originRootId: String, val links: List<ChainLink>) {
        init {
            require(links.isNotEmpty()) { "ThreadChains.Chain must have at least one link." }
        }
        val rootCount: Int get() = links.size
        val title: String get() = links.first().title
        val latestUpdatedAt: Long get() = links.maxOf { it.lastUpdatedAt }
        val totalChapters: Int get() = links.sumOf { it.chapterCount }
        val totalCompactions: Int get() = links.sumOf { it.compactionCount }
        val isMultiRoot: Boolean get() = links.size > 1
    }

    /**
     * Decodes a [ThreadMarkerKind.LINEAGE_SRC] marker's `payloadJson` into a [LineagePointer].
     * Returns `null` for any other marker kind, a blank/absent payload, or a payload missing the
     * required `srcRootId`/`srcNodeId` strings — an honest "no lineage known" rather than a throw,
     * since a marker row is a retroactive, append-only fact this file only ever reads.
     */
    fun lineagePointer(marker: ThreadMarker): LineagePointer? {
        if (marker.kind != ThreadMarkerKind.LINEAGE_SRC) return null
        val payload = marker.payloadJson?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val obj = JSONObject(payload)
            val srcRootId = obj.optString("srcRootId").takeIf { it.isNotBlank() } ?: return null
            val srcNodeId = obj.optString("srcNodeId").takeIf { it.isNotBlank() } ?: return null
            val kind = obj.optString("lineageKind").takeIf { it.isNotBlank() }
                ?.let { runCatching { TreeFork.LineageKind.valueOf(it) }.getOrNull() }
            LineagePointer(srcRootId = srcRootId, srcNodeId = srcNodeId, lineageKind = kind)
        } catch (_: org.json.JSONException) {
            null
        }
    }

    /**
     * Groups [summaries] into [Chain]s by Fork/Spawn lineage, folding in [markers] (any kind, any
     * root — this function does its own filtering/grouping).
     *
     * Union-find over root ids: two roots are the same chain iff one's [LineagePointer.srcRootId]
     * is the other, transitively. A lineage pointer to a root that isn't in [summaries] (the
     * source conversation was deleted, or this is a partial view) degrades gracefully — the
     * pointed-to root simply isn't unioned, so the pointing root surfaces as its own chain's
     * origin rather than being dropped.
     */
    fun build(summaries: List<Conversations.Summary>, markers: List<ThreadMarker>): List<Chain> {
        if (summaries.isEmpty()) return emptyList()

        val markersByRoot = markers.groupBy { it.rootId }
        val lineageByRoot: Map<String, LineagePointer> = markersByRoot.mapNotNull { (rootId, ms) ->
            ms.firstOrNull { it.kind == ThreadMarkerKind.LINEAGE_SRC }
                ?.let(::lineagePointer)
                ?.let { rootId to it }
        }.toMap()

        val knownRoots = summaries.mapTo(HashSet()) { it.rootId }
        val parent = HashMap<String, String>()
        summaries.forEach { parent[it.rootId] = it.rootId }
        fun find(x: String): String {
            var r = x
            while (parent.getValue(r) != r) r = parent.getValue(r)
            return r
        }
        fun union(a: String, b: String) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }
        lineageByRoot.forEach { (rootId, ptr) ->
            if (ptr.srcRootId in knownRoots) union(rootId, ptr.srcRootId)
        }

        return summaries.groupBy { find(it.rootId) }.values.map { members ->
            val links = members.sortedBy { it.createdMillis }.map { summary ->
                val ms = markersByRoot[summary.rootId].orEmpty()
                ChainLink(
                    rootId = summary.rootId,
                    title = summary.title,
                    createdMillis = summary.createdMillis,
                    lastUpdatedAt = summary.lastUpdatedAt,
                    lineage = lineageByRoot[summary.rootId],
                    chapterCount = ms.count { it.kind == ThreadMarkerKind.CHAPTER },
                    sessionStartCount = ms.count { it.kind == ThreadMarkerKind.SESSION_START },
                    compactionCount = ms.count { it.kind == ThreadMarkerKind.COMPACTION_RUN },
                )
            }
            Chain(originRootId = links.first().rootId, links = links)
        }.sortedByDescending { it.latestUpdatedAt }
    }
}
