package dev.aarso.domain.diff

/**
 * A proposed, **reviewable** set of file edits — the agent's output as a material diff you
 * approve, never a silent write (docs/build-plan.md, Sprint 5). Built on the existing
 * [LineDiff] so every change renders as standard unified diff and a "+x −y" stat. A ChangeSet
 * is inert data: it describes what *would* change; nothing is committed until the caller
 * approves and a committer applies it. Pure Kotlin; JVM-tested.
 */

enum class ChangeOp { CREATE, MODIFY, DELETE }

/** One file's proposed change. [oldText] is "" for a create; [newText] is "" for a delete. */
data class FileChange(
    val path: String,
    val oldText: String,
    val newText: String,
) {
    val op: ChangeOp get() = when {
        oldText.isEmpty() && newText.isNotEmpty() -> ChangeOp.CREATE
        newText.isEmpty() && oldText.isNotEmpty() -> ChangeOp.DELETE
        else -> ChangeOp.MODIFY
    }

    /** The change's add/remove stat (for a glanceable badge). */
    val stat: LineDiff.Stat get() = LineDiff.stat(oldText, newText)

    /** True when the proposal actually differs from what's on disk. */
    val isNoop: Boolean get() = oldText == newText

    /** Standard unified diff, paths in git's `a/`…`b/`… form. */
    fun unifiedDiff(context: Int = 3): String =
        LineDiff.unified(oldText, newText, "a/$path", "b/$path", context)
}

/** A bundle of file changes proposed together (e.g. one issue's fix). */
data class ChangeSet(val changes: List<FileChange>) {

    /** Only the changes that actually differ. */
    val effective: List<FileChange> get() = changes.filterNot { it.isNoop }

    val isEmpty: Boolean get() = effective.isEmpty()

    /** Aggregate stat across all effective changes. */
    val stat: LineDiff.Stat
        get() = effective.fold(LineDiff.Stat(0, 0)) { acc, c ->
            LineDiff.Stat(acc.added + c.stat.added, acc.removed + c.stat.removed)
        }

    /** The combined unified diff for the whole set (each file in turn). */
    fun unifiedDiff(context: Int = 3): String =
        effective.joinToString("\n") { it.unifiedDiff(context) }

    companion object {
        /**
         * Build a ChangeSet from what was read (`old`, absent = "") and what's proposed
         * (`new`, absent = a delete). Only paths present in either map appear.
         */
        fun of(old: Map<String, String>, new: Map<String, String>): ChangeSet {
            val paths = (old.keys + new.keys).toSortedSet()
            return ChangeSet(paths.map { p -> FileChange(p, old[p] ?: "", new[p] ?: "") })
        }
    }
}
