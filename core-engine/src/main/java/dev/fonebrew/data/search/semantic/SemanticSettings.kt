package dev.fonebrew.data.search.semantic

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The user's semantic-search choices. Default OFF, no model chosen. Kept out of `SessionStore`
 * so this lane adds a file rather than editing a shared one.
 */
interface SemanticSettings {
    val enabled: StateFlow<Boolean>
    /** File name inside the embedding-model directory, or null. */
    val modelFile: StateFlow<String?>
    val paused: StateFlow<Boolean>
    fun setEnabled(value: Boolean)
    fun setModelFile(name: String?)
    fun setPaused(value: Boolean)
}

class InMemorySemanticSettings(enabled: Boolean = false, modelFile: String? = null, paused: Boolean = false) : SemanticSettings {
    private val e = MutableStateFlow(enabled)
    private val m = MutableStateFlow(modelFile)
    private val p = MutableStateFlow(paused)
    override val enabled: StateFlow<Boolean> = e.asStateFlow()
    override val modelFile: StateFlow<String?> = m.asStateFlow()
    override val paused: StateFlow<Boolean> = p.asStateFlow()
    override fun setEnabled(value: Boolean) { e.value = value }
    override fun setModelFile(name: String?) { m.value = name }
    override fun setPaused(value: Boolean) { p.value = value }
}

class PrefsSemanticSettings(context: Context) : SemanticSettings {
    private val prefs = context.applicationContext.getSharedPreferences("fonebrew.semantic", Context.MODE_PRIVATE)
    private val e = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    private val m = MutableStateFlow(prefs.getString(KEY_MODEL, null))
    private val p = MutableStateFlow(prefs.getBoolean(KEY_PAUSED, false))
    override val enabled: StateFlow<Boolean> = e.asStateFlow()
    override val modelFile: StateFlow<String?> = m.asStateFlow()
    override val paused: StateFlow<Boolean> = p.asStateFlow()
    override fun setEnabled(value: Boolean) { prefs.edit().putBoolean(KEY_ENABLED, value).apply(); e.value = value }
    override fun setModelFile(name: String?) { prefs.edit().putString(KEY_MODEL, name).apply(); m.value = name }
    override fun setPaused(value: Boolean) { prefs.edit().putBoolean(KEY_PAUSED, value).apply(); p.value = value }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_MODEL = "model_file"
        const val KEY_PAUSED = "paused"
    }
}
