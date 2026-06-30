package dev.aarso.domain.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/**
 * JVM unit tests for [LocaleFormat]. Every case pins an explicit [Locale] (and, for time,
 * an explicit [ZoneId] + fixed epoch millis) so results are deterministic regardless of the
 * machine's default locale or zone.
 *
 * For the script-specific locales (hi-IN, ur) we assert the *grouping pattern* with Western
 * digits under en-IN rather than a specific Devanagari/Arabic-Indic output, per the brief.
 */
class LocaleFormatTest {

    private val enUS = Locale.US
    private val enIN = Locale("en", "IN")
    private val hiIN = Locale("hi", "IN")
    private val esES = Locale("es", "ES")
    private val utc: ZoneId = ZoneOffset.UTC

    // ---- grouping: the headline Indian-vs-US case ---------------------------------------

    @Test fun `tokens use US grouping under en-US`() {
        assertEquals("123,456", LocaleFormat.tokens(123_456L, enUS))
    }

    @Test fun `tokens use Indian lakh grouping under en-IN`() {
        // Six-digit number → "1,23,456" (lakh grouping), Western digits under en-IN.
        assertEquals("1,23,456", LocaleFormat.tokens(123_456L, enIN))
    }

    @Test fun `forLanguageTag en-IN also gives Indian grouping`() {
        assertEquals("1,23,456", LocaleFormat.tokens(123_456L, Locale.forLanguageTag("en-IN")))
    }

    @Test fun `hi-IN uses Indian grouping pattern`() {
        // Assert the grouping *positions* (two commas, last group of three) regardless of digit glyphs.
        val out = LocaleFormat.tokens(123_456L, hiIN)
        assertEquals(2, out.count { it == ',' })
    }

    @Test fun `count overloads agree for Int and Long`() {
        assertEquals(LocaleFormat.count(7_890L, enUS), LocaleFormat.count(7_890, enUS))
        assertEquals("7,890", LocaleFormat.count(7_890, enUS))
    }

    @Test fun `large number grouping under en-IN`() {
        // 12,34,567 — crore-adjacent grouping check.
        assertEquals("12,34,567", LocaleFormat.tokens(1_234_567L, enIN))
    }

    // ---- percent ------------------------------------------------------------------------

    @Test fun `percent renders fraction as whole percent`() {
        assertEquals("78%", LocaleFormat.percent(0.78, enUS))
    }

    @Test fun `percent honors decimals`() {
        assertEquals("78.5%", LocaleFormat.percent(0.785, enUS, decimals = 1))
    }

    @Test fun `percent zero and one`() {
        assertEquals("0%", LocaleFormat.percent(0.0, enUS))
        assertEquals("100%", LocaleFormat.percent(1.0, enUS))
    }

    // ---- decimal ------------------------------------------------------------------------

    @Test fun `decimal trims trailing zeros and groups`() {
        assertEquals("1,234.5", LocaleFormat.decimal(1234.5, enUS, maxFractionDigits = 2))
    }

    @Test fun `decimal uses es-ES separators`() {
        // Spanish: '.' groups, ',' is the decimal mark.
        assertEquals("1.234,5", LocaleFormat.decimal(1234.5, esES, maxFractionDigits = 2))
    }

    // ---- currency (minor units) ---------------------------------------------------------

    @Test fun `USD minor units scale by two fraction digits`() {
        assertEquals("$123.45", LocaleFormat.currencyMinor(12_345L, "USD", enUS))
    }

    @Test fun `INR minor units format under en-IN with rupee and lakh grouping`() {
        // 1,23,456.78 rupees from paise; symbol ₹, Indian grouping.
        val out = LocaleFormat.currencyMinor(12_345_678L, "INR", enIN)
        assertTrue("expected rupee sign in $out", out.contains("₹"))
        assertTrue("expected lakh grouping in $out", out.contains("1,23,456"))
        assertTrue("expected paise in $out", out.contains(".78"))
    }

    @Test fun `JPY has zero fraction digits so minor equals major`() {
        // 1234 yen-minor == 1234 yen; grouped, no decimal point.
        val out = LocaleFormat.currencyMinor(1_234L, "JPY", enUS)
        assertTrue("expected 1,234 in $out", out.contains("1,234"))
        assertFalse("JPY should have no decimal point: $out", out.contains("."))
    }

    // ---- duration -----------------------------------------------------------------------

    @Test fun `duration sub-second renders ms`() {
        assertEquals("350ms", LocaleFormat.durationMs(350L, enUS))
    }

    @Test fun `duration seconds render with one decimal`() {
        assertEquals("1.2s", LocaleFormat.durationMs(1_200L, enUS))
    }

    @Test fun `duration minutes and seconds`() {
        assertEquals("2m 3s", LocaleFormat.durationMs(123_000L, enUS))
    }

    @Test fun `duration whole minutes omit seconds`() {
        assertEquals("2m", LocaleFormat.durationMs(120_000L, enUS))
    }

    @Test fun `duration hours and minutes`() {
        // 1h 4m = 3_840_000 ms
        assertEquals("1h 4m", LocaleFormat.durationMs(3_840_000L, enUS))
    }

    @Test fun `duration whole hours omit minutes`() {
        assertEquals("1h", LocaleFormat.durationMs(3_600_000L, enUS))
    }

    // ---- bytes --------------------------------------------------------------------------

    @Test fun `bytes below 1000 stay raw`() {
        assertEquals("512 B", LocaleFormat.bytes(512L, enUS))
    }

    @Test fun `bytes use decimal MB`() {
        assertEquals("10.5 MB", LocaleFormat.bytes(10_500_000L, enUS))
    }

    @Test fun `bytes use decimal GB`() {
        assertEquals("2.5 GB", LocaleFormat.bytes(2_500_000_000L, enUS))
    }

    @Test fun `kilobytes boundary`() {
        assertEquals("1 kB", LocaleFormat.bytes(1_000L, enUS))
    }

    // ---- relative / absolute time -------------------------------------------------------

    private val now = 1_700_000_000_000L // fixed reference epoch ms

    @Test fun `relative just now`() {
        assertEquals("just now", LocaleFormat.relativeOrAbsolute(now - 5_000L, now, utc, enUS))
    }

    @Test fun `relative minutes ago`() {
        assertEquals("3 minutes ago", LocaleFormat.relativeOrAbsolute(now - 180_000L, now, utc, enUS))
    }

    @Test fun `relative one minute singular`() {
        assertEquals("1 minute ago", LocaleFormat.relativeOrAbsolute(now - 60_000L, now, utc, enUS))
    }

    @Test fun `relative hours ago`() {
        assertEquals("2 hours ago", LocaleFormat.relativeOrAbsolute(now - 7_200_000L, now, utc, enUS))
    }

    @Test fun `relative future in hours`() {
        assertEquals("in 2 hours", LocaleFormat.relativeOrAbsolute(now + 7_200_000L, now, utc, enUS))
    }

    @Test fun `relative days ago`() {
        assertEquals("5 days ago", LocaleFormat.relativeOrAbsolute(now - 5L * 86_400_000L, now, utc, enUS))
    }

    @Test fun `over threshold falls back to absolute`() {
        // 30 days ago → past the 7-day threshold → absolute medium date+time, not a relative phrase.
        val out = LocaleFormat.relativeOrAbsolute(now - 30L * 86_400_000L, now, utc, enUS)
        assertFalse("should not be relative: $out", out.contains("ago"))
        assertEquals(LocaleFormat.dateTimeAbsolute(now - 30L * 86_400_000L, utc, enUS), out)
    }

    @Test fun `absolute date time is non-empty and locale-formatted`() {
        val out = LocaleFormat.dateTimeAbsolute(now, utc, enUS)
        assertTrue("expected a year in $out", out.contains("2023"))
    }
}
