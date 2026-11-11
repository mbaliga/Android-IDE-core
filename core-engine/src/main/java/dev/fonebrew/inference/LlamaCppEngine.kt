package dev.fonebrew.inference

import dev.fonebrew.domain.GeneratedToken
import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.SamplingParams
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

    private val slot = LoadedModelSlot(object : ModelNative {
        override fun load(modelPath: String, contextSize: Int): Long = nativeLoadModel(modelPath, contextSize)
        override fun free(handle: Long) = nativeFree(handle)
        override fun requestStop(handle: Long) = nativeRequestStop(handle)
    })

    override val tokenizerId: String
        get() = slot.path?.let { "llama.cpp:" + it.substringAfterLast('/') } ?: "llama.cpp:unloaded"
    override val supportsLogprobs: Boolean = true
    override val supportsSamplingParams: Boolean = true
    override val isLoaded: Boolean get() = slot.isLoaded

    // load/unload/generate are serialised by [slot]; see LoadedModelSlot for the rules.
    override suspend fun loadModel(modelPath: String, contextSize: Int) = withContext(Dispatchers.Default) {
        slot.load(modelPath, contextSize)
    }

    override suspend fun unload() = withContext(Dispatchers.Default) {
        slot.unload()
    }

    // Not behind the slot lock: callers count tokens while a reply is still streaming, and
    // generate() holds the lock for the whole stream. Reads a snapshot of the handle, as before.
    override suspend fun countTokens(text: String): Int = withContext(Dispatchers.Default) {
        val handle = slot.handle
        if (handle == 0L) 0 else nativeCountTokens(handle, text.toByteArray(Charsets.UTF_8))
    }

    override fun generate(
        messages: List<MessageNode>,
        params: SamplingParams,
        sessionLoadPath: String?,
        sessionSavePath: String?,
    ): Flow<GeneratedToken> = callbackFlow {
        // The slot lock is held for the whole stream so the model can't be freed or swapped
        // mid-decode; unload()/loadModel() ask the decode to stop, then take the lock.
        slot.withHandle { handle ->
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
                // Let the native call unwind before the lock (and so a free) is released.
                worker.join(NATIVE_UNWIND_WAIT_MS)
            }
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
        private const val NATIVE_UNWIND_WAIT_MS = 5_000L

        init {
            // The native build targets armv8.2-a+dotprod+i8mm+fp16 with no runtime dispatch, so a
            // CPU lacking one of those would die with SIGILL the moment the library runs. Refuse
            // to load it instead; EngineProvider surfaces the same reason to the UI.
            CpuFeatureCheck.ofThisDevice().requireSupported()
            System.loadLibrary("aarso_llama")
        }
    }
}
