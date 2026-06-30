package dev.aarso.domain.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam contract: the lens is pluggable and currently inert. These tests guard the
 * boundary — they must NOT assert any drift/idiolect behaviour (that is Issue #2). They
 * only prove the seam holds open honestly.
 */
class MirrorSeamTest {

    @Test fun `default lens is inert and reports nothing`() {
        val seam = MirrorSeam()
        assertFalse(seam.active)
        assertFalse(seam.lens.installed)
        val r = seam.lens.reflect(Observation("I wrote this in my own voice."))
        assertEquals(Reflection.NONE, r)
        assertFalse(r.present)
        assertTrue(r.notes.isEmpty())
    }

    @Test fun `the inert lens never fabricates a reflection for any input`() {
        val cases = listOf("", "a", "a much longer passage with several clauses, em-dashes — and lists")
        for (text in cases) {
            assertEquals(Reflection.NONE, InertMirrorLens.reflect(Observation(text)))
        }
    }

    @Test fun `a host can install and uninstall a lens through the seam`() {
        // A *test-only* fake stands in for the future Issue-#2 lens; it carries no real
        // metric — it only proves the seam swaps the interface implementation.
        val fake = object : MirrorLens {
            override fun reflect(observation: Observation) =
                Reflection(present = true, notes = listOf("observed:${observation.text.length}"))
            override val installed: Boolean = true
        }

        val seam = MirrorSeam()
        seam.install(fake)
        assertTrue(seam.active)
        assertSame(fake, seam.lens)
        assertTrue(seam.lens.reflect(Observation("hello")).present)

        seam.uninstall()
        assertFalse(seam.active)
        assertEquals(Reflection.NONE, seam.lens.reflect(Observation("hello")))
    }
}
