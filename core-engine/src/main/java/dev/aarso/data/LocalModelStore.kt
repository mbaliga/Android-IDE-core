package dev.aarso.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** A GGUF file downloaded to app storage. */
data class LocalModel(val file: File) {
    val name: String get() = file.name
    val sizeBytes: Long get() = file.length()
    val path: String get() = file.absolutePath
}

/**
 * Tracks the GGUF models the user has downloaded to private app storage. The
 * reactive [models] list drives both the download manager and the model picker
 * (downloaded models appear as on-device models — runnable once the native engine
 * is built).
 */
class LocalModelStore(context: Context) {

    val dir: File = File(context.filesDir, "models").apply { mkdirs() }

    private val _models = MutableStateFlow(scan())
    val models: StateFlow<List<LocalModel>> = _models.asStateFlow()

    fun refresh() {
        _models.value = scan()
    }

    fun delete(model: LocalModel) {
        model.file.delete()
        refresh()
    }

    fun fileFor(name: String): File = File(dir, name)

    private fun scan(): List<LocalModel> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf") }
            ?.sortedBy { it.name }
            ?.map { LocalModel(it) }
            ?: emptyList()
}
