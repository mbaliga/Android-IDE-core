package dev.aarso.domain.cost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeTierCodecTest {

    private val sample = FreeTierCatalog(
        lastUpdated = "2026-06-21",
        providers = listOf(
            FreeTierProvider(
                id = "google_gemini", name = "Google Gemini", providerKind = "gemini",
                kind = FreeTierKind.ONGOING_FREE, summary = "free tier in AI Studio",
                requestsPerDay = 1500, tokensPerDay = null, requiresCard = false,
                sourceUrl = "https://ai.google.dev",
            ),
            FreeTierProvider(
                id = "deepseek", name = "DeepSeek", providerKind = "openai",
                kind = FreeTierKind.TRIAL_CREDIT, summary = "trial tokens",
                trialCredit = "10M tokens", requiresCard = false,
                sourceUrl = "https://platform.deepseek.com",
            ),
        ),
    )

    @Test fun `round-trips through json`() {
        val back = FreeTierCodec.decode(FreeTierCodec.encode(sample))
        assertEquals("2026-06-21", back.lastUpdated)
        assertEquals(2, back.providers.size)
        val g = back.byId("google_gemini")!!
        assertEquals(1500, g.requestsPerDay)
        assertNull(g.tokensPerDay)
        assertEquals(FreeTierKind.ONGOING_FREE, g.kind)
        assertEquals("10M tokens", back.byId("deepseek")!!.trialCredit)
    }

    @Test fun `splits ongoing and trial`() {
        assertEquals(listOf("google_gemini"), sample.ongoing.map { it.id })
        assertEquals(listOf("deepseek"), sample.trial.map { it.id })
    }

    @Test fun `tolerates empty`() {
        val c = FreeTierCodec.decode("{}")
        assertTrue(c.providers.isEmpty())
    }
}
