package dev.fonebrew.inference.cloud

import dev.fonebrew.domain.cloud.CloudProvider
import dev.fonebrew.domain.cloud.ProviderKind
import dev.fonebrew.inference.InferenceEngine

object CloudEngineFactory {
    fun create(provider: CloudProvider, apiKey: String): InferenceEngine = when (provider.kind) {
        ProviderKind.OPENAI_COMPATIBLE -> OpenAiCompatEngine(provider, apiKey)
        ProviderKind.ANTHROPIC -> AnthropicEngine(provider, apiKey)
        ProviderKind.GEMINI -> GeminiEngine(provider, apiKey)
    }
}
