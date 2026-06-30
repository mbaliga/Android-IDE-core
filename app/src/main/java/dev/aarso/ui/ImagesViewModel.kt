package dev.aarso.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.aarso.AarsoApp
import dev.aarso.data.DeviceInfo
import dev.aarso.data.DownloadCenter
import dev.aarso.data.ImageProviderStore
import dev.aarso.data.ImageStore
import dev.aarso.data.LocalModel
import dev.aarso.data.ModelDownloader
import dev.aarso.data.SdModelStore
import dev.aarso.domain.catalog.SdCatalog
import dev.aarso.domain.catalog.SdCatalogModel
import dev.aarso.domain.device.FitResult
import dev.aarso.domain.device.ModelFit
import dev.aarso.domain.image.ImageParams
import dev.aarso.domain.image.ImageProvider
import dev.aarso.inference.EngineProvider
import dev.aarso.inference.image.ImageEngineFactory
import dev.aarso.inference.image.SdImageEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val SD_LOCAL_ID = "sd-local"

/** One pane in the fan-out preview (§4a/§4c). */
data class ImageTile(
    val engineId: String,
    val label: String,
    val generating: Boolean,
    val path: String?,
    val error: String?,
)

/**
 * Image generation as the §4a fan-out primitive (§4c): fan one prompt to chosen
 * image models — cloud providers (parallel) and/or the on-device SD engine — then
 * preview, keep, delete. On-device runs after unloading the LLM (RAM, §4c).
 */
class ImagesViewModel(
    app: Application,
    private val providerStore: ImageProviderStore,
    private val imageStore: ImageStore,
    private val engineProvider: EngineProvider,
    private val sdModelStore: SdModelStore,
    private val sdDownloader: ModelDownloader,
    private val downloadCenter: DownloadCenter,
) : AndroidViewModel(app) {

    val providers: StateFlow<List<ImageProvider>> = providerStore.providers
    val sdModels: StateFlow<List<LocalModel>> = sdModelStore.models
    val sdCatalog: List<SdCatalogModel> = SdCatalog.models
    private val device = DeviceInfo.read(app)

    fun fit(sizeBytes: Long): FitResult = ModelFit.check(sizeBytes, device)

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected

    private val _selectedSdPath = MutableStateFlow<String?>(null)
    val selectedSdPath: StateFlow<String?> = _selectedSdPath

    private val _tiles = MutableStateFlow<List<ImageTile>>(emptyList())
    val tiles: StateFlow<List<ImageTile>> = _tiles

    private val _gallery = MutableStateFlow(imageStore.list().map { it.absolutePath })
    val gallery: StateFlow<List<String>> = _gallery

    /** The in-flight SD model download, if any — runs in the process-wide center. */
    val sdDownload: StateFlow<ModelDownloader.Progress?> =
        downloadCenter.active
            .map { active -> active.entries.firstOrNull { it.key.startsWith("sd:") }?.value?.progress }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun hasKey(id: String): Boolean = providerStore.hasApiKey(id)

    fun toggle(id: String) {
        _selected.update { if (id in it) it - id else it + id }
    }

    fun selectSdModel(path: String) {
        _selectedSdPath.value = path
        _selected.update { it + SD_LOCAL_ID }
    }

    fun downloadSdModel(url: String) {
        val name = url.substringBefore('?').substringAfterLast('/').ifBlank { "sd-model.safetensors" }
        downloadCenter.enqueue("sd:$name", url, name, sdDownloader)
    }

    fun deleteSdModel(model: LocalModel) = sdModelStore.delete(model)

    fun generate(prompt: String) {
        val text = prompt.trim()
        if (text.isEmpty()) return
        val chosenCloud = providers.value.filter { it.id in _selected.value && providerStore.hasApiKey(it.id) }
        val sdSelected = SD_LOCAL_ID in _selected.value && _selectedSdPath.value != null
        if (chosenCloud.isEmpty() && !sdSelected) return

        _tiles.value = buildList {
            chosenCloud.forEach { add(ImageTile(it.id, it.displayName, true, null, null)) }
            if (sdSelected) {
                val name = _selectedSdPath.value!!.substringAfterLast('/')
                add(ImageTile(SD_LOCAL_ID, "On-device · $name", true, null, null))
            }
        }

        // Cloud providers fan out in parallel.
        chosenCloud.forEach { p ->
            viewModelScope.launch {
                val key = providerStore.apiKey(p.id) ?: return@launch
                val engine = ImageEngineFactory.create(p, key, imageStore)
                runCatching { engine.generate(text, ImageParams()) }
                    .onSuccess { setTile(p.id) { t -> t.copy(generating = false, path = it) }; refreshGallery() }
                    .onFailure { setTile(p.id) { t -> t.copy(generating = false, error = it.message ?: "failed") } }
            }
        }

        // On-device SD: unload the LLM first (RAM), then generate (slow, sequential).
        if (sdSelected) {
            val modelPath = _selectedSdPath.value!!
            viewModelScope.launch {
                engineProvider.unloadLocalModel()
                val engine = SdImageEngine(modelPath, imageStore)
                runCatching { engine.generate(text, ImageParams(size = 512, steps = 20)) }
                    .onSuccess { setTile(SD_LOCAL_ID) { t -> t.copy(generating = false, path = it) }; refreshGallery() }
                    .onFailure { setTile(SD_LOCAL_ID) { t -> t.copy(generating = false, error = it.message ?: "failed") } }
                engine.release()
            }
        }
    }

    fun deleteImage(path: String) {
        imageStore.delete(path)
        refreshGallery()
        _tiles.update { tiles -> tiles.map { if (it.path == path) it.copy(path = null) else it } }
    }

    private fun refreshGallery() {
        _gallery.value = imageStore.list().map { it.absolutePath }
    }

    private fun setTile(engineId: String, f: (ImageTile) -> ImageTile) {
        _tiles.update { tiles -> tiles.map { if (it.engineId == engineId) f(it) else it } }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AarsoApp
                val c = app.container
                ImagesViewModel(app, c.imageProviderStore, c.imageStore, c.engineProvider, c.sdModelStore, c.sdModelDownloader, c.downloadCenter)
            }
        }
    }
}
