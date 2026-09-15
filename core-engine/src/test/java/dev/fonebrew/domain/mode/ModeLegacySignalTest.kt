package dev.fonebrew.domain.mode

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeLegacySignalTest {

    @Test fun `neither onboarding nor spatial map seen -- fresh install, no legacy signal`() {
        assertFalse(ModeLegacySignal.resolve(onboardingDone = false, spatialMapSeen = false))
    }

    @Test fun `onboarding done alone is a legacy signal`() {
        assertTrue(ModeLegacySignal.resolve(onboardingDone = true, spatialMapSeen = false))
    }

    @Test fun `spatial map seen alone is a legacy signal`() {
        assertTrue(ModeLegacySignal.resolve(onboardingDone = false, spatialMapSeen = true))
    }

    @Test fun `both onboarding done and spatial map seen is a legacy signal`() {
        assertTrue(ModeLegacySignal.resolve(onboardingDone = true, spatialMapSeen = true))
    }
}
