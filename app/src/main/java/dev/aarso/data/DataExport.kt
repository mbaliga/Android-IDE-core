package dev.aarso.data

import dev.aarso.di.AppContainer
import dev.aarso.domain.sync.TreeArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Exports the whole local profile as one open JSON object (IA cross-cutting: "every space
 * exportable; the entire app config + all user data to the farthest extent possible").
 *
 * **API keys and Git tokens are deliberately excluded** — they live encrypted in the Android
 * Keystore and never leave it (binding rule 5). Everything else that's yours is here: appearance
 * & defaults, bookmarks, per-conversation projects, notes, incidents, council participants, your
 * saved loops (BPMN), and the full message tree.
 */
object DataExport {

    suspend fun toJson(container: AppContainer): String = withContext(Dispatchers.IO) {
        val s = container.sessionStore
        val root = JSONObject()
        root.put("format", "aarso.export.v1")
        root.put("exportedAt", System.currentTimeMillis())
        root.put("note", "API keys & Git tokens are NOT included — they stay in the Android Keystore (rule 5).")

        root.put(
            "appearance",
            JSONObject()
                .put("themeMode", s.themeMode.value)
                .put("accent", s.accentColor.value)
                .put("texture", s.textureIntensity.value.toDouble())
                .put("gradient", s.gradientColor.value),
        )
        root.put(
            "defaults",
            JSONObject()
                .put("council", s.councilDefault.value)
                .put("disclosureTier", s.disclosureTier.value),
        )
        root.put("bookmarks", JSONArray(s.bookmarkedRoots.value.toList()))
        root.put("conversationProjects", JSONObject(s.conversationProjects.value as Map<*, *>))

        root.put(
            "notes",
            JSONArray().apply { container.notesStore.notes.value.forEach { put(JSONObject().put("text", it.text).put("updatedAt", it.updatedAt)) } },
        )
        root.put(
            "incidents",
            JSONArray().apply {
                container.incidentsStore.incidents.value.forEach {
                    put(JSONObject().put("title", it.title).put("detail", it.detail).put("trend", it.trend.name).put("createdAt", it.createdAt))
                }
            },
        )
        root.put(
            "councilParticipants",
            JSONArray().apply {
                container.councilStore.participants.value.forEach {
                    put(JSONObject().put("name", it.name).put("instructions", it.instructions).put("modelId", it.modelId ?: "").put("memory", it.memory))
                }
            },
        )
        root.put(
            "loops",
            JSONArray().apply {
                container.loopStore.loops.value.forEach {
                    put(JSONObject().put("name", it.name).put("state", it.state.name).put("bpmn", it.bpmnXml ?: ""))
                }
            },
        )

        val treeFiles = TreeArchive.write(container.repository.tree().allNodes())
        root.put("tree", JSONObject(treeFiles as Map<*, *>))

        root.toString(2)
    }
}
