package dev.fonebrew.domain.workspace.tree

import dev.fonebrew.domain.workspace.ignore.IgnoreMatcher
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** One regular file found by [FileWalker]. */
data class WalkedFile(val rel: String, val path: Path, val sizeBytes: Long)

/**
 * Lazy, deterministic, iterative depth-first walk of one root for indexing and searching. Honours
 * the ignore rules (an ignored directory is not entered), never follows symlinks (links are
 * skipped: a link to a directory could loop or leave the root), sorts each directory with
 * [NaturalOrder] so two walks of an unchanged tree agree, and is bounded: at most [maxFiles] files
 * overall and [DirectoryLister.SCAN_CAP] entries per directory. [truncated] / [skippedDirs] say
 * when a bound was hit, so a caller never presents a partial walk as complete. Iterative (explicit
 * stack), so a pathologically deep tree cannot overflow the call stack.
 */
class FileWalker(
    private val root: Path,
    private val ignore: IgnoreMatcher? = null,
    private val maxFiles: Int = 500_000,
    private val perDirCap: Int = DirectoryLister.SCAN_CAP,
) {
    @Volatile var truncated: Boolean = false
        private set
    @Volatile var unreadableDirs: Int = 0
        private set

    fun files(): Sequence<WalkedFile> = sequence {
        var emitted = 0
        val stack = ArrayDeque<String>()
        stack.addLast("")
        while (stack.isNotEmpty()) {
            val dirRel = stack.removeLast()
            val dir = if (dirRel.isEmpty()) root else root.resolve(dirRel)
            val names = ArrayList<Pair<String, BasicFileAttributes>>()
            try {
                Files.newDirectoryStream(dir).use { ds ->
                    for (p in ds) {
                        if (names.size >= perDirCap) { truncated = true; break }
                        try {
                            names.add(p.fileName.toString() to Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS))
                        } catch (_: java.io.IOException) { /* vanished */ }
                    }
                }
            } catch (_: java.io.IOException) {
                unreadableDirs++
                continue
            } catch (_: java.nio.file.DirectoryIteratorException) {
                unreadableDirs++
                continue
            }
            names.sortWith { a, b -> NaturalOrder.compareNames(a.first, b.first) }
            val subdirs = ArrayList<String>()
            for ((name, attrs) in names) {
                if (attrs.isSymbolicLink) continue
                val rel = if (dirRel.isEmpty()) name else "$dirRel/$name"
                if (attrs.isDirectory) {
                    if (ignore != null && ignore.isIgnored(rel, true)) continue
                    subdirs.add(rel)
                } else if (attrs.isRegularFile) {
                    if (ignore != null && ignore.isIgnored(rel, false)) continue
                    if (emitted >= maxFiles) { truncated = true; return@sequence }
                    emitted++
                    yield(WalkedFile(rel, dir.resolve(name), attrs.size()))
                }
            }
            // push in reverse so the first sorted subdirectory is visited first
            for (i in subdirs.indices.reversed()) stack.addLast(subdirs[i])
        }
    }
}
