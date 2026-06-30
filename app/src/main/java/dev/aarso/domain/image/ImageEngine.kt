package dev.aarso.domain.image

/**
 * Image-generation engine — the visual counterpart to text [InferenceEngine].
 * Implementations are cloud providers (OpenAI gpt-image-1, Stability, Imagen) and,
 * later, on-device stable-diffusion.cpp. Used by the §4a fan-out primitive: offer
 * a choice of models, fan out, preview, select (§4c).
 *
 * [generate] returns the path to a saved image file, or throws on failure.
 */
interface ImageEngine {
    val id: String
    val displayName: String

    /** Whether this engine runs on-device (vs a watched cloud provider). */
    val onDevice: Boolean

    suspend fun generate(prompt: String, params: ImageParams): String
}

data class ImageParams(
    val size: Int = 1024,
    val negativePrompt: String? = null,
    val steps: Int = 8,
    val seed: Long? = null,
)
