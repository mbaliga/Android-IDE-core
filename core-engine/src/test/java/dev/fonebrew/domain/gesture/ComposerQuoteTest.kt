package dev.fonebrew.domain.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerQuoteTest {

    @Test fun `quoting into an empty composer produces just the quote block`() {
        val result = ComposerQuote.quote("", "hello world")
        assertEquals("> hello world\n\n", result)
    }

    @Test fun `quoting prefixes existing composer text rather than replacing it`() {
        val result = ComposerQuote.quote("my draft", "hello world")
        assertEquals("> hello world\n\nmy draft", result)
    }

    @Test fun `blank existing input is treated the same as empty`() {
        val result = ComposerQuote.quote("   ", "hello world")
        assertEquals("> hello world\n\n", result)
    }

    @Test fun `multi-line messages get a quote marker on every line`() {
        val result = ComposerQuote.quote("", "line one\nline two")
        assertEquals("> line one\n> line two\n\n", result)
    }

    @Test fun `reply adds a Replying to header the plain quote does not`() {
        val quoted = ComposerQuote.quote("", "hello")
        val replied = ComposerQuote.reply("", "hello")
        assertEquals("> hello\n\n", quoted)
        assertEquals("Replying to:\n> hello\n\n", replied)
    }

    @Test fun `reply also prefixes existing composer text`() {
        val result = ComposerQuote.reply("my draft", "hello")
        assertEquals("Replying to:\n> hello\n\nmy draft", result)
    }

    @Test fun `a long message is excerpted with an ellipsis rather than quoted in full`() {
        val long = "x".repeat(500)
        val result = ComposerQuote.quote("", long)
        // 240 chars of 'x' plus the ellipsis, wrapped in the quote marker.
        assertTrue(result.startsWith("> " + "x".repeat(240) + "…"))
        assertTrue(result.length < 260)
    }

    @Test fun `leading and trailing whitespace on the source message is trimmed`() {
        val result = ComposerQuote.quote("", "  hello  \n")
        assertEquals("> hello\n\n", result)
    }
}
