package dev.fonebrew.domain.workspace.files

import dev.fonebrew.domain.workspace.roots.Guarded
import dev.fonebrew.domain.workspace.roots.PathParse
import dev.fonebrew.domain.workspace.roots.Workspace
import dev.fonebrew.domain.workspace.roots.WorkspacePath
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

enum class FileOpRefusal {
    PATH_ESCAPE, CROSS_ROOT, ALREADY_EXISTS, NOT_FOUND, IS_ROOT, INTO_SELF, INVALID_NAME,
    UNKNOWN_ROOT, TOO_LARGE_FOR_TRASH, WRONG_TYPE, TOO_MANY_ENTRIES,
}

sealed interface FileOpResult {
    /** [undo] is set by a trash delete; [note] carries honest side information (e.g. skipped symlinks). */
    data class Done(val path: WorkspacePath? = null, val undo: UndoToken? = null, val note: String? = null) : FileOpResult
    data class Refused(val reason: FileOpRefusal, val message: String) : FileOpResult
    data class Failed(val message: String) : FileOpResult
}

/**
 * Create / rename / move / copy / delete over a [Workspace], every path escape-guarded and every
 * outcome typed (nothing here throws for an expected condition). Rules:
 *  - delete moves to [trash] and returns an [UndoToken]; the only way to destroy outright is the
 *    explicit `permanent = true`, and an item too big for the trash is refused unless that flag is set;
 *  - nothing is overwritten unless `overwrite = true`, and a directory is never overwritten;
 *  - rename and move across roots are refused with [FileOpRefusal.CROSS_ROOT] (copy may cross);
 *  - moving or copying a directory into itself is refused;
 *  - links are operated on as links: delete/rename/move never touch a link's target, and a copied
 *    directory skips symlinks (counted in the result note) rather than duplicating an escape.
 */
class FileOperations(private val workspace: Workspace, private val trash: Trash) {

    fun createFile(path: WorkspacePath, content: ByteArray = ByteArray(0), overwrite: Boolean = false): FileOpResult =
        guarded { 
            if (path.isRoot) return@guarded refused(FileOpRefusal.IS_ROOT, "the root itself cannot be created")
            val p = real(path) ?: return@guarded escape(path)
            val exists = Files.exists(p, LinkOption.NOFOLLOW_LINKS)
            if (exists && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) return@guarded refused(FileOpRefusal.WRONG_TYPE, "'${path.rel}' is a directory")
            if (exists && !overwrite) return@guarded refused(FileOpRefusal.ALREADY_EXISTS, "'${path.rel}' exists; overwrite needs an explicit flag")
            AtomicFileWriter.write(p, content)
            FileOpResult.Done(path)
        }

    fun createDirectory(path: WorkspacePath): FileOpResult = guarded {
        if (path.isRoot) return@guarded refused(FileOpRefusal.IS_ROOT, "the root itself cannot be created")
        val p = real(path) ?: return@guarded escape(path)
        if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return@guarded refused(FileOpRefusal.ALREADY_EXISTS, "'${path.rel}' exists")
        Files.createDirectories(p)
        FileOpResult.Done(path)
    }

    fun rename(path: WorkspacePath, newName: String, overwrite: Boolean = false): FileOpResult {
        if (path.isRoot) return refused(FileOpRefusal.IS_ROOT, "a root is renamed through the workspace, not the file system")
        if (newName.isEmpty() || newName == "." || newName == ".." || newName.contains('/') || newName.contains('\u0000')) {
            return refused(FileOpRefusal.INVALID_NAME, "'$newName' is not a single file name")
        }
        val dest = path.parent!!.child(newName)
        return when (dest) {
            is PathParse.Ok -> move(path, dest.path, overwrite)
            is PathParse.Refused -> refused(FileOpRefusal.INVALID_NAME, "'$newName' is not a valid name")
        }
    }

    fun move(from: WorkspacePath, to: WorkspacePath, overwrite: Boolean = false): FileOpResult = guarded {
        if (from.rootId != to.rootId) return@guarded refused(FileOpRefusal.CROSS_ROOT, "a move cannot cross roots ('${from.rootId}' to '${to.rootId}'); copy then delete instead")
        if (from.isRoot || to.isRoot) return@guarded refused(FileOpRefusal.IS_ROOT, "a root cannot be moved")
        val src = real(from, follow = false) ?: return@guarded escape(from)
        val dst = real(to, follow = false) ?: return@guarded escape(to)
        if (!Files.exists(src, LinkOption.NOFOLLOW_LINKS)) return@guarded refused(FileOpRefusal.NOT_FOUND, "'${from.rel}' does not exist")
        if (to.rel.startsWith(from.rel + "/") || to.rel == from.rel) return@guarded refused(FileOpRefusal.INTO_SELF, "cannot move '${from.rel}' into itself")
        val dstExists = Files.exists(dst, LinkOption.NOFOLLOW_LINKS)
        val caseOnly = dstExists && from.rel.equals(to.rel, ignoreCase = true) && Files.isSameFile(src, dst)
        if (dstExists && !caseOnly) {
            if (Files.isDirectory(dst, LinkOption.NOFOLLOW_LINKS) || Files.isDirectory(src, LinkOption.NOFOLLOW_LINKS)) {
                return@guarded refused(FileOpRefusal.ALREADY_EXISTS, "'${to.rel}' exists; directories are never overwritten")
            }
            if (!overwrite) return@guarded refused(FileOpRefusal.ALREADY_EXISTS, "'${to.rel}' exists; overwrite needs an explicit flag")
        }
        dst.parent?.let { Files.createDirectories(it) }
        if (dstExists && !caseOnly) Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING) else Trash.moveTree(src, dst)
        FileOpResult.Done(to)
    }

    fun copy(from: WorkspacePath, to: WorkspacePath, overwrite: Boolean = false): FileOpResult = guarded {
        if (from.isRoot || to.isRoot) return@guarded refused(FileOpRefusal.IS_ROOT, "a root cannot be copied or overwritten")
        val src = real(from) ?: return@guarded escape(from)
        val dst = real(to, follow = false) ?: return@guarded escape(to)
        if (!Files.exists(src)) return@guarded refused(FileOpRefusal.NOT_FOUND, "'${from.rel}' does not exist")
        if (from.rootId == to.rootId && (to.rel == from.rel || to.rel.startsWith(from.rel + "/"))) {
            return@guarded refused(FileOpRefusal.INTO_SELF, "cannot copy '${from.rel}' into itself")
        }
        val dstExists = Files.exists(dst, LinkOption.NOFOLLOW_LINKS)
        if (dstExists) {
            if (Files.isDirectory(dst, LinkOption.NOFOLLOW_LINKS) || Files.isDirectory(src)) {
                return@guarded refused(FileOpRefusal.ALREADY_EXISTS, "'${to.rel}' exists; directories are never overwritten")
            }
            if (!overwrite) return@guarded refused(FileOpRefusal.ALREADY_EXISTS, "'${to.rel}' exists; overwrite needs an explicit flag")
        }
        dst.parent?.let { Files.createDirectories(it) }
        if (Files.isDirectory(src)) {
            var entries = 0
            var skipped = 0
            Files.walkFileTree(src, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (++entries > MAX_COPY_ENTRIES) throw TooManyEntries()
                    Files.createDirectories(dst.resolve(src.relativize(dir).toString())); return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (++entries > MAX_COPY_ENTRIES) throw TooManyEntries()
                    if (attrs.isSymbolicLink) { skipped++; return FileVisitResult.CONTINUE }
                    Files.copy(file, dst.resolve(src.relativize(file).toString()), StandardCopyOption.COPY_ATTRIBUTES)
                    return FileVisitResult.CONTINUE
                }
            })
            FileOpResult.Done(to, note = if (skipped > 0) "$skipped symlink(s) were not copied" else null)
        } else {
            val opts = if (dstExists) arrayOf(StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES) else arrayOf(StandardCopyOption.COPY_ATTRIBUTES)
            Files.copy(src, dst, *opts)
            FileOpResult.Done(to)
        }
    }

    /** Trash delete by default (undoable); `permanent = true` destroys outright and is the ONLY path that does. */
    fun delete(path: WorkspacePath, permanent: Boolean = false): FileOpResult = guarded {
        if (path.isRoot) return@guarded refused(FileOpRefusal.IS_ROOT, "a root is removed from the workspace, never deleted from disk")
        val p = real(path, follow = false) ?: return@guarded escape(path)
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return@guarded refused(FileOpRefusal.NOT_FOUND, "'${path.rel}' does not exist")
        if (permanent) {
            Trash.deleteTree(p)
            return@guarded FileOpResult.Done(path, note = "permanently deleted; not undoable")
        }
        when (val r = trash.put(path.rootId, path.rel, p)) {
            is TrashPut.Ok -> FileOpResult.Done(path, undo = r.token)
            is TrashPut.TooLarge -> refused(FileOpRefusal.TOO_LARGE_FOR_TRASH, "too large for the trash (${r.bytes} bytes, cap ${r.capBytes}); delete again with permanent = true to destroy it")
            is TrashPut.Failed -> FileOpResult.Failed("could not move to trash: ${r.message}")
        }
    }

    fun undoDelete(token: UndoToken): FileOpResult = guarded {
        when (val r = trash.restore(token) { rootId, rel ->
            when (val parsed = WorkspacePath.parse(rootId, rel)) {
                is PathParse.Ok -> workspace.resolve(parsed.path, followFinalSymlink = false)
                is PathParse.Refused -> Guarded.Escape("origin path refused")
            }
        }) {
            is TrashRestore.Ok -> FileOpResult.Done(WorkspacePath.of(r.rootId, r.rel))
            TrashRestore.Unknown -> refused(FileOpRefusal.NOT_FOUND, "that undo token is unknown or its item was purged")
            is TrashRestore.OriginOccupied -> refused(FileOpRefusal.ALREADY_EXISTS, "'${r.rel}' exists again; undo will not overwrite it")
            is TrashRestore.Refused -> refused(FileOpRefusal.UNKNOWN_ROOT, r.reason)
            is TrashRestore.Failed -> FileOpResult.Failed(r.message)
        }
    }

    private class TooManyEntries : RuntimeException()

    private fun real(p: WorkspacePath, follow: Boolean = true): Path? = when (val g = workspace.resolve(p, follow)) {
        is Guarded.Ok -> g.path
        is Guarded.Escape -> null
    }

    private fun escape(p: WorkspacePath) = when (val g = workspace.resolve(p)) {
        is Guarded.Escape -> if (g.reason.startsWith("unknown root")) refused(FileOpRefusal.UNKNOWN_ROOT, g.reason) else refused(FileOpRefusal.PATH_ESCAPE, g.reason)
        is Guarded.Ok -> refused(FileOpRefusal.PATH_ESCAPE, "path refused")
    }

    private fun refused(r: FileOpRefusal, m: String) = FileOpResult.Refused(r, m)

    private inline fun guarded(block: () -> FileOpResult): FileOpResult = try {
        block()
    } catch (e: TooManyEntries) {
        refused(FileOpRefusal.TOO_MANY_ENTRIES, "more than $MAX_COPY_ENTRIES entries; the copy was stopped (a partial copy may remain)")
    } catch (e: IOException) {
        FileOpResult.Failed(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
    }

    companion object {
        const val MAX_COPY_ENTRIES = 200_000
    }
}
