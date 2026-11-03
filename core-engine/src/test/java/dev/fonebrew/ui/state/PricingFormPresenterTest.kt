package dev.fonebrew.ui.state

import dev.fonebrew.domain.cost.PricingBook
import dev.fonebrew.domain.cost.UsagePricing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PricingFormPresenterTest {

    // ---- rows / origin -----------------------------------------------------------------

    @Test fun `a model with an explicit price is labelled EXPLICIT`() {
        val book = PricingBook().with("cloud:claude-x", UsagePricing(300, 1500))
        val rows = PricingFormPresenter.rows(book, listOf("cloud:claude-x" to "Anthropic · claude-x"))
        assertEquals(1, rows.size)
        assertEquals(PriceOrigin.EXPLICIT, rows[0].origin)
        assertEquals(UsagePricing(300, 1500), rows[0].pricing)
    }

    @Test fun `an unpriced model with the default fallback is labelled DEFAULT_PLACEHOLDER`() {
        val rows = PricingFormPresenter.rows(PricingBook(), listOf("cloud:gpt" to "OpenAI · gpt"))
        assertEquals(PriceOrigin.DEFAULT_PLACEHOLDER, rows[0].origin)
        assertEquals(UsagePricing.CONSERVATIVE_DEFAULT, rows[0].pricing)
    }

    @Test fun `an unpriced model under a customised fallback is labelled CUSTOM_FALLBACK`() {
        val book = PricingBook().withFallback(UsagePricing(50, 80))
        val rows = PricingFormPresenter.rows(book, listOf("cloud:gpt" to "OpenAI · gpt"))
        assertEquals(PriceOrigin.CUSTOM_FALLBACK, rows[0].origin)
        assertEquals(UsagePricing(50, 80), rows[0].pricing)
    }

    @Test fun `rows preserve the caller's given order and never re-sort`() {
        val models = listOf("cloud:b" to "B", "cloud:a" to "A")
        val rows = PricingFormPresenter.rows(PricingBook(), models)
        assertEquals(listOf("cloud:b", "cloud:a"), rows.map { it.tokenizerId })
    }

    // ---- parseRate ----------------------------------------------------------------------

    @Test fun `a valid non-negative whole number parses cleanly`() {
        val f = PricingFormPresenter.parseRate("300")
        assertEquals(300L, f.value)
        assertNull(f.error)
        assertTrue(f.isValid)
    }

    @Test fun `blank is rejected, not silently coerced to zero`() {
        val f = PricingFormPresenter.parseRate("")
        assertNull(f.value)
        assertEquals("required", f.error)
        assertTrue(!f.isValid)
    }

    @Test fun `non-numeric input is rejected`() {
        val f = PricingFormPresenter.parseRate("abc")
        assertNull(f.value)
        assertEquals("whole number only", f.error)
    }

    @Test fun `a decimal is rejected — minor units are whole numbers`() {
        val f = PricingFormPresenter.parseRate("1.5")
        assertNull(f.value)
        assertEquals("whole number only", f.error)
    }

    @Test fun `negative is rejected`() {
        val f = PricingFormPresenter.parseRate("-5")
        assertNull(f.value)
        assertEquals("can't be negative", f.error)
    }

    @Test fun `zero is a valid rate — a model can honestly cost nothing`() {
        val f = PricingFormPresenter.parseRate("0")
        assertEquals(0L, f.value)
        assertNull(f.error)
    }

    @Test fun `surrounding whitespace is trimmed`() {
        val f = PricingFormPresenter.parseRate("  42  ")
        assertEquals(42L, f.value)
    }

    // ---- parsePricing ---------------------------------------------------------------------

    @Test fun `both fields valid yields a UsagePricing`() {
        val p = PricingFormPresenter.parsePricing("300", "1500")
        assertEquals(UsagePricing(300, 1500), p)
    }

    @Test fun `either field invalid yields null — Save stays disabled`() {
        assertNull(PricingFormPresenter.parsePricing("", "1500"))
        assertNull(PricingFormPresenter.parsePricing("300", "abc"))
        assertNull(PricingFormPresenter.parsePricing("-1", "0"))
    }
}
