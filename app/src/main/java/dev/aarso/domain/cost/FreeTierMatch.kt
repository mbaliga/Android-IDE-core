package dev.aarso.domain.cost

import dev.aarso.domain.cloud.CloudProvider
import dev.aarso.domain.cloud.ProviderKind

/**
 * Best-effort association of a configured [CloudProvider] to a [FreeTierProvider] in the catalog,
 * so usage can be shown against the right published limit without making the user hand-link them.
 * Heuristic: match the base URL / name / model against known host keywords, then fall back to the
 * provider kind. Pure → JVM-tested. A miss simply means "no published limit to compare against."
 */
object FreeTierMatch {

    private val HOST_KEYWORDS = linkedMapOf(
        "groq" to "groq",
        "deepseek" to "deepseek",
        "openrouter" to "openrouter",
        "together" to "together",
        "cerebras" to "cerebras",
        "mistral" to "mistral",
        "cohere" to "cohere",
        "githubcopilot" to "github_models",
        "models.github" to "github_models",
        "generativelanguage" to "google_gemini",
        "googleapis" to "google_gemini",
        "anthropic" to "anthropic",
        "api.openai.com" to "openai",
    )

    fun match(provider: CloudProvider, catalog: FreeTierCatalog): FreeTierProvider? {
        val hay = "${provider.displayName} ${provider.baseUrl} ${provider.model}".lowercase()
        HOST_KEYWORDS.entries.firstOrNull { hay.contains(it.key) }?.let { return catalog.byId(it.value) }
        return when (provider.kind) {
            ProviderKind.GEMINI -> catalog.byId("google_gemini")
            ProviderKind.ANTHROPIC -> catalog.byId("anthropic")
            else -> null
        }
    }
}
