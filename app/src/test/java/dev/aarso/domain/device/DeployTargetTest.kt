package dev.aarso.domain.device

import dev.aarso.domain.remote.RemoteHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeployTargetTest {

    @Test fun `a valid FQBN is accepted and a malformed one is rejected`() {
        assertEquals("arduino:avr:uno", Fqbn("arduino:avr:uno").value)
        assertThrows { Fqbn("arduino:avr") }
        assertThrows { Fqbn("") }
        assertThrows { Fqbn("a::c") }
    }

    @Test fun `serial port rejects blank`() {
        assertEquals("/dev/ttyACM0", SerialPort("/dev/ttyACM0").path)
        assertThrows { SerialPort("") }
    }

    @Test fun `targets expose a legible name`() {
        val pi = RemoteHost("pi", "192.168.1.10", 22, "pi")
        assertEquals("pi", DeployTarget.Remote(pi).name)
        val ard = DeployTarget.Arduino(pi, Fqbn("arduino:avr:uno"), SerialPort("/dev/ttyACM0"), "uno-on-pi")
        assertEquals("uno-on-pi", ard.name)
    }

    private fun assertThrows(block: () -> Unit) {
        var threw = false
        try { block() } catch (e: IllegalArgumentException) { threw = true }
        assertTrue("expected IllegalArgumentException", threw)
    }
}
