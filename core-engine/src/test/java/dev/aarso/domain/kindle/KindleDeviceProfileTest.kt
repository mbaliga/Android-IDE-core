package dev.aarso.domain.kindle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KindleDeviceProfileTest {
    private val firmware = KindleFirmwareVersion.parse("5.17.1.0.3")
    private val oasis = KindleDeviceProfile(
        id = "kindle-oasis-3-koa3",
        family = "Kindle",
        model = "Kindle Oasis 3",
        generation = "10th gen",
        aliases = setOf("KOA3", "Kindle Oasis (10th Generation)"),
        serialPrefixes = setOf("G000WP04"),
        certifiedFirmware = setOf(firmware),
        provisioningRanges = listOf(KindleFirmwareRange(firmware, firmware)),
        displayWidth = 1264,
        displayHeight = 1680,
        pageButtons = true,
    )

    @Test fun matchesSerialWithFormattingRemoved() {
        val result = KindleProfileRegistry(listOf(oasis)).match(
            KindleIdentity(serial = "G000 WP04 1234", firmware = firmware),
        )
        assertEquals(oasis, (result as KindleProfileMatch.Matched).profile)
        assertTrue(result.confidence >= 100)
    }

    @Test fun amazonVendorAloneDoesNotSelectAProfile() {
        assertEquals(
            KindleProfileMatch.Unknown,
            KindleProfileRegistry(listOf(oasis)).match(KindleIdentity(usbVendorId = 0x1949)),
        )
    }

    @Test fun firmwareComparisonAndGuardrailAreNumeric() {
        assertTrue(KindleFirmwareVersion.parse("5.17.1.0.3") < KindleFirmwareVersion.parse("5.18.0"))
        assertTrue(oasis.supportsProvisioning(firmware))
        assertFalse(oasis.supportsProvisioning(KindleFirmwareVersion.parse("5.17.1.0.4")))
    }
}

