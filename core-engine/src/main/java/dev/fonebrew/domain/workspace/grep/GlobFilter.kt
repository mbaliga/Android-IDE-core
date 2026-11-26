package dev.fonebrew.domain.workspace.grep

import dev.fonebrew.domain.workspace.ignore.Wildmatch

/**
 * Include / exclude globs for search. A glob without `/` matches a name at any depth (`*.kt`,
 * `build`); one with `/` is anchored at the root (app/src with a double star after it). `**` works as in gitignore. A
 * trailing `/` is accepted and ignored. Excluding a directory excludes everything under it.
 */
class GlobFilter(includes: List<String>, excludes: List<String>) {
    private class G(val anchored: Boolean, val w: Wildmatch)

    private val inc = includes.mapNotNull(::compile)
    private val exc = excludes.mapNotNull(::compile)

    fun accepts(rel: String): Boolean {
        val parts = rel.split('/')
        // any ancestor or the file itself matched by an exclude
        var prefix = ""
        for (p in parts) {
            prefix = if (prefix.isEmpty()) p else "$prefix/$p"
            if (exc.any { hit(it, prefix, p) }) return false
        }
        if (inc.isEmpty()) return true
        // an include matches the file, or a directory above it (include "src" = everything in src)
        prefix = ""
        for (p in parts) {
            prefix = if (prefix.isEmpty()) p else "$prefix/$p"
            if (inc.any { hit(it, prefix, p) }) return true
        }
        return false
    }

    private fun hit(g: G, rel: String, name: String) = if (g.anchored) g.w.matches(rel) else g.w.matches(name)

    private fun compile(raw: String): G? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        if (s.endsWith("/")) s = s.dropLast(1)
        val anchored = s.contains('/')
        if (s.startsWith("/")) s = s.substring(1)
        if (s.isEmpty()) return null
        return G(anchored, Wildmatch.compile(s, pathname = anchored))
    }
}
