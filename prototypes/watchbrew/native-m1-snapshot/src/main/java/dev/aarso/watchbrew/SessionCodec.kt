// SPDX-License-Identifier: Apache-2.0
package dev.aarso.watchbrew

import org.json.JSONArray
import org.json.JSONObject

/** Versioned local demo snapshot. This is not the portable conversation archive. */
object SessionCodec {
    fun encode(s: Session): String = JSONObject().apply {
        put("version", 1); put("draft", s.draft); put("replyTo", s.replyTo)
        put("connected", s.connected); put("host", s.host.name); put("mode", s.mode.name)
        put("branch", s.branch); put("task", s.task.name); put("rating", s.rating)
        put("displayEnabled", s.displayEnabled); put("notice", s.notice)
        put("active", s.active?.let(::requestJson))
        put("outbox", JSONArray().apply { s.outbox.forEach { put(requestJson(it)) } })
        put("history", JSONArray(s.history))
    }.toString()

    fun decode(text: String): Session {
        require(text.length <= 128_000) { "Snapshot too large" }
        val json = JSONObject(text)
        require(json.getInt("version") == 1) { "Unsupported snapshot version" }
        val outbox = json.getJSONArray("outbox")
        val history = json.getJSONArray("history")
        require(outbox.length() <= SessionReducer.MAX_OUTBOX && history.length() <= 100)
        return Session(
            draft = json.getString("draft").also { require(it.length <= 1200) },
            replyTo = json.optionalString("replyTo"), connected = json.getBoolean("connected"),
            host = Host.valueOf(json.getString("host")), mode = ConversationMode.valueOf(json.getString("mode")),
            branch = json.getInt("branch").also { require(it > 0) }, task = TaskStatus.valueOf(json.getString("task")),
            active = json.optJSONObject("active")?.let(::request),
            outbox = List(outbox.length()) { request(outbox.getJSONObject(it)) },
            rating = json.getInt("rating").also { require(it in -2..2) },
            displayEnabled = json.getBoolean("displayEnabled"),
            history = List(history.length()) { history.getString(it) }, notice = json.getString("notice"),
        ).also { require(!it.busy || it.active != null) { "Running task has no request" } }
    }
    private fun requestJson(r: Request) = JSONObject().apply {
        put("id", r.id); put("text", r.text); put("host", r.host.name)
        put("mode", r.mode.name); put("branch", r.branch); put("replyTo", r.replyTo)
    }
    private fun request(j: JSONObject) = Request(j.getString("id").also { require(it.isNotBlank()) },
        j.getString("text").also { require(it.length <= 1200) }, Host.valueOf(j.getString("host")),
        ConversationMode.valueOf(j.getString("mode")), j.getInt("branch").also { require(it > 0) }, j.optionalString("replyTo"))
    private fun JSONObject.optionalString(key: String) = if (has(key) && !isNull(key)) getString(key) else null
}
