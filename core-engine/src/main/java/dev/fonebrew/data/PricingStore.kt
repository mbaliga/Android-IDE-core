package dev.fonebrew.data

import android.content.Context
import dev.fonebrew.domain.cost.CurrencyPref
import dev.fonebrew.domain.cost.PricingBook
import dev.fonebrew.domain.cost.PricingCodec
import dev.fonebrew.domain.cost.UsagePricing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists the user's [PricingBook] (Cost epic G1 / P2) plus their **display-currency**
 * preference — an ISO-4217 code, nothing more ([CurrencyPref]: no exchange rate is ever
 * fetched, binding rule 1). Prices are the user's to set, not ours to assert (binding rule 8) —
 * this just remembers what they entered, serialised by [PricingCodec] in private
 * SharedPreferences. Read it to price a finished cloud turn, or to label a cost figure with the
 * right currency symbol.
 */
class PricingStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("aarso.pricing", Context.MODE_PRIVATE)

    private val _book = MutableStateFlow(load())
    val book: StateFlow<PricingBook> = _book.asStateFlow()

    private val _currencyCode = MutableStateFlow(loadCurrencyCode())
    /** ISO-4217 code every cost figure should be labelled with (default [CurrencyPref.DEFAULT]).
     *  A plain text/pick preference — this store never fetches or infers an exchange rate. */
    val currencyCode: StateFlow<String> = _currencyCode.asStateFlow()

    fun set(book: PricingBook) {
        prefs.edit().putString(KEY, PricingCodec.encode(book)).apply()
        _book.value = book
    }

    fun setPrice(modelId: String, pricing: UsagePricing) = set(book.value.with(modelId, pricing))
    fun setFallback(pricing: UsagePricing) = set(book.value.withFallback(pricing))
    fun clear(modelId: String) = set(book.value.without(modelId))

    /** Set the display currency. Normalised through [CurrencyPref] so a typo can never persist
     *  a code that would later crash a currency-formatted render. */
    fun setCurrencyCode(code: String) {
        val normalized = CurrencyPref.normalize(code)
        prefs.edit().putString(CURRENCY_KEY, normalized).apply()
        _currencyCode.value = normalized
    }

    private fun load(): PricingBook =
        prefs.getString(KEY, null)?.let { PricingCodec.decode(it) } ?: PricingBook()

    private fun loadCurrencyCode(): String =
        prefs.getString(CURRENCY_KEY, null)?.let(CurrencyPref::normalize) ?: CurrencyPref.DEFAULT

    private companion object {
        const val KEY = "book"
        const val CURRENCY_KEY = "currency"
    }
}
