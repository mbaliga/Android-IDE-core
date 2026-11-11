package dev.fonebrew.ui.develop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareModesTest {
    @Test fun `full flavor offers USB`() {
        assertTrue(HardwareMode.USB in HardwareModes.available(usbAvailable = true))
    }

    @Test fun `play flavor offers no USB mode`() {
        val modes = HardwareModes.available(usbAvailable = false)
        assertFalse(HardwareMode.USB in modes)
        assertEquals(listOf(HardwareMode.PI, HardwareMode.ARDUINO, HardwareMode.ESP), modes)
    }

    @Test fun `no host defaults to USB only where USB exists`() {
        assertEquals(HardwareMode.USB, HardwareModes.default(hasHosts = false, usbAvailable = true))
        assertEquals(HardwareMode.PI, HardwareModes.default(hasHosts = false, usbAvailable = false))
    }

    @Test fun `with a host the default is the Pi path`() {
        assertEquals(HardwareMode.PI, HardwareModes.default(hasHosts = true, usbAvailable = true))
        assertEquals(HardwareMode.PI, HardwareModes.default(hasHosts = true, usbAvailable = false))
    }

    @Test fun `the default is always an available mode`() {
        for (hosts in listOf(true, false)) for (usb in listOf(true, false)) {
            assertTrue(HardwareModes.default(hosts, usb) in HardwareModes.available(usb))
        }
    }
}
