package dev.fonebrew.domain.workspace.tree

import dev.fonebrew.contracts.workspace.ResourceChangeKind
import java.io.Closeable
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Change detection for the directories a tree has expanded. Each watched directory is registered
 * with a `java.nio` [WatchService] when the platform offers one; a directory the service cannot
 * register (or a platform with none) is POLLED every [pollMs] and compared with [PollingDiffer].
 * Raw events pass through a [ChangeCoalescer] and are delivered to [sink] in batches once the
 * stream has been quiet for [debounceMs] (0 = deliver per batch immediately). One daemon thread;
 * [close] stops it. [sink] runs on that thread, so it must be quick or hand off.
 *
 * Non-recursive by design: the tree watches exactly its expanded directories. JVM-tested for
 * "a create/modify/delete inside a watched directory is eventually reported"; real delivery
 * latency and inotify limits on a phone are owner-verified.
 */
class DirectoryWatcher(
    private val debounceMs: Long = 150L,
    private val pollMs: Long = 2_000L,
    useWatchService: Boolean = true,
    private val sink: (List<FsEvent>) -> Unit,
) : Closeable {

    private class Polled(val path: Path, var last: Map<String, EntrySig>)

    private val service: WatchService? = if (!useWatchService) null else try { FileSystems.getDefault().newWatchService() } catch (_: Exception) { null }
    private val keys = ConcurrentHashMap<WatchKey, Pair<String, Path>>()
    private val polled = ConcurrentHashMap<String, Polled>()
    private val coalescer = ChangeCoalescer()
    private val closed = AtomicBoolean(false)
    private val thread = Thread(::loop, "workspace-dir-watcher").apply { isDaemon = true; start() }

    /** True if [dirRel] is being watched by the OS service, false if it fell back to polling. */
    fun watch(dirRel: String, dir: Path): Boolean {
        if (closed.get()) return false
        val svc = service
        if (svc != null) {
            try {
                val key = dir.register(
                    svc,
                    StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE,
                )
                keys[key] = dirRel to dir
                return true
            } catch (_: Exception) {
                // fall through to polling
            }
        }
        polled[dirRel] = Polled(dir, snapshot(dir))
        return false
    }

    fun unwatch(dirRel: String) {
        polled.remove(dirRel)
        keys.entries.removeIf { (k, v) -> if (v.first == dirRel) { k.cancel(); true } else false }
    }

    private fun loop() {
        var lastActivity = 0L
        var nextPoll = System.currentTimeMillis() + pollMs
        try {
            while (!closed.get()) {
                val wait = if (coalescer.hasPending) debounceMs.coerceAtLeast(1L) else minOf(200L, pollMs)
                val key = if (service != null) service.poll(wait, java.util.concurrent.TimeUnit.MILLISECONDS) else { Thread.sleep(wait); null }
                if (key != null) {
                    val meta = keys[key]
                    if (meta != null) {
                        for (ev in key.pollEvents()) {
                            val name = (ev.context() as? Path)?.toString() ?: continue
                            val kind = when (ev.kind()) {
                                StandardWatchEventKinds.ENTRY_CREATE -> ResourceChangeKind.CREATED
                                StandardWatchEventKinds.ENTRY_DELETE -> ResourceChangeKind.DELETED
                                else -> ResourceChangeKind.MODIFIED
                            }
                            coalescer.offer(FsEvent(meta.first, name, kind))
                            lastActivity = System.currentTimeMillis()
                        }
                    }
                    if (!key.reset()) keys.remove(key)
                }
                if (polled.isNotEmpty() && System.currentTimeMillis() >= nextPoll) {
                    nextPoll = System.currentTimeMillis() + pollMs
                    for ((rel, p) in polled) {
                        val cur = snapshot(p.path)
                        val changes = PollingDiffer.diff(rel, p.last, cur)
                        changes.forEach { coalescer.offer(it) }
                        p.last = cur
                        if (changes.isNotEmpty()) lastActivity = System.currentTimeMillis()
                    }
                }
                if (coalescer.hasPending && System.currentTimeMillis() - lastActivity >= debounceMs) {
                    val batch = coalescer.drain()
                    if (batch.isNotEmpty()) sink(batch)
                }
            }
        } catch (_: java.nio.file.ClosedWatchServiceException) {
        } catch (_: InterruptedException) {
        }
    }

    private fun snapshot(dir: Path): Map<String, EntrySig> {
        val m = HashMap<String, EntrySig>()
        try {
            Files.newDirectoryStream(dir).use { ds ->
                for (p in ds) {
                    if (m.size >= 20_000) break
                    try {
                        val a = Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        m[p.fileName.toString()] = EntrySig(a.isDirectory, a.size(), a.lastModifiedTime().toMillis())
                    } catch (_: java.io.IOException) { /* vanished */ }
                }
            }
        } catch (_: java.io.IOException) { /* directory gone: empty snapshot reports deletions */ }
        return m
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { service?.close() } catch (_: Exception) {}
        thread.interrupt()
    }
}
