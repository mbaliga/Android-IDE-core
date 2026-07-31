package dev.aarso.domain.search

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [Segmenter] (Doc §13 nos. 1-6). All tests pin
 * `boundarySource = Segmenter.JavaTextBoundarySource` explicitly — the JVM-portable,
 * `java.text.BreakIterator`-backed source — rather than relying on [Segmenter.defaultSource]'s
 * environment detection, so these tests are deterministic regardless of what JVM they run on.
 *
 * What this file does **not** claim: real ICU4C dictionary segmentation for a lone-script
 * Chinese run (`android.icu.text.BreakIterator`, [Segmenter.IcuBoundarySource]) is real-device
 * behaviour only — see the class KDoc on [Segmenter]. Every property asserted here (no
 * mid-syllable shattering, ZWNJ/ZWJ non-splitting, NFKC offset preservation, mixed-script
 * splitting) was empirically verified to hold on `java.text.BreakIterator` before being encoded
 * as a test, not assumed.
 */
class SegmenterTest {

    private val src = Segmenter.JavaTextBoundarySource

    private fun words(text: String, locale: Locale = Locale.ROOT): List<String> =
        Segmenter.segment(text, locale, src).map { text.substring(it.startOriginal, it.endOriginal) }

    // ---- 1. Devanagari matra clusters ----

    @Test fun `devanagari matra clusters survive as one token`() {
        val text = "मैंने किताबें पढ़ीं"
        val tokens = words(text, Locale.forLanguageTag("hi"))
        // Each word keeps its matras/virama attached — not shattered into bare consonants.
        assertEquals(listOf("मैंने", "किताबें", "पढ़ीं"), tokens)
    }

    @Test fun `devanagari token spans cover the full cluster, not a bare consonant`() {
        val text = "किताब"
        val spans = Segmenter.segment(text, Locale.forLanguageTag("hi"), src)
        assertEquals(1, spans.size)
        assertEquals(0, spans[0].startOriginal)
        assertEquals(text.length, spans[0].endOriginal)
    }

    // ---- 2. Arabic with/without harakat ----

    @Test fun `arabic word with harakat is not split at each diacritic`() {
        val withHarakat = "كِتَاب"
        val spans = Segmenter.segment(withHarakat, Locale.forLanguageTag("ar"), src)
        // One token for the whole diacritic-laden word — harakat don't act as separators.
        assertEquals(1, spans.size)
        assertEquals(withHarakat, withHarakat.substring(spans[0].startOriginal, spans[0].endOriginal))
    }

    @Test fun `arabic word without harakat segments the same shape`() {
        val bare = "كتاب جميل"
        assertEquals(listOf("كتاب", "جميل"), words(bare, Locale.forLanguageTag("ar")))
    }

    // ---- 3. Japanese segmentation (mixed kanji/hiragana; see class KDoc for the CJK caveat) ----

    @Test fun `japanese mixed-script text is not one opaque blob`() {
        val text = "東京駅で会った"
        val tokens = words(text, Locale.JAPANESE)
        assertTrue("expected more than one token, got $tokens", tokens.size > 1)
        // The station name kanji run stays together as a unit findable by a query for it.
        assertTrue(tokens.contains("東京駅"))
    }

    // ---- 4. NFKC length-changing round trip ----

    @Test fun `ligature normalizes but original offsets still cover only the source word`() {
        val text = "the ﬁle is new" // U+FB01 LATIN SMALL LIGATURE FI, part of the word "ﬁle"
        val spans = Segmenter.segment(text, Locale.ROOT, src)
        val ligatureSpan = spans.first { it.normalized == "file" }
        // The whole original word "ﬁle" is 3 source codepoints (ligature + l + e); NFKC expands
        // it to 4 ("file") — the offsets track the original span length, not the normalized one.
        assertEquals(4, ligatureSpan.startOriginal)
        assertEquals(7, ligatureSpan.endOriginal)
        assertEquals("ﬁle", text.substring(ligatureSpan.startOriginal, ligatureSpan.endOriginal))
    }

    @Test fun `circled digit normalizes to ascii with offsets on the single source codepoint`() {
        val text = "item ① done" // U+2460 CIRCLED DIGIT ONE
        val spans = Segmenter.segment(text, Locale.ROOT, src)
        val span = spans.first { it.normalized == "1" }
        assertEquals(5, span.startOriginal)
        assertEquals(6, span.endOriginal)
    }

    @Test fun `halfwidth katakana normalizes to fullwidth`() {
        // U+FF76 HALFWIDTH KATAKANA LETTER KA, U+FF9E HALFWIDTH VOICED SOUND MARK -> ガ (U+30AC)
        val text = "ｶﾞ"
        val spans = Segmenter.segment(text, Locale.JAPANESE, src)
        assertEquals(1, spans.size)
        assertEquals("ガ", spans[0].normalized)
        assertEquals(0, spans[0].startOriginal)
        assertEquals(text.length, spans[0].endOriginal)
    }

    // ---- 5. Mixed-script query ----

    @Test fun `mixed script text segments each script as its own token`() {
        assertEquals(listOf("gradle", "कैश", "build"), words("gradle कैश build", Locale.ROOT))
    }

    // ---- 6. ZWNJ / ZWJ do not split tokens ----

    @Test fun `zwj does not split a devanagari conjunct`() {
        val text = "क्‍ष" // contains a ZWJ (U+200D) inside the conjunct
        val spans = Segmenter.segment(text, Locale.forLanguageTag("hi"), src)
        assertEquals(1, spans.size)
        assertEquals(text, text.substring(spans[0].startOriginal, spans[0].endOriginal))
    }

    @Test fun `zwnj does not split a persian word`() {
        val text = "می‌روم" // ZWNJ (U+200C) between می and روم, one word
        val spans = Segmenter.segment(text, Locale.forLanguageTag("fa"), src)
        assertEquals(1, spans.size)
    }

    // ---- housekeeping ----

    @Test fun `empty text segments to nothing`() {
        assertTrue(Segmenter.segment("", Locale.ROOT, src).isEmpty())
    }

    @Test fun `whitespace-only text segments to nothing`() {
        assertTrue(Segmenter.segment("   \t  ", Locale.ROOT, src).isEmpty())
    }

    @Test fun `tokenizeForIndex space-joins normalized tokens`() {
        assertEquals("gradle कैश build", Segmenter.tokenizeForIndex("Gradle  कैश  BUILD", Locale.ROOT, src))
    }

    @Test fun `defaultSource resolves to a working boundary source on this jvm`() {
        // Whichever source Segmenter picks for this environment, it must not throw and must
        // produce sane output — this is the "no special-casing needed by callers" contract.
        val spans = Segmenter.segment("hello world", Locale.ROOT)
        assertEquals(listOf("hello", "world"), spans.map { it.normalized })
    }
}
