package dev.aarso.domain.catalog

import org.json.JSONArray
import org.json.JSONObject

/**
 * The shared, cross-app catalog of downloadable AI models. Per the owner's constellation-wide
 * data-sharing model, Nooz owns this list (its `ai-catalogue/models.json`, see that repo's
 * `ai-catalogue/README.md` for the consumer contract) and every app — Aarso included — consumes
 * it rather than hand-maintaining its own. This file is a straight mirror of that schema: `kind`
 * discriminates chat GGUFs from single-file SD checkpoints, `policySafe` is the flag a
 * policy-restricted storefront build filters on (see
 * [dev.aarso.flavor.InvocationFeatures.CATALOG_POLICY_SAFE_ONLY]), and a null [RemoteCatalogEntry
 * .downloadUrl] means no verified mirror is known yet — never coerce that into "available"
 * (Nooz's own honesty rule).
 */
enum class RemoteCatalogKind { LLM_GGUF, SD_IMAGE, UNKNOWN }

data class RemoteCatalogEntry(
    val id: String,
    val name: String,
    val kind: RemoteCatalogKind,
    val family: String?,
    val params: String?,
    val quant: String?,
    val contextWindow: Int?,
    val sizeBytes: Long,
    val downloadUrl: String?,
    val hfRepo: String?,
    val hfFile: String?,
    val sha256: String?,
    val policySafe: Boolean,
    val verifiedAt: String?,
    val note: String?,
)

data class RemoteModelCatalog(
    val schemaVersion: Int,
    val lastUpdated: String,
    val entries: List<RemoteCatalogEntry>,
)

/** Pure JSON decode for Nooz's `ai-catalogue/models.json` — JVM-tested, no Android deps. */
object RemoteCatalogCodec {

    fun decode(json: String): RemoteModelCatalog {
        val o = JSONObject(json)
        val arr = o.optJSONArray("models") ?: JSONArray()
        val entries = (0 until arr.length()).mapNotNull { i ->
            val m = arr.optJSONObject(i) ?: return@mapNotNull null
            runCatching {
                RemoteCatalogEntry(
                    id = m.getString("id"),
                    name = m.getString("name"),
                    kind = runCatching { RemoteCatalogKind.valueOf(m.optString("kind", "LLM_GGUF")) }
                        .getOrDefault(RemoteCatalogKind.UNKNOWN),
                    family = m.stringOrNull("family"),
                    params = m.stringOrNull("params"),
                    quant = m.stringOrNull("quant"),
                    contextWindow = if (m.has("contextWindow") && !m.isNull("contextWindow")) m.optInt("contextWindow") else null,
                    sizeBytes = m.optLong("sizeBytes", 0L),
                    downloadUrl = m.stringOrNull("downloadUrl"),
                    hfRepo = m.stringOrNull("hfRepo"),
                    hfFile = m.stringOrNull("hfFile"),
                    sha256 = m.stringOrNull("sha256"),
                    policySafe = m.optBoolean("policySafe", true),
                    verifiedAt = m.stringOrNull("verifiedAt"),
                    note = m.stringOrNull("note"),
                )
            }.getOrNull()
        }
        return RemoteModelCatalog(
            schemaVersion = o.optInt("schemaVersion", 1),
            lastUpdated = o.optString("lastUpdated"),
            entries = entries,
        )
    }

    /** Absent, JSON null, and blank all collapse to Kotlin null — the schema's "unknown" marker. */
    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).ifBlank { null }
}
