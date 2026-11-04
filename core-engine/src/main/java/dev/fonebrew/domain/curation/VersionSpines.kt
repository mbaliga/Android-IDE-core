package dev.fonebrew.domain.curation

import dev.fonebrew.domain.tree.MessageTree

/**
 * Computes which message ids lie on the ancestor path of any [Version]'s branch tip — the
 * "Version spines never compact below F2" signal ([CompactionContract]'s `isOnVersionSpine`
 * parameter, STUDIO_UX_SPEC.md §4.4). Shared so every caller (the compaction engine, and the
 * curation-sheet UI's own fidelity resolution when a user creates a directive) computes the same
 * set the same way, rather than each re-deriving it.
 */
object VersionSpines {
    fun computeIds(tree: MessageTree, versions: List<Version>): Set<String> {
        val ids = mutableSetOf<String>()
        for (version in versions) {
            val path = runCatching { tree.pathToRoot(version.branchTipMsgId) }.getOrNull() ?: continue
            for (node in path) ids += node.id
        }
        return ids
    }
}
