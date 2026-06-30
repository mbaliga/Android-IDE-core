package dev.aarso.domain.cost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PricingBookTest {

    @Test fun `explicit price wins`() {
        val book = PricingBook().with("cloud:claude-x", UsagePricing(300, 1500))
        assertEquals(UsagePricing(300, 1500), book.priceFor("cloud:claude-x"))
        assertTrue(book.isExplicit("cloud:claude-x"))
    }

    @Test fun `unpriced cloud model falls back to the labelled default`() {
        val book = PricingBook()
        assertEquals(UsagePricing.CONSERVATIVE_DEFAULT, book.priceFor("cloud:unknown"))
        assertFalse(book.isExplicit("cloud:unknown"))
    }

    @Test fun `on-device models cost no money unless priced`() {
        val book = PricingBook()
        assertEquals(UsagePricing.ON_DEVICE, book.priceFor("local:qwen-2.5"))
        // ...but the user can still set one (e.g. to value their own time/energy)
        val priced = book.with("local:qwen-2.5", UsagePricing(0, 0))
        assertTrue(priced.isExplicit("local:qwen-2.5"))
    }

    @Test fun `with and without are immutable transforms`() {
        val a = PricingBook().with("m", UsagePricing(1, 2))
        val b = a.without("m")
        assertTrue(a.isExplicit("m"))
        assertFalse(b.isExplicit("m"))
    }

    @Test fun `custom fallback is honoured`() {
        val book = PricingBook().withFallback(UsagePricing(50, 80))
        assertEquals(UsagePricing(50, 80), book.priceFor("cloud:whatever"))
    }

    @Test fun `codec round-trips the book`() {
        val book = PricingBook(
            byModel = mapOf(
                "cloud:claude" to UsagePricing(300, 1500),
                "cloud:gpt" to UsagePricing(250, 1000),
            ),
            fallback = UsagePricing(5, 9),
        )
        val restored = PricingCodec.decode(PricingCodec.encode(book))
        assertEquals(book, restored)
    }

    @Test fun `decoding garbage yields an empty book, not a crash`() {
        assertEquals(PricingBook(), PricingCodec.decode("not json"))
    }
}
