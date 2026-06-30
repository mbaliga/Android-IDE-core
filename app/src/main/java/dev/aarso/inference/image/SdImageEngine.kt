package dev.aarso.inference.image

import dev.aarso.data.ImageStore
import dev.aarso.domain.image.ImageEngine
import dev.aarso.domain.image.ImageParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device image generation via stable-diffusion.cpp (§4c). Runs a quantized SD
 * GGUF on CPU and writes a PNG. CPU-only — slow (tens of seconds to minutes per
 * image). One model resident at a time; the LLM should be unloaded first since
 * the image model contends for RAM.
 *
 * Backed by libaarso_sd.so from the :sdengine module — sd.cpp with its OWN ggml,
 * built separately so it never collides with llama.cpp's ggml in :app.
 */
class SdImageEngine(
    private val modelPath: String,
    private val store: ImageStore,
    private val threads: Int = 4,
) : ImageEngine {

    override val id = "sd-local:${modelPath.substringAfterLast('/')}"
    override val displayName = "On-device · ${modelPath.substringAfterLast('/')}"
    override val onDevice = true

    @Volatile
    private var handle = 0L

    override suspend fun generate(prompt: String, params: ImageParams): String = withContext(Dispatchers.Default) {
        if (handle == 0L) {
            handle = nativeSdLoad(modelPath, threads)
            check(handle != 0L) { "failed to load SD model: $modelPath" }
        }
        val out = store.newPath("png")
        val ok = nativeSdTxt2Img(
            handle, prompt, params.negativePrompt, params.steps,
            params.size, params.size, params.seed ?: -1L, out,
        )
        check(ok) { "image generation failed" }
        out
    }

    fun release() {
        if (handle != 0L) {
            nativeSdFree(handle)
            handle = 0L
        }
    }

    private external fun nativeSdLoad(modelPath: String, threads: Int): Long
    private external fun nativeSdFree(handle: Long)
    private external fun nativeSdTxt2Img(
        handle: Long,
        prompt: String,
        negative: String?,
        steps: Int,
        width: Int,
        height: Int,
        seed: Long,
        outPath: String,
    ): Boolean

    companion object {
        init {
            System.loadLibrary("aarso_sd")
        }
    }
}
