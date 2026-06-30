package dev.aarso.domain.inspect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenInspectorTest {

    private fun score(token: String, entropy: Double, logprob: Double = -1.0) =
        TokenScore(token = token, logprob = logprob, entropy = entropy)

    // --- heatmap: normalization ---

    @Test
    fun heatmapNormalizesMaxToOneMinToZero() {
        val tokens = listOf(
            score("a", entropy = 0.0),
            score("b", entropy = 1.0),
            score("c", entropy = 2.0),
        )
        val cells = TokenInspector.heatmap(tokens)
        assertEquals(0.0, cells[0].intensity, 1e-9) // min entropy
        assertEquals(0.5, cells[1].intensity, 1e-9) // midpoint
        assertEquals(1.0, cells[2].intensity, 1e-9) // max entropy
    }

    @Test
    fun heatmapPreservesOrderAndTokens() {
        val tokens = listOf(score("x", 3.0), score("y", 1.0), score("z", 2.0))
        val cells = TokenInspector.heatmap(tokens)
        assertEquals(listOf("x", "y", "z"), cells.map { it.token })
    }

    @Test
    fun heatmapAllEqualEntropyGivesZeroIntensity() {
        val tokens = listOf(score("a", 4.0), score("b", 4.0), score("c", 4.0))
        val cells = TokenInspector.heatmap(tokens)
        assertTrue(cells.all { it.intensity == 0.0 })
        assertTrue(cells.all { it.bucket == 0 })
    }

    @Test
    fun heatmapSingleTokenGivesZeroIntensity() {
        val cells = TokenInspector.heatmap(listOf(score("solo", 7.0)))
        assertEquals(1, cells.size)
        assertEquals(0.0, cells[0].intensity, 1e-9)
        assertEquals(0, cells[0].bucket)
    }

    @Test
    fun heatmapEmptyIsEmpty() {
        assertTrue(TokenInspector.heatmap(emptyList()).isEmpty())
    }

    // --- heatmap: bucket bounds ---

    @Test
    fun bucketsStayWithinBounds() {
        val tokens = (0..20).map { score("t$it", entropy = it.toDouble()) }
        val buckets = 5
        val cells = TokenInspector.heatmap(tokens, buckets = buckets)
        assertTrue(cells.all { it.bucket in 0..(buckets - 1) })
    }

    @Test
    fun maxEntropyLandsInTopBucket() {
        val tokens = listOf(score("lo", 0.0), score("hi", 10.0))
        val cells = TokenInspector.heatmap(tokens, buckets = 5)
        assertEquals(0, cells[0].bucket)
        assertEquals(4, cells[1].bucket) // intensity 1.0 clamped into top bucket
    }

    @Test
    fun singleBucketAlwaysZero() {
        val tokens = listOf(score("a", 0.0), score("b", 5.0), score("c", 10.0))
        val cells = TokenInspector.heatmap(tokens, buckets = 1)
        assertTrue(cells.all { it.bucket == 0 })
    }

    // --- highestUncertainty ---

    @Test
    fun highestUncertaintyPicksMaxEntropy() {
        val tokens = listOf(score("a", 1.0), score("b", 9.0), score("c", 3.0))
        val peak = TokenInspector.highestUncertainty(tokens)
        assertNotNull(peak)
        assertEquals("b", peak!!.token)
    }

    @Test
    fun highestUncertaintyTieTakesFirst() {
        val tokens = listOf(score("a", 5.0), score("b", 5.0))
        assertEquals("a", TokenInspector.highestUncertainty(tokens)!!.token)
    }

    @Test
    fun highestUncertaintyNullOnEmpty() {
        assertNull(TokenInspector.highestUncertainty(emptyList()))
    }

    // --- summary: availability honesty ---

    @Test
    fun summaryUnavailableIsHonest() {
        val s = TokenInspector.summary(emptyList(), Availability.UNAVAILABLE)
        assertEquals("Logprobs not provided by this provider.", s)
    }

    @Test
    fun summaryUnavailableEvenWithTokens() {
        // UNAVAILABLE must never fabricate numbers from whatever tokens are passed.
        val tokens = listOf(score("a", 1.0))
        val s = TokenInspector.summary(tokens, Availability.UNAVAILABLE)
        assertEquals("Logprobs not provided by this provider.", s)
    }

    @Test
    fun summaryEmptyTokensFull() {
        assertEquals("No tokens to inspect.", TokenInspector.summary(emptyList(), Availability.FULL))
    }

    @Test
    fun summaryFullNamesPeakToken() {
        val tokens = listOf(score("the", 0.2), score("quick", 4.0))
        val s = TokenInspector.summary(tokens, Availability.FULL)
        assertTrue(s.contains("2 tokens"))
        assertTrue(s.contains("quick"))
    }

    @Test
    fun summaryTopKNotesProviderLimit() {
        val tokens = listOf(score("a", 1.0), score("b", 2.0))
        val s = TokenInspector.summary(tokens, Availability.TOPK_ONLY)
        assertTrue(s.contains("Top-k only"))
        assertTrue(s.contains("b"))
    }

    // --- determinism ---

    @Test
    fun heatmapIsDeterministic() {
        val tokens = listOf(score("a", 0.0), score("b", 1.5), score("c", 3.0))
        assertEquals(TokenInspector.heatmap(tokens), TokenInspector.heatmap(tokens))
    }

    @Test
    fun summaryIsDeterministic() {
        val tokens = listOf(score("a", 1.0), score("b", 2.0))
        assertEquals(
            TokenInspector.summary(tokens, Availability.FULL),
            TokenInspector.summary(tokens, Availability.FULL),
        )
    }
}
