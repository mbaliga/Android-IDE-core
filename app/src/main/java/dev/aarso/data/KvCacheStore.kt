package dev.aarso.data

import android.content.Context
import java.io.File

/**
 * Stores llama.cpp KV-cache session snapshots keyed by the node they were saved
 * at (handoff §8.3). Snapshotting at a node lets a later branch from it resume
 * without reprocessing the whole prefix. Lives in the cache dir — the OS may
 * reclaim it, and a missing snapshot simply means a full prefill (correct, slower).
 */
class KvCacheStore(context: Context) {
    private val dir: File = File(context.cacheDir, "kv").apply { mkdirs() }

    fun pathFor(nodeId: String): String = File(dir, "$nodeId.session").absolutePath
    fun exists(nodeId: String): Boolean = File(dir, "$nodeId.session").exists()
}
