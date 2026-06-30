package dev.aarso.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A free-form project note. Local-first, plain text, yours. */
data class Note(val id: String, val text: String, val updatedAt: Long)

/**
 * Persists project notes (the Project room's Notes tab) as plain JSON in private
 * SharedPreferences — local-first, no network, no telemetry. Newest first.
 */
class NotesStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("aarso.notes", Context.MODE_PRIVATE)

    private val _notes = MutableStateFlow(load())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    fun add(text: String): String {
        val id = UUID.randomUUID().toString()
        persist(listOf(Note(id, text, System.currentTimeMillis())) + _notes.value)
        return id
    }

    fun update(id: String, text: String) {
        persist(_notes.value.map { if (it.id == id) it.copy(text = text, updatedAt = System.currentTimeMillis()) else it })
    }

    fun remove(id: String) = persist(_notes.value.filterNot { it.id == id })

    private fun persist(list: List<Note>) {
        val sorted = list.sortedByDescending { it.updatedAt }
        val arr = JSONArray()
        for (n in sorted) arr.put(JSONObject().put("id", n.id).put("text", n.text).put("updatedAt", n.updatedAt))
        prefs.edit().putString(KEY, arr.toString()).apply()
        _notes.value = sorted
    }

    private fun load(): List<Note> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            runCatching { Note(o.getString("id"), o.getString("text"), o.optLong("updatedAt")) }.getOrNull()
        }.sortedByDescending { it.updatedAt }
    }

    private companion object { const val KEY = "notes" }
}
