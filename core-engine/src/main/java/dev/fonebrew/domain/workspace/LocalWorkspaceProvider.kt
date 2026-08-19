package dev.fonebrew.domain.workspace

import dev.fonebrew.contracts.workspace.ResourceChange
import dev.fonebrew.contracts.workspace.ResourceChangeKind
import dev.fonebrew.contracts.workspace.ResourcePage
import dev.fonebrew.contracts.workspace.ResourceProvider
import dev.fonebrew.contracts.workspace.ResourceRead
import dev.fonebrew.contracts.workspace.ResourceStat
import dev.fonebrew.contracts.workspace.ResourceUri
import dev.fonebrew.contracts.workspace.ResourceWrite
import dev.fonebrew.domain.contracts.Digest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.time.Instant

/**
 * The LOCAL member of the five-provider [ResourceProvider] vocabulary (WORKSPACE_KERNEL_SPEC.md
 * §2.3), backed directly by the on-device filesystem under [root] -- greenfield per WP0_SURVEY.md
 * §1(b) (this domain has no prior art anywhere in the constellation). SAF/SSH/SNAPSHOT/VIRTUAL are
 * out of scope for WP-3 (its brief names the local provider only; SSH is WP-5's job, reusing the
 * already-real sshj lane at `domain/remote/`).
 *
 * Revision semantics (FB-RAT-WS-004): `expectedRevision`/the returned revision is the file's
 * content digest (SHA-256 hex via [dev.fonebrew.domain.contracts.Digest]), not an mtime -- two writes
 * landing within the same filesystem timestamp granularity must not be able to defeat the
 * conflict check the way an mtime-only revision could.
 */
class LocalWorkspaceProvider(private val root: File) : dev.fonebrew.contracts.workspace.WorkspaceProvider {

    private fun toFile(uri: ResourceUri): File {
        require(uri.provider == ResourceProvider.LOCAL) {
            "LocalWorkspaceProvider only serves ResourceProvider.LOCAL, got ${uri.provider}."
        }
        val relative = uri.raw.removePrefix("local://")
        return File(root, relative)
    }

    private fun toUri(file: File): ResourceUri {
        val relative = file.relativeTo(root).path.replace(File.separatorChar, '/')
        return ResourceUri(ResourceProvider.LOCAL, "local://$relative", displayPath = relative)
    }

    private fun revisionOf(file: File): String = Digest.of(file.readBytes()).digestHex

    override suspend fun stat(uri: ResourceUri): ResourceStat {
        val file = toFile(uri)
        return if (!file.exists()) {
            ResourceStat(uri, exists = false, revision = null, sizeBytes = null, lastModifiedUtc = null, isDirectory = false)
        } else {
            ResourceStat(
                uri = uri,
                exists = true,
                revision = if (file.isFile) revisionOf(file) else null,
                sizeBytes = if (file.isFile) file.length() else null,
                lastModifiedUtc = Instant.ofEpochMilli(file.lastModified()),
                isDirectory = file.isDirectory
            )
        }
    }

    override suspend fun read(uri: ResourceUri, expectedRevision: String?): ResourceRead {
        val file = toFile(uri)
        require(file.isFile) { "LocalWorkspaceProvider.read: '${uri.raw}' is not a regular file." }
        val bytes = file.readBytes()
        val revision = Digest.of(bytes).digestHex
        if (expectedRevision != null) {
            require(expectedRevision == revision) {
                "LocalWorkspaceProvider.read: revision mismatch for '${uri.raw}' " +
                    "(expected=$expectedRevision, actual=$revision) -- caller should stat() first, " +
                    "not assume a stale read is still current."
            }
        }
        return ResourceRead(uri, revision, bytes, encoding = StandardCharsets.UTF_8.name())
    }

    /**
     * FB-RAT-WS-004: the conflict check. A `null` [expectedRevision] means "unconditional write"
     * (e.g. a brand-new file where there is nothing to conflict with) and always succeeds; a
     * non-null one that no longer matches the file's current on-disk revision returns
     * [ResourceWrite.Conflict] instead of silently overwriting.
     */
    override suspend fun write(uri: ResourceUri, bytes: ByteArray, expectedRevision: String?): ResourceWrite {
        val file = toFile(uri)
        if (expectedRevision != null && file.exists()) {
            val actual = revisionOf(file)
            if (actual != expectedRevision) {
                return ResourceWrite.Conflict(
                    uri = uri,
                    expectedRevision = expectedRevision,
                    actualRevision = actual,
                    remoteDigest = Digest.of(file.readBytes())
                )
            }
        }
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        val digest = Digest.of(bytes)
        return ResourceWrite.Success(uri, newRevision = digest.digestHex, digest = digest)
    }

    /**
     * Real OS-level filesystem events via [java.nio.file.WatchService] -- the one place this
     * provider genuinely needs `Flow` (the single named exception to the domain's toolchain
     * constraint, per `WorkspaceProvider.watch()`'s doc comment). Owner-verified for real delivery
     * timing/coalescing on-device; JVM-tested only for "an event fired after a write inside
     * [scope] is observed," per this repo's environment-honesty rule.
     */
    override fun watch(scope: ResourceUri): Flow<ResourceChange> = callbackFlow {
        val dir = toFile(scope)
        require(dir.isDirectory) { "LocalWorkspaceProvider.watch: '${scope.raw}' is not a directory." }
        val watchService = FileSystems.getDefault().newWatchService()
        val watchedPath: Path = dir.toPath()
        watchedPath.register(
            watchService,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_DELETE
        )
        val thread = Thread {
            try {
                while (true) {
                    val key = watchService.take()
                    for (event in key.pollEvents()) {
                        @Suppress("UNCHECKED_CAST")
                        val ev = event as WatchEvent<Path>
                        val changedFile = watchedPath.resolve(ev.context()).toFile()
                        val kind = when (event.kind()) {
                            StandardWatchEventKinds.ENTRY_CREATE -> ResourceChangeKind.CREATED
                            StandardWatchEventKinds.ENTRY_DELETE -> ResourceChangeKind.DELETED
                            else -> ResourceChangeKind.MODIFIED
                        }
                        val newRevision = if (kind != ResourceChangeKind.DELETED && changedFile.isFile) {
                            revisionOf(changedFile)
                        } else null
                        trySend(ResourceChange(toUri(changedFile), kind, newRevision, Instant.now()))
                    }
                    if (!key.reset()) break
                }
            } catch (_: ClosedWatchServiceException) {
                // watchService.close() below -- expected on cancellation, not an error.
            } catch (_: InterruptedException) {
                // Same: cooperative shutdown, not an error.
            }
        }
        thread.isDaemon = true
        thread.start()
        awaitClose { watchService.close() }
    }

    override suspend fun list(scope: ResourceUri, cursor: String?): ResourcePage {
        val dir = toFile(scope)
        require(dir.isDirectory) { "LocalWorkspaceProvider.list: '${scope.raw}' is not a directory." }
        val entries = (dir.listFiles() ?: emptyArray()).sortedBy { it.name }
        val offset = cursor?.toIntOrNull() ?: 0
        val pageSize = 200
        val page = entries.drop(offset).take(pageSize)
        val stats = page.map { f ->
            ResourceStat(
                uri = toUri(f),
                exists = true,
                revision = if (f.isFile) revisionOf(f) else null,
                sizeBytes = if (f.isFile) f.length() else null,
                lastModifiedUtc = Instant.ofEpochMilli(f.lastModified()),
                isDirectory = f.isDirectory
            )
        }
        val nextOffset = offset + page.size
        return ResourcePage(stats, nextCursor = if (nextOffset < entries.size) nextOffset.toString() else null)
    }
}
