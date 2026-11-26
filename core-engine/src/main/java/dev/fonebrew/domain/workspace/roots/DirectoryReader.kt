package dev.fonebrew.domain.workspace.roots

/** One directory entry as the tree sees it (no content, no digest: listing must stay cheap). */
data class DirEntry(
    val name: String,
    /** True only for a real directory, or a symlink to a directory that stays inside the root. */
    val isDir: Boolean,
    val isSymlink: Boolean,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    /** A symlink whose target is outside the root or cannot be resolved: shown, never followed. */
    val linkEscapes: Boolean = false,
)

sealed interface DirRead {
    /** [entries] are unsorted; [truncated] is true when the directory held more than the scan cap. */
    data class Ok(val entries: List<DirEntry>, val truncated: Boolean) : DirRead
    object Missing : DirRead
    object NotDirectory : DirRead
    data class Refused(val reason: String) : DirRead
    data class Unreadable(val message: String) : DirRead
}

/** The one capability the lazy tree needs from a root; tests fake it to simulate races. */
fun interface DirectoryReader {
    fun listDir(dirRel: String, maxScan: Int): DirRead
}
