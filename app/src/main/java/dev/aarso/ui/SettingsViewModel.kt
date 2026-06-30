package dev.aarso.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.aarso.AarsoApp
import dev.aarso.data.ImageProviderStore
import dev.aarso.data.ProviderStore
import dev.aarso.domain.cloud.CloudProvider
import dev.aarso.domain.cloud.ProviderKind
import dev.aarso.domain.image.ImageProvider
import dev.aarso.domain.image.ImageProviderKind
import kotlinx.coroutines.flow.StateFlow

/** Manages the user's cloud (text + image) providers and their (encrypted) keys. */
class SettingsViewModel(
    private val store: ProviderStore,
    private val imageStore: ImageProviderStore,
) : ViewModel() {

    val providers: StateFlow<List<CloudProvider>> = store.providers
    val imageProviders: StateFlow<List<ImageProvider>> = imageStore.providers

    fun hasKey(id: String): Boolean = store.hasApiKey(id)

    fun save(
        existingId: String?,
        displayName: String,
        kind: ProviderKind,
        baseUrl: String,
        model: String,
        contextWindow: Int,
        apiKey: String,
    ) {
        val id = existingId ?: store.newId()
        store.upsert(
            CloudProvider(
                id = id,
                displayName = displayName.trim().ifBlank { kind.label },
                kind = kind,
                baseUrl = baseUrl.trim().ifBlank { kind.defaultBaseUrl },
                model = model.trim(),
                contextWindow = contextWindow.coerceAtLeast(256),
            ),
            apiKey = apiKey,
        )
    }

    fun remove(id: String) = store.remove(id)

    // --- Image providers ---
    fun hasImageKey(id: String): Boolean = imageStore.hasApiKey(id)

    fun saveImage(kind: ImageProviderKind, displayName: String, baseUrl: String, model: String, apiKey: String) {
        imageStore.upsert(
            ImageProvider(
                id = imageStore.newId(),
                displayName = displayName.trim().ifBlank { kind.label },
                kind = kind,
                baseUrl = baseUrl.trim().ifBlank { kind.defaultBaseUrl },
                model = model.trim().ifBlank { kind.defaultModel },
            ),
            apiKey = apiKey,
        )
    }

    fun removeImage(id: String) = imageStore.remove(id)

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AarsoApp
                SettingsViewModel(app.container.providerStore, app.container.imageProviderStore)
            }
        }
    }
}
