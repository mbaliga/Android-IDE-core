package dev.aarso.domain.reliability

import org.json.JSONObject

/**
 * A mid-stream checkpoint of an assistant reply that hasn't been persisted to the message tree
 * yet (daily-driver.md W3 — crash-safe partial replies). The tree is append-only and an
 * assistant node is only ever inserted once its turn finishes streaming, so a hard crash
 * mid-stream would otherwise lose the whole in-progress reply. This is the file-based
 * checkpoint that survives that crash — same "no node mutation, checkpoint to a file" shape as
 * [dev.aarso.data.KvCacheStore]'s KV-cache session snapshots, just carrying the streamed text
 * instead of a llama.cpp session blob. org.json codec, same convention as
 * [dev.aarso.domain.tree.Attachments]/[dev.aarso.domain.tree.Sources] — no new JSON library.
 *
 * @property userNodeId the turn this reply belongs to — also the checkpoint file's name
 *   (`filesDir/outbox/<userNodeId>.partial.json`, [dev.aarso.data.PartialReplyStore]).
 * @property modelId the model that was generating this reply.
 * @property text the streamed text so far.
 * @property updatedAt wall-clock millis of this checkpoint.
 */
data class PartialReply(
    val userNodeId: String,
    val modelId: String,
    val text: String,
    val updatedAt: Long,
) {
    fun encode(): String = JSONObject().apply {
        put("userNodeId", userNodeId)
        put("modelId", modelId)
        put("text", text)
        put("updatedAt", updatedAt)
    }.toString()

    companion object {
        /**
         * Malformed/incomplete JSON (including a torn write from the very crash that orphaned
         * the file) decodes to null rather than throwing — a checkpoint is best-effort by
         * nature, and a recovery pass must never itself crash on a bad one. A missing/blank
         * `userNodeId` is likewise treated as unusable, since it's the only way a checkpoint
         * maps back to a tree node.
         */
        fun decode(json: String): PartialReply? {
            val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
            val userNodeId = obj.optString("userNodeId", "")
            if (userNodeId.isEmpty()) return null
            return PartialReply(
                userNodeId = userNodeId,
                modelId = obj.optString("modelId", ""),
                text = obj.optString("text", ""),
                updatedAt = obj.optLong("updatedAt", 0L),
            )
        }
    }
}
