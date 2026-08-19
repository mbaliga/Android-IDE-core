package dev.fonebrew.domain.language

import dev.fonebrew.contracts.language.DeliveryFlavor
import dev.fonebrew.contracts.language.ToolchainCapsuleManifest
import dev.fonebrew.contracts.language.ToolchainDeliveryMechanism
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolchainDeliveryLegalityTest {

    @Test
    fun `INTERPRETER_SCRIPTS, BUNDLED_JNILIBS, and REMOTE are legal on both flavors`() {
        for (mechanism in listOf(ToolchainDeliveryMechanism.INTERPRETER_SCRIPTS, ToolchainDeliveryMechanism.BUNDLED_JNILIBS, ToolchainDeliveryMechanism.REMOTE)) {
            assertTrue("$mechanism should be legal for FULL", ToolchainDeliveryLegality.isLegalFor(mechanism, DeliveryFlavor.FULL))
            assertTrue("$mechanism should be legal for PLAY", ToolchainDeliveryLegality.isLegalFor(mechanism, DeliveryFlavor.PLAY))
        }
    }

    @Test
    fun `PLAY_DYNAMIC_FEATURE is Play-only`() {
        assertTrue(ToolchainDeliveryLegality.isLegalFor(ToolchainDeliveryMechanism.PLAY_DYNAMIC_FEATURE, DeliveryFlavor.PLAY))
        assertFalse(ToolchainDeliveryLegality.isLegalFor(ToolchainDeliveryMechanism.PLAY_DYNAMIC_FEATURE, DeliveryFlavor.FULL))
    }

    @Test
    fun `CAPSULE_APK is full-sideload-only`() {
        assertTrue(ToolchainDeliveryLegality.isLegalFor(ToolchainDeliveryMechanism.CAPSULE_APK, DeliveryFlavor.FULL))
        assertFalse(ToolchainDeliveryLegality.isLegalFor(ToolchainDeliveryMechanism.CAPSULE_APK, DeliveryFlavor.PLAY))
    }

    @Test
    fun `prohibitedMechanismsFor PLAY excludes exactly CAPSULE_APK, and for FULL excludes exactly PLAY_DYNAMIC_FEATURE`() {
        assertEquals(setOf(ToolchainDeliveryMechanism.CAPSULE_APK), ToolchainDeliveryLegality.prohibitedMechanismsFor(DeliveryFlavor.PLAY))
        assertEquals(setOf(ToolchainDeliveryMechanism.PLAY_DYNAMIC_FEATURE), ToolchainDeliveryLegality.prohibitedMechanismsFor(DeliveryFlavor.FULL))
    }

    @Test
    fun `isLegalFor a manifest defers to its own declared mechanism`() {
        val bundled = ToolchainCapsuleManifest(
            capsuleId = "capsule.rust-analyzer", languageId = "rust",
            deliveryMechanism = ToolchainDeliveryMechanism.BUNDLED_JNILIBS,
            semanticVersion = "1.0.0", targetAbis = listOf("arm64-v8a"),
        )
        assertTrue(ToolchainDeliveryLegality.isLegalFor(bundled, DeliveryFlavor.PLAY))

        val capsuleApk = bundled.copy(deliveryMechanism = ToolchainDeliveryMechanism.CAPSULE_APK)
        assertFalse(ToolchainDeliveryLegality.isLegalFor(capsuleApk, DeliveryFlavor.PLAY))
    }
}
