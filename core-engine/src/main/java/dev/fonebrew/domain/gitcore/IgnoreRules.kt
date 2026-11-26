package dev.fonebrew.domain.gitcore

/**
 * PORT: decides whether an untracked path is ignored. The real `.gitignore` matcher is another
 * lane's work; this lane ships only the port and a no-ignore default so [status] has a seam.
 * Tracked files are never ignored regardless of what this says.
 */
fun interface IgnoreRules {
    /** [path] is work-tree relative with `/` separators. */
    fun isIgnored(path: String, isDirectory: Boolean): Boolean

    companion object {
        val NONE = IgnoreRules { _, _ -> false }
    }
}
