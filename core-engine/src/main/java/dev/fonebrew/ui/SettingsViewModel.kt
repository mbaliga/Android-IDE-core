package dev.fonebrew.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.fonebrew.FonebrewApp
import dev.fonebrew.data.ImageProviderStore
import dev.fonebrew.data.Object3dProviderConfig
import dev.fonebrew.data.Object3dProviderStore
import dev.fonebrew.data.ProviderStore
import dev.fonebrew.domain.cloud.CloudProvider
import dev.fonebrew.domain.cloud.ProviderKind
import dev.fonebrew.domain.image.ImageProvider
import dev.fonebrew.domain.image.ImageProviderKind
import dev.fonebrew.domain.object3d.Object3dCloudProvider
import kotlinx.coroutines.flow.StateFlow

/** Manages the user's cloud (text + image + 3D) providers and their (encrypted) keys. */
class SettingsViewModel(
    private val store: ProviderStore,
    private val imageStore: ImageProviderStore,
    private val object3dStore: Object3dProviderStore,
) : ViewModel() {

    val providers: StateFlow<List<CloudProvider>> = store.providers
    val imageProviders: StateFlow<List<ImageProvider>> = imageStore.providers
    val object3dProviders: StateFlow<List<Object3dProviderConfig>> = object3dStore.providers

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

    // --- 3D object cloud providers (docs/design/objects-3d.md §1/§5) ---
    fun hasObject3dKey(id: String): Boolean = object3dStore.hasApiKey(id)

    fun saveObject3d(kind: Object3dCloudProvider, displayName: String, baseUrl: String, apiKey: String) {
        object3dStore.upsert(
            Object3dProviderConfig(
                id = object3dStore.newId(),
                displayName = displayName.trim().ifBlank { kind.label },
                kind = kind,
                baseUrl = baseUrl.trim().ifBlank { kind.apiBaseUrl },
            ),
            apiKey = apiKey,
        )
    }

    fun removeObject3d(id: String) = object3dStore.remove(id)

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as FonebrewApp
                SettingsViewModel(
                    app.container.providerStore,
                    app.container.imageProviderStore,
                    app.container.object3dProviderStore,
                )
            }
        }
    }
}
