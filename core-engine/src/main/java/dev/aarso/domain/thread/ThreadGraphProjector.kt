// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
package dev.aarso.domain.thread

import dev.aarso.domain.MessageNode
import dev.aarso.domain.tree.MessageTree
import dev.aarso.domain.tree.TreeFork
import java.time.Instant

/**
 * **WP9** — projects a [ThreadGraph] snapshot from the three plain facts the rest of
 * `dev.aarso.domain.thread` already works from: the message tree, every [ThreadMarker], and every
 * [DelegationEvent]. Same "presenter over already-loaded domain facts" shape
 * [dev.aarso.domain.thread.ThreadChains.build] uses (it takes `List<Conversations.Summary>` +
 * `List<ThreadMarker>`, not a store) — this file does its own grouping/lookups rather than asking
 * the caller to pre-shape anything, so both [dev.aarso.data.ThreadObserver] (WP9's only caller
 * today) and a future WP10 `ThreadMapCanvas`/`GraphRoom` projector call it identically.
 *
 * Pure, deterministic, JVM-tested, Room/Android-independent — the placement decision's "graph
 * substrate lives in core, pure JVM, extraction-ready" (THREAD_TOPOLOGY_PLAN.md). Per binding
 * constraint 3 (Issue #2, in advance): this reads only the three snapshot inputs above — it never
 * touches [dev.aarso.domain.mirror.AarsoEventLog], and it computes no drift/idiolect number, only
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
     *   entry. Every other tree node (root or not) becomes [ThreadNodeKind.MESSAGE].
     * - Every [ThreadMarker] becomes a [ThreadNodeKind.MARKER] node, `parentId` = its
     *   [ThreadMarker.anchorMsgId] (null for e.g. `LINEAGE_SRC`, which anchors nothing in *this*
     *   conversation — see [dev.aarso.data.ThreadMarkerStore.markLineageSource]'s KDoc).
     * - Every [DelegationEvent] with a non-null [DelegationEvent.rootId] becomes a
     *   [ThreadNodeKind.DELEGATION] node, `parentId` = its [DelegationEvent.anchorMsgId]. A
     *   delegation with **no** rootId (the domain type allows one; nothing currently produces it)
     *   is honestly omitted rather than fabricating a `rootId` the schema requires non-blank.
     *
     * ### Edges
     * - [ThreadEdgeKind.REPLY]: real tree parent -> child, for every non-root [MessageNode].
     * - [ThreadEdgeKind.FORK] / [ThreadEdgeKind.SPAWN]: lineage source node -> Fork/Spawn root,
     *   kind from [TreeFork.LINEAGE_KIND_KEY].
     * - [ThreadEdgeKind.MARKER_ANCHOR] / [ThreadEdgeKind.DELEGATION_ANCHOR]: marker/delegation node
     *   -> its `anchorMsgId`, when present.
     * - [ThreadEdgeKind.LINEAGE]: a [ThreadMarkerKind.LINEAGE_SRC] marker node -> the
     *   [ThreadChains.LineagePointer.srcNodeId] its payload names (decoded via
     *   [ThreadChains.lineagePointer], the exact same parse WP6 uses — one decoder, two readers).
     *
     * Any edge whose target id isn't a node this call actually produced (a deleted message, a
     * lineage pointer into a conversation outside this snapshot) is dropped, never fabricated —
     * the same graceful-degradation rule [ThreadChains.build] documents for its own dangling
     * pointers. [ThreadGraph.nodes] ids are unique by construction (every id source — message,
     * marker, delegation — is itself a unique-id domain type); [ThreadGraphProjectorTest] asserts
     * this holds, per `thread-graph.schema.json`'s own "a future test MUST assert it" note.
     */
    fun project(
        tree: MessageTree,
        markers: List<ThreadMarker>,
        delegations: List<DelegationEvent>,
        generatedAtUtc: Instant,
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
            } else {
                nodes += ThreadGraphNode(
                    id = node.id,
                    kind = ThreadNodeKind.MESSAGE,
                    rootId = rootIdOf(node),
                    parentId = node.parentId,
                    at = Instant.ofEpochMilli(node.createdAt),
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

        for (delegation in delegations) {
            val rootId = delegation.rootId ?: continue
            nodes += ThreadGraphNode(
                id = delegation.id,
                kind = ThreadNodeKind.DELEGATION,
                rootId = rootId,
                parentId = delegation.anchorMsgId,
                at = Instant.ofEpochMilli(delegation.at),
                label = delegation.kind.name,
            )
            val anchor = delegation.anchorMsgId
            if (anchor != null && anchor in treeNodeIds) {
                edges += ThreadGraphEdge(from = delegation.id, to = anchor, kind = ThreadEdgeKind.DELEGATION_ANCHOR)
            }
        }

        return ThreadGraph(generatedAtUtc = generatedAtUtc, nodes = nodes, edges = edges)
    }
}
