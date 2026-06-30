package dev.aarso.domain.provenance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvenanceTest {

    // ---- per-state non-colour identity (icon + label + watched) ----

    @Test
    fun localIdentity() {
        assertEquals("home", ProvenanceState.LOCAL.iconKey)
        assertEquals("On-device", ProvenanceState.LOCAL.label)
        assertFalse(ProvenanceState.LOCAL.watched)
    }

    @Test
    fun cloudIdentity() {
        assertEquals("cloud", ProvenanceState.CLOUD.iconKey)
        assertEquals("Cloud", ProvenanceState.CLOUD.label)
        assertTrue("cloud reached off-device — must be watched", ProvenanceState.CLOUD.watched)
    }

    @Test
    fun mixedIdentity() {
        assertEquals("split", ProvenanceState.MIXED.iconKey)
        assertEquals("Mixed", ProvenanceState.MIXED.label)
        assertTrue("mixed reached off-device — must be watched", ProvenanceState.MIXED.watched)
    }

    @Test
    fun unknownIdentity() {
        assertEquals("question", ProvenanceState.UNKNOWN.iconKey)
        assertEquals("Unknown", ProvenanceState.UNKNOWN.label)
        assertFalse("unknown makes no claim — not watched", ProvenanceState.UNKNOWN.watched)
    }

    @Test
    fun watchedIsExactlyCloudAndMixed() {
        val watched = ProvenanceState.entries.filter { it.watched }.toSet()
        assertEquals(setOf(ProvenanceState.CLOUD, ProvenanceState.MIXED), watched)
    }

    @Test
    fun everyStateHasNonColourCues() {
        // Colour-independence: icon + label must be present and unique per state.
        for (s in ProvenanceState.entries) {
            assertTrue("iconKey blank for $s", s.iconKey.isNotBlank())
            assertTrue("label blank for $s", s.label.isNotBlank())
        }
        val icons = ProvenanceState.entries.map { it.iconKey }.toSet()
        val labels = ProvenanceState.entries.map { it.label }.toSet()
        assertEquals("iconKeys must be distinct", ProvenanceState.entries.size, icons.size)
        assertEquals("labels must be distinct", ProvenanceState.entries.size, labels.size)
        assertNotEquals(ProvenanceState.LOCAL.iconKey, ProvenanceState.CLOUD.iconKey)
    }

    // ---- ofTier ----

    @Test
    fun ofTierMapsThroughTier() {
        assertEquals(ProvenanceState.LOCAL, Provenances.ofTier(Tier.ON_DEVICE))
        assertEquals(ProvenanceState.LOCAL, Provenances.ofTier(Tier.RUNNER))
        assertEquals(ProvenanceState.CLOUD, Provenances.ofTier(Tier.CLOUD))
    }

    // ---- combine() ----

    @Test
    fun combineEmptyIsUnknown() {
        assertEquals(ProvenanceState.UNKNOWN, Provenances.combine(emptyList()))
    }

    @Test
    fun combineAllLocal() {
        assertEquals(
            ProvenanceState.LOCAL,
            Provenances.combine(listOf(ProvenanceState.LOCAL, ProvenanceState.LOCAL)),
        )
    }

    @Test
    fun combineSingleLocal() {
        assertEquals(ProvenanceState.LOCAL, Provenances.combine(listOf(ProvenanceState.LOCAL)))
    }

    @Test
    fun combineAllCloud() {
        assertEquals(
            ProvenanceState.CLOUD,
            Provenances.combine(listOf(ProvenanceState.CLOUD, ProvenanceState.CLOUD)),
        )
    }

    @Test
    fun combineLocalAndCloudIsMixed() {
        assertEquals(
            ProvenanceState.MIXED,
            Provenances.combine(listOf(ProvenanceState.LOCAL, ProvenanceState.CLOUD)),
        )
    }

    @Test
    fun combineWithExplicitMixedIsMixed() {
        assertEquals(
            ProvenanceState.MIXED,
            Provenances.combine(listOf(ProvenanceState.LOCAL, ProvenanceState.MIXED)),
        )
        assertEquals(
            ProvenanceState.MIXED,
            Provenances.combine(listOf(ProvenanceState.MIXED)),
        )
    }

    @Test
    fun combineAllUnknownStaysUnknown() {
        assertEquals(
            ProvenanceState.UNKNOWN,
            Provenances.combine(listOf(ProvenanceState.UNKNOWN, ProvenanceState.UNKNOWN)),
        )
    }

    @Test
    fun combineUnknownWithLocalDegradesToMixed() {
        // Cannot certify "all local" once an unknown is present → conservative MIXED.
        assertEquals(
            ProvenanceState.MIXED,
            Provenances.combine(listOf(ProvenanceState.LOCAL, ProvenanceState.UNKNOWN)),
        )
    }

    @Test
    fun combineUnknownWithCloudDegradesToMixed() {
        assertEquals(
            ProvenanceState.MIXED,
            Provenances.combine(listOf(ProvenanceState.CLOUD, ProvenanceState.UNKNOWN)),
        )
    }

    @Test
    fun combineLocalCloudUnknownIsMixed() {
        assertEquals(
            ProvenanceState.MIXED,
            Provenances.combine(
                listOf(ProvenanceState.LOCAL, ProvenanceState.CLOUD, ProvenanceState.UNKNOWN),
            ),
        )
    }

    @Test
    fun combineNeverUnderStatesReach() {
        // Any cloud touch in the set means the result is at least watched.
        val withCloud = listOf(ProvenanceState.LOCAL, ProvenanceState.LOCAL, ProvenanceState.CLOUD)
        assertTrue(Provenances.combine(withCloud).watched)
    }
}
