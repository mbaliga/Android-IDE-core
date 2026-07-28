package dev.aarso.data

import android.content.Context
import dev.aarso.domain.loop.Loop
import dev.aarso.domain.loop.LoopLifecycle
import dev.aarso.domain.loop.LoopState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Local persistence for Loops (docs/design/workflow-builder.md): the BPMN definition
 * + the lifecycle envelope, so the Loops list survives process death. A loop's BPMN
 * also syncs to the user's Git host (.bpmn); this is the on-device index. All
 * lifecycle transitions go through the pure [LoopLifecycle]. Owner-verified (Android).
 */
class LoopStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("aarso.loops", Context.MODE_PRIVATE)

    private val _loops = MutableStateFlow(load())
    val loops: StateFlow<List<Loop>> = _loops.asStateFlow()

    fun get(id: String): Loop? = _loops.value.firstOrNull { it.id == id }

    fun inState(state: LoopState): List<Loop> = LoopLifecycle.inState(_loops.value, state)

    /** Create or replace a loop (the builder edits Unused drafts; lifecycle guards apply upstream). */
    fun save(loop: Loop) = mutate { list -> list.filterNot { it.id == loop.id } + loop }

    fun trigger(id: String, now: Long = System.currentTimeMillis()) = transform(id) { LoopLifecycle.trigger(it, now) }

    fun retire(id: String, now: Long = System.currentTimeMillis()) = transform(id) { LoopLifecycle.retire(it, now) }

    /** Duplicate any loop into a fresh non-live Unused draft; returns the copy. */
    fun duplicate(id: String, now: Long = System.currentTimeMillis()): Loop? {
        val src = get(id) ?: return null
        val copy = LoopLifecycle.duplicate(src, UUID.randomUUID().toString(), now)
        save(copy)
        return copy
    }

    fun delete(id: String) = mutate { list -> list.filterNot { it.id == id } }

    private fun transform(id: String, f: (Loop) -> Loop) = mutate { list -> list.map { if (it.id == id) f(it) else it } }

    private fun mutate(f: (List<Loop>) -> List<Loop>) {
        val next = f(_loops.value).sortedByDescending { it.updatedAt }
        prefs.edit().putString(KEY, encode(next)).apply()
        _loops.value = next
    }

    private fun load(): List<Loop> = runCatching { decode(prefs.getString(KEY, null) ?: "[]") }.getOrDefault(emptyList())

    private fun encode(loops: List<Loop>): String {
        val arr = JSONArray()
        for (l in loops) {
            arr.put(
                JSONObject()
                    .put("id", l.id)
                    .put("name", l.name)
                    .put("bpmn", l.bpmnXml ?: JSONObject.NULL)
                    .put("state", l.state.name)
                    .put("createdAt", l.createdAt)
                    .put("updatedAt", l.updatedAt)
                    .put("lastRunAt", l.lastRunAt ?: JSONObject.NULL),
            )
        }
        return arr.toString()
    }

    private fun decode(json: String): List<Loop> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Loop(
                id = o.getString("id"),
                name = o.optString("name"),
                bpmnXml = if (o.isNull("bpmn")) null else o.optString("bpmn").ifEmpty { null },
                state = runCatching { LoopState.valueOf(o.optString("state")) }.getOrDefault(LoopState.UNUSED),
                createdAt = o.optLong("createdAt"),
                updatedAt = o.optLong("updatedAt"),
                lastRunAt = if (o.isNull("lastRunAt")) null else o.optLong("lastRunAt"),
            )
        }
    }

    private companion object {
        const val KEY = "loops.v1"
    }
}
