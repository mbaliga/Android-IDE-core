package dev.aarso.domain.tree

import dev.aarso.domain.MessageNode
import java.util.UUID

/**
 * **Fork** (THREAD_TOPOLOGY_PLAN.md WP2) — the full-fidelity half of the "spin off an independent
 * conversation" pair, the other half being [dev.aarso.domain.bridge.SpawnBridges] ("Spawn").
 * Fork never compresses or invents: it duplicates real messages, verbatim, into a brand-new
 * conversation (a fresh root, `parentId == null`) that shows up as its own entry in the
 * Conversations room rather than as another branch nested under the source tree. Spawn is the
 * opposite trade-off — small footprint, a condensed bridge summary, no full copy.
 *
 * **What gets copied — a documented design decision, flagged for owner review (CLAUDE.md rule 6,
 * "honest uncertainty").** THREAD_TOPOLOGY_PLAN.md's WP2 bullet names this
 * `TreeFork.copySubtree` without pinning down direction. [copySubtree] here copies the **ancestor
 * chain** — [MessageTree.pathToRoot] of the node the fork is invoked on — because "Fork from this
 * message" most usefully means *"give me an independent copy of the conversation as it stood up
 * to here, that I can now take in a different direction"* (the git-fork idiom: a copy you own
 * outright), not the still-unexplored branches below it (which stay exactly where they are,
 * reachable in the original conversation via the existing ‹ › alternative pager). The function is
 * still literally "copy a subtree" in the general tree sense — a root-to-node path is a connected,
 * degenerate subtree of the source tree — so a future WP that also wants to fork *descendants*
 * (e.g. pulling out an already-branchy exploration) can extend the node-list this function accepts
 * without a rename.
 *
 * Pure, deterministic given [idGen]/[now] — no Android, no I/O. The caller
 * ([dev.aarso.ui.ChatViewModel.forkFrom]) is responsible for actually inserting [ForkResult.nodes]
 * into the tree store (parent-first order, so the FK-style `parentId` reference is always already
 * present) and for writing the [dev.aarso.domain.thread.ThreadEvent]/
 * [dev.aarso.domain.thread.ThreadMarker] lineage receipts alongside it.
 */
object TreeFork {

    /** Which "spin off a new conversation" action produced a lineage-tagged root — shared between
     *  [copySubtree] (Fork) and [dev.aarso.domain.bridge.SpawnBridges] (Spawn) so both write the
     *  same `lineage.kind` vocabulary rather than each inventing their own tag. */
    enum class LineageKind { FORK, SPAWN }

    /** Insert-time node-metadata keys THREAD_TOPOLOGY_PLAN.md's Data Model section names verbatim
     *  ("lineage.kind|srcRoot|srcNode|at") — minted once at insert on the new root, never edited
     *  after (binding constraint 5). */
    const val LINEAGE_KIND_KEY = "lineage.kind"
    const val LINEAGE_SRC_ROOT_KEY = "lineage.srcRoot"
    const val LINEAGE_SRC_NODE_KEY = "lineage.srcNode"
    const val LINEAGE_AT_KEY = "lineage.at"

    /** A metadata value that references another node's id by the *pre-copy* id — remapped below
     *  so a forked copy never carries a dangling cross-reference into the source tree's id space
     *  when the referenced node was itself part of the copy. Today this is council-fan-out's own
     *  write-only `"council"` backref (`ChatViewModel.sendCouncil`); listed explicitly (not
     *  inferred) so a new id-valued metadata key added later doesn't silently need the same care
     *  without a reviewer noticing. */
    private val ID_VALUED_METADATA_KEYS = setOf("council")

    data class ForkResult(
        /** The copied nodes, parent-first (index 0 is the new, parentless root). */
        val nodes: List<MessageNode>,
        /** Old id -> new id, in copy order — callers needing to translate a stale reference (e.g.
         *  a bookmark on the source path) can use this; [copySubtree] already applies it to the
         *  copy's own [ID_VALUED_METADATA_KEYS] internally. */
        val idMap: Map<String, String>,
    ) {
        val newRootId: String get() = nodes.first().id
    }

    /**
     * Copies [tree]'s root→[fromNodeId] ancestor chain into a fresh, independent chain: new ids,
     * `parentId` remapped to the new chain, original `role`/`content`/`modelId`/`createdAt`
     * preserved verbatim (a real copy, not a fabrication — every fact stays true), and lineage
     * metadata ([LINEAGE_KIND_KEY] etc.) minted only on the new root.
     *
     * `createdAt` is preserved rather than stamped to [now]: the messages really were said at
     * their original times, and overwriting that would misrepresent history to satisfy a "recently
     * active" sort — an honest trade-off documented here so it isn't mistaken for an oversight.
     *
     * @throws IllegalArgumentException if [fromNodeId] is not a known node in [tree].
     */
    fun copySubtree(
        tree: MessageTree,
        fromNodeId: String,
        srcRootId: String,
        now: Long,
        idGen: () -> String = { UUID.randomUUID().toString() },
    ): ForkResult {
        val path = tree.pathToRoot(fromNodeId)
        require(path.isNotEmpty()) { "TreeFork.copySubtree: unknown node '$fromNodeId'" }

        val idMap = LinkedHashMap<String, String>()
        val copied = path.mapIndexed { index, original ->
            val newId = idGen()
            idMap[original.id] = newId
            val newParentId = if (index == 0) null else idMap.getValue(path[index - 1].id)
            val remappedMetadata = original.metadata.mapValues { (key, value) ->
                if (key in ID_VALUED_METADATA_KEYS) idMap[value] ?: value else value
            }
            val metadata = if (index == 0) {
                remappedMetadata + mapOf(
                    LINEAGE_KIND_KEY to LineageKind.FORK.name,
                    LINEAGE_SRC_ROOT_KEY to srcRootId,
                    LINEAGE_SRC_NODE_KEY to fromNodeId,
                    LINEAGE_AT_KEY to now.toString(),
                )
            } else {
                remappedMetadata
            }
            original.copy(id = newId, parentId = newParentId, metadata = metadata)
        }
        return ForkResult(nodes = copied, idMap = idMap)
    }
}
