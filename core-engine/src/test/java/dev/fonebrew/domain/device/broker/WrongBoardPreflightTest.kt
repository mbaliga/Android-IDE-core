package dev.fonebrew.domain.device.broker

import dev.fonebrew.contracts.devices.BoardRef
import dev.fonebrew.contracts.devices.CatalogRef
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exhaustive coverage of DEVICE_STATE_AND_SAFETY_SPEC.md §4/FB-RAT-DEV-007's exact matcher rule. */
class WrongBoardPreflightTest {

    @Test
    fun `two cataloged boards under the same catalog with the same catalogId match`() {
        val device = BoardRef(CatalogRef.ARDUINO_CLI, "Arduino Uno", "arduino:avr:uno")
        val artifact = BoardRef(CatalogRef.ARDUINO_CLI, "Uno (compat list entry)", "arduino:avr:uno")
        assertTrue(WrongBoardPreflight.isCompatible(device, listOf(artifact)))
    }

    @Test
    fun `the same catalogId string under two DIFFERENT catalogs is NOT a match -- this is the exact wrong-board trap the spec names`() {
        val device = BoardRef(CatalogRef.ARDUINO_CLI, "Some Arduino board", "esp32dev")
        val artifact = BoardRef(CatalogRef.PLATFORMIO, "ESP32 Dev Module", "esp32dev")
        assertFalse(WrongBoardPreflight.isCompatible(device, listOf(artifact)))
    }

    @Test
    fun `two cataloged boards with different catalogIds under the same catalog do not match`() {
        val device = BoardRef(CatalogRef.ARDUINO_CLI, "Arduino Uno", "arduino:avr:uno")
        val artifact = BoardRef(CatalogRef.ARDUINO_CLI, "Arduino Nano", "arduino:avr:nano")
        assertFalse(WrongBoardPreflight.isCompatible(device, listOf(artifact)))
    }

    @Test
    fun `an UNCATALOGED device falls back to exact displayName matching`() {
        val device = BoardRef(CatalogRef.UNCATALOGED, "Generic CH340 Clone Board")
        val artifactSame = BoardRef(CatalogRef.UNCATALOGED, "Generic CH340 Clone Board")
        val artifactDifferent = BoardRef(CatalogRef.UNCATALOGED, "Some Other Board")
        assertTrue(WrongBoardPreflight.isCompatible(device, listOf(artifactSame)))
        assertFalse(WrongBoardPreflight.isCompatible(device, listOf(artifactDifferent)))
    }

    @Test
    fun `a cataloged device against an UNCATALOGED artifact entry falls back to displayName -- catalogId is ignored once either side lacks one`() {
        val device = BoardRef(CatalogRef.ARDUINO_CLI, "Arduino Uno", "arduino:avr:uno")
        val artifact = BoardRef(CatalogRef.UNCATALOGED, "Arduino Uno") // same display name, no catalogId
        assertTrue(WrongBoardPreflight.isCompatible(device, listOf(artifact)))
    }

    @Test
    fun `isCompatible checks every entry in a multi-board compatibility list, not just the first`() {
        val device = BoardRef(CatalogRef.PLATFORMIO, "ESP32 Dev Module", "esp32dev")
        val compatibility = listOf(
            BoardRef(CatalogRef.ARDUINO_CLI, "Arduino Uno", "arduino:avr:uno"),
            BoardRef(CatalogRef.PLATFORMIO, "ESP32 Dev Module", "esp32dev"), // the real match, listed second
        )
        assertTrue(WrongBoardPreflight.isCompatible(device, compatibility))
    }
}
