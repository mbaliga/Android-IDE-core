package dev.aarso.data

import android.content.Context
import dev.aarso.domain.reliability.PartialReply
import java.io.File

/**
 * File-based checkpoints of a still-streaming assistant reply (daily-driver.md W3 — crash-safe
 * partial replies). One `<userNodeId>.partial.json` per in-flight turn under `filesDir/outbox/`
 * — `filesDir`, not `cacheDir` like [KvCacheStore]'s snapshots: a checkpoint must survive until
 * the next launch's recovery scan reads and deletes it, so it can't be something the OS is free
 * to reclaim under memory pressure while the app is backgrounded.
 *
 * This class is pure I/O — the actual encode/decode is [PartialReply] (org.json, JVM-testable)
 * and the recovery decision is [dev.aarso.domain.reliability.PartialReplyRecovery] (pure,
 * JVM-testable); this store is what [dev.aarso.ui.ChatViewModel] calls to read/write/delete the
 * files those two describe.
 */
class PartialReplyStore(context: Context) {
    private val dir: File = File(context.filesDir, "outbox").apply { mkdirs() }

    private fun fileFor(userNodeId: String): File = File(dir, "$userNodeId.partial.json")

    /** Writes (overwrites) the checkpoint for [reply]'s turn. Best-effort: a failed write just
     *  means the next periodic checkpoint or the eventual real insert wins instead — never
     *  worth failing the generation over. */
    fun write(reply: PartialReply) {
        runCatching { fileFor(reply.userNodeId).writeText(reply.encode()) }
    }

    /** Deletes the checkpoint for [userNodeId], if any. Safe to call even when none exists
     *  (the common case: most turns finish before ever writing a first checkpoint). */
    fun delete(userNodeId: String) {
        runCatching { fileFor(userNodeId).delete() }
    }

    /** Every checkpoint currently on disk, decoded — read once at `ChatViewModel` init to plan
     *  recovery. A file that fails to parse (including a torn write from the very crash that
     *  orphaned it) is deleted right here, immediately, rather than merely skipped: there is no
     *  [PartialReply] to hand `ChatViewModel`, so it would never reach the delete-after-recovery
     *  path in [dev.aarso.domain.reliability.PartialReplyRecovery]'s caller and would otherwise
     *  sit in `filesDir/outbox/` forever, re-read and re-skipped on every future launch. */
    fun readAll(): List<PartialReply> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".partial.json") }
            ?.mapNotNull { f ->
                val decoded = runCatching { f.readText() }.getOrNull()?.let(PartialReply::decode)
                if (decoded == null) runCatching { f.delete() }
                decoded
            }
            .orEmpty()
}
