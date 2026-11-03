package dev.fonebrew.domain.thread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageConfidenceTest {

    @Test fun `averages Confidence fromEntropy over every token that reported one`() {
        // entropy 0f -> confidence 1.0; entropy 4f (DEFAULT_MAX_ENTROPY) -> confidence 0.0.
        val result = MessageConfidence.fromEntropies(listOf(0f, 4f))
        assertEquals(0.5, result!!, 1e-9)
    }

    @Test fun `null when no token reported an entropy at all`() {
        assertNull(MessageConfidence.fromEntropies(listOf(null, null)))
    }

    @Test fun `null on an empty list, never a fabricated default`() {
        assertNull(MessageConfidence.fromEntropies(emptyList()))
    }

    @Test fun `a partial mix averages only the tokens that reported an entropy`() {
        // 0f -> 1.0, null -> skipped entirely (never averaged in as 0).
        val result = MessageConfidence.fromEntropies(listOf(0f, null))
        assertEquals(1.0, result!!, 1e-9)
    }

    @Test fun `the result always lands in 0 to 1, matching ThreadGraphNode confidence's own range`() {
        val result = MessageConfidence.fromEntropies(listOf(-5f, 100f))
        assertTrue(result!! in 0.0..1.0)
    }
}
