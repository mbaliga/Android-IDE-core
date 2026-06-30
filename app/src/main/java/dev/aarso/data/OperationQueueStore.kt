package dev.aarso.data

import android.content.Context
import dev.aarso.domain.net.OpStatus
import dev.aarso.domain.net.OperationQueue
import dev.aarso.domain.net.QueuedOp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the [OperationQueue] (P5) so queued network actions survive process death and the
 * subway — the durable half of "connectivity-resilient." Plain JSON in private SharedPreferences;
 * the worker ([dev.aarso.domain.net.OperationWorker]) loads/saves through [queue]/[set].
 */
class OperationQueueStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("aarso.opqueue", Context.MODE_PRIVATE)

    private val _queue = MutableStateFlow(load())
    val queue: StateFlow<OperationQueue> = _queue.asStateFlow()

    fun set(q: OperationQueue) {
        persist(q)
        _queue.value = q
    }

    private fun persist(q: OperationQueue) {
        val arr = JSONArray()
        for (op in q.ops) arr.put(
            JSONObject()
                .put("id", op.id).put("kind", op.kind).put("payload", op.payload)
                .put("status", op.status.name).put("attempts", op.attempts)
                .put("nextAttemptAt", op.nextAttemptAt).put("lastError", op.lastError ?: JSONObject.NULL),
        )
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private fun load(): OperationQueue {
        val raw = prefs.getString(KEY, null) ?: return OperationQueue()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return OperationQueue()
        val ops = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            runCatching {
                QueuedOp(
                    id = o.getString("id"),
                    kind = o.getString("kind"),
                    payload = o.optString("payload"),
                    status = OpStatus.valueOf(o.optString("status", "PENDING")),
                    attempts = o.optInt("attempts", 0),
                    nextAttemptAt = o.optLong("nextAttemptAt", 0),
                    lastError = o.optString("lastError").ifBlank { null },
                )
            }.getOrNull()
        }
        return OperationQueue(ops)
    }

    private companion object { const val KEY = "queue" }
}
