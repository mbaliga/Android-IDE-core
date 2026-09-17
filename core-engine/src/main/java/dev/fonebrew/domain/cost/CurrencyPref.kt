package dev.fonebrew.domain.cost

import java.util.Currency

/**
 * The user's chosen **display currency** — an ISO-4217 code, nothing more (Cost epic, last
 * mile: the Provider-pricing settings form). This never fetches or assumes an exchange rate
 * (binding rule 1: no phoning home, ever) — a [UsagePricing] rate stays in whatever currency
 * the user typed it in; this only changes the *label*
 * [dev.fonebrew.domain.format.LocaleFormat.currencyMinor] prints next to a cost figure, which
 * the user is responsible for keeping consistent with the rates they entered.
 *
 * [isValid] is checked against the JVM's own ISO-4217 table ([Currency.getInstance]) purely so
 * a typo can never reach [dev.fonebrew.domain.format.LocaleFormat.currencyMinor] and crash it —
 * this is a shape check against data already on-device, not a network lookup or a live-rates
 * call of any kind.
 *
 * Pure Kotlin; JVM-tested.
 */
object CurrencyPref {
    /** The app's default until the user picks otherwise. */
    const val DEFAULT = "USD"

    /** True when [code] (case/whitespace-insensitive) is a currency the JVM's own ISO-4217
     *  table recognises. */
    fun isValid(code: String): Boolean =
        runCatching { Currency.getInstance(code.trim().uppercase()) }.isSuccess

    /** Normalise to uppercase; falls back to [DEFAULT] for anything [isValid] rejects — never
     *  persists a code that would later crash a currency-formatted render. */
    fun normalize(code: String): String {
        val trimmed = code.trim().uppercase()
        return if (isValid(trimmed)) trimmed else DEFAULT
    }
}
