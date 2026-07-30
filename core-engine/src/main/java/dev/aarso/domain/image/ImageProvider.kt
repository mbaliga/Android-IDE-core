package dev.aarso.domain.image

/**
 * A user-configured cloud image provider — a "watched object" like the text
 * providers (opt-in, isolated, key stored encrypted). Best-in-class image models
 * are cloud-only (handoff §4c); on-device (stable-diffusion.cpp) is a separate path.
 */
data class ImageProvider(
    val id: String,
    val displayName: String,
    val kind: ImageProviderKind,
    val baseUrl: String,
    val model: String,
)

enum class ImageProviderKind(
    val label: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
) {
    OPENAI_IMAGE("OpenAI (gpt-image-1)", "https://api.openai.com/v1", "gpt-image-1"),
    STABILITY("Stability AI (SD3)", "https://api.stability.ai", "sd3.5-large"),
    GEMINI_IMAGE("Google Imagen", "https://generativelanguage.googleapis.com", "imagen-3.0-generate-002"),
}
