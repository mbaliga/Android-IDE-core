package dev.aarso.data

import android.content.Context
import dev.aarso.domain.council.Council
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * One council member, defined individually like a person in a group chat (IA §B4): a name, its
 * own instructions, **its own model** (on-device or a watched cloud provider — null = the chat's
 * active model), and **long-term memory** that's injected into its context every turn.
 *
 * (Per-member *files* are a tracked follow-up — real file→context needs multimodal/document
 * plumbing the engines don't have yet, so we don't fake it; rule 6.)
 */
data class Participant(
    val id: String,
    val name: String,
    val instructions: String,
    val modelId: String? = null,
    val memory: String = "",
)

/**
 * Persists the council roster (IA §B4). Local-first JSON in private prefs; defaults to
 * [Council.defaultAgents] until the user edits it. The personas council fans out over this
 * roster, resolving each member's own model and folding in its memory.
 */
class CouncilStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("aarso.council", Context.MODE_PRIVATE)

    private val _participants = MutableStateFlow(load())
    val participants: StateFlow<List<Participant>> = _participants.asStateFlow()

    /** Replace the whole roster (the editor saves at once). Empty → the defaults. */
    fun setAll(list: List<Participant>) {
        val clean = list.filter { it.name.isNotBlank() }.ifEmpty { defaults() }
        val arr = JSONArray()
        for (p in clean) arr.put(
            JSONObject().put("id", p.id).put("name", p.name).put("instructions", p.instructions)
                .put("modelId", p.modelId ?: JSONObject.NULL).put("memory", p.memory),
        )
        prefs.edit().putString(KEY, arr.toString()).apply()
        _participants.value = clean
    }

    private fun defaults(): List<Participant> =
        Council.defaultAgents.map { Participant(UUID.randomUUID().toString(), it.name, it.systemPrompt) }

    private fun load(): List<Participant> {
        val raw = prefs.getString(KEY, null) ?: return defaults()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return defaults()
        val list = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            runCatching {
                Participant(
                    id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                    name = o.getString("name"),
                    instructions = o.optString("instructions"),
                    modelId = o.optString("modelId").ifBlank { null }.takeIf { it != "null" },
                    memory = o.optString("memory"),
                )
            }.getOrNull()
        }
        return list.ifEmpty { defaults() }
    }

    companion object {
        private const val KEY = "participants"

        /** The system prompt a member runs with: its instructions plus any long-term memory. */
        fun systemPromptFor(p: Participant): String = buildString {
            append(p.instructions)
            if (p.memory.isNotBlank()) {
                append("\n\nLong-term memory (remember across this conversation):\n").append(p.memory)
            }
        }
    }
}
