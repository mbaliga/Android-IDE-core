package dev.aarso.inference.cloud

import dev.aarso.domain.cloud.CloudProvider
import dev.aarso.domain.cloud.ProviderKind
import dev.aarso.inference.InferenceEngine

object CloudEngineFactory {
    fun create(provider: CloudProvider, apiKey: String): InferenceEngine = when (provider.kind) {
        ProviderKind.OPENAI_COMPATIBLE -> OpenAiCompatEngine(provider, apiKey)
        ProviderKind.ANTHROPIC -> AnthropicEngine(provider, apiKey)
        ProviderKind.GEMINI -> GeminiEngine(provider, apiKey)
    }
}
