package dev.aarso.domain.format

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.MessageFormat
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale
import kotlin.math.abs

/**
 * **LocaleFormat** — the one place numbers, dates, durations and sizes become *words*.
 *
 * Doc 00 §3.3 is a binding rule, not a nicety: *every number, date, duration, token count,
 * percentage and currency must be locale-aware* — never `String.format` with a literal
 * grouping/decimal symbol. The motivating failure is the **Indian grouping** convention: a
 * six-digit token count reads "1,23,456" for a Hindi / Konkani / Indian-English reader and
 * "123,456" for a US reader. A naive `%,d` against the JVM default locale silently gets one
 * of those wrong for half the priority locales (English, Hindi, Konkani, Spanish, Japanese,
 * Urdu — the last RTL). This object makes the *right* one fall out of the locale.
 *
 * **Design constraints (on-thesis: legible + testable):**
 *  - Every function takes an **explicit [Locale]** — these are pure, deterministic functions.
 *    They never read a device/global default locale, so a JVM unit test can pin `en-IN` and
 *    assert the grouping without an Android runtime or an emulator.
 *  - JVM-only primitives: [java.text.NumberFormat], [java.time], [java.text.MessageFormat],
 *    [java.time.format.DateTimeFormatter]. Deliberately **no `android.icu.*`** — that package
 *    is absent under plain JVM unit tests, and the JDK formatters already carry CLDR data
 *    (including Indian grouping and localized digits) sufficient for the priority locales.
 *  - Digits are always rendered through the locale's [NumberFormat], so an Urdu/Arabic or
 *    Devanagari-digit locale gets its own glyphs even inside composite strings (durations,
 *    byte sizes, relative times) — the digits never leak as bare ASCII.
 *
 * Pure domain, JVM-tested. No Android imports; safe to call from anywhere in `domain/`.
 */
object LocaleFormat {

    // ---- integers -----------------------------------------------------------------------

    /**
     * A **token count** as a locale-grouped integer. Identical to [count] but named for the
     * call site that dominates the UI (token meters), so intent is legible at the use point.
     *
     * Examples: `123456` → "123,456" under `en-US`; → "1,23,456" under `en-IN` / `hi-IN`
     * (Indian lakh grouping). The grouping symbol and digit glyphs come from [locale].
     */
    fun tokens(count: Long, locale: Locale): String = grouped(count, locale)

    /** A generic grouped integer (Long). See [tokens] for the grouping rationale. */
    fun count(n: Long, locale: Locale): String = grouped(n, locale)

    /** A generic grouped integer (Int) — convenience overload of [count]. */
    fun count(n: Int, locale: Locale): String = grouped(n.toLong(), locale)

    private fun grouped(n: Long, locale: Locale): String {
        if (isIndianLocale(locale)) {
            val nf = NumberFormat.getIntegerInstance(locale)
            nf.isGroupingUsed = false
            val sym = java.text.DecimalFormatSymbols.getInstance(locale)
            val body = indianGroupInt(nf.format(abs(n)), sym.groupingSeparator)
            return if (n < 0) "${sym.minusSign}$body" else body
        }
        val nf = NumberFormat.getIntegerInstance(locale)
        nf.isGroupingUsed = true
        return nf.format(n)
    }

    /**
     * Indian (lakh) locales group `3,2,2…` (e.g. "12,34,567"), which standard
     * [java.text.DecimalFormat] cannot express (its grouping size is uniform) and which this
     * JDK's CLDR provider does **not** reliably emit for `en-IN`/`hi-IN`. Doc 00 §3.3 flags
     * Indian grouping as a first-class requirement and a robustness canary, so we supply it
     * ourselves rather than trust the platform locale data.
     */
    private fun isIndianLocale(locale: Locale): Boolean {
        if (locale.country.equals("IN", ignoreCase = true)) return true
        return locale.language.lowercase(Locale.ROOT) in INDIAN_LANGUAGES
    }

    private val INDIAN_LANGUAGES = setOf(
        "hi", "kok", "bn", "ta", "te", "mr", "gu", "kn", "ml", "pa", "or", "as", "ne", "si",
    )

    /**
     * Group a pure (already-localized, separator-free) digit string in the Indian convention:
     * the last three digits, then groups of two, joined by [sep]. Digit *glyphs* are preserved
     * (Devanagari/Arabic-Indic digits are single BMP chars, so char-position grouping is safe).
     */
    private fun indianGroupInt(digits: String, sep: Char): String {
        if (digits.length <= 3) return digits
        val last3 = digits.takeLast(3)
        val rest = digits.dropLast(3)
        val sb = StringBuilder()
        var i = rest.length
        while (i > 0) {
            val start = maxOf(0, i - 2)
            if (sb.isNotEmpty()) sb.insert(0, sep)
            sb.insert(0, rest.substring(start, i))
            i = start
        }
        return "$sb$sep$last3"
    }

    // ---- percent / decimal --------------------------------------------------------------

    /**
     * A **percentage** from a *fraction* in 0..1 (so `0.78` → "78%", not "0.78%"). [decimals]
     * fixes the fraction digits shown (default 0). The `%` sign placement, the decimal
     * separator and the digits are all locale-driven; some locales add a space before `%`.
     */
    fun percent(fraction: Double, locale: Locale, decimals: Int = 0): String {
        val nf = NumberFormat.getPercentInstance(locale)
        nf.minimumFractionDigits = decimals.coerceAtLeast(0)
        nf.maximumFractionDigits = decimals.coerceAtLeast(0)
        return nf.format(fraction)
    }

    /**
     * A **decimal** number with up to [maxFractionDigits] fraction digits (trailing zeros are
     * dropped — minimum 0). The decimal and grouping separators follow [locale]
     * (e.g. `1234.5` → "1,234.5" under `en-US`, "1.234,5" under `es-ES`).
     */
    fun decimal(value: Double, locale: Locale, maxFractionDigits: Int = 2): String {
        val nf = NumberFormat.getNumberInstance(locale)
        nf.minimumFractionDigits = 0
        nf.maximumFractionDigits = maxFractionDigits.coerceAtLeast(0)
        return nf.format(value)
    }

    // ---- currency -----------------------------------------------------------------------

    /**
     * A **currency amount given in minor units** (cents, paise, sen…). The minor amount is
     * scaled down by the currency's own default fraction digits — `12345` USD-minor → `123.45`
     * → "$123.45"; `12345` INR-minor → `123.45` → "₹123.45"; `1234` JPY-minor → `1234` →
     * "￥1,234" (JPY has 0 fraction digits, so minor == major). The symbol, grouping and
     * digit glyphs come from [locale]; the *currency* (and thus its fraction-digit count and
     * symbol family) comes from [currencyCode] — the two are independent on purpose, so an
     * Indian-locale user can still see a USD price formatted with Indian grouping.
     *
     * @param currencyCode an ISO-4217 code, e.g. "USD", "INR", "JPY".
     */
    fun currencyMinor(amountMinorUnits: Long, currencyCode: String, locale: Locale): String {
        val currency = Currency.getInstance(currencyCode.uppercase(Locale.ROOT))
        val fractionDigits = currency.defaultFractionDigits.coerceAtLeast(0)
        // Scale minor → major exactly via BigDecimal (no binary-float rounding error).
        val major = BigDecimal(amountMinorUnits)
            .movePointLeft(fractionDigits)
            .setScale(fractionDigits, RoundingMode.UNNECESSARY)
        if (isIndianLocale(locale)) {
            // Lakh-grouped currency the platform CLDR won't emit here: symbol + 3,2,2 integer
            // + localized decimal + fraction. (See indianGroupInt.)
            val dfs = java.text.DecimalFormatSymbols.getInstance(locale)
            val plain = NumberFormat.getNumberInstance(locale).apply {
                isGroupingUsed = false
                minimumFractionDigits = fractionDigits
                maximumFractionDigits = fractionDigits
            }.format(major.abs())
            val parts = plain.split(dfs.decimalSeparator)
            val intGrouped = indianGroupInt(parts[0], dfs.groupingSeparator)
            val body = if (parts.size > 1) "$intGrouped${dfs.decimalSeparator}${parts[1]}" else intGrouped
            val sign = if (major.signum() < 0) dfs.minusSign.toString() else ""
            return "$sign${currency.getSymbol(locale)}$body"
        }
        val nf = NumberFormat.getCurrencyInstance(locale)
        nf.currency = currency
        nf.minimumFractionDigits = fractionDigits
        nf.maximumFractionDigits = fractionDigits
        return nf.format(major)
    }

    // ---- duration -----------------------------------------------------------------------

    /**
     * A **compact human duration** for [ms] milliseconds. The breakpoints, ascending:
     *  - `< 1000 ms` → "350ms"
     *  - `< 60 s`    → "1.2s" (one fraction digit)
     *  - `< 60 min`  → "2m 3s" (seconds omitted when zero → "2m")
     *  - `>= 60 min` → "1h 4m" (minutes omitted when zero → "1h")
     *
     * Every digit is rendered through [locale]'s number format (so Devanagari/Urdu digits
     * appear), while the unit letters (ms/s/m/h) stay ASCII — intentionally compact and
     * script-neutral, matching the dense token/latency meters in the UI. Negative inputs are
     * treated as their magnitude.
     */
    fun durationMs(ms: Long, locale: Locale): String {
        val total = abs(ms)
        return when {
            total < 1_000L -> intUnit(total, "ms", locale)
            total < 60_000L -> {
                val seconds = total / 1000.0
                "${decimal(seconds, locale, maxFractionDigits = 1)}s"
            }
            total < 3_600_000L -> {
                val m = total / 60_000L
                val s = (total % 60_000L) / 1000L
                if (s == 0L) intUnit(m, "m", locale)
                else "${intUnit(m, "m", locale)} ${intUnit(s, "s", locale)}"
            }
            else -> {
                val h = total / 3_600_000L
                val m = (total % 3_600_000L) / 60_000L
                if (m == 0L) intUnit(h, "h", locale)
                else "${intUnit(h, "h", locale)} ${intUnit(m, "m", locale)}"
            }
        }
    }

    private fun intUnit(value: Long, unit: String, locale: Locale): String {
        val nf = NumberFormat.getIntegerInstance(locale)
        nf.isGroupingUsed = false // small unit-scoped numbers read better ungrouped
        return "${nf.format(value)}$unit"
    }

    // ---- bytes --------------------------------------------------------------------------

    /**
     * A **human byte size** using **decimal (SI) units** — 1 kB = 1000 B, 1 MB = 1_000_000 B,
     * up through TB. (Decimal, not binary, is chosen deliberately: it matches how storage and
     * download sizes are advertised, which is what users compare against.) One fraction digit
     * above the byte threshold (e.g. `10_500_000` → "10.5 MB"); raw byte counts below 1000 are
     * shown whole ("512 B"). Digits and the decimal separator follow [locale]; the unit label
     * stays ASCII. Negative inputs are treated as their magnitude.
     */
    fun bytes(bytes: Long, locale: Locale): String {
        val total = abs(bytes)
        if (total < 1000L) return "${count(total, locale)} B"
        val units = listOf("kB", "MB", "GB", "TB", "PB")
        var value = total.toDouble()
        var unitIndex = -1
        while (value >= 1000.0 && unitIndex < units.size - 1) {
            value /= 1000.0
            unitIndex++
        }
        return "${decimal(value, locale, maxFractionDigits = 1)} ${units[unitIndex]}"
    }

    // ---- date / time --------------------------------------------------------------------

    /**
     * A **localized absolute date+time** at [FormatStyle.MEDIUM] (e.g. en-US
     * "Jun 30, 2026, 1:23:45 PM"). [epochMillis] is interpreted in [zone]; the field order,
     * month names, separators and digits follow [locale]. Use [relativeOrAbsolute] when a
     * relative phrase is wanted for recent timestamps.
     */
    fun dateTimeAbsolute(epochMillis: Long, zone: ZoneId, locale: Locale): String {
        val zoned = Instant.ofEpochMilli(epochMillis).atZone(zone)
        val fmt = DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withLocale(locale)
        return zoned.format(fmt)
    }

    /**
     * A **relative phrase** ("just now", "3 minutes ago", "in 2 hours", "5 days ago") when
     * `|now − then|` is under [thresholdMs] (default 7 days), otherwise the localized absolute
     * date+time from [dateTimeAbsolute].
     *
     * The relative buckets are implemented here — *not* via `android.icu.RelativeDateTimeFormatter*
     * which is unavailable under JVM tests — using [MessageFormat] with the locale's number
     * format substituted in, so the count digits localize. Buckets, ascending by magnitude:
     *  - `< 30 s`               → "just now"
     *  - `< 60 min`             → N minutes (≥ 1 min); seconds bucket folds into minutes once
     *                             past 60 s, sub-minute non-trivial gaps read as "N seconds"
     *  - `< 24 h`               → N hours
     *  - `< thresholdMs`        → N days
     *  - `>= thresholdMs`       → absolute
     *
     * Direction: a *future* [epochMillis] (then > now) phrases as "in N …"; past as "N … ago".
     * Phrasing is intentionally simple English templates with localized *numbers* — full
     * per-language wording is a later i18n-resources concern, but the digits are correct today.
     */
    fun relativeOrAbsolute(
        epochMillis: Long,
        nowMillis: Long,
        zone: ZoneId,
        locale: Locale,
        thresholdMs: Long = 7L * 24 * 3600 * 1000,
    ): String {
        val deltaMs = epochMillis - nowMillis // > 0 → future, < 0 → past
        val magnitude = abs(deltaMs)
        if (magnitude >= thresholdMs) return dateTimeAbsolute(epochMillis, zone, locale)

        val future = deltaMs > 0
        return when {
            magnitude < 30_000L -> "just now"
            magnitude < 60_000L -> relPhrase(magnitude / 1000L, "second", future, locale)
            magnitude < 3_600_000L -> relPhrase(magnitude / 60_000L, "minute", future, locale)
            magnitude < 86_400_000L -> relPhrase(magnitude / 3_600_000L, "hour", future, locale)
            else -> relPhrase(magnitude / 86_400_000L, "day", future, locale)
        }
    }

    /**
     * Build one relative phrase. [n] is the (already bucketed) whole count, [unit] is the
     * singular English unit; the count is localized via [MessageFormat] so its digits follow
     * [locale]. Pluralization is the trivial English "+s" rule (good enough for the count
     * digits being correct today; richer plural rules are a resource-file concern).
     */
    private fun relPhrase(n: Long, unit: String, future: Boolean, locale: Locale): String {
        val localizedN = MessageFormat("{0}", locale).format(arrayOf<Any>(n))
        val unitWord = if (n == 1L) unit else "${unit}s"
        return if (future) "in $localizedN $unitWord" else "$localizedN $unitWord ago"
    }
}
