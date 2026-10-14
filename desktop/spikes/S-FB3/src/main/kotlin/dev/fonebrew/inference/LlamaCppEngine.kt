package dev.fonebrew.inference

/**
 * SPIKE BINDING CLASS, not core's LlamaCppEngine. It declares the same five `external` functions,
 * with the same types, in the same package and class name as
 * core-engine/src/main/java/dev/fonebrew/inference/LlamaCppEngine.kt, because JNI resolves
 * `Java_dev_fonebrew_inference_LlamaCppEngine_*` by exactly that name. `TokenSink` is the same
 * shape too (the shim looks up onToken/onDone/onError by name + descriptor).
 * The loader is the same call core makes: System.loadLibrary("aarso_llama").
 */
class LlamaCppEngine {

    interface TokenSink {
        fun onToken(utf8: ByteArray, logprob: Float, entropy: Float)
        fun onDone()
        fun onError(message: String)
    }

    fun loadModel(path: String, contextSize: Int): Long = nativeLoadModel(path, contextSize)
    fun free(handle: Long) = nativeFree(handle)
    fun countTokens(handle: Long, text: String): Int = nativeCountTokens(handle, text.toByteArray(Charsets.UTF_8))
    fun requestStop(handle: Long) = nativeRequestStop(handle)
    fun generate(handle: Long, sink: TokenSink) = nativeGenerate(
        handle, arrayOf("user"), arrayOf("hi".toByteArray()), floatArrayOf(0.8f, 0.95f, 40f, 0.05f, 1.1f, 8f), sink, null, null,
    )

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
