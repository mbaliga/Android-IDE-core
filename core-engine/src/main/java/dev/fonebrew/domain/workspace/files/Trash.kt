package dev.fonebrew.domain.workspace.files

import dev.fonebrew.domain.workspace.roots.Guarded
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicLong

@JvmInline value class UndoToken(val id: String)

data class TrashItem(val token: UndoToken, val rootId: String, val rel: String, val isDir: Boolean, val bytes: Long, val deletedAtMs: Long)

sealed interface TrashPut {
    data class Ok(val token: UndoToken) : TrashPut
    data class TooLarge(val bytes: Long, val capBytes: Long) : TrashPut
    data class Failed(val message: String) : TrashPut
}

sealed interface TrashRestore {
    data class Ok(val rootId: String, val rel: String) : TrashRestore
    object Unknown : TrashRestore
    data class OriginOccupied(val rootId: String, val rel: String) : TrashRestore
    data class Refused(val reason: String) : TrashRestore
    data class Failed(val message: String) : TrashRestore
}

/**
 * The per-workspace trash that every non-permanent delete goes through. Layout:
 * `<dir>/<id>/payload` plus `<dir>/<id>/meta.json` (origin root + relative path, size, time).
 * Retention is BOUNDED three ways and enforced on every [put]: at most [maxItems] items, none
 * older than [maxAgeMs], and at most [maxBytes] in total (oldest evicted first). An item larger
 * than [maxBytes] is refused ([TrashPut.TooLarge]) rather than silently kept over the cap or
 * silently deleted for good; the caller must then ask for a permanent delete explicitly.
 * The trash directory must live OUTSIDE every workspace root (the workspace owner wires it under
 * app storage), otherwise deleted files would reappear in the tree and in searches.
 */
class Trash(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    val maxItems: Int = 50,
    val maxAgeMs: Long = 7L * 24 * 3600 * 1000,
    val maxBytes: Long = 512L * 1024 * 1024,
) {
    private val seq = AtomicLong()
    private val lock = Any()

    fun put(rootId: String, rel: String, source: Path): TrashPut = synchronized(lock) {
        val now = clock()
        val isDir = Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)
        val bytes = try { sizeOf(source) } catch (e: TooMany) { return TrashPut.TooLarge(Long.MAX_VALUE, maxBytes) }
        if (bytes > maxBytes) return TrashPut.TooLarge(bytes, maxBytes)
        val id = "$now-${seq.incrementAndGet()}"
        val slot = File(dir, id)
        try {
            slot.mkdirs()
            val payload = File(slot, "payload").toPath()
            moveTree(source, payload)
            File(slot, "meta.json").writeText(
                JSONObject().put("rootId", rootId).put("rel", rel).put("isDir", isDir).put("bytes", bytes).put("at", now).toString()
            )
        } catch (e: IOException) {
            slot.deleteRecursively()
            return TrashPut.Failed(e.javaClass.simpleName)
        }
        purgeLocked(now)
        TrashPut.Ok(UndoToken(id))
    }

    fun list(): List<TrashItem> = synchronized(lock) { items().sortedBy { it.deletedAtMs } }

    /** [resolve] maps the origin (root id, relative path) to a guarded real path again; the guard applies on undo too. */
    fun restore(token: UndoToken, resolve: (String, String) -> Guarded?): TrashRestore = synchronized(lock) {
        if (!ID.matches(token.id)) return TrashRestore.Unknown
        val slot = File(dir, token.id)
        val item = readMeta(slot) ?: return TrashRestore.Unknown
        val target = when (val g = resolve(item.rootId, item.rel)) {
            null -> return TrashRestore.Refused("the root '${item.rootId}' is no longer in the workspace")
            is Guarded.Escape -> return TrashRestore.Refused(g.reason)
            is Guarded.Ok -> g.path
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return TrashRestore.OriginOccupied(item.rootId, item.rel)
        try {
            target.parent?.let { Files.createDirectories(it) }
            moveTree(File(slot, "payload").toPath(), target)
            slot.deleteRecursively()
        } catch (e: IOException) {
            return TrashRestore.Failed(e.javaClass.simpleName)
        }
        TrashRestore.Ok(item.rootId, item.rel)
    }

    fun purge(): Int = synchronized(lock) { purgeLocked(clock()) }

    private fun purgeLocked(now: Long): Int {
        var removed = 0
        var all = items().sortedBy { it.deletedAtMs }.toMutableList()
        fun drop(i: TrashItem) { File(dir, i.token.id).deleteRecursively(); all.remove(i); removed++ }
        all.filter { now - it.deletedAtMs > maxAgeMs }.forEach { drop(it) }
        while (all.size > maxItems) drop(all.first())
        while (all.size > 1 && all.sumOf { it.bytes } > maxBytes) drop(all.first())
        // slots whose metadata is unreadable are debris from an interrupted put/restore
        dir.listFiles()?.filter { it.isDirectory && readMeta(it) == null && now - it.lastModified() > maxAgeMs }?.forEach { it.deleteRecursively(); removed++ }
        return removed
    }

    private fun items(): List<TrashItem> = dir.listFiles()?.filter { it.isDirectory }?.mapNotNull { readMeta(it) } ?: emptyList()

    private fun readMeta(slot: File): TrashItem? = try {
        val m = File(slot, "meta.json")
        if (!m.isFile || !File(slot, "payload").exists() && !Files.isSymbolicLink(File(slot, "payload").toPath())) null
        else JSONObject(m.readText()).let {
            TrashItem(UndoToken(slot.name), it.getString("rootId"), it.getString("rel"), it.getBoolean("isDir"), it.getLong("bytes"), it.getLong("at"))
        }
    } catch (_: Exception) { null }

    private class TooMany : Exception()

    private fun sizeOf(p: Path): Long {
        var total = 0L
        var n = 0
        Files.walkFileTree(p, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (++n > MAX_WALK) throw TooMany()
                total += attrs.size(); return FileVisitResult.CONTINUE
            }
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (++n > MAX_WALK) throw TooMany()
                return FileVisitResult.CONTINUE
            }
        })
        return total
    }

    companion object {
        private val ID = Regex("[0-9]+-[0-9]+")
        const val MAX_WALK = 1_000_000

        /** Rename when possible; across file systems copy then delete (links are copied as links, never followed). */
        fun moveTree(from: Path, to: Path) {
            try {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
                return
            } catch (_: AtomicMoveNotSupportedException) {
            } catch (_: java.nio.file.FileSystemException) {
                // cross-device or similar: fall back below
            }
            Files.walkFileTree(from, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.createDirectories(to.resolve(from.relativize(dir).toString())); return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val dest = to.resolve(from.relativize(file).toString())
                    if (attrs.isSymbolicLink) Files.createSymbolicLink(dest, Files.readSymbolicLink(file))
                    else Files.copy(file, dest, StandardCopyOption.COPY_ATTRIBUTES)
                    return FileVisitResult.CONTINUE
                }
            })
            deleteTree(from)
        }

        /** Deletes a tree without following symlinks (a link is removed, its target untouched). */
        fun deleteTree(p: Path) {
            Files.walkFileTree(p, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.delete(file); return FileVisitResult.CONTINUE }
                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult { Files.delete(dir); return FileVisitResult.CONTINUE }
            })
        }
    }
}
