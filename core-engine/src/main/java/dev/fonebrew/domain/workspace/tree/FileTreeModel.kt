package dev.fonebrew.domain.workspace.tree

import dev.fonebrew.domain.workspace.roots.DirEntry

/** One row of the flattened visible tree. State is carried by the row type and fields, never by colour. */
sealed interface TreeRow {
    val depth: Int

    data class Item(
        val rel: String,
        override val depth: Int,
        val entry: DirEntry,
        val expanded: Boolean,
    ) : TreeRow

    /** "N more": the directory has further entries not yet shown (page size [DirectoryLister.PAGE]). */
    data class LoadMore(val dirRel: String, override val depth: Int, val remaining: Int) : TreeRow

    /** The scan cap was hit: the listing above is a prefix of what the directory holds. */
    data class Truncated(val dirRel: String, override val depth: Int, val scanCap: Int) : TreeRow

    data class Unreadable(val dirRel: String, override val depth: Int, val message: String) : TreeRow
}

/**
 * Lazy, expandable model of ONE root's directory tree. Directories are read only when expanded;
 * each expanded directory shows a page of [DirectoryLister.PAGE] entries at a time ([loadMore]).
 * Thread-safe through a coarse lock; the caller does the I/O off the main thread.
 *
 * Restoring UI state: [restoreExpanded] re-expands saved directories, silently dropping ones that
 * no longer exist (or are no longer directories) and reporting them.
 */
class FileTreeModel(
    private val lister: DirectoryLister,
    private val showIgnored: Boolean = false,
    private val pageSize: Int = DirectoryLister.PAGE,
) {
    private class Node(val listing: Listing?, val error: String?, var shown: Int)

    private val lock = Any()
    private val expanded = LinkedHashSet<String>()
    private val nodes = HashMap<String, Node>()

    init { synchronized(lock) { load("") } }

    fun expandedDirs(): Set<String> = synchronized(lock) { expanded.toSet() }

    fun isExpanded(dirRel: String): Boolean = synchronized(lock) { dirRel in expanded }

    /** Expands (loading it) a directory; false if it is gone. */
    fun expand(dirRel: String): Boolean = synchronized(lock) {
        if (dirRel.isEmpty()) return@synchronized true
        if (!load(dirRel)) { expanded.remove(dirRel); return@synchronized false }
        expanded.add(dirRel)
        true
    }

    fun collapse(dirRel: String) = synchronized(lock) { expanded.remove(dirRel); nodes.remove(dirRel); Unit }

    fun toggle(dirRel: String): Boolean = synchronized(lock) {
        if (dirRel in expanded) { collapse(dirRel); false } else expand(dirRel)
    }

    /** Re-reads a directory (e.g. after a change event). Collapses it if it vanished. */
    fun refresh(dirRel: String) = synchronized(lock) {
        if (dirRel.isEmpty() || dirRel in expanded) {
            val keep = nodes[dirRel]?.shown
            if (!load(dirRel) && dirRel.isNotEmpty()) { expanded.remove(dirRel); dropBelow(dirRel) }
            else if (keep != null) nodes[dirRel]?.let { it.shown = maxOf(it.shown, keep) }
        }
    }

    fun loadMore(dirRel: String) = synchronized(lock) {
        val n = nodes[dirRel] ?: return@synchronized
        n.shown = minOf(n.shown + pageSize, n.listing?.entries?.size ?: n.shown)
    }

    /** Returns the directories that could not be restored. */
    fun restoreExpanded(dirs: Collection<String>): List<String> = synchronized(lock) {
        val lost = ArrayList<String>()
        // parents before children so a child of a vanished parent is dropped too
        for (d in dirs.sortedBy { it.count { c -> c == '/' } }) {
            val parent = d.substringBeforeLast('/', "")
            if (parent.isNotEmpty() && parent !in expanded) { lost.add(d); continue }
            if (!expand(d)) lost.add(d)
        }
        lost
    }

    fun rows(): List<TreeRow> = synchronized(lock) {
        val out = ArrayList<TreeRow>()
        emit("", 0, out)
        out
    }

    private fun emit(dirRel: String, depth: Int, out: MutableList<TreeRow>) {
        val n = nodes[dirRel] ?: return
        if (n.error != null) { out.add(TreeRow.Unreadable(dirRel, depth, n.error)); return }
        val l = n.listing!!
        val shown = l.entries.take(n.shown)
        for (e in shown) {
            val rel = if (dirRel.isEmpty()) e.name else "$dirRel/${e.name}"
            val open = e.isDir && rel in expanded
            out.add(TreeRow.Item(rel, depth, e, open))
            if (open) emit(rel, depth + 1, out)
        }
        if (n.shown < l.entries.size) out.add(TreeRow.LoadMore(dirRel, depth, l.entries.size - n.shown))
        else if (l.truncated) out.add(TreeRow.Truncated(dirRel, depth, DirectoryLister.SCAN_CAP))
    }

    private fun load(dirRel: String): Boolean {
        return when (val r = lister.list(dirRel, showIgnored)) {
            is ListOutcome.Ok -> { nodes[dirRel] = Node(r.listing, null, minOf(pageSize, r.listing.entries.size)); true }
            ListOutcome.Missing -> { nodes.remove(dirRel); false }
            is ListOutcome.Failed -> { nodes[dirRel] = Node(null, r.message, 0); true }
        }
    }

    private fun dropBelow(dirRel: String) {
        val pre = "$dirRel/"
        expanded.removeAll { it.startsWith(pre) }
        nodes.keys.removeAll { it == dirRel || it.startsWith(pre) }
    }
}
