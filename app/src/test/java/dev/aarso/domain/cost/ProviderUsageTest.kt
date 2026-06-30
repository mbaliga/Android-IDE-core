package dev.aarso.domain.cost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderUsageTest {

    @Test fun `anthropic non-stream usage`() {
        val json = """{"type":"message","usage":{"input_tokens":1200,"output_tokens":340}}"""
        assertEquals(UsageReport(1200, 340), ProviderUsage.fromAnthropic(json))
    }

    @Test fun `anthropic message_start nests usage under message`() {
        val json = """{"type":"message_start","message":{"usage":{"input_tokens":1200,"output_tokens":1}}}"""
        assertEquals(UsageReport(1200, 1), ProviderUsage.fromAnthropic(json))
    }

    @Test fun `openai usage`() {
        val json = """{"usage":{"prompt_tokens":800,"completion_tokens":256,"total_tokens":1056}}"""
        assertEquals(UsageReport(800, 256), ProviderUsage.fromOpenAi(json))
    }

    @Test fun `gemini usageMetadata`() {
        val json = """{"usageMetadata":{"promptTokenCount":500,"candidatesTokenCount":120,"totalTokenCount":620}}"""
        assertEquals(UsageReport(500, 120), ProviderUsage.fromGemini(json))
    }

    @Test fun `no usage present returns null`() {
        assertNull(ProviderUsage.fromAnthropic("""{"type":"content_block_delta","delta":{"text":"hi"}}"""))
        assertNull(ProviderUsage.fromOpenAi("""{"choices":[{"delta":{"content":"hi"}}]}"""))
        assertNull(ProviderUsage.fromGemini("""{"candidates":[]}"""))
        assertNull(ProviderUsage.fromOpenAi("not json"))
    }

    @Test fun `usage prices into advice cost — money and raw tokens`() {
        val usage = UsageReport(inputTokens = 1000, outputTokens = 500)
        val cost = usage.toAdviceCost(UsagePricing(centsPer1kInput = 30, centsPer1kOutput = 60))
        // 1000*30/1000 + 500*60/1000 = 30 + 30 = 60; tokens 1500
        assertEquals(CostVector(moneyMinor = 60, minutes = 0, tokens = 1500), cost)
    }

    @Test fun `on-device usage costs no money but still counts tokens`() {
        val cost = UsageReport(2000, 1000).toAdviceCost(UsagePricing.ON_DEVICE)
        assertEquals(0L, cost.moneyMinor)
        assertEquals(3000L, cost.tokens)
    }
}
