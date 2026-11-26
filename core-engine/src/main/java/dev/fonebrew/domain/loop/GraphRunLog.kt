package dev.fonebrew.domain.loop

import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.Role

/**
 * Persists a [GraphRunResult] as a detached sub-tree of the append-only message tree — the
 * same mechanism [RunLog] uses for the canonical loop, generalised to an arbitrary graph run
 * (docs/build-plan.md, Sprint 4). Reusing the tree spine means a graph run gets branching,
 * history, Tree-zoom clustering, and Git backup for free.
 *
 * Shape: an objective root (parentId = null) → one ASSISTANT node per executed [GraphStep], in
 * execution order, each tagged with the run id, the source BPMN node id, the role, and its step
 * index. Pure; JVM-tested. The data layer maps these to Room rows.
 */
object GraphRunLog {

    const val TAG_RUN = RunLog.TAG_RUN          // "loopRunId" — shared so Tree clustering is uniform
    const val TAG_LOOP = RunLog.TAG_LOOP
    const val TAG_ROLE = RunLog.TAG_ROLE
    const val TAG_STEP = "stepIndex"
    const val TAG_BPMN_NODE = "bpmnNodeId"
    const val TAG_STOPPED = RunLog.TAG_STOPPED
    const val TAG_TOOL = "toolId"
    const val TAG_HOSTS = "hostsContacted"
    const val TAG_ARGS = "toolArgs"
    const val TAG_FAILED = "toolFailed"

    /** Map [result] to its sub-tree nodes, parent-before-child (insert in order). */
    fun toNodes(
        objective: String,
        result: GraphRunResult,
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

        val nodes = ArrayList<MessageNode>(result.steps.size + 1)
        val root = MessageNode(
            id = idGen(), parentId = null, role = Role.USER, content = objective, createdAt = now,
            metadata = tag("objective", mapOf(TAG_STOPPED to result.stoppedBecause)),
        )
        nodes += root
        var parentId = root.id

        for (step in result.steps) {
            val node = MessageNode(
                id = idGen(),
                parentId = parentId,
                role = Role.ASSISTANT,
                content = step.output,
                modelId = step.model,
                createdAt = now,
                metadata = tag(
                    step.role,
                    buildMap {
                        put(TAG_STEP, step.index.toString())
                        put(TAG_BPMN_NODE, step.nodeId)
                        step.toolId?.let { put(TAG_TOOL, it) }
                        step.argsSummary?.let { put(TAG_ARGS, it) }
                        if (step.hosts.isNotEmpty()) put(TAG_HOSTS, step.hosts.joinToString(","))
                        if (step.failed) put(TAG_FAILED, "true")
                    },
                ),
            )
            nodes += node
            parentId = node.id
        }
        return nodes
    }
}
