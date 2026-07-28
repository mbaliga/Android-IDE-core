package dev.aarso.domain.cost

import dev.aarso.domain.cloud.CloudProvider
import dev.aarso.domain.cloud.ProviderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FreeTierMatchTest {

    private val catalog = FreeTierCatalog(
        "2026-06-21",
        listOf(
            tier("groq", "gemini" to false), tier("google_gemini"), tier("anthropic"), tier("deepseek"),
        ),
    )

    private fun tier(id: String, x: Pair<String, Boolean>? = null) =
        FreeTierProvider(id, id, null, FreeTierKind.ONGOING_FREE, "", sourceUrl = "")

    private fun provider(name: String, base: String, kind: ProviderKind = ProviderKind.OPENAI_COMPATIBLE) =
        CloudProvider(id = "p", displayName = name, kind = kind, baseUrl = base, model = "m", contextWindow = 8192)

    @Test fun `matches by host keyword`() {
        assertEquals("groq", FreeTierMatch.match(provider("My Groq", "https://api.groq.com/openai/v1"), catalog)?.id)
        assertEquals("deepseek", FreeTierMatch.match(provider("DS", "https://api.deepseek.com"), catalog)?.id)
    }

    @Test fun `falls back to kind`() {
        assertEquals("google_gemini", FreeTierMatch.match(provider("g", "https://x", ProviderKind.GEMINI), catalog)?.id)
        assertEquals("anthropic", FreeTierMatch.match(provider("c", "https://y", ProviderKind.ANTHROPIC), catalog)?.id)
    }

    @Test fun `unknown openai-compatible host has no match`() {
        assertNull(FreeTierMatch.match(provider("local", "https://my.box/v1"), catalog))
    }
}
