// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
package dev.fonebrew.domain.thread

import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.curation.BookmarkKind
import dev.fonebrew.domain.curation.MessageBookmark
import dev.fonebrew.domain.loop.RunLog
import dev.fonebrew.domain.tree.MessageTree
import dev.fonebrew.domain.tree.TreeFork
import java.time.Instant

/**
 * **WP9** — projects a [ThreadGraph] snapshot from the three plain facts the rest of
 * `dev.fonebrew.domain.thread` already works from: the message tree, every [ThreadMarker], and every
 * [DelegationEvent]. Same "presenter over already-loaded domain facts" shape
 * [dev.fonebrew.domain.thread.ThreadChains.build] uses (it takes `List<Conversations.Summary>` +
 * `List<ThreadMarker>`, not a store) — this file does its own grouping/lookups rather than asking
 * the caller to pre-shape anything, so both [dev.fonebrew.data.ThreadObserver] (WP9's only caller
 * today) and a future WP10 `ThreadMapCanvas`/`GraphRoom` projector call it identically.
 *
 * Pure, deterministic, JVM-tested, Room/Android-independent — the placement decision's "graph
 * substrate lives in core, pure JVM, extraction-ready" (THREAD_TOPOLOGY_PLAN.md). Per binding
 * constraint 3 (Issue #2, in advance): this reads only the three snapshot inputs above — it never
 * touches [dev.fonebrew.domain.mirror.AarsoEventLog], and it computes no drift/idiolect number, only
 * a structural re-shape of facts that already exist.
 */
object ThreadGraphProjector {

    /**
     * Projects [tree]/[markers]/[delegations] into one [ThreadGraph] as of [generatedAtUtc].
     *
     * ### Node kinds
     * - A tree node with no parent (`MessageNode.isRoot`) that carries [TreeFork.LINEAGE_KIND_KEY]
     *   metadata (a Fork/Spawn root — WP2's `TreeFork.copySubtree`/`SpawnBridges`) becomes a
     *   [ThreadNodeKind.FORK_ROOT] or [ThreadNodeKind.SPAWN_ROOT] node, and its graph `parentId` is
     *   the **lineage source node** ([TreeFork.LINEAGE_SRC_NODE_KEY]) — not the (necessarily null)
     *   tree parent — because "what is this root attached to in the graph" means "where did it
     *   fork/spawn from," matching `fixtures/thread/valid/thread-graph-valid.json`'s own FORK_ROOT
     *   entry.
     * - **1.1.0**: a root with no lineage metadata but a [RunLog.TAG_RUN] (`"loopRunId"`) tag —
     *   `RunLog`/`GraphRunLog` already write this into the tree for a council loop / graph-loop run
     *   (a detached sub-tree, `parentId = null` by construction) — becomes a
     *   [ThreadNodeKind.RUN_ROOT], `parentId = null` (a loop run forks from nothing; it's a whole
     *   new detached subtree, not a lineage-sourced one), `label` = a short preview of the
     *   objective text already sitting in [MessageNode.content] (real data already on the node,
     *   never a second lookup or a fabricated summary).
     * - Every other tree node (root or not) becomes [ThreadNodeKind.MESSAGE]. **1.1.0**: if its
     *   metadata carries [MessageConfidence.METADATA_KEY] (only ever written at generation time —
     *   see that object's KDoc), that value becomes [ThreadGraphNode.confidence]; an unparsable or
     *   out-of-range value is dropped rather than failing the whole projection over one bad tag,
     *   same tolerant-degrade posture the dangling-edge cases below already use.
     * - Every [ThreadMarker] becomes a [ThreadNodeKind.MARKER] node, `parentId` = its
     *   [ThreadMarker.anchorMsgId] (null for e.g. `LINEAGE_SRC`, which anchors nothing in *this*
     *   conversation — see [dev.fonebrew.data.ThreadMarkerStore.markLineageSource]'s KDoc).
     * - **1.1.0**: every [MessageBookmark] in [bookmarks] with [BookmarkKind.DECISION] becomes a
     *   [ThreadNodeKind.DECISION] node — a curation-domain fact, distinct from [ThreadNodeKind.MARKER]
     *   ([ThreadMarkerKind] has no DECISION case at all) — `parentId` = its
     *   [dev.fonebrew.domain.curation.MessageRef.msgId], `label` = the bookmark's own `label`. A
     *   decision anchored to a message this snapshot doesn't have (deleted, or outside this tree)
     *   is honestly omitted — its `rootId` can't be computed without fabricating one, same "omitted,
     *   never fabricated" rule the no-rootId delegation case below already follows.
     * - Every [DelegationEvent] with a non-null [DelegationEvent.rootId] becomes a
     *   [ThreadNodeKind.DELEGATION] node, `parentId` = its [DelegationEvent.anchorMsgId],
     *   **1.1.0** `outcome` = its current [DelegationEvent.outcome] (`PENDING` until
     *   [DelegationOutcomes.correlate], WP8, resolves it — never re-derived here, only carried). A
     *   delegation with **no** rootId (the domain type allows one; nothing currently produces it)
     *   is honestly omitted rather than fabricating a `rootId` the schema requires non-blank.
     *
     * ### Edges
     * - [ThreadEdgeKind.REPLY]: real tree parent -> child, for every non-root [MessageNode].
     * - [ThreadEdgeKind.FORK] / [ThreadEdgeKind.SPAWN]: lineage source node -> Fork/Spawn root,
     *   kind from [TreeFork.LINEAGE_KIND_KEY]. A [ThreadNodeKind.RUN_ROOT] emits neither — it has
     *   no lineage source to point back to.
     * - [ThreadEdgeKind.MARKER_ANCHOR] / [ThreadEdgeKind.DECISION_ANCHOR] / [ThreadEdgeKind.DELEGATION_ANCHOR]:
     *   marker/decision/delegation node -> its anchor message id, when present.
     * - [ThreadEdgeKind.LINEAGE]: a [ThreadMarkerKind.LINEAGE_SRC] marker node -> the
     *   [ThreadChains.LineagePointer.srcNodeId] its payload names (decoded via
     *   [ThreadChains.lineagePointer], the exact same parse WP6 uses — one decoder, two readers).
     *
     * Any edge whose target id isn't a node this call actually produced (a deleted message, a
     * lineage pointer into a conversation outside this snapshot) is dropped, never fabricated —
     * the same graceful-degradation rule [ThreadChains.build] documents for its own dangling
     * pointers. [ThreadGraph.nodes] ids are unique by construction (every id source — message,
     * marker, bookmark, delegation — is itself a unique-id domain type); [ThreadGraphProjectorTest]
     * asserts this holds, per `thread-graph.schema.json`'s own "a future test MUST assert it" note.
     *
     * @param bookmarks **1.1.0**, defaulted to empty so [dev.fonebrew.data.ThreadObserver]'s
     *   existing call (which has no [dev.fonebrew.data.CurationStore] to draw from, and whose
     *   descriptive-remarks surface this fix doesn't touch) keeps compiling and behaving exactly
     *   as before. [dev.fonebrew.ui.ChatViewModel.loadThreadGraph] — the call `GraphRoom` actually
     *   renders — passes the real list.
     */
    fun project(
        tree: MessageTree,
        markers: List<ThreadMarker>,
        delegations: List<DelegationEvent>,
        generatedAtUtc: Instant,
        bookmarks: List<MessageBookmark> = emptyList(),
    ): ThreadGraph {
        val allTreeNodes = tree.allNodes()
        val treeNodeIds = allTreeNodes.mapTo(HashSet()) { it.id }
        val rootIdCache = HashMap<String, String>()
        fun rootIdOf(node: MessageNode): String {
            rootIdCache[node.id]?.let { return it }
            val chain = tree.pathToRoot(node.id)
            val rootId = chain.firstOrNull()?.id ?: node.id
            chain.forEach { rootIdCache[it.id] = rootId }
            return rootId
        }

        val nodes = ArrayList<ThreadGraphNode>(allTreeNodes.size + markers.size + delegations.size)
        val edges = ArrayList<ThreadGraphEdge>()

        for (node in allTreeNodes) {
            val lineageKind = node.metadata[TreeFork.LINEAGE_KIND_KEY]
                ?.let { runCatching { TreeFork.LineageKind.valueOf(it) }.getOrNull() }
            if (node.isRoot && lineageKind != null) {
                val srcNodeId = node.metadata[TreeFork.LINEAGE_SRC_NODE_KEY]
                nodes += ThreadGraphNode(
                    id = node.id,
                    kind = if (lineageKind == TreeFork.LineageKind.FORK) ThreadNodeKind.FORK_ROOT else ThreadNodeKind.SPAWN_ROOT,
                    rootId = node.id,
                    parentId = srcNodeId,
                    at = Instant.ofEpochMilli(node.createdAt),
                )
                if (srcNodeId != null && srcNodeId in treeNodeIds) {
                    edges += ThreadGraphEdge(
                        from = srcNodeId,
                        to = node.id,
                        kind = if (lineageKind == TreeFork.LineageKind.FORK) ThreadEdgeKind.FORK else ThreadEdgeKind.SPAWN,
                    )
                }
            } else if (node.isRoot && node.metadata.containsKey(RunLog.TAG_RUN)) {
                // 1.1.0 (gap 3, "develop work invisible"): RunLog/GraphRunLog already write a loop
                // run as a detached sub-tree tagged with loopRunId — this makes that existing fact
                // a first-class node instead of an indistinguishable plain MESSAGE root. No new
                // storage: the tag was already there (WP8/CORE_PHASES.md P3), only unread by the
                // projector until now.
                nodes += ThreadGraphNode(
                    id = node.id,
                    kind = ThreadNodeKind.RUN_ROOT,
                    rootId = node.id,
                    parentId = null,
                    at = Instant.ofEpochMilli(node.createdAt),
                    label = node.content.take(RUN_ROOT_LABEL_MAX_CHARS).ifBlank { null },
                )
                // No FORK/SPAWN-style edge: a loop run forks from nothing, it's a wholly new
                // detached subtree (RunLog/GraphRunLog's own KDoc).
            } else {
                nodes += ThreadGraphNode(
                    id = node.id,
                    kind = ThreadNodeKind.MESSAGE,
                    rootId = rootIdOf(node),
                    parentId = node.parentId,
                    at = Instant.ofEpochMilli(node.createdAt),
                    confidence = node.metadata[MessageConfidence.METADATA_KEY]
                        ?.toDoubleOrNull()?.takeIf { it in 0.0..1.0 },
                )
                val parentId = node.parentId
                if (parentId != null && parentId in treeNodeIds) {
                    edges += ThreadGraphEdge(from = parentId, to = node.id, kind = ThreadEdgeKind.REPLY)
                }
            }
        }

        for (marker in markers) {
            nodes += ThreadGraphNode(
                id = marker.id,
                kind = ThreadNodeKind.MARKER,
                rootId = marker.rootId,
                parentId = marker.anchorMsgId,
                at = Instant.ofEpochMilli(marker.at),
                label = marker.label,
            )
            val anchor = marker.anchorMsgId
            if (anchor != null && anchor in treeNodeIds) {
                edges += ThreadGraphEdge(from = marker.id, to = anchor, kind = ThreadEdgeKind.MARKER_ANCHOR)
            }
            if (marker.kind == ThreadMarkerKind.LINEAGE_SRC) {
                val pointer = ThreadChains.lineagePointer(marker)
                if (pointer != null && pointer.srcNodeId in treeNodeIds) {
                    edges += ThreadGraphEdge(from = marker.id, to = pointer.srcNodeId, kind = ThreadEdgeKind.LINEAGE)
                }
            }
        }

        // 1.1.0 (gap 1, "decisions invisible"): a curation MessageBookmark(kind=DECISION) is a
        // different domain object from a ThreadMarker — ThreadMarkerKind has no DECISION case at
        // all — so it needs its own projection here, not a shoehorn into the marker loop above.
        for (bookmark in bookmarks) {
            if (bookmark.kind != BookmarkKind.DECISION) continue
            val anchorMsgId = bookmark.ref.msgId
            val anchorNode = tree.node(anchorMsgId) ?: continue // omitted, never fabricated a rootId
            nodes += ThreadGraphNode(
                id = bookmark.id,
                kind = ThreadNodeKind.DECISION,
                rootId = rootIdOf(anchorNode),
                parentId = anchorMsgId,
                at = Instant.ofEpochMilli(bookmark.at),
                label = bookmark.label,
            )
            if (anchorMsgId in treeNodeIds) {
                edges += ThreadGraphEdge(from = bookmark.id, to = anchorMsgId, kind = ThreadEdgeKind.DECISION_ANCHOR)
            }
        }

        for (delegation in delegations) {
            val rootId = delegation.rootId ?: continue
            nodes += ThreadGraphNode(
                id = delegation.id,
                kind = ThreadNodeKind.DELEGATION,
                rootId = rootId,
                parentId = delegation.anchorMsgId,
                at = Instant.ofEpochMilli(delegation.at),
                label = delegation.kind.name,
                outcome = delegation.outcome,
            )
            val anchor = delegation.anchorMsgId
            if (anchor != null && anchor in treeNodeIds) {
                edges += ThreadGraphEdge(from = delegation.id, to = anchor, kind = ThreadEdgeKind.DELEGATION_ANCHOR)
            }
        }

        return ThreadGraph(generatedAtUtc = generatedAtUtc, nodes = nodes, edges = edges)
    }

    /** Chars of [MessageNode.content] previewed onto a [ThreadNodeKind.RUN_ROOT] node's `label` —
     *  real data already on the node (the run's own objective text), never a fabricated summary;
     *  just bounded so one long objective doesn't blow out the graph's label rendering. */
    private const val RUN_ROOT_LABEL_MAX_CHARS = 60
}
