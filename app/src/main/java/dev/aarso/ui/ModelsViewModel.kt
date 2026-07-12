package dev.aarso.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.aarso.AarsoApp
import dev.aarso.data.DeviceInfo
import dev.aarso.data.DownloadCenter
import dev.aarso.data.LocalModel
import dev.aarso.data.LocalModelStore
import dev.aarso.data.ModelCatalogStore
import dev.aarso.data.ModelCatalogUpdater
import dev.aarso.data.ModelDownloader
import dev.aarso.domain.catalog.CatalogModel
import dev.aarso.domain.catalog.ModelCatalogMapper
import dev.aarso.domain.device.DeviceSpec
import dev.aarso.domain.device.FitResult
import dev.aarso.domain.device.ModelFit
import dev.aarso.flavor.InvocationFeatures
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Drives the model download manager: device-fit gating (handoff §1) and
 * user-initiated GGUF downloads (§3, sanctioned egress). Downloads run in the
 * process-wide [DownloadCenter], so they survive leaving this screen.
 *
 * The catalog itself is Nooz's shared `ai-catalogue/models.json` (see [ModelCatalogStore]) —
 * [catalog] starts from the bundled/synced snapshot and [refreshCatalog] re-reads it after a
 * consented [ModelCatalogUpdater.update] fetch.
 */
class ModelsViewModel(
    app: Application,
    private val store: LocalModelStore,
    private val downloader: ModelDownloader,
    private val center: DownloadCenter,
    private val catalogStore: ModelCatalogStore,
) : AndroidViewModel(app) {

    val device: DeviceSpec = DeviceInfo.read(app)

    var catalog: List<CatalogModel> by mutableStateOf(loadCatalog())
        private set
    var catalogLastUpdated: String by mutableStateOf(catalogStore.catalog().lastUpdated)
        private set

    val downloaded: StateFlow<List<LocalModel>> = store.models

    val progress: StateFlow<Map<String, ModelDownloader.Progress>> =
        center.active
            .map { active -> active.mapValues { it.value.progress } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun fit(sizeBytes: Long): FitResult = ModelFit.check(sizeBytes, device)

    fun download(id: String, url: String, fileName: String) =
        center.enqueue(id, url, fileName, downloader)

    /** No-op when the catalog has no verified mirror for this model yet (null downloadUrl) —
     *  the UI gates the download action on that, per the catalog's own honesty rule. */
    fun downloadCatalog(model: CatalogModel) {
        val url = model.downloadUrl ?: return
        download(model.id, url, model.fileName)
    }

    fun downloadCustom(url: String) {
        val name = url.substringAfterLast('/').ifBlank { "model.gguf" }
            .let { if (it.endsWith(".gguf")) it else "$it.gguf" }
        download("custom:$name", url, name)
    }

    fun retry(id: String) = center.retry(id)

    fun cancel(id: String) = center.cancel(id)

    fun delete(model: LocalModel) = store.delete(model)

    fun isDownloaded(fileName: String): Boolean = downloaded.value.any { it.name == fileName }

    /** Re-read the catalog store — call after a consented [ModelCatalogUpdater.update] succeeds. */
    fun refreshCatalog() {
        catalog = loadCatalog()
        catalogLastUpdated = catalogStore.catalog().lastUpdated
    }

    private fun loadCatalog(): List<CatalogModel> =
        ModelCatalogMapper.chatModels(catalogStore.catalog(), InvocationFeatures.CATALOG_POLICY_SAFE_ONLY)

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AarsoApp
                ModelsViewModel(
                    app,
                    app.container.localModelStore,
                    app.container.modelDownloader,
                    app.container.downloadCenter,
                    app.container.modelCatalogStore,
                )
            }
        }
    }
}
