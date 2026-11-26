package dev.fonebrew.domain.workspace.roots

import dev.fonebrew.contracts.workspace.ResourceChange
import dev.fonebrew.contracts.workspace.ResourceChangeKind
import dev.fonebrew.contracts.workspace.ResourcePage
import dev.fonebrew.contracts.workspace.ResourceProvider
import dev.fonebrew.contracts.workspace.ResourceRead
import dev.fonebrew.contracts.workspace.ResourceStat
import dev.fonebrew.contracts.workspace.ResourceUri
import dev.fonebrew.contracts.workspace.ResourceWrite
import dev.fonebrew.contracts.workspace.WorkspaceProvider
import dev.fonebrew.domain.contracts.Digest
import dev.fonebrew.domain.workspace.files.AtomicFileWriter
import dev.fonebrew.domain.workspace.tree.DirectoryWatcher
import dev.fonebrew.domain.workspace.tree.NaturalOrder
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant

/** A path the guard refused; an [IllegalArgumentException] like the parked provider's `require`s. */
class PathEscapeException(message: String) : IllegalArgumentException(message)

/**
 * The java.io member of the [WorkspaceProvider] family for ONE workspace root, with the path and
 * symlink escape guard the parked [dev.fonebrew.domain.workspace.LocalWorkspaceProvider] lacks
 * (that class is left as it was). URIs are `local://<relative>` as in the parked class; revisions
 * are SHA-256 content digests (FB-RAT-WS-004), computed on [stat]/[read]/[write] but NOT on
 * [list], where a null revision means "not computed" (listing must not hash every file).
 * Writes are atomic temp+rename and conflict-checked against `expectedRevision`.
 */
class LocalFileProvider(rootDir: File) : WorkspaceProvider, DirectoryReader {

    val guard = PathGuard(rootDir)
    val rootPath: Path get() = guard.realRoot

    private fun relOf(uri: ResourceUri): String {
        require(uri.provider == ResourceProvider.LOCAL) { "LocalFileProvider only serves LOCAL, got ${uri.provider}." }
        val raw = uri.raw.removePrefix("local://")
        return when (val p = WorkspacePath.parse("_", raw)) {
            is PathParse.Ok -> p.path.rel
            is PathParse.Refused -> throw PathEscapeException("path refused: ${p.reason}")
        }
    }

    private fun resolved(rel: String, follow: Boolean = true): Path = when (val g = guard.resolve(rel, follow)) {
        is Guarded.Ok -> g.path
        is Guarded.Escape -> throw PathEscapeException(g.reason)
    }

    private fun uriOf(rel: String) = ResourceUri(ResourceProvider.LOCAL, "local://$rel", displayPath = rel)

    override suspend fun stat(uri: ResourceUri): ResourceStat {
        val rel = relOf(uri)
        val p = resolved(rel)
        if (!Files.exists(p)) return ResourceStat(uri, false, null, null, null, false)
        val isFile = Files.isRegularFile(p)
        return ResourceStat(
            uri = uri, exists = true,
            revision = if (isFile && Files.size(p) <= MAX_READ_BYTES) Digest.of(Files.readAllBytes(p)).digestHex else null,
            sizeBytes = if (isFile) Files.size(p) else null,
            lastModifiedUtc = Instant.ofEpochMilli(Files.getLastModifiedTime(p).toMillis()),
            isDirectory = Files.isDirectory(p),
        )
    }

    override suspend fun read(uri: ResourceUri, expectedRevision: String?): ResourceRead {
        val p = resolved(relOf(uri))
        require(Files.isRegularFile(p)) { "LocalFileProvider.read: '${uri.raw}' is not a regular file." }
        require(Files.size(p) <= MAX_READ_BYTES) { "LocalFileProvider.read: '${uri.raw}' exceeds the $MAX_READ_BYTES byte read cap." }
        val bytes = Files.readAllBytes(p)
        val revision = Digest.of(bytes).digestHex
        if (expectedRevision != null) {
            require(expectedRevision == revision) { "LocalFileProvider.read: revision mismatch for '${uri.raw}'." }
        }
        return ResourceRead(uri, revision, bytes, StandardCharsets.UTF_8.name())
    }

    override suspend fun write(uri: ResourceUri, bytes: ByteArray, expectedRevision: String?): ResourceWrite {
        val p = resolved(relOf(uri))
        if (expectedRevision != null && Files.isRegularFile(p)) {
            val actualBytes = Files.readAllBytes(p)
            val actual = Digest.of(actualBytes)
            if (actual.digestHex != expectedRevision) {
                return ResourceWrite.Conflict(uri, expectedRevision, actual.digestHex, actual)
            }
        }
        require(!Files.isDirectory(p)) { "LocalFileProvider.write: '${uri.raw}' is a directory." }
        AtomicFileWriter.write(p, bytes)
        val d = Digest.of(bytes)
        return ResourceWrite.Success(uri, d.digestHex, d)
    }

    override fun watch(scope: ResourceUri): Flow<ResourceChange> = callbackFlow {
        val rel = relOf(scope)
        val dir = resolved(rel)
        require(Files.isDirectory(dir)) { "LocalFileProvider.watch: '${scope.raw}' is not a directory." }
        val watcher = DirectoryWatcher(debounceMs = 0L) { events ->
            for (e in events) {
                val child = if (rel.isEmpty()) e.name else "$rel/${e.name}"
                trySend(ResourceChange(uriOf(child), e.kind, null, Instant.now()))
            }
        }
        watcher.watch(rel, dir)
        awaitClose { watcher.close() }
    }

    override suspend fun list(scope: ResourceUri, cursor: String?): ResourcePage {
        val rel = relOf(scope)
        val read = listDir(rel, MAX_SCAN)
        require(read is DirRead.Ok) { "LocalFileProvider.list: '${scope.raw}' is not a readable directory ($read)." }
        val sorted = read.entries.sortedWith(NaturalOrder.entries)
        val offset = cursor?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val page = sorted.drop(offset).take(PAGE)
        val items = page.map { e ->
            ResourceStat(
                uri = uriOf(if (rel.isEmpty()) e.name else "$rel/${e.name}"), exists = true, revision = null,
                sizeBytes = if (!e.isDir) e.sizeBytes else null,
                lastModifiedUtc = Instant.ofEpochMilli(e.lastModifiedMs), isDirectory = e.isDir,
            )
        }
        val next = offset + page.size
        return ResourcePage(items, if (next < sorted.size) next.toString() else null)
    }

    override fun listDir(dirRel: String, maxScan: Int): DirRead {
        val dir = when (val g = guard.resolve(dirRel)) {
            is Guarded.Ok -> g.path
            is Guarded.Escape -> return DirRead.Refused(g.reason)
        }
        if (!Files.exists(dir)) return DirRead.Missing
        if (!Files.isDirectory(dir)) return DirRead.NotDirectory
        val out = ArrayList<DirEntry>()
        var truncated = false
        try {
            Files.newDirectoryStream(dir).use { ds ->
                for (p in ds) {
                    if (out.size >= maxScan) { truncated = true; break }
                    entryOf(p)?.let { out.add(it) }
                }
            }
        } catch (e: java.io.IOException) {
            return DirRead.Unreadable(e.javaClass.simpleName)
        } catch (e: java.nio.file.DirectoryIteratorException) {
            return DirRead.Unreadable("directory changed while reading")
        }
        return DirRead.Ok(out, truncated)
    }

    private fun entryOf(p: Path): DirEntry? {
        val attrs = try {
            Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return null // vanished between the directory read and the stat: skip, never throw
        } catch (_: java.io.IOException) {
            return null
        }
        val name = p.fileName.toString()
        if (!attrs.isSymbolicLink) {
            return DirEntry(name, attrs.isDirectory, false, attrs.size(), attrs.lastModifiedTime().toMillis())
        }
        val real = try { p.toRealPath() } catch (_: Exception) { null }
        val escapes = real == null || !real.startsWith(guard.realRoot)
        val isDir = !escapes && Files.isDirectory(real!!)
        return DirEntry(name, isDir, true, attrs.size(), attrs.lastModifiedTime().toMillis(), linkEscapes = escapes)
    }

    companion object {
        const val MAX_READ_BYTES = 64L * 1024 * 1024
        const val MAX_SCAN = 20_000
        const val PAGE = 200
    }
}
