package dev.fonebrew.domain.gitcore

import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

enum class FileKind { FILE, DIRECTORY, SYMLINK, OTHER }

/** An lstat result: never follows a symlink. [ino]/[dev] are 0 where the platform has none. */
data class FileStat(
    val kind: FileKind,
    val size: Long,
    val mtimeSeconds: Long,
    val mtimeNanos: Int,
    val ctimeSeconds: Long,
    val ctimeNanos: Int,
    val dev: Long,
    val ino: Long,
    val uid: Long,
    val gid: Long,
    val executable: Boolean,
)

/** Random-access read handle (pack files). */
interface RandomAccessSource : AutoCloseable {
    val length: Long
    /** Reads up to [len] bytes at [offset]; returns the count read (0 at/after EOF). */
    fun read(offset: Long, buf: ByteArray, bufOffset: Int, len: Int): Int
}

/**
 * The only door the engine uses to touch storage. Paths are absolute strings using `/`.
 * Everything that matters for safety (lstat semantics, no-follow, exclusive create, atomic
 * replace) is spelled out here so another platform implementation must honour the same contract.
 */
interface FileSystemPort {
    /** lstat; null when the path does not exist. Never follows a final symlink. */
    fun stat(path: String): FileStat?
    fun readBytes(path: String, maxBytes: Long): ByteArray
    fun openRead(path: String): InputStream
    /** Creates/truncates [path] (parents created) for streaming writes; not atomic - callers write a temp name then [rename]. */
    fun openWrite(path: String): java.io.OutputStream
    fun openRandomAccess(path: String): RandomAccessSource
    /** Writes via temp file + rename in the same directory; creates parents. Replaces a symlink leaf, never follows it. */
    fun writeAtomic(path: String, bytes: ByteArray, executable: Boolean = false)
    /** Creates the file only if absent (lock files). False when it already exists. */
    fun createExclusive(path: String, bytes: ByteArray): Boolean
    fun mkdirs(path: String)
    fun list(path: String): List<String>
    fun delete(path: String): Boolean
    fun deleteRecursively(path: String)
    fun rename(from: String, to: String)
    fun readSymlink(path: String): String?
    fun createSymlink(path: String, target: String)
    fun setExecutable(path: String, executable: Boolean)
    /** Sets mtime (used so checked-out files carry a stable stat). */
    fun setMtime(path: String, seconds: Long, nanos: Int)
}

/** [FileSystemPort] over java.nio.file. */
class JvmFileSystem : FileSystemPort {
    private fun p(path: String): Path = Paths.get(path)
    private val nofollow = arrayOf(LinkOption.NOFOLLOW_LINKS)

    override fun stat(path: String): FileStat? {
        val pp = p(path)
        return try {
            val attrs: Map<String, Any?> = try {
                Files.readAttributes(pp, "unix:*", *nofollow)
            } catch (_: UnsupportedOperationException) {
                Files.readAttributes(pp, "*", *nofollow)
            }
            val basic = Files.readAttributes(pp, java.nio.file.attribute.BasicFileAttributes::class.java, *nofollow)
            val kind = when {
                basic.isSymbolicLink -> FileKind.SYMLINK
                basic.isDirectory -> FileKind.DIRECTORY
                basic.isRegularFile -> FileKind.FILE
                else -> FileKind.OTHER
            }
            val m = basic.lastModifiedTime().toInstant()
            val c = (attrs["ctime"] as? java.nio.file.attribute.FileTime)?.toInstant() ?: m
            val mode = (attrs["mode"] as? Int) ?: 0
            FileStat(
                kind, basic.size(), m.epochSecond, m.nano, c.epochSecond, c.nano,
                (attrs["dev"] as? Long) ?: 0L, (attrs["ino"] as? Long) ?: 0L,
                ((attrs["uid"] as? Int) ?: 0).toLong(), ((attrs["gid"] as? Int) ?: 0).toLong(),
                kind == FileKind.FILE && (mode and 0b001_000_000) != 0,
            )
        } catch (_: NoSuchFileException) {
            null
        } catch (e: java.nio.file.FileSystemException) {
            // A file where a directory is expected ("Not a directory") means the path does not exist.
            if (e.reason?.contains("Not a directory", ignoreCase = true) == true) null
            else throw GitException(GitErrorKind.IO, "stat failed: ${e.javaClass.simpleName}", cause = e)
        } catch (e: IOException) {
            throw GitException(GitErrorKind.IO, "stat failed: ${e.javaClass.simpleName}", cause = e)
        }
    }

    override fun readBytes(path: String, maxBytes: Long): ByteArray {
        try {
            Files.newInputStream(p(path), StandardOpenOption.READ).use { ins ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) limit("file exceeds read cap of $maxBytes bytes")
                    out.write(buf, 0, n)
                }
                return out.toByteArray()
            }
        } catch (_: NoSuchFileException) {
            throw GitException(GitErrorKind.NOT_FOUND, "no such file")
        } catch (e: IOException) {
            throw GitException(GitErrorKind.IO, "read failed: ${e.javaClass.simpleName}", cause = e)
        }
    }

    override fun openRead(path: String): InputStream = try {
        Files.newInputStream(p(path), StandardOpenOption.READ)
    } catch (_: NoSuchFileException) {
        throw GitException(GitErrorKind.NOT_FOUND, "no such file")
    } catch (e: IOException) {
        throw GitException(GitErrorKind.IO, "open failed: ${e.javaClass.simpleName}", cause = e)
    }

    override fun openWrite(path: String): java.io.OutputStream = try {
        p(path).parent?.let { Files.createDirectories(it) }
        Files.newOutputStream(p(path), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
    } catch (e: IOException) {
        throw GitException(GitErrorKind.IO, "open for write failed: ${e.javaClass.simpleName}", cause = e)
    }

    override fun openRandomAccess(path: String): RandomAccessSource {
        val raf = try { RandomAccessFile(path, "r") } catch (e: IOException) {
            throw GitException(GitErrorKind.NOT_FOUND, "cannot open ${p(path).fileName}", cause = e)
        }
        return object : RandomAccessSource {
            override val length: Long get() = raf.length()
            override fun read(offset: Long, buf: ByteArray, bufOffset: Int, len: Int): Int {
                raf.seek(offset)
                var done = 0
                while (done < len) {
                    val n = raf.read(buf, bufOffset + done, len - done)
                    if (n < 0) break
                    done += n
                }
                return done
            }
            override fun close() = raf.close()
        }
    }

    override fun writeAtomic(path: String, bytes: ByteArray, executable: Boolean) {
        val target = p(path)
        try {
            target.parent?.let { Files.createDirectories(it) }
            val tmp = Files.createTempFile(target.parent, ".tmp-", ".part")
            try {
                Files.write(tmp, bytes)
                setExec(tmp, executable)
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(tmp)
            }
        } catch (e: IOException) {
            throw GitException(GitErrorKind.IO, "write failed: ${e.javaClass.simpleName}", cause = e)
        }
    }

    override fun createExclusive(path: String, bytes: ByteArray): Boolean = try {
        val target = p(path)
        target.parent?.let { Files.createDirectories(it) }
        Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        true
    } catch (_: FileAlreadyExistsException) {
        false
    } catch (e: IOException) {
        throw GitException(GitErrorKind.IO, "create failed: ${e.javaClass.simpleName}", cause = e)
    }

    override fun mkdirs(path: String) {
        try { Files.createDirectories(p(path)) } catch (e: IOException) {
            throw GitException(GitErrorKind.IO, "mkdir failed: ${e.javaClass.simpleName}", cause = e)
        }
    }

    override fun list(path: String): List<String> = try {
        Files.newDirectoryStream(p(path)).use { ds -> ds.map { it.fileName.toString() }.sorted() }
    } catch (_: NoSuchFileException) {
        emptyList()
    } catch (e: IOException) {
        throw GitException(GitErrorKind.IO, "list failed: ${e.javaClass.simpleName}", cause = e)
    }

    override fun delete(path: String): Boolean = try { Files.deleteIfExists(p(path)) } catch (e: IOException) {
        throw GitException(GitErrorKind.IO, "delete failed: ${e.javaClass.simpleName}", cause = e)
    }

    override fun deleteRecursively(path: String) {
        val root = p(path)
        val st = stat(path) ?: return
        if (st.kind == FileKind.DIRECTORY) {
            for (n in list(path)) deleteRecursively("$path/$n")
        }
        Files.deleteIfExists(root)
    }

    override fun rename(from: String, to: String) {
        try {
            Files.createDirectories(p(to).parent)
            try {
                Files.move(p(from), p(to), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(p(from), p(to), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            throw GitException(GitErrorKind.IO, "rename failed: ${e.javaClass.simpleName}", cause = e)
        }
    }

    override fun readSymlink(path: String): String? = try {
        Files.readSymbolicLink(p(path)).toString()
    } catch (_: IOException) {
        null
    } catch (_: UnsupportedOperationException) {
        null
    }

    override fun createSymlink(path: String, target: String) {
        try {
            val l = p(path)
            l.parent?.let { Files.createDirectories(it) }
            Files.deleteIfExists(l)
            Files.createSymbolicLink(l, Paths.get(target))
        } catch (e: IOException) {
            throw GitException(GitErrorKind.IO, "symlink failed: ${e.javaClass.simpleName}", cause = e)
        } catch (e: UnsupportedOperationException) {
            throw GitException(GitErrorKind.UNSUPPORTED, "symlinks unsupported here", cause = e)
        }
    }

    override fun setExecutable(path: String, executable: Boolean) = setExec(p(path), executable)

    private fun setExec(pp: Path, exec: Boolean) {
        try {
            // Same bits git would create under a 022 umask: rw-r--r-- or rwxr-xr-x.
            Files.setPosixFilePermissions(pp, PosixFilePermissions.fromString(if (exec) "rwxr-xr-x" else "rw-r--r--"))
        } catch (_: UnsupportedOperationException) {
        } catch (e: IOException) {
            throw GitException(GitErrorKind.IO, "chmod failed: ${e.javaClass.simpleName}", cause = e)
        }
    }

    override fun setMtime(path: String, seconds: Long, nanos: Int) {
        try {
            Files.setLastModifiedTime(p(path), java.nio.file.attribute.FileTime.from(java.time.Instant.ofEpochSecond(seconds, nanos.toLong())))
        } catch (_: IOException) {
        }
    }
}
