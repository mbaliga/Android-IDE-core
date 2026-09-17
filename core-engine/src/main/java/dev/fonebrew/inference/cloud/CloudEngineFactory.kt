package dev.fonebrew.inference.cloud

import dev.fonebrew.domain.cloud.CloudProvider
import dev.fonebrew.domain.cloud.ProviderKind
import dev.fonebrew.inference.InferenceEngine

object CloudEngineFactory {
    /**
     * [webSearchEnabled] is the W2 per-turn search opt-in (the composer's globe chip,
     * `daily-driver.md` W2) — not a provider-instance property, so it's a call parameter here
     * rather than read off [provider]. Defaults to `false`: every call site that doesn't pass it
     * keeps building today's requests unchanged. Wired through to both
     * [ProviderKind.ANTHROPIC] (`AnthropicEngine`'s `web_search_20260209` tool) and
     * [ProviderKind.GEMINI] (`GeminiEngine`'s `google_search` grounding tool);
     * [ProviderKind.OPENAI_COMPATIBLE] has no standard server-side search
     * (`ProviderKind.supportsSearch=false` from W0), so it's left unwired.
     */
    fun create(
        provider: CloudProvider,
        apiKey: String,
        webSearchEnabled: Boolean = false,
    ): InferenceEngine = when (provider.kind) {
        ProviderKind.OPENAI_COMPATIBLE -> OpenAiCompatEngine(provider, apiKey)
        ProviderKind.ANTHROPIC -> AnthropicEngine(provider, apiKey, webSearchEnabled)
        ProviderKind.GEMINI -> GeminiEngine(provider, apiKey, webSearchEnabled)
    }
}
