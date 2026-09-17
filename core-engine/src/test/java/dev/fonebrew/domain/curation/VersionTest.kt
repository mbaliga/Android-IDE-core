package dev.fonebrew.domain.curation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VersionTest {

    private fun v(id: String, tip: String, at: Long) =
        Version(id = id, branchTipMsgId = tip, name = id, at = at)

    @Test fun `atTip finds the version marked at an exact branch tip`() {
        val versions = listOf(v("v1", "m1", 1L), v("v2", "m2", 2L))
        assertEquals("v2", Versions.atTip(versions, "m2")?.id)
    }

    @Test fun `atTip returns null when nothing is marked there`() {
        val versions = listOf(v("v1", "m1", 1L))
        assertNull(Versions.atTip(versions, "m2"))
    }

    @Test fun `atTip on a re-marked tip returns the newest`() {
        val versions = listOf(v("v1", "m1", 1L), v("v2", "m1", 5L))
        assertEquals("v2", Versions.atTip(versions, "m1")?.id)
    }

    @Test fun `mergeAddWins keeps the newer entry on an id collision`() {
        val a = listOf(v("v1", "m1", 1L).copy(note = "old"))
        val b = listOf(v("v1", "m1", 9L).copy(note = "new"))
        val merged = Versions.mergeAddWins(a, b)
        assertEquals(1, merged.size)
        assertEquals("new", merged.single().note)
    }

    @Test fun `mergeAddWins unions disjoint sets without dropping anything`() {
        val a = listOf(v("v1", "m1", 1L))
        val b = listOf(v("v2", "m2", 2L), v("v3", "m3", 3L))
        assertEquals(3, Versions.mergeAddWins(a, b).size)
    }
}
