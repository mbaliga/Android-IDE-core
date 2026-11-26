package dev.fonebrew.embedding

import dev.fonebrew.inference.CpuFeatureCheck

/**
 * The native seam for embedding. Kotlin tests supply a fake; production uses [LlamaEmbedNative].
 * Status codes are shared with `llama_embed_jni.cpp` (keep the two in step).
 */
interface EmbedNative {
    /** Positive = handle; <= 0 = a [LoadCode] failure. */
    fun load(modelPath: String, contextSize: Int, threads: Int): Long

    /** Writes the L2-normalised vector into [out] (size must be >= dim). Returns an [EmbedCode]. */
    fun embed(handle: Long, utf8: ByteArray?, out: FloatArray?): Int

    fun dim(handle: Long): Int

    /** llama.cpp pooling type as the GGUF declared it (1 mean, 2 cls, 3 last). */
    fun pooling(handle: Long): Int

    fun unload(handle: Long)
}

/** Negative return values of [EmbedNative.load]. */
enum class LoadCode(val code: Long, val message: String) {
    BAD_ARGS(-1, "bad arguments"),
    FILE_MISSING(-2, "model file missing or unreadable"),
    MODEL_LOAD(-3, "llama.cpp could not load this model (not a supported embedding GGUF, or out of memory)"),
    CONTEXT(-4, "could not create an embedding context (out of memory?)"),
    POOLING_UNSUPPORTED(-5, "model declares no usable pooling (not an embedding model, or a reranker)"),
    INTERNAL(-6, "internal native error"),
    LIBRARY_UNAVAILABLE(-100, "native library is not available on this device"),
    ;

    companion object {
        fun of(code: Long): LoadCode = entries.firstOrNull { it.code == code } ?: INTERNAL
    }
}

/** Return values of [EmbedNative.embed]. */
enum class EmbedCode(val code: Int) {
    OK(0), OK_TRUNCATED(1),
    BAD_ARGS(-1), EMPTY(-2), TOO_LARGE(-3), TOKENIZE(-4), DECODE(-5), NON_FINITE(-6),
    INVALID_HANDLE(-7), INTERNAL(-8), OUTPUT_SIZE(-9),
    ;

    companion object {
        fun of(code: Int): EmbedCode = entries.firstOrNull { it.code == code } ?: INTERNAL
    }
}

/** Real JNI-backed implementation; loads `aarso_llama` (the same library as chat) on first use. */
class LlamaEmbedNative : EmbedNative {

    private val available: Boolean by lazy {
        try {
            CpuFeatureCheck.ofThisDevice().requireSupported()
            System.loadLibrary("aarso_llama")
            true
        } catch (_: Throwable) {
            false
        }
    }

    override fun load(modelPath: String, contextSize: Int, threads: Int): Long =
        if (!available) LoadCode.LIBRARY_UNAVAILABLE.code else nativeLoad(modelPath, contextSize, threads)

    override fun embed(handle: Long, utf8: ByteArray?, out: FloatArray?): Int =
        if (!available) EmbedCode.INVALID_HANDLE.code else nativeEmbed(handle, utf8, out)

    override fun dim(handle: Long): Int = if (!available) 0 else nativeDim(handle)
    override fun pooling(handle: Long): Int = if (!available) -1 else nativePooling(handle)
    override fun unload(handle: Long) {
        if (available) nativeUnload(handle)
    }

    private external fun nativeLoad(modelPath: String, contextSize: Int, threads: Int): Long
    private external fun nativeEmbed(handle: Long, utf8: ByteArray?, out: FloatArray?): Int
    private external fun nativeDim(handle: Long): Int
    private external fun nativePooling(handle: Long): Int
    private external fun nativeUnload(handle: Long)
}
