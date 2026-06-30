package dev.aarso.domain.cost

import org.json.JSONArray
import org.json.JSONObject

/**
 * The free-tier guide (owner ask): which cloud sources have a free tier and how much. These are
 * **external facts that drift**, so the catalog is data (a bundled JSON, refreshed by a pipeline),
 * each entry carries its own [sourceUrl], and the catalog carries [FreeTierCatalog.lastUpdated] so
 * staleness is visible. We don't assert numbers as truth — we show them with provenance and a
 * "verify on the provider's site" stance (binding rule 8: never present external numbers as ours).
 */
enum class FreeTierKind { ONGOING_FREE, TRIAL_CREDIT }

data class FreeTierProvider(
    val id: String,
    val name: String,
    /** Which app provider-kind this maps to (anthropic / openai-compatible / gemini), if any. */
    val providerKind: String?,
    val kind: FreeTierKind,
    val summary: String,
    /** Optional structured caps (for usage tracking); null when not published in that unit. */
    val requestsPerDay: Int? = null,
    val tokensPerDay: Long? = null,
    val tokensPerMonth: Long? = null,
    /** For trial-credit providers, the headline grant, e.g. "$5 for 90 days" or "10M tokens". */
    val trialCredit: String? = null,
    val requiresCard: Boolean = false,
    val sourceUrl: String,
)

data class FreeTierCatalog(
    /** ISO-ish date the data was last refreshed (e.g. "2026-06-21"). */
    val lastUpdated: String,
    val providers: List<FreeTierProvider>,
) {
    val ongoing: List<FreeTierProvider> get() = providers.filter { it.kind == FreeTierKind.ONGOING_FREE }
    val trial: List<FreeTierProvider> get() = providers.filter { it.kind == FreeTierKind.TRIAL_CREDIT }

    fun byId(id: String): FreeTierProvider? = providers.firstOrNull { it.id == id }
}

/** Pure JSON round-trip for [FreeTierCatalog] — JVM-tested; also what the update pipeline writes. */
object FreeTierCodec {

    fun decode(json: String): FreeTierCatalog {
        val o = JSONObject(json)
        val arr = o.optJSONArray("providers") ?: JSONArray()
        val providers = (0 until arr.length()).mapNotNull { i ->
            val p = arr.optJSONObject(i) ?: return@mapNotNull null
            runCatching {
                FreeTierProvider(
                    id = p.getString("id"),
                    name = p.getString("name"),
                    providerKind = p.optString("providerKind").ifBlank { null },
                    kind = FreeTierKind.valueOf(p.optString("kind", "ONGOING_FREE")),
                    summary = p.optString("summary"),
                    requestsPerDay = if (p.has("requestsPerDay")) p.optInt("requestsPerDay") else null,
                    tokensPerDay = if (p.has("tokensPerDay")) p.optLong("tokensPerDay") else null,
                    tokensPerMonth = if (p.has("tokensPerMonth")) p.optLong("tokensPerMonth") else null,
                    trialCredit = p.optString("trialCredit").ifBlank { null },
                    requiresCard = p.optBoolean("requiresCard", false),
                    sourceUrl = p.optString("sourceUrl"),
                )
            }.getOrNull()
        }
        return FreeTierCatalog(o.optString("lastUpdated"), providers)
    }

    fun encode(catalog: FreeTierCatalog): String {
        val arr = JSONArray()
        for (p in catalog.providers) {
            val o = JSONObject()
                .put("id", p.id).put("name", p.name)
                .put("providerKind", p.providerKind ?: JSONObject.NULL)
                .put("kind", p.kind.name).put("summary", p.summary)
                .put("requiresCard", p.requiresCard).put("sourceUrl", p.sourceUrl)
            p.requestsPerDay?.let { o.put("requestsPerDay", it) }
            p.tokensPerDay?.let { o.put("tokensPerDay", it) }
            p.tokensPerMonth?.let { o.put("tokensPerMonth", it) }
            p.trialCredit?.let { o.put("trialCredit", it) }
            arr.put(o)
        }
        return JSONObject().put("lastUpdated", catalog.lastUpdated).put("providers", arr).toString(2)
    }
}
