package dev.fonebrew.domain.cloud

/**
 * A user-configured cloud model endpoint (handoff §3, generalized at the owner's
 * request to any provider). Every cloud provider is a **watched object**: opt-in,
 * isolated, never a hidden default — the app always defaults to on-device models.
 *
 * The three [ProviderKind]s cover the field generically:
 *  - [ProviderKind.OPENAI_COMPATIBLE] — OpenAI, DeepSeek, Alibaba/Qwen
 *    compatible-mode, and any self-hosted OpenAI-compatible server.
 *  - [ProviderKind.ANTHROPIC] — Claude (Messages API; no logprobs, and Opus 4.7+
 *    rejects sampling knobs, so those controls are hidden, §3).
 *  - [ProviderKind.GEMINI] — Google Generative Language API.
 *
 * The API key is NOT stored here; it lives encrypted (Android Keystore) and is
 * referenced by [id].
 */
data class CloudProvider(
    val id: String,
    val displayName: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val model: String,
    val contextWindow: Int,
    /** Per-configured-instance, not per-kind: model ids are user-typed free text
     *  (`SettingsRoom.kt` `ProviderForm`), so vision support can't be inferred from
     *  a catalog. Default on for cloud; the owner unchecks it for a blind model. */
    val supportsVision: Boolean = true,
)

enum class ProviderKind(
    val label: String,
    val defaultBaseUrl: String,
    /** Sampling knobs apply? Anthropic Opus 4.7+ rejects them (§3). */
    val supportsSampling: Boolean,
    /** Server-side web search tool exists for this kind? OpenAI-compatible has no
     *  standard server-side search, so it's false there (W2). */
    val supportsSearch: Boolean,
) {
    OPENAI_COMPATIBLE("OpenAI-compatible", "https://api.openai.com/v1", true, false),
    ANTHROPIC("Anthropic (Claude)", "https://api.anthropic.com", false, true),
    GEMINI("Google Gemini", "https://generativelanguage.googleapis.com", true, true),
}
