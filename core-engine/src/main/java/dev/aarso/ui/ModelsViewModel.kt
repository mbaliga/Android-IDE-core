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
import dev.aarso.data.LocalModel
import dev.aarso.data.LocalModelStore
import dev.aarso.data.ModelDownloader
import dev.aarso.domain.catalog.CatalogModel
import dev.aarso.domain.catalog.ModelCatalog
import dev.aarso.domain.device.DeviceSpec
import dev.aarso.domain.device.FitResult
import dev.aarso.domain.device.ModelFit
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Drives the model download manager: device-fit gating (handoff §1) and
 * user-initiated GGUF downloads (§3, sanctioned egress). Downloads run in the
 * process-wide [DownloadCenter], so they survive leaving this screen.
 */
class ModelsViewModel(
    app: Application,
    private val store: LocalModelStore,
    private val downloader: ModelDownloader,
    private val center: DownloadCenter,
) : AndroidViewModel(app) {

    val device: DeviceSpec = DeviceInfo.read(app)
    val catalog: List<CatalogModel> = ModelCatalog.models
    val downloaded: StateFlow<List<LocalModel>> = store.models

    val progress: StateFlow<Map<String, ModelDownloader.Progress>> =
        center.active
            .map { active -> active.mapValues { it.value.progress } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun fit(sizeBytes: Long): FitResult = ModelFit.check(sizeBytes, device)

    fun download(id: String, url: String, fileName: String) =
        center.enqueue(id, url, fileName, downloader)

    fun downloadCatalog(model: CatalogModel) = download(model.id, model.downloadUrl, model.hfFile)

    fun downloadCustom(url: String) {
        val name = url.substringAfterLast('/').ifBlank { "model.gguf" }
            .let { if (it.endsWith(".gguf")) it else "$it.gguf" }
        download("custom:$name", url, name)
    }

    fun retry(id: String) = center.retry(id)

    fun cancel(id: String) = center.cancel(id)

    fun delete(model: LocalModel) = store.delete(model)

    fun isDownloaded(fileName: String): Boolean = downloaded.value.any { it.name == fileName }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AarsoApp
                ModelsViewModel(
                    app,
                    app.container.localModelStore,
                    app.container.modelDownloader,
                    app.container.downloadCenter,
                )
            }
        }
    }
}
