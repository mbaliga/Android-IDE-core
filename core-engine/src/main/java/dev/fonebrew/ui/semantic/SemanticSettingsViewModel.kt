package dev.fonebrew.ui.semantic

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.fonebrew.FonebrewApp
import dev.fonebrew.data.search.semantic.EmbeddingModelCatalog
import dev.fonebrew.data.search.semantic.ImportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Thin shell over [dev.fonebrew.data.search.semantic.SemanticSearchService]; the logic worth testing is in [SemanticSettingsPresenter]. */
class SemanticSettingsViewModel(app: Application, private val container: dev.fonebrew.di.AppContainer) : AndroidViewModel(app) {

    private val service = container.semanticSearchService
    private val settings = container.semanticSettings
    private val models = container.embeddingModelStore

    /** One-shot message for the screen (import refused, download rejected). */
    val message = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            models.verifyFailure.collect { f -> if (f != null) { message.value = f; models.verifyFailure.value = null } }
        }
    }

    val ui: StateFlow<SemanticSettingsPresenter.UiState> = combine(
        settings.enabled, settings.modelFile, settings.paused, models.models, service.status,
    ) { _, _, _, _, _ -> Unit }
        .combine(container.downloadCenter.active) { _, active ->
            val downloads = active.filterKeys { it.startsWith(DL_PREFIX) }
                .mapKeys { it.key.removePrefix(DL_PREFIX) }
                .mapValues { it.value.progress.fraction }
            current(downloads)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), current(emptyMap()))

    private fun current(downloads: Map<String, Float>) = SemanticSettingsPresenter.present(
        enabled = settings.enabled.value,
        selectedModel = settings.modelFile.value,
        paused = settings.paused.value,
        installed = models.models.value,
        status = service.status.value,
        catalog = EmbeddingModelCatalog.entries,
        downloads = downloads,
        knownLicenseByFile = EmbeddingModelCatalog.entries.associate { it.fileName to it.license },
    )

    fun setEnabled(on: Boolean) = settings.setEnabled(on)
    fun selectModel(name: String) = settings.setModelFile(name)
    fun pause() = settings.setPaused(true)
    fun resume() = settings.setPaused(false)
    fun rebuild() { viewModelScope.launch(Dispatchers.IO) { service.rebuild() } }
    fun clear() { viewModelScope.launch(Dispatchers.IO) { service.clear() } }

    fun deleteModel(name: String) {
        if (settings.modelFile.value == name) settings.setModelFile(null)
        models.delete(name)
    }

    fun importPicked(uri: Uri) {
        viewModelScope.launch {
            val cr = getApplication<Application>().contentResolver
            val result = withContext(Dispatchers.IO) {
                val name = cr.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) c.getString(i) else null
                } ?: uri.lastPathSegment.orEmpty()
                val stream = cr.openInputStream(uri) ?: return@withContext ImportResult.Rejected("Could not open the file")
                models.importFrom(name, stream)
            }
            when (result) {
                is ImportResult.Imported -> message.update { "Added ${result.model.name}. Choose it to use it." }
                is ImportResult.Rejected -> message.update { result.reason }
            }
        }
    }

    /** Only ever called from the explicit Download button. */
    fun download(entryId: String) {
        val e = EmbeddingModelCatalog.entries.firstOrNull { it.id == entryId } ?: return
        models.expectVerification(e.fileName, e.sha256)
        container.downloadCenter.enqueue(DL_PREFIX + e.id, e.url, e.fileName, container.embeddingModelDownloader)
    }

    fun cancelDownload(entryId: String) = container.downloadCenter.cancel(DL_PREFIX + entryId)

    fun consumeMessage() { message.value = null }

    companion object {
        private const val DL_PREFIX = "embed:"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as FonebrewApp
                SemanticSettingsViewModel(app, app.container)
            }
        }
    }
}
