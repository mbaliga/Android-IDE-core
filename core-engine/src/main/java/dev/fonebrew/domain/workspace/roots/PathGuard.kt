package dev.fonebrew.domain.workspace.roots

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Outcome of resolving a root-relative path to a real file-system path. */
sealed interface Guarded {
    data class Ok(val path: Path) : Guarded
    data class Escape(val reason: String) : Guarded
}

/**
 * Resolves root-relative paths while proving they stay under the real (symlink-resolved) root.
 * Walks the path one segment at a time: a symlink on the way is resolved and its real target must
 * still be under the root, a dangling or looping link is refused (a write through it could create
 * a file wherever the link points), and once a segment does not exist the rest cannot contain a
 * link so it is appended unchecked.
 *
 * [followFinalSymlink] = false is for delete / rename / move, which act on the link itself and
 * must not touch its target. Limit (stated in the design note): a symlink swapped in between this
 * check and the caller's use is a time-of-check/time-of-use window `java.io` cannot close.
 */
class PathGuard(rootDir: File) {
    val realRoot: Path = rootDir.toPath().toRealPath()

    fun resolve(rel: String, followFinalSymlink: Boolean = true): Guarded {
        if (rel.isEmpty()) return Guarded.Ok(realRoot)
        val segs = rel.split('/')
        var cur = realRoot
        for ((i, seg) in segs.withIndex()) {
            val last = i == segs.size - 1
            val next = cur.resolve(seg)
            if (!Files.exists(next, LinkOption.NOFOLLOW_LINKS)) {
                var p = next
                for (j in i + 1 until segs.size) p = p.resolve(segs[j])
                return if (p.startsWith(realRoot)) Guarded.Ok(p) else Guarded.Escape("resolved outside root")
            }
            if (Files.isSymbolicLink(next)) {
                if (last && !followFinalSymlink) return Guarded.Ok(next)
                val real = try { next.toRealPath() } catch (_: Exception) {
                    return Guarded.Escape("dangling or looping symlink at '${segs.take(i + 1).joinToString("/")}'")
                }
                if (!real.startsWith(realRoot)) {
                    return Guarded.Escape("symlink at '${segs.take(i + 1).joinToString("/")}' points outside the root")
                }
                cur = real
            } else {
                cur = next
            }
        }
        return Guarded.Ok(cur)
    }
}
