package dev.fonebrew.inference.image

/**
 * SPIKE BINDING CLASS, not core's SdImageEngine: same package, class name and `external` signatures
 * as sdengine's JNI symbols (Java_dev_fonebrew_inference_image_SdImageEngine_*), same loader call
 * as core (System.loadLibrary("aarso_sd")).
 */
class SdImageEngine {
    fun load(modelPath: String, threads: Int): Long = nativeSdLoad(modelPath, threads)
    fun free(handle: Long) = nativeSdFree(handle)
    fun txt2img(handle: Long, prompt: String, outPath: String): Boolean =
        nativeSdTxt2Img(handle, prompt, null, 1, 64, 64, 1L, outPath)

    private external fun nativeSdLoad(modelPath: String, threads: Int): Long
    private external fun nativeSdFree(handle: Long)
    private external fun nativeSdTxt2Img(
        handle: Long, prompt: String, negative: String?, steps: Int, width: Int, height: Int, seed: Long, outPath: String,
    ): Boolean

    companion object {
        init {
            System.loadLibrary("aarso_sd")
        }
    }
}
