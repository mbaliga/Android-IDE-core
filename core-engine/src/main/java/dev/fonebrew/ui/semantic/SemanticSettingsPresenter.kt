package dev.fonebrew.ui.semantic

import dev.fonebrew.data.search.semantic.EmbeddingCatalogEntry
import dev.fonebrew.data.search.semantic.EmbeddingModelFile
import dev.fonebrew.data.search.semantic.SemanticStatus
import dev.fonebrew.domain.search.semantic.SemanticIndexState

/**
 * Pure derivation of the Settings -> Models -> "Semantic search" section. State is carried by a
 * glyph with a text label (never colour alone): the shapes differ (off / hollow / half / check /
 * triangle), so they read the same in greyscale and for red-green colour blindness.
 */
object SemanticSettingsPresenter {

    const val PRIVACY_LINE =
        "Embedding runs on this phone only. Your text and the vectors made from it are stored in this " +
            "app's private storage and are never uploaded."

    enum class DownloadState { NOT_DOWNLOADED, DOWNLOADING, INSTALLED }

    data class ModelRow(
        val name: String,
        val sizeText: String,
        val licenseText: String,
        val selected: Boolean,
    )

    data class CatalogRow(
        val id: String,
        val title: String,
        val fileName: String,
        val sizeText: String,
        val licenseText: String,
        val hostLine: String,
        val state: DownloadState,
        val progress: Float?,
    )

    data class UiState(
        val enabled: Boolean,
        val glyph: String,
        val statusLabel: String,
        /** Null when there is nothing to show a bar for. */
        val progress: Float?,
        val canPause: Boolean,
        val canResume: Boolean,
        val canRebuild: Boolean,
        val canClear: Boolean,
        val storageLine: String,
        val privacyLine: String,
        val errorLine: String?,
        val models: List<ModelRow>,
        val catalog: List<CatalogRow>,
        val noModelHint: String?,
    )

    fun present(
        enabled: Boolean,
        selectedModel: String?,
        paused: Boolean,
        installed: List<EmbeddingModelFile>,
        status: SemanticStatus,
        catalog: List<EmbeddingCatalogEntry>,
        /** catalog entry id -> fraction 0..1 for an in-flight download. */
        downloads: Map<String, Float> = emptyMap(),
        knownLicenseByFile: Map<String, String> = emptyMap(),
    ): UiState {
        val idx = status.index
        val (glyph, label, progress) = when (idx) {
            SemanticIndexState.NotEnabled -> Triple("○", "Off", null)
            SemanticIndexState.NoModel ->
                if (status.loadError != null) Triple("△", "Model could not be loaded", null)
                else Triple("◌", "No embedding model chosen", null)
            is SemanticIndexState.Building -> when {
                idx.total <= 0 || (idx.done == 0 && idx.total == 1 && status.embedderId == null) ->
                    Triple("◐", "Loading the model…", null)
                idx.paused -> Triple("◐", "Paused at ${idx.done} of ${idx.total}", idx.done.toFloat() / idx.total)
                else -> Triple("◐", "Building ${idx.done} of ${idx.total}", idx.done.toFloat() / idx.total)
            }
            SemanticIndexState.Ready -> Triple("✓", "Ready", null)
            is SemanticIndexState.Stale ->
                Triple("△", "${idx.count} item${if (idx.count == 1) "" else "s"} made by a different model; rebuild to refresh", null)
        }
        val hasModel = idx != SemanticIndexState.NotEnabled && idx != SemanticIndexState.NoModel
        val building = idx is SemanticIndexState.Building
        val installedNames = installed.map { it.name }.toSet()
        return UiState(
            enabled = enabled,
            glyph = glyph,
            statusLabel = label,
            progress = progress,
            canPause = building && !paused,
            canResume = enabled && hasModel && paused,
            canRebuild = enabled && hasModel,
            canClear = enabled && hasModel || status.storeBytes > 0,
            storageLine = "Index storage: ${formatBytes(status.storeBytes)}",
            privacyLine = PRIVACY_LINE,
            errorLine = status.loadError?.let { "Could not load the model: $it" } ?: status.indexError?.let { "Indexing stopped: $it" },
            models = installed.map {
                ModelRow(
                    name = it.name,
                    sizeText = formatBytes(it.sizeBytes),
                    licenseText = knownLicenseByFile[it.name]?.let { l -> "License: $l" }
                        ?: "License unknown – check the model's own page",
                    selected = it.name == selectedModel,
                )
            },
            catalog = catalog.map { e ->
                val dl = downloads[e.id]
                CatalogRow(
                    id = e.id,
                    title = e.displayName,
                    fileName = e.fileName,
                    sizeText = formatBytes(e.sizeBytes),
                    licenseText = "License: ${e.license}",
                    hostLine = "Downloads from ${e.host} when you tap Download",
                    state = when {
                        e.fileName in installedNames -> DownloadState.INSTALLED
                        dl != null -> DownloadState.DOWNLOADING
                        else -> DownloadState.NOT_DOWNLOADED
                    },
                    progress = dl,
                )
            },
            noModelHint = if (installed.isEmpty()) "No embedding model on this phone yet. Choose a .gguf file or download the one below." else null,
        )
    }

    fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> "%.1f KiB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MiB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GiB".format(bytes / (1024.0 * 1024 * 1024))
    }
}
