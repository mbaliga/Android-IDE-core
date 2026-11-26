package dev.fonebrew.data

import android.content.Context
import dev.fonebrew.widget.WidgetPrivacy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The per-widget privacy switches (see [WidgetPrivacy]): local, private prefs, no network. Both
 * default to OFF, and so does a read that throws. The storage is a seam ([read]/[write]) so the
 * defaults and round-trip are JVM-tested without Android prefs; [forContext] is the production wiring.
 */
class WidgetPrivacyStore(
    private val read: (key: String) -> Boolean?,
    private val write: (key: String, value: Boolean) -> Unit,
) {
    private val _state = MutableStateFlow(load())
    val state: StateFlow<WidgetPrivacy> = _state.asStateFlow()

    fun setShowTaskTitles(on: Boolean) {
        write(KEY_TASKS, on)
        _state.value = _state.value.copy(showTaskTitles = on)
    }

    fun setShowLoopNames(on: Boolean) {
        write(KEY_LOOPS, on)
        _state.value = _state.value.copy(showLoopNames = on)
    }

    private fun load(): WidgetPrivacy =
        try {
            WidgetPrivacy(showTaskTitles = read(KEY_TASKS) == true, showLoopNames = read(KEY_LOOPS) == true)
        } catch (e: RuntimeException) {
            WidgetPrivacy.OFF
        }

    companion object {
        const val KEY_TASKS = "show_task_titles"
        const val KEY_LOOPS = "show_loop_names"

        fun forContext(context: Context): WidgetPrivacyStore {
            val prefs = context.applicationContext.getSharedPreferences("aarso.widget.privacy", Context.MODE_PRIVATE)
            return WidgetPrivacyStore(
                read = { key -> if (prefs.contains(key)) prefs.getBoolean(key, false) else null },
                write = { key, value -> prefs.edit().putBoolean(key, value).apply() },
            )
        }
    }
}
