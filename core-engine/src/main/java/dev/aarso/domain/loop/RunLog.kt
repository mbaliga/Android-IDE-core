package dev.aarso.domain.loop

import dev.aarso.domain.MessageNode
import dev.aarso.domain.Role
import dev.aarso.domain.council.Expert
import dev.aarso.domain.council.RunResult

/**
 * Persists a council loop run as a **detached sub-tree** of the append-only message
 * tree — the run's Log (docs/design/workflow-builder.md §5). The sub-tree has its
 * own root (the objective node, `parentId = null`), so a run is its own little tree:
 * its home is the Loop's Log, and it shows in the Tree (zoom-out) as a distinct
 * cluster tagged [TAG_RUN], keeping the conversation tree uncluttered.
 *
 * Reuses the tree spine ⇒ branching, history, and Git backup come for free. Pure;
 * JVM-tested. Node tags (metadata): run/loop id, nodeRole (objective/proposal/
 * critique), iteration, approved — `nodeRole` + `modelId` tell proposer from critic.
 */
object RunLog {

    const val TAG_RUN = "loopRunId"
    const val TAG_LOOP = "loopId"
    const val TAG_ROLE = "nodeRole"
    const val TAG_ITER = "iteration"
    const val TAG_APPROVED = "approved"
    const val TAG_STOPPED = "stoppedBecause"

    /**
     * Map [result] to its sub-tree nodes, **parent-before-child** (insert in order).
     * The chain is objective → proposal₁ → critique₁ → proposal₂ → … so the Log
     * reads top-to-bottom. [proposer]/[critic] supply the producing model per node.
     */
    fun toNodes(
        objective: String,
        proposer: Expert,
        critic: Expert,
        result: RunResult,
        loopRunId: String,
        loopId: String?,
        now: Long,
        idGen: () -> String,
    ): List<MessageNode> {
        fun tag(role: String, extra: Map<String, String> = emptyMap()): Map<String, String> = buildMap {
            put(TAG_RUN, loopRunId)
            if (loopId != null) put(TAG_LOOP, loopId)
            put(TAG_ROLE, role)
            putAll(extra)
        }

        val nodes = ArrayList<MessageNode>()
        val root = MessageNode(
            id = idGen(), parentId = null, role = Role.USER, content = objective, createdAt = now,
            metadata = tag("objective", mapOf(TAG_STOPPED to result.stoppedBecause)),
        )
        nodes += root
        var parentId = root.id

        for (iter in result.iterations) {
            val proposal = MessageNode(
                id = idGen(), parentId = parentId, role = Role.ASSISTANT, content = iter.proposal,
                modelId = proposer.model, createdAt = now,
                metadata = tag("proposal", mapOf(TAG_ITER to iter.n.toString())),
            )
            val critique = MessageNode(
                id = idGen(), parentId = proposal.id, role = Role.ASSISTANT, content = iter.critique,
                modelId = critic.model, createdAt = now,
                metadata = tag("critique", mapOf(TAG_ITER to iter.n.toString(), TAG_APPROVED to iter.approved.toString())),
            )
            nodes += proposal
            nodes += critique
            parentId = critique.id
        }
        return nodes
    }

    /** True for a node belonging to run [loopRunId]. */
    fun isRunNode(node: MessageNode, loopRunId: String): Boolean = node.metadata[TAG_RUN] == loopRunId

    /** A run's nodes in Log reading order: objective, then per-iteration proposal→critique. */
    fun ordered(nodes: List<MessageNode>): List<MessageNode> = nodes.sortedWith(
        compareBy(
            { it.metadata[TAG_ITER]?.toIntOrNull() ?: 0 },
            { if (it.metadata[TAG_ROLE] == "critique") 1 else 0 },
        ),
    )
}
