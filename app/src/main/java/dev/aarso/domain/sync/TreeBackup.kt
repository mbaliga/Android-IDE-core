package dev.aarso.domain.sync

import dev.aarso.domain.MessageNode

/**
 * Plans tree backup AND restore against a Git repo (docs/design/tree-sovereignty.md).
 * The tree is append-only with unique node ids, so:
 *  - backup only ever **creates files that aren't there yet** (no updates/shas), and
 *  - restore is a **union**: import any node we don't already have.
 * Pure; JVM-tested. Network execution lives in data/GitBackup.
 */
object TreeBackup {

    /** The subset of [local] (path → content from [TreeArchive.write]) not yet in the
     *  remote, i.e. the files to create this run. */
    fun plan(local: Map<String, String>, existingRemotePaths: Set<String>): Map<String, String> =
        local.filterKeys { it !in existingRemotePaths }

    /**
     * The nodes from [incoming] to import — those not already present (by id) —
     * ordered **parent-before-child** so the append-only insert never references a
     * missing parent. Orphans (a parent that is neither local nor incoming) are
     * dropped (returned in [ImportPlan.orphans]).
     */
    fun importPlan(incoming: List<MessageNode>, existingIds: Set<String>): ImportPlan {
        val available = HashSet(existingIds)
        val remaining = incoming.filter { it.id !in existingIds }.toMutableList()
        val ordered = ArrayList<MessageNode>(remaining.size)
        var progressed = true
        while (remaining.isNotEmpty() && progressed) {
            progressed = false
            val it = remaining.iterator()
            while (it.hasNext()) {
                val n = it.next()
                if (n.parentId == null || n.parentId in available) {
                    ordered += n
                    available += n.id
                    it.remove()
                    progressed = true
                }
            }
        }
        return ImportPlan(ordered, remaining.toList())
    }

    data class ImportPlan(val toInsert: List<MessageNode>, val orphans: List<MessageNode>)
}
