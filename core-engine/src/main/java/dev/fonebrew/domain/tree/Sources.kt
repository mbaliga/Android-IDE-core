package dev.fonebrew.domain.tree

import dev.fonebrew.domain.cloud.Source
import org.json.JSONArray
import org.json.JSONObject

/**
 * Web-search result sources a server-side search tool surfaced during a turn
 * (daily-driver.md W2 — "Web search"). Stored on the assistant node as
 * `metadata[Conversations.SOURCES_KEY]` = a JSON array of `{title, url}` — same
 * org.json convention as [Attachments] (`data/Converters.kt`'s precedent), no new
 * JSON library. Deliberately mirrors [Attachments]'s shape/tolerance rather than
 * living inside `domain/cloud/Source.kt`: that file is the pure per-provider parse
 * target, this is the tree-metadata codec, matching how [Attachments] sits beside
 * (not inside) the attachment file-store.
 */
object Sources {

    /** [sources] -> JSON array string, e.g. `[{"title":"...","url":"..."}]`. */
    fun encode(sources: List<Source>): String {
        val arr = JSONArray()
        for (s in sources) {
            val obj = JSONObject()
            obj.put("title", s.title)
            obj.put("url", s.url)
            arr.put(obj)
        }
        return arr.toString()
    }

    /** JSON array string -> [Source] list. Blank/malformed input decodes to an empty
     *  list rather than throwing — this reads persisted node metadata, and a render pass
     *  must never crash on it. Elements missing a non-empty "url" are skipped; a missing
     *  "title" falls back to the URL itself so a footer row is never blank. */
    fun decode(json: String?): List<Source> {
        if (json.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Source>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val url = obj.optString("url", "")
            if (url.isEmpty()) continue
            out += Source(title = obj.optString("title", "").ifEmpty { url }, url = url)
        }
        return out
    }
}
