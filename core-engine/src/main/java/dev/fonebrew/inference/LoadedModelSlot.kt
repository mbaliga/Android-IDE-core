package dev.fonebrew.inference

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** The three native calls the resident-model state machine needs; [LlamaCppEngine] binds them to JNI. */
internal interface ModelNative {
    /** Returns a non-zero handle, or 0 on failure (may also throw). */
    fun load(modelPath: String, contextSize: Int): Long
    fun free(handle: Long)

    /** Ask a running decode on [handle] to stop. Must be safe to call from any thread, without the slot lock. */
    fun requestStop(handle: Long)

    /** Token count of [utf8] under [handle]. Only ever called while [handle] is guaranteed live. */
    fun countTokens(handle: Long, utf8: ByteArray): Int
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
 * [countTokens] must work while a reply streams (the generate lock is held for the whole stream),
 * so it does not use the mutex. It takes the read side of [freeGuard] and re-reads the handle
 * inside it; [freeResident] takes the write side around clear-and-free. A free therefore waits for
 * an in-flight count, and a count that starts after the free sees 0 — never a freed handle. Because
 * it never touches the mutex it cannot deadlock against [withHandle], even when called from inside
 * a generation's own collector.
 *
 * [handle] and [path] are readable without the lock for cheap state queries only; do not use them
 * to decide whether to act.
 */
internal class LoadedModelSlot(private val native: ModelNative) {
    private val mutex = Mutex()
    private val freeGuard = ReentrantReadWriteLock()

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

    /** Blocking, brief (a tokenize call). 0 when nothing is loaded, as before. */
    fun countTokens(utf8: ByteArray): Int = freeGuard.read {
        val h = handle
        if (h == 0L) 0 else native.countTokens(h, utf8)
    }

    private fun freeResident() = freeGuard.write {
        val h = handle
        // Clear first: even if free() throws, the slot must not keep pointing at a dead handle.
        handle = 0L
        path = null
        if (h != 0L) native.free(h)
    }
}
