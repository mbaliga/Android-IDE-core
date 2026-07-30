package dev.aarso.inference

import dev.aarso.data.ProviderStore
import dev.aarso.domain.model.ModelSpec
import dev.aarso.domain.model.Runtime
import dev.aarso.inference.cloud.CloudEngineFactory

/**
 * Resolves the runtime [InferenceEngine] for a [ModelSpec]. Decoupling the model
 * (identity) from the engine (what executes) is what makes switching legible.
 *
 *  - ECHO_DEV  → the shared echo stand-in.
 *  - LOCAL_GGUF → the native engine once built; null until then.
 *  - CLOUD      → a provider engine, but only when an API key is stored; without
 *                 a key it is not runnable (the UI prompts to add one in Settings).
 */
class EngineProvider(
    private val echo: InferenceEngine,
    private val providers: ProviderStore,
) {

    // Lazily constructed so the native library is only loaded when a local model
    // is actually used; if libaarso_llama.so is missing/broken, this stays null
    // and local models report unavailable instead of crashing the app.
    private val llama: InferenceEngine? by lazy {
        runCatching { LlamaCppEngine() }.getOrNull()
    }

    fun engineFor(spec: ModelSpec): InferenceEngine? = when (spec.runtime) {
        Runtime.ECHO_DEV -> echo
        Runtime.LOCAL_GGUF -> if (spec.modelPath != null) llama else null
        Runtime.CLOUD -> {
            val pid = spec.providerId
            val provider = pid?.let { providers.byId(it) }
            val key = pid?.let { providers.apiKey(it) }
            if (provider != null && !key.isNullOrBlank()) {
                CloudEngineFactory.create(provider, key)
            } else {
                null
            }
        }
    }

    fun isRunnable(spec: ModelSpec): Boolean = engineFor(spec) != null

    /** Free the resident local LLM so an on-device image model can use the RAM (§4c). */
    suspend fun unloadLocalModel() {
        llama?.let { if (it.isLoaded) it.unload() }
    }

    /** Why a spec is not runnable, for the UI. Null when it is runnable. */
    fun unavailableReason(spec: ModelSpec): String? = when {
        isRunnable(spec) -> null
        spec.runtime == Runtime.LOCAL_GGUF && llama == null -> "native engine unavailable on this device"
        spec.runtime == Runtime.LOCAL_GGUF -> "no model file"
        spec.runtime == Runtime.CLOUD -> "add an API key for this provider in Settings"
        else -> "not runnable"
    }
}
