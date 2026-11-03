package dev.fonebrew.domain.tree

import org.json.JSONArray
import org.json.JSONObject

/**
 * User-node photo/file attachments (daily-driver.md W1 — vision input). Stored on the
 * node as `metadata[Conversations.ATTACHMENTS_KEY]` = a JSON array of `{path, mime}` —
 * path-only in v1, no width/height/kind fields (CLAUDE.md decision register #4: metadata
 * + files, not a Room table, since the tree is append-only). The files themselves live in
 * `dev.fonebrew.data.AttachmentStore`; this is just the pure encode/decode, org.json like
 * `data/Converters.kt` elsewhere in this codebase — no new JSON library.
 */
object Attachments {

    data class Attachment(val path: String, val mime: String)

    /** [attachments] -> JSON array string, e.g. `[{"path":"...","mime":"image/jpeg"}]`. */
    fun encode(attachments: List<Attachment>): String {
        val arr = JSONArray()
        for (a in attachments) {
            val obj = JSONObject()
            obj.put("path", a.path)
            obj.put("mime", a.mime)
            arr.put(obj)
        }
        return arr.toString()
    }

    /** JSON array string -> [Attachment] list. Blank/malformed input decodes to an empty
     *  list rather than throwing — this reads persisted node metadata, and a render pass
     *  must never crash on it. Elements missing a non-empty "path" are skipped. */
    fun decode(json: String?): List<Attachment> {
        if (json.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Attachment>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val path = obj.optString("path", "")
            if (path.isEmpty()) continue
            out += Attachment(path, obj.optString("mime", ""))
        }
        return out
    }
}
