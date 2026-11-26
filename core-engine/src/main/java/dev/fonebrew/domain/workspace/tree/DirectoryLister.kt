package dev.fonebrew.domain.workspace.tree

import dev.fonebrew.domain.workspace.ignore.IgnoreMatcher
import dev.fonebrew.domain.workspace.roots.DirEntry
import dev.fonebrew.domain.workspace.roots.DirRead
import dev.fonebrew.domain.workspace.roots.DirectoryReader

/** A sorted view of one directory with its ignore bookkeeping. */
data class Listing(
    val entries: List<DirEntry>,
    /** More than [DirectoryLister.SCAN_CAP] raw entries existed; [entries] is a sorted prefix of the scan, not of the directory. */
    val truncated: Boolean,
    val hiddenByIgnore: Int,
)

sealed interface ListOutcome {
    data class Ok(val listing: Listing) : ListOutcome
    object Missing : ListOutcome
    data class Failed(val message: String) : ListOutcome
}

/**
 * Reads, filters and sorts one directory. COST BOUND: at most [scanCap] raw entries (default
 * [SCAN_CAP] = 20,000) are read from the file system per call, so a directory with millions of
 * names costs a fixed amount of memory and time; the result says [Listing.truncated] instead of
 * pretending to be complete. Entries that vanish mid-read are skipped by the reader.
 */
class DirectoryLister(
    private val reader: DirectoryReader,
    private val ignore: IgnoreMatcher? = null,
    private val scanCap: Int = SCAN_CAP,
) {
    fun list(dirRel: String, showIgnored: Boolean = false): ListOutcome {
        return when (val r = reader.listDir(dirRel, scanCap)) {
            is DirRead.Ok -> {
                var hidden = 0
                val kept = if (ignore == null || showIgnored) r.entries else r.entries.filter { e ->
                    val rel = if (dirRel.isEmpty()) e.name else "$dirRel/${e.name}"
                    val ig = ignore.isIgnored(rel, e.isDir)
                    if (ig) hidden++
                    !ig
                }
                ListOutcome.Ok(Listing(kept.sortedWith(NaturalOrder.entries), r.truncated, hidden))
            }
            DirRead.Missing, DirRead.NotDirectory -> ListOutcome.Missing
            is DirRead.Refused -> ListOutcome.Failed("refused: ${r.reason}")
            is DirRead.Unreadable -> ListOutcome.Failed(r.message)
        }
    }

    companion object {
        const val SCAN_CAP = 20_000
        const val PAGE = 200
    }
}
