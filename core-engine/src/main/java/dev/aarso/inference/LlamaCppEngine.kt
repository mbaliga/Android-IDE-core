package dev.aarso.inference

import dev.aarso.domain.GeneratedToken
import dev.aarso.domain.MessageNode
import dev.aarso.domain.SamplingParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

/**
 * llama.cpp-backed engine over JNI (handoff §3). Per-token log-probabilities and
 * entropy come straight from the decode loop — the reason llama.cpp was chosen
 * over Ollama, and what powers the §5a confidence colouring.
 *
 * One instance is shared across local models; [loadModel] is idempotent and swaps
 * the resident model when the path changes (one phone holds one model at a time).
 * Native calls run off the main thread.
 */
class LlamaCppEngine : InferenceEngine {

    @Volatile
    private var nativeHandle: Long = 0L

    @Volatile
    private var loadedModelPath: String? = null

    override val tokenizerId: String
        get() = loadedModelPath?.let { "llama.cpp:" + it.substringAfterLast('/') } ?: "llama.cpp:unloaded"
    override val supportsLogprobs: Boolean = true
    override val supportsSamplingParams: Boolean = true
    override val isLoaded: Boolean get() = nativeHandle != 0L

    override suspend fun loadModel(modelPath: String, contextSize: Int) = withContext(Dispatchers.Default) {
        if (nativeHandle != 0L && loadedModelPath == modelPath) return@withContext
        if (nativeHandle != 0L) {
            nativeFree(nativeHandle)
            nativeHandle = 0L
            loadedModelPath = null
        }
        val handle = nativeLoadModel(modelPath, contextSize)
        check(handle != 0L) { "Failed to load model: $modelPath" }
        nativeHandle = handle
        loadedModelPath = modelPath
    }

    override suspend fun unload() = withContext(Dispatchers.Default) {
        if (nativeHandle != 0L) {
            nativeFree(nativeHandle)
            nativeHandle = 0L
            loadedModelPath = null
        }
    }

    override suspend fun countTokens(text: String): Int = withContext(Dispatchers.Default) {
        val handle = nativeHandle
        if (handle == 0L) 0 else nativeCountTokens(handle, text.toByteArray(Charsets.UTF_8))
    }

    override fun generate(
        messages: List<MessageNode>,
        params: SamplingParams,
        sessionLoadPath: String?,
        sessionSavePath: String?,
    ): Flow<GeneratedToken> = callbackFlow {
        val handle = nativeHandle
        check(handle != 0L) { "No model loaded." }
        val roles = messages.map { it.role.wire }.toTypedArray()
        // Text crosses the JNI boundary as real UTF-8 bytes in both directions:
        // jstrings carry Modified UTF-8, which garbles emoji on the way in and
        // aborts the process (NewStringUTF) on the way out.
        val contents = messages.map { it.content.toByteArray(Charsets.UTF_8) }.toTypedArray()
        val sink = object : TokenSink {
            override fun onToken(utf8: ByteArray, logprob: Float, entropy: Float) {
                trySend(
                    GeneratedToken(
                        text = String(utf8, Charsets.UTF_8),
                        logprob = logprob.takeUnless { it.isNaN() },
                        entropy = entropy.takeUnless { it.isNaN() },
                    ),
                )
            }

            override fun onDone() {
                close()
            }

            override fun onError(message: String) {
                close(IllegalStateException(message))
            }
        }
        // Run the (blocking) decode loop on its own thread; its JNIEnv is valid
        // for the sink callbacks, and the Flow collector stays responsive.
        val worker = Thread {
            runCatching {
                nativeGenerate(handle, roles, contents, params.toNativeArgs(), sink, sessionLoadPath, sessionSavePath)
            }.onFailure { close(it) }
        }.apply { isDaemon = true; start() }

        awaitClose {
            nativeRequestStop(handle)
            worker.interrupt()
        }
    }

    private fun SamplingParams.toNativeArgs(): FloatArray =
        floatArrayOf(temperature, topP, topK.toFloat(), minP, repeatPenalty, maxTokens.toFloat())

    /**
     * Callback surface the native layer drives, one call per streamed chunk.
     * Token text arrives as UTF-8 bytes, already split on character boundaries
     * by the native side (a jstring can't carry emoji across JNI safely).
     */
    interface TokenSink {
        fun onToken(utf8: ByteArray, logprob: Float, entropy: Float)
        fun onDone()
        fun onError(message: String)
    }

    private external fun nativeLoadModel(modelPath: String, contextSize: Int): Long
    private external fun nativeFree(handle: Long)
    private external fun nativeCountTokens(handle: Long, utf8: ByteArray): Int
    private external fun nativeGenerate(
        handle: Long,
        roles: Array<String>,
        contents: Array<ByteArray>,
        samplingArgs: FloatArray,
        sink: TokenSink,
        sessionLoadPath: String?,
        sessionSavePath: String?,
    )
    private external fun nativeRequestStop(handle: Long)

    companion object {
        init {
            System.loadLibrary("aarso_llama")
        }
    }
}
