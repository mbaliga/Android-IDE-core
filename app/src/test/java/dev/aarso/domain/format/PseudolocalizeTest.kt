package dev.aarso.domain.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [Pseudolocalize] — the CI i18n smoke alarm.
 *
 * These assert the four Doc 00 §3.3 guarantees on the *pure* transform:
 * accenting (legible substitution), ~40% expansion (truncation canary), forced RTL (Urdu canary),
 * and — the load-bearing one — **placeholder safety** under accenting + filler + RTL.
 */
class PseudolocalizeTest {

    // The framing/marks, mirrored from the object so the tests read intentionally.
    private val open = '⟦'
    private val close = '⟧'
    private val filler = '·'
    private val rle = '‫'
    private val pdf = '‬'

    // ---- accenting ----------------------------------------------------------------------

    @Test fun lowercase_a_becomes_accented() {
        val out = Pseudolocalize.transform("a", expand = false)
        assertEquals("á", out)
    }

    @Test fun uppercase_A_becomes_accented() {
        assertEquals("Á", Pseudolocalize.transform("A", expand = false))
    }

    @Test fun word_is_accented_letter_for_letter() {
        // "Send" → each Latin letter mapped, length preserved (no placeholders, no expansion).
        val out = Pseudolocalize.transform("Send", expand = false)
        assertEquals("Šéñð", out) // d → ð in the look-alike map
        assertEquals("Send".length, out.length)
    }

    @Test fun original_ascii_letters_do_not_survive_verbatim() {
        val out = Pseudolocalize.transform("hello", expand = false)
        assertFalse("plain ASCII should have been accented", out.contains('h'))
        assertFalse(out.contains('e'))
        assertFalse(out.contains('l'))
        assertFalse(out.contains('o'))
    }

    @Test fun digits_are_unchanged() {
        val out = Pseudolocalize.transform("v12345", expand = false)
        assertTrue("digits must pass through", out.contains("12345"))
    }

    @Test fun punctuation_and_whitespace_unchanged() {
        val out = Pseudolocalize.transform("Hi, world! (ok)", expand = false)
        assertTrue(out.contains(","))
        assertTrue(out.contains("!"))
        assertTrue(out.contains("("))
        assertTrue(out.contains(")"))
        assertTrue(out.contains(" "))
    }

    @Test fun non_letters_only_string_is_untouched() {
        val src = "123 - 456 :: 789"
        assertEquals(src, Pseudolocalize.transform(src, expand = false))
    }

    // ---- expansion ----------------------------------------------------------------------

    @Test fun expansion_makes_result_longer() {
        val src = "Generate a response"
        val out = Pseudolocalize.transform(src) // expand = true by default
        assertTrue("expanded output must be longer than source", out.length > src.length)
    }

    @Test fun expansion_reaches_at_least_1_3x_visible() {
        val src = "Settings and preferences for the workbench host"
        val out = Pseudolocalize.transform(src)
        // Strip the framing brackets to measure the visible (accented + filler) body.
        val visible = out.trim(open, close)
        assertTrue(
            "expected >= ~1.3x (budget is 1.40x); got ${visible.length} vs ${src.length}",
            visible.length >= Math.ceil(src.length * 1.3).toInt()
        )
    }

    @Test fun expansion_adds_brackets() {
        val out = Pseudolocalize.transform("Hello")
        assertTrue("open bracket present", out.contains(open))
        assertTrue("close bracket present", out.contains(close))
        assertEquals("starts with open bracket", open, out.first())
        assertEquals("ends with close bracket", close, out.last())
    }

    @Test fun expansion_uses_filler_dots() {
        val out = Pseudolocalize.transform("Run")
        assertTrue("filler middle-dots present", out.contains(filler))
    }

    @Test fun no_expansion_when_disabled() {
        val out = Pseudolocalize.transform("Run", expand = false)
        assertFalse(out.contains(open))
        assertFalse(out.contains(close))
        assertFalse(out.contains(filler))
    }

    @Test fun expansion_is_deterministic() {
        val a = Pseudolocalize.transform("Deterministic, please")
        val b = Pseudolocalize.transform("Deterministic, please")
        assertEquals(a, b)
    }

    // ---- rtl ----------------------------------------------------------------------------

    @Test fun rtl_wraps_in_rle_and_pdf() {
        val out = Pseudolocalize.transform("Send", rtl = true)
        assertEquals("opens with RLE", rle, out.first())
        assertEquals("closes with PDF", pdf, out.last())
    }

    @Test fun no_rtl_marks_when_disabled() {
        val out = Pseudolocalize.transform("Send", rtl = false)
        assertFalse(out.contains(rle))
        assertFalse(out.contains(pdf))
    }

    @Test fun rtl_and_expand_compose() {
        val out = Pseudolocalize.transform("Send", expand = true, rtl = true)
        assertEquals(rle, out.first())
        assertEquals(pdf, out.last())
        assertTrue(out.contains(open))
        assertTrue(out.contains(close))
    }

    // ---- placeholder safety (critical) --------------------------------------------------

    @Test fun icu_brace_placeholder_survives_verbatim() {
        val src = "You have {count} messages"
        val out = Pseudolocalize.transform(src)
        assertTrue("{count} must appear verbatim", out.contains("{count}"))
        assertTrue(Pseudolocalize.isPlaceholderSafe(src, out))
    }

    @Test fun icu_indexed_placeholder_survives() {
        val src = "Hello {0}, welcome to {1}"
        val out = Pseudolocalize.transform(src)
        assertTrue(out.contains("{0}"))
        assertTrue(out.contains("{1}"))
        assertTrue(Pseudolocalize.isPlaceholderSafe(src, out))
    }

    @Test fun printf_s_placeholder_survives() {
        val src = "Connected to %s"
        val out = Pseudolocalize.transform(src)
        assertTrue("%s must appear verbatim", out.contains("%s"))
        assertTrue(Pseudolocalize.isPlaceholderSafe(src, out))
    }

    @Test fun printf_d_placeholder_survives() {
        val src = "Loaded %d tokens"
        val out = Pseudolocalize.transform(src)
        assertTrue(out.contains("%d"))
        assertTrue(Pseudolocalize.isPlaceholderSafe(src, out))
    }

    @Test fun printf_positional_placeholder_survives() {
        val src = "Step %1\$s of %2\$d"
        val out = Pseudolocalize.transform(src)
        assertTrue("%1\$s must survive", out.contains("%1\$s"))
        assertTrue("%2\$d must survive", out.contains("%2\$d"))
        assertTrue(Pseudolocalize.isPlaceholderSafe(src, out))
    }

    @Test fun placeholder_contents_are_not_accented() {
        // The letters inside {count} and after %s must NOT be accented.
        val out = Pseudolocalize.transform("{count} of %s", expand = false)
        assertTrue(out.contains("{count}"))
        assertTrue(out.contains("%s"))
    }

    @Test fun placeholder_safe_under_rtl_and_expand() {
        val src = "{count} files in %s"
        val out = Pseudolocalize.transform(src, expand = true, rtl = true)
        assertTrue(out.contains("{count}"))
        assertTrue(out.contains("%s"))
        assertTrue(Pseudolocalize.isPlaceholderSafe(src, out))
    }

    @Test fun duplicate_placeholders_preserved_in_count() {
        val src = "{0} then {0} again"
        val out = Pseudolocalize.transform(src)
        // Two {0} occurrences must both survive — multiset, not set.
        assertEquals(2, Regex("\\{0}").findAll(out).count())
        assertTrue(Pseudolocalize.isPlaceholderSafe(src, out))
    }

    @Test fun text_around_placeholder_is_still_accented() {
        val out = Pseudolocalize.transform("Save {0} now", expand = false)
        // "Save" and "now" should be accented; "{0}" intact.
        assertTrue(out.contains("{0}"))
        assertFalse("the surrounding 'S' should be accented", out.contains("Save"))
        assertFalse("the surrounding 'now' should be accented", out.contains("now"))
    }

    @Test fun string_with_no_placeholders_is_placeholder_safe() {
        val src = "Just plain words here"
        val out = Pseudolocalize.transform(src)
        assertTrue(Pseudolocalize.isPlaceholderSafe(src, out))
    }

    @Test fun is_placeholder_safe_detects_a_dropped_placeholder() {
        // Sanity-check the helper itself: a tampered pseudo missing {count} is NOT safe.
        assertFalse(Pseudolocalize.isPlaceholderSafe("Have {count} items", "Háʋé íţéɱš"))
    }

    @Test fun percent_escape_is_treated_as_placeholder() {
        // "%%" is the printf literal-percent escape; it must pass through untouched and be
        // accounted for by the safety check.
        val src = "100%% done"
        val out = Pseudolocalize.transform(src)
        assertTrue(out.contains("%%"))
        assertTrue(Pseudolocalize.isPlaceholderSafe(src, out))
    }

    // ---- edge cases ---------------------------------------------------------------------

    @Test fun empty_string_no_expand_is_empty() {
        assertEquals("", Pseudolocalize.transform("", expand = false))
    }

    @Test fun empty_string_with_expand_is_empty_bracketed_run() {
        val out = Pseudolocalize.transform("", expand = true)
        assertEquals("$open$close", out)
    }

    @Test fun empty_string_is_placeholder_safe() {
        val out = Pseudolocalize.transform("")
        assertTrue(Pseudolocalize.isPlaceholderSafe("", out))
    }
}
