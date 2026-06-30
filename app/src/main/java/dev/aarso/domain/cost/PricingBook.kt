package dev.aarso.domain.cost

import org.json.JSONObject

/**
 * Per-model prices, **set by the user** (Cost epic, P2). Provider prices change and are not ours
 * to assert as authoritative (binding rule 8: don't invent the numbers) — so this is a book the
 * user fills, with a single, clearly-labelled fallback for anything unpriced. It backs the
 * "Provider pricing" config surface (the Compose form is 🎨; this is the model + persistence
 * codec it reads/writes). Pure Kotlin; JVM-tested.
 *
 * Model ids match the engines' `tokenizerId` (e.g. `cloud:claude-…`). On-device models cost no
 * money, so [priceFor] returns [UsagePricing.ON_DEVICE] for non-`cloud:` ids unless the user has
 * set an explicit price — time/energy is their real cost, not money (handoff thesis).
 */
data class PricingBook(
    val byModel: Map<String, UsagePricing> = emptyMap(),
    val fallback: UsagePricing = UsagePricing.CONSERVATIVE_DEFAULT,
) {
    /** The price for [modelId]: an explicit entry, else ON_DEVICE for local models, else [fallback]. */
    fun priceFor(modelId: String): UsagePricing =
        byModel[modelId] ?: if (isCloud(modelId)) fallback else UsagePricing.ON_DEVICE

    /** True when the user has set an explicit price for [modelId] (vs. a fallback/on-device guess). */
    fun isExplicit(modelId: String): Boolean = byModel.containsKey(modelId)

    fun with(modelId: String, pricing: UsagePricing): PricingBook =
        copy(byModel = byModel + (modelId to pricing))

    fun without(modelId: String): PricingBook = copy(byModel = byModel - modelId)

    fun withFallback(pricing: UsagePricing): PricingBook = copy(fallback = pricing)

    private fun isCloud(modelId: String): Boolean = modelId.startsWith("cloud:")
}

/** JSON (de)serialisation for persisting a [PricingBook] (the data-layer store writes this string). */
object PricingCodec {

    fun encode(book: PricingBook): String {
        val models = JSONObject()
        for ((id, p) in book.byModel) {
            models.put(id, JSONObject().put("in", p.centsPer1kInput).put("out", p.centsPer1kOutput))
        }
        return JSONObject()
            .put("fallback", JSONObject().put("in", book.fallback.centsPer1kInput).put("out", book.fallback.centsPer1kOutput))
            .put("models", models)
            .toString()
    }

    fun decode(json: String): PricingBook {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return PricingBook()
        val fb = root.optJSONObject("fallback")?.let {
            UsagePricing(it.optLong("in", 1), it.optLong("out", 3))
        } ?: UsagePricing.CONSERVATIVE_DEFAULT
        val models = root.optJSONObject("models")
        val map = LinkedHashMap<String, UsagePricing>()
        if (models != null) {
            for (key in models.keys()) {
                val o = models.getJSONObject(key)
                map[key] = UsagePricing(o.optLong("in", 0), o.optLong("out", 0))
            }
        }
        return PricingBook(map, fb)
    }
}
