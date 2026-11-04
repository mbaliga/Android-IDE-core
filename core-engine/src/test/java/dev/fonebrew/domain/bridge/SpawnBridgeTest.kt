package dev.fonebrew.domain.bridge

import dev.fonebrew.domain.provenance.ProvenanceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM unit tests for the Spawn-side bridge builder — mirrors [dev.fonebrew.domain.bridge.SummaryBridgeTest]'s
 *  discipline since [SpawnBridges.build] wraps [SummaryBridges] rather than reimplementing it. */
class SpawnBridgeTest {

    // ---- header() ------------------------------------------------------------------------------

    @Test
    fun header_prefixesSpawnedFrom() {
        assertEquals("Spawned from: hello world", SpawnBridges.header("hello world"))
    }

    @Test
    fun header_collapsesWhitespace() {
        assertEquals("Spawned from: line one line two", SpawnBridges.header("line one\n\tline   two"))
    }

    @Test
    fun header_truncatesLongExcerpt_withEllipsis() {
        val header = SpawnBridges.header("x".repeat(200))
        assertEquals("Spawned from: " + "x".repeat(60) + "…", header)
    }

    @Test
    fun header_shortExcerpt_notTruncated() {
        val header = SpawnBridges.header("short")
        assertFalse(header.contains("…"))
    }

    @Test
    fun header_blankExcerpt_fallsBackToGenericPhrase() {
        assertEquals("Spawned from a prior conversation", SpawnBridges.header("   "))
    }

    // ---- build() ---------------------------------------------------------------------------------

    @Test
    fun build_wiresHeaderFromExcerpt() {
        val bridge = SpawnBridges.build(
            srcRootId = "root-1",
            srcNodeId = "msg-1",
            srcExcerpt = "the original ask",
            priorTurns = emptyList(),
            authorModel = null,
            authorProvenance = ProvenanceState.UNKNOWN,
        )
        assertEquals("Spawned from: the original ask", bridge.summary.header)
    }

    @Test
    fun build_threadsLineagePointers() {
        val bridge = SpawnBridges.build(
            srcRootId = "root-1",
            srcNodeId = "msg-42",
            srcExcerpt = "x",
            priorTurns = emptyList(),
            authorModel = null,
            authorProvenance = ProvenanceState.UNKNOWN,
        )
        assertEquals("root-1", bridge.srcRootId)
        assertEquals("msg-42", bridge.srcNodeId)
    }

    @Test
    fun build_reusesSelectCarryForward() {
        val turns = listOf(PriorTurn("user", "goal", 1), PriorTurn("assistant", "reply", 1))
        val bridge = SpawnBridges.build(
            srcRootId = "r", srcNodeId = "n", srcExcerpt = "x",
            priorTurns = turns, authorModel = null, authorProvenance = ProvenanceState.UNKNOWN,
        )
        assertEquals(SummaryBridges.selectCarryForward(turns), bridge.summary.carriedForward)
    }

    @Test
    fun build_emptyPriorTurns_fullPriorAvailableFalse() {
        val bridge = SpawnBridges.build(
            srcRootId = "r", srcNodeId = "n", srcExcerpt = "x",
            priorTurns = emptyList(), authorModel = "Claude", authorProvenance = ProvenanceState.CLOUD,
        )
        assertFalse(bridge.summary.fullPriorAvailable)
    }

    @Test
    fun build_withPriorTurns_fullPriorAvailableTrue() {
        val bridge = SpawnBridges.build(
            srcRootId = "r", srcNodeId = "n", srcExcerpt = "x",
            priorTurns = listOf(PriorTurn("user", "hi", 1)), authorModel = null, authorProvenance = ProvenanceState.LOCAL,
        )
        assertTrue(bridge.summary.fullPriorAvailable)
    }

    @Test
    fun build_threadsAuthorModelAndProvenance() {
        val bridge = SpawnBridges.build(
            srcRootId = "r", srcNodeId = "n", srcExcerpt = "x",
            priorTurns = emptyList(), authorModel = "gemini-1.5", authorProvenance = ProvenanceState.CLOUD,
        )
        assertEquals("gemini-1.5", bridge.summary.authorModel)
        assertEquals(ProvenanceState.CLOUD, bridge.summary.authorProvenance)
    }

    @Test
    fun build_nullAuthorModel_preserved() {
        val bridge = SpawnBridges.build(
            srcRootId = "r", srcNodeId = "n", srcExcerpt = "x",
            priorTurns = emptyList(), authorModel = null, authorProvenance = ProvenanceState.LOCAL,
        )
        assertNull(bridge.summary.authorModel)
    }

    @Test
    fun build_honoursCustomCaps() {
        val turns = (1..10).map { PriorTurn("assistant", "t$it", 1) }
        val bridge = SpawnBridges.build(
            srcRootId = "r", srcNodeId = "n", srcExcerpt = "x",
            priorTurns = turns, authorModel = null, authorProvenance = ProvenanceState.UNKNOWN,
            maxBullets = 2, maxTokens = 1000,
        )
        assertEquals(2, bridge.summary.carriedForward.size)
    }

    @Test
    fun build_isDeterministic() {
        val turns = listOf(PriorTurn("user", "goal", 2))
        val a = SpawnBridges.build("r", "n", "x", turns, "Claude", ProvenanceState.CLOUD)
        val b = SpawnBridges.build("r", "n", "x", turns, "Claude", ProvenanceState.CLOUD)
        assertEquals(a, b)
    }
}
