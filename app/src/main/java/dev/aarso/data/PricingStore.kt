package dev.aarso.data

import android.content.Context
import dev.aarso.domain.cost.PricingBook
import dev.aarso.domain.cost.PricingCodec
import dev.aarso.domain.cost.UsagePricing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists the user's [PricingBook] (Cost epic G1 / P2). Prices are the user's to set, not ours
 * to assert (binding rule 8) — this just remembers what they entered, serialised by
 * [PricingCodec] in private SharedPreferences. Read it to price a finished cloud turn.
 */
class PricingStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("aarso.pricing", Context.MODE_PRIVATE)

    private val _book = MutableStateFlow(load())
    val book: StateFlow<PricingBook> = _book.asStateFlow()

    fun set(book: PricingBook) {
        prefs.edit().putString(KEY, PricingCodec.encode(book)).apply()
        _book.value = book
    }

    fun setPrice(modelId: String, pricing: UsagePricing) = set(book.value.with(modelId, pricing))
    fun setFallback(pricing: UsagePricing) = set(book.value.withFallback(pricing))
    fun clear(modelId: String) = set(book.value.without(modelId))

    private fun load(): PricingBook =
        prefs.getString(KEY, null)?.let { PricingCodec.decode(it) } ?: PricingBook()

    private companion object { const val KEY = "book" }
}
