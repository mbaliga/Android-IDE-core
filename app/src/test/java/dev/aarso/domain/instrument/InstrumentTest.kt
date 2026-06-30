package dev.aarso.domain.instrument

import dev.aarso.domain.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TokenStatsTest {

    @Test
    fun splitsInputAndOutputByRole() {
        val stats = TokenStats.of(
            listOf(
                Role.SYSTEM to 10,
                Role.USER to 20,
                Role.ASSISTANT to 90,
                Role.USER to 10,
                Role.ASSISTANT to 30,
            ),
        )
        assertEquals(40, stats.inputTokens) // 10 + 20 + 10
        assertEquals(120, stats.outputTokens) // 90 + 30
        assertEquals(160, stats.total)
        assertEquals(3.0f, stats.outputPerInput!!, 1e-6f)
    }

    @Test
    fun noInput_ratioIsNull() {
        assertNull(TokenStats.of(listOf(Role.ASSISTANT to 5)).outputPerInput)
    }
}

class ConfidenceTest {

    @Test
    fun lowEntropyIsHighConfidence() {
        assertEquals(1.0f, Confidence.fromEntropy(0.0f)!!, 1e-6f)
    }

    @Test
    fun highEntropySaturatesToZero() {
        assertEquals(0.0f, Confidence.fromEntropy(10.0f, maxEntropy = 4.0f)!!, 1e-6f)
    }

    @Test
    fun midRangeIsProportional() {
        assertEquals(0.5f, Confidence.fromEntropy(2.0f, maxEntropy = 4.0f)!!, 1e-6f)
    }

    @Test
    fun absentEntropyIsNull() {
        assertNull(Confidence.fromEntropy(null))
    }
}
