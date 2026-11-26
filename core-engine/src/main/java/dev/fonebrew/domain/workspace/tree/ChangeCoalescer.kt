package dev.fonebrew.domain.workspace.tree

import dev.fonebrew.contracts.workspace.ResourceChangeKind

/** One observed change inside a watched directory. */
data class FsEvent(val dirRel: String, val name: String, val kind: ResourceChangeKind)

/**
 * Debounce merge: a burst of events about the same entry collapses to its net effect, in first-seen
 * order. created+modified = created, created+deleted = nothing happened, modified+deleted =
 * deleted, deleted+created = modified (replaced), anything+modified keeps the stronger kind.
 * Pure; the watcher decides when to [drain].
 */
class ChangeCoalescer {
    private val pending = LinkedHashMap<Pair<String, String>, ResourceChangeKind?>()

    fun offer(e: FsEvent) {
        val key = e.dirRel to e.name
        if (!pending.containsKey(key)) { pending[key] = e.kind; return }
        val old = pending[key]
        pending[key] = merge(old, e.kind)
    }

    private fun merge(old: ResourceChangeKind?, new: ResourceChangeKind): ResourceChangeKind? = when {
        old == null -> new
        old == ResourceChangeKind.CREATED && new == ResourceChangeKind.MODIFIED -> ResourceChangeKind.CREATED
        old == ResourceChangeKind.CREATED && new == ResourceChangeKind.DELETED -> null
        old == ResourceChangeKind.DELETED && new == ResourceChangeKind.CREATED -> ResourceChangeKind.MODIFIED
        new == ResourceChangeKind.DELETED -> ResourceChangeKind.DELETED
        old == ResourceChangeKind.DELETED -> ResourceChangeKind.DELETED
        else -> if (old == ResourceChangeKind.MODIFIED) new else old
    }

    val hasPending: Boolean get() = pending.isNotEmpty()

    fun drain(): List<FsEvent> {
        val out = pending.entries.mapNotNull { (k, v) -> v?.let { FsEvent(k.first, k.second, it) } }
        pending.clear()
        return out
    }
}
