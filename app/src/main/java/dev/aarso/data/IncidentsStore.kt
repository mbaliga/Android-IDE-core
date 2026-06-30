package dev.aarso.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Direction of an incident's effect on the project, vs before (IA §E3). */
enum class IncidentTrend(val symbol: String, val label: String) {
    UP("▲", "Increase"), DOWN("▼", "Decrease"), SAME("▬", "Unchanged")
}

/** A project incident: a major issue, direction change, or scope change, with a trend. */
data class Incident(
    val id: String,
    val title: String,
    val detail: String,
    val trend: IncidentTrend,
    val createdAt: Long,
)

/**
 * Persists project incidents (the Project room's Incidents tab) as plain JSON in private
 * SharedPreferences — local-first, no network, no telemetry. Newest first.
 */
class IncidentsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("aarso.incidents", Context.MODE_PRIVATE)

    private val _incidents = MutableStateFlow(load())
    val incidents: StateFlow<List<Incident>> = _incidents.asStateFlow()

    fun add(title: String, detail: String, trend: IncidentTrend): String {
        val id = UUID.randomUUID().toString()
        persist(listOf(Incident(id, title, detail, trend, System.currentTimeMillis())) + _incidents.value)
        return id
    }

    fun remove(id: String) = persist(_incidents.value.filterNot { it.id == id })

    private fun persist(list: List<Incident>) {
        val sorted = list.sortedByDescending { it.createdAt }
        val arr = JSONArray()
        for (i in sorted) arr.put(
            JSONObject().put("id", i.id).put("title", i.title).put("detail", i.detail)
                .put("trend", i.trend.name).put("createdAt", i.createdAt),
        )
        prefs.edit().putString(KEY, arr.toString()).apply()
        _incidents.value = sorted
    }

    private fun load(): List<Incident> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            runCatching {
                Incident(
                    o.getString("id"), o.getString("title"), o.optString("detail"),
                    runCatching { IncidentTrend.valueOf(o.optString("trend", "SAME")) }.getOrDefault(IncidentTrend.SAME),
                    o.optLong("createdAt"),
                )
            }.getOrNull()
        }.sortedByDescending { it.createdAt }
    }

    private companion object { const val KEY = "incidents" }
}
