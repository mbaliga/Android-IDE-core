package dev.aarso.domain.cost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageAccumulatorTest {

    @Test fun `anthropic stream - input on message_start, output on message_delta`() {
        val acc = UsageAccumulator()
        // message_start carries input_tokens (output 0 so far)
        acc.merge(ProviderUsage.fromAnthropic("""{"message":{"usage":{"input_tokens":120,"output_tokens":0}}}"""))
        // a couple of message_delta events grow output_tokens (cumulative)
        acc.merge(ProviderUsage.fromAnthropic("""{"usage":{"output_tokens":15}}"""))
        acc.merge(ProviderUsage.fromAnthropic("""{"usage":{"output_tokens":42}}"""))
        assertEquals(120, acc.current.inputTokens)
        assertEquals(42, acc.current.outputTokens)
        assertTrue(acc.hasUsage)
    }

    @Test fun `openai single usage block lands whole`() {
        val acc = UsageAccumulator()
        acc.merge(ProviderUsage.fromOpenAi("""{"usage":{"prompt_tokens":300,"completion_tokens":88}}"""))
        assertEquals(300, acc.current.inputTokens)
        assertEquals(88, acc.current.outputTokens)
    }

    @Test fun `gemini cumulative chunks keep the max`() {
        val acc = UsageAccumulator()
        acc.merge(ProviderUsage.fromGemini("""{"usageMetadata":{"promptTokenCount":50,"candidatesTokenCount":10}}"""))
        acc.merge(ProviderUsage.fromGemini("""{"usageMetadata":{"promptTokenCount":50,"candidatesTokenCount":33}}"""))
        assertEquals(50, acc.current.inputTokens)
        assertEquals(33, acc.current.outputTokens)
    }

    @Test fun `non-usage events are ignored and never lower the running total`() {
        val acc = UsageAccumulator()
        acc.merge(ProviderUsage.fromAnthropic("""{"message":{"usage":{"input_tokens":120,"output_tokens":50}}}"""))
        acc.merge(ProviderUsage.fromAnthropic("""{"type":"content_block_delta","delta":{"text":"hi"}}""")) // no usage → null
        assertEquals(120, acc.current.inputTokens)
        assertEquals(50, acc.current.outputTokens)
    }

    @Test fun `reset clears the running usage`() {
        val acc = UsageAccumulator()
        acc.merge(UsageReport(10, 20))
        acc.reset()
        assertEquals(UsageReport.ZERO, acc.current)
        assertFalse(acc.hasUsage)
    }

    @Test fun `captured usage prices into advice cost`() {
        val acc = UsageAccumulator()
        acc.merge(ProviderUsage.fromOpenAi("""{"usage":{"prompt_tokens":1000,"completion_tokens":1000}}"""))
        val cost = acc.current.toAdviceCost(UsagePricing(centsPer1kInput = 2, centsPer1kOutput = 6))
        // 1k in @2 + 1k out @6 = 8 minor units
        assertEquals(8, cost.moneyMinor)
        assertEquals(2000, cost.tokens)
    }
}
