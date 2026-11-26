package dev.fonebrew.domain.gitcore

import java.util.Locale

/**
 * The single gate every tree entry, index entry and checkout path passes through. These are the
 * known git path-traversal / `.git` overwrite vulnerability classes, written as rules:
 *  - no empty, `.` or `..` components; no absolute paths; no drive letters; no backslash;
 *  - no NUL; length capped;
 *  - no component that names the git directory under any common folding: case, trailing
 *    dots/spaces (NTFS), the 8.3 short name `git~1`, or HFS+-ignorable Unicode inside `.git`.
 * Writing through symlinks is a separate runtime check ([WorkTreeFs]); this class is pure.
 */
object PathSafety {
    private val IGNORABLE = setOf(
        '\u200c', '\u200d', '\u200e', '\u200f', '\u202a', '\u202b', '\u202c', '\u202d', '\u202e',
        '\u206a', '\u206b', '\u206c', '\u206d', '\u206e', '\u206f', '\ufeff',
    )

    /** Returns null if [path] is a safe work-tree-relative path, else the reason it is not. */
    fun violation(path: String, limits: GitLimits = GitLimits()): String? {
        if (path.isEmpty()) return "empty path"
        if (path.toByteArray(Charsets.UTF_8).size > limits.maxPathBytes) return "path longer than ${limits.maxPathBytes} bytes"
        if (path.indexOf('\u0000') >= 0) return "path contains NUL"
        if (path.indexOf('\\') >= 0) return "path contains a backslash"
        if (path.startsWith("/")) return "absolute path"
        if (path.length >= 2 && path[1] == ':' && path[0].isLetter()) return "drive-letter path"
        for (comp in path.split('/')) {
            if (comp.isEmpty()) return "empty path component"
            if (comp == "." || comp == "..") return "'$comp' path component"
            if (isGitDirName(comp)) return "component names the git directory"
        }
        return null
    }

    fun check(path: String, limits: GitLimits = GitLimits()) {
        violation(path, limits)?.let { throw GitException(GitErrorKind.UNSAFE_PATH, "unsafe path '${display(path)}': $it", listOf(display(path))) }
    }

    fun isSafe(path: String) = violation(path) == null

    /** True when [component] would resolve to the `.git` directory on some filesystem. */
    fun isGitDirName(component: String): Boolean {
        var s = component.filter { it !in IGNORABLE }
        s = s.trimEnd('.', ' ')
        val l = s.lowercase(Locale.ROOT)
        return l == ".git" || l == "git~1"
    }

    /** Makes a path safe to put in an error message (no control characters). */
    fun display(path: String): String = path.map { if (it.isISOControl()) '?' else it }.joinToString("").take(200)

    fun join(root: String, rel: String): String = root.trimEnd('/') + "/" + rel
}
