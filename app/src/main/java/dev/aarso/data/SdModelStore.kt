package dev.aarso.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Downloaded Stable Diffusion GGUFs, kept in a separate directory from the LLM
 * models so they never appear in the chat model picker (§4c). Used by the
 * on-device image engine.
 */
class SdModelStore(context: Context) {
    val dir: File = File(context.filesDir, "sd-models").apply { mkdirs() }

    private val _models = MutableStateFlow(scan())
    val models: StateFlow<List<LocalModel>> = _models.asStateFlow()

    fun refresh() { _models.value = scan() }

    fun delete(model: LocalModel) {
        model.file.delete()
        refresh()
    }

    private fun scan(): List<LocalModel> =
        dir.listFiles { f ->
            f.isFile && (f.name.endsWith(".gguf") || f.name.endsWith(".safetensors") || f.name.endsWith(".ckpt"))
        }?.sortedBy { it.name }?.map { LocalModel(it) } ?: emptyList()
}
