package dev.aarso.domain.disclosure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisclosureTest {

    @Test fun `core shows only core surfaces`() {
        assertTrue(Disclosure.isRevealed(Surface.CHAT, DisclosureTier.CORE))
        assertFalse(Disclosure.isRevealed(Surface.IMAGES, DisclosureTier.CORE))
        assertFalse(Disclosure.isRevealed(Surface.LOOPS, DisclosureTier.CORE))
    }

    @Test fun `studio adds studio surfaces but not power`() {
        assertTrue(Disclosure.isRevealed(Surface.IMAGES, DisclosureTier.STUDIO))
        assertTrue(Disclosure.isRevealed(Surface.TREE_MAP, DisclosureTier.STUDIO))
        assertFalse(Disclosure.isRevealed(Surface.LOOPS, DisclosureTier.STUDIO))
    }

    @Test fun `power reveals everything`() {
        assertTrue(Surface.entries.all { Disclosure.isRevealed(it, DisclosureTier.POWER) })
    }

    @Test fun `the watched badge is mandatory at every tier`() {
        for (tier in DisclosureTier.entries) {
            assertTrue(Disclosure.isRevealed(Surface.WATCHED_BADGE, tier))
        }
        // ...and cannot be disabled by an override (thesis invariant).
        val hide = DisclosureOverrides(disabled = setOf(Surface.WATCHED_BADGE))
        assertTrue(Disclosure.isRevealed(Surface.WATCHED_BADGE, DisclosureTier.CORE, hide))
    }

    @Test fun `overrides reveal one surface early and hide a non-mandatory one`() {
        val on = DisclosureOverrides(enabled = setOf(Surface.LOOPS))
        assertTrue(Disclosure.isRevealed(Surface.LOOPS, DisclosureTier.CORE, on))
        val off = DisclosureOverrides(disabled = setOf(Surface.IMAGES))
        assertFalse(Disclosure.isRevealed(Surface.IMAGES, DisclosureTier.POWER, off))
    }

    @Test fun `intent maps to the starting tier`() {
        assertEquals(DisclosureTier.CORE, Disclosure.tierFor(Intent.PRIVATE_CHAT))
        assertEquals(DisclosureTier.STUDIO, Disclosure.tierFor(Intent.CHAT_AND_IMAGES))
        assertEquals(DisclosureTier.POWER, Disclosure.tierFor(Intent.POWER_USER))
    }

    @Test fun `surfacesFor grows monotonically with tier`() {
        val core = Disclosure.surfacesFor(DisclosureTier.CORE).size
        val studio = Disclosure.surfacesFor(DisclosureTier.STUDIO).size
        val power = Disclosure.surfacesFor(DisclosureTier.POWER).size
        assertTrue(core < studio && studio < power)
        assertEquals(Surface.entries.size, power)
    }

    @Test fun `persisted name round-trips and defaults to power`() {
        assertEquals(DisclosureTier.STUDIO, Disclosure.tierOf("STUDIO"))
        assertEquals(DisclosureTier.POWER, Disclosure.tierOf(null))
        assertEquals(DisclosureTier.POWER, Disclosure.tierOf("nonsense"))
    }
}
