package dev.aarso.domain.device

import dev.aarso.domain.device.recipe.DeviceRecipes
import dev.aarso.domain.remote.RemoteHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceRecipesTest {

    private val pi = RemoteHost("pi", "192.168.1.10", 22, "pi")
    private val uno = DeployTarget.Arduino(pi, Fqbn("arduino:avr:uno"), SerialPort("/dev/ttyACM0"))

    @Test fun `run builds an interpreter command with quoted path and args`() {
        val req = DeviceRecipes.run(DeployTarget.Remote(pi), "python3", "/home/pi/app.py", listOf("--fast"))
        assertEquals("python3 '/home/pi/app.py' '--fast'", req.command)
    }

    @Test fun `compile and upload build the arduino-cli commands`() {
        assertEquals(
            "arduino-cli compile --fqbn 'arduino:avr:uno' '/home/pi/blink'",
            DeviceRecipes.compile(uno, "/home/pi/blink").command,
        )
        assertEquals(
            "arduino-cli upload -p '/dev/ttyACM0' --fqbn 'arduino:avr:uno' '/home/pi/blink'",
            DeviceRecipes.upload(uno, "/home/pi/blink").command,
        )
    }

    @Test fun `compileAndUpload chains with && so upload only runs on a clean build`() {
        val cmd = DeviceRecipes.compileAndUpload(uno, "/home/pi/blink").command
        assertTrue(cmd.contains("compile"))
        assertTrue(cmd.contains(" && "))
        assertTrue(cmd.indexOf("compile") < cmd.indexOf("upload"))
    }

    @Test fun `espOta builds an espota push and rejects a blank ip`() {
        val req = DeviceRecipes.espOta("192.168.1.50", "/tmp/fw.bin")
        assertEquals("python3 'espota.py' -i '192.168.1.50' -p 3232 -f '/tmp/fw.bin'", req.command)
        var threw = false
        try { DeviceRecipes.espOta("", "/tmp/fw.bin") } catch (e: IllegalArgumentException) { threw = true }
        assertTrue(threw)
    }

    @Test fun `paths with spaces and quotes are safely quoted`() {
        val req = DeviceRecipes.compile(uno, "/home/pi/My Sketch")
        assertTrue(req.command.contains("'/home/pi/My Sketch'"))
    }
}
