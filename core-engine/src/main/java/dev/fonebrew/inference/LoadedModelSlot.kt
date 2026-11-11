package dev.fonebrew.inference

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The three native calls the resident-model state machine needs; [LlamaCppEngine] binds them to JNI. */
internal interface ModelNative {
    /** Returns a non-zero handle, or 0 on failure (may also throw). */
    fun load(modelPath: String, contextSize: Int): Long
    fun free(handle: Long)

    /** Ask a running decode on [handle] to stop. Must be safe to call from any thread, without the slot lock. */
    fun requestStop(handle: Long)
}

/**
 * The one-resident-model state machine behind [LlamaCppEngine], kept free of JNI so the
 * concurrency rules are JVM-tested.
 *
 * Every transition (load, unload, and a whole generation via [withHandle]) runs under one
 * [Mutex], so:
 *  - two coroutines asking for the same model cannot both see "nothing loaded" and both load
 *    (the old check-then-act on a `@Volatile` handle);
 *  - a model is never freed under a running decode: [unload] and a swapping [load] first ask the
 *    decode to stop, then wait for the lock the decode holds;
 *  - a failed load leaves the slot cleanly unloaded (handle 0, no path), never half-swapped.
 *
 * [handle] and [path] are readable without the lock for cheap state queries only; do not use them
 * to decide whether to act.
 */
internal class LoadedModelSlot(private val native: ModelNative) {
    private val mutex = Mutex()

    @Volatile
    var handle: Long = 0L
        private set

    @Volatile
    var path: String? = null
        private set

    val isLoaded: Boolean get() = handle != 0L

    suspend fun load(modelPath: String, contextSize: Int) {
        // A different model is wanted: stop any running decode so the lock frees up promptly.
        // Same path is the idempotent no-op below and must not interrupt a generation.
        val resident = handle
        if (resident != 0L && path != modelPath) native.requestStop(resident)
        mutex.withLock {
            if (handle != 0L && path == modelPath) return
            freeResident()
            val fresh = native.load(modelPath, contextSize) // a throw leaves the slot unloaded
            check(fresh != 0L) { "Failed to load model: $modelPath" }
            handle = fresh
            path = modelPath
        }
    }

    suspend fun unload() {
        val resident = handle
        if (resident != 0L) native.requestStop(resident)
        mutex.withLock { freeResident() }
    }

    /**
     * Runs [block] with the resident handle while holding the lock, so the model cannot be
     * freed or swapped until [block] returns. Throws if nothing is loaded.
     */
    suspend fun <T> withHandle(block: suspend (handle: Long) -> T): T = mutex.withLock {
        val h = handle
        check(h != 0L) { "No model loaded." }
        block(h)
    }

    private fun freeResident() {
        val h = handle
        // Clear first: even if free() throws, the slot must not keep pointing at a dead handle.
        handle = 0L
        path = null
        if (h != 0L) native.free(h)
    }
}
