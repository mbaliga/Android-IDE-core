package dev.fonebrew.domain.workspace.tree

import dev.fonebrew.contracts.workspace.ResourceChangeKind

/** What a poll remembers about an entry; any difference counts as a modification. */
data class EntrySig(val isDir: Boolean, val size: Long, val modifiedMs: Long)

/** The polling fallback's comparison, kept pure so it is tested without a clock or a disk. */
object PollingDiffer {
    fun diff(dirRel: String, old: Map<String, EntrySig>, new: Map<String, EntrySig>): List<FsEvent> {
        val out = ArrayList<FsEvent>()
        for (name in new.keys.sorted()) {
            val o = old[name]
            if (o == null) out.add(FsEvent(dirRel, name, ResourceChangeKind.CREATED))
            else if (o != new[name]) out.add(FsEvent(dirRel, name, ResourceChangeKind.MODIFIED))
        }
        for (name in old.keys.sorted()) if (name !in new) out.add(FsEvent(dirRel, name, ResourceChangeKind.DELETED))
        return out
    }
}
