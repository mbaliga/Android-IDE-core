package dev.fonebrew.ui.state

import dev.fonebrew.domain.cost.PricingBook
import dev.fonebrew.domain.cost.UsagePricing

/**
 * Where a [PricingRow]'s current [PricingRow.pricing] actually comes from — the label/glyph the
 * Provider-pricing form shows instead of ever leaning on colour (the owner is red-green
 * colorblind: state must never live in hue alone).
 */
enum class PriceOrigin {
    /** The user typed a price for this exact model ([PricingBook.isExplicit] is true). */
    EXPLICIT,

    /** No per-model price, but the *fallback* rate has itself been changed away from
     *  [UsagePricing.CONSERVATIVE_DEFAULT] — still a number the user chose, just not
     *  model-specific. */
    CUSTOM_FALLBACK,

    /** No per-model price and the fallback is still the built-in placeholder — a labelled
     *  guess, never presented as a real quote (`docs/design/cost.md`'s binding sovereignty
     *  stance: the engine never invents the numbers). */
    DEFAULT_PLACEHOLDER,
}

/** One row of the Provider-pricing form: a priceable model, its current effective per-1k rates,
 *  and where that price came from. */
data class PricingRow(
    /** The exact key [PricingBook.priceFor] uses — an engine's [dev.fonebrew.domain.model.
     *  ModelSpec.tokenizerId] (e.g. `cloud:claude-sonnet-4-5`), so a price set here is
     *  guaranteed to be the price a real turn on that model gets charged. */
    val tokenizerId: String,
    /** Human label for the row (e.g. "Anthropic · claude-sonnet-4-5"). */
    val label: String,
    val pricing: UsagePricing,
    val origin: PriceOrigin,
)

/** One parsed numeric form field: the raw text, the parsed value (non-negative whole number),
 *  and a human error when [raw] didn't parse. Blank is rejected too, same as garbage — never
 *  silently coerced to 0, which would read as a real "this model is free" price the user never
 *  typed. */
data class RateField(val raw: String, val value: Long?, val error: String?) {
    val isValid: Boolean get() = error == null && value != null
}

/**
 * Pure presenter for the "Provider pricing" settings form (Cost epic, last mile —
 * `docs/design/cost.md`'s binding sovereignty stance: **the engine never invents the
 * numbers**). Every figure a [PricingRow] shows was either typed by the user or is the
 * clearly-[PriceOrigin]-labelled [UsagePricing.CONSERVATIVE_DEFAULT] placeholder — never
 * presented as a real quote, and never fetched (binding rule 1: no phoning home — pricing is
 * typed in, not looked up).
 *
 * No Android, no I/O, no clock: the Compose form + [dev.fonebrew.data.PricingStore] own
 * reading/writing the actual [PricingBook]; this object only derives row state and parses
 * input, so both are JVM-tested without a device.
 */
object PricingFormPresenter {

    /** One row per (tokenizerId, label) pair in [models], in the given order — the caller
     *  decides sort order (e.g. the provider list's own display order); this never re-sorts. */
    fun rows(book: PricingBook, models: List<Pair<String, String>>): List<PricingRow> =
        models.map { (tokenizerId, label) ->
            PricingRow(
                tokenizerId = tokenizerId,
                label = label,
                pricing = book.priceFor(tokenizerId),
                origin = originFor(book, tokenizerId),
            )
        }

    /** [PriceOrigin] for one [tokenizerId] against [book] — see the enum's own KDoc for what
     *  each value means. */
    fun originFor(book: PricingBook, tokenizerId: String): PriceOrigin = when {
        book.isExplicit(tokenizerId) -> PriceOrigin.EXPLICIT
        book.fallback != UsagePricing.CONSERVATIVE_DEFAULT -> PriceOrigin.CUSTOM_FALLBACK
        else -> PriceOrigin.DEFAULT_PLACEHOLDER
    }

    /** Parse one rate field the user typed — a per-1,000-token minor-currency-unit integer.
     *  Never coerces blank/garbage/negative to 0. */
    fun parseRate(raw: String): RateField {
        val trimmed = raw.trim()
        val n = trimmed.toLongOrNull()
        return when {
            trimmed.isEmpty() -> RateField(raw, null, "required")
            n == null -> RateField(raw, null, "whole number only")
            n < 0 -> RateField(raw, null, "can't be negative")
            else -> RateField(raw, n, null)
        }
    }

    /** Both fields parsed together into a [UsagePricing], or `null` while either is invalid —
     *  the form's Save action stays disabled until this is non-null. */
    fun parsePricing(inputRaw: String, outputRaw: String): UsagePricing? {
        val input = parseRate(inputRaw)
        val output = parseRate(outputRaw)
        return if (input.isValid && output.isValid) UsagePricing(input.value!!, output.value!!) else null
    }
}
