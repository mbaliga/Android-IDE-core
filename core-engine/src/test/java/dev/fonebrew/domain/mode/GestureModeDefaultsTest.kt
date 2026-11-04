package dev.fonebrew.domain.mode

import dev.aarso.interactionmode.InteractionMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureModeDefaultsTest {

    @Test fun `defaultFor is OFF in REGULAR mode`() {
        assertFalse(GestureModeDefaults.defaultFor(InteractionMode.REGULAR))
    }

    @Test fun `defaultFor is ON in ASOC mode`() {
        assertTrue(GestureModeDefaults.defaultFor(InteractionMode.ASOC))
    }

    // Exhaustive over hasExplicitChoice x explicitValue x mode (2 x 2 x 2 = 8 cases): an
    // explicit choice always wins, in either mode and either direction, and an absent choice
    // always falls through to defaultFor(mode) regardless of what explicitValue happens to hold
    // (SessionStore always passes prefs.getBoolean(KEY, true) for explicitValue even when the
    // key was never written, so resolve() must never trust explicitValue on its own).

    @Test fun `explicit TRUE wins in REGULAR mode`() {
        assertTrue(GestureModeDefaults.resolve(hasExplicitChoice = true, explicitValue = true, mode = InteractionMode.REGULAR))
    }

    @Test fun `explicit FALSE wins in REGULAR mode`() {
        assertFalse(GestureModeDefaults.resolve(hasExplicitChoice = true, explicitValue = false, mode = InteractionMode.REGULAR))
    }

    @Test fun `explicit TRUE wins in ASOC mode`() {
        assertTrue(GestureModeDefaults.resolve(hasExplicitChoice = true, explicitValue = true, mode = InteractionMode.ASOC))
    }

    @Test fun `explicit FALSE wins in ASOC mode`() {
        assertFalse(GestureModeDefaults.resolve(hasExplicitChoice = true, explicitValue = false, mode = InteractionMode.ASOC))
    }

    @Test fun `no explicit choice falls through to the REGULAR default (OFF) even if explicitValue is stored TRUE`() {
        assertFalse(GestureModeDefaults.resolve(hasExplicitChoice = false, explicitValue = true, mode = InteractionMode.REGULAR))
    }

    @Test fun `no explicit choice falls through to the REGULAR default (OFF) when explicitValue is FALSE too`() {
        assertFalse(GestureModeDefaults.resolve(hasExplicitChoice = false, explicitValue = false, mode = InteractionMode.REGULAR))
    }

    @Test fun `no explicit choice falls through to the ASOC default (ON) even if explicitValue is stored FALSE`() {
        assertTrue(GestureModeDefaults.resolve(hasExplicitChoice = false, explicitValue = false, mode = InteractionMode.ASOC))
    }

    @Test fun `no explicit choice falls through to the ASOC default (ON) when explicitValue is TRUE too`() {
        assertTrue(GestureModeDefaults.resolve(hasExplicitChoice = false, explicitValue = true, mode = InteractionMode.ASOC))
    }
}
