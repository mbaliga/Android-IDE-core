package dev.aarso.domain.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArduinoCliTest {

    @Test fun `a clean compile reports ok and parses flash and ram usage`() {
        val out = """
            Sketch uses 924 bytes (2%) of program storage space. Maximum is 32256 bytes.
            Global variables use 9 bytes (0%) of dynamic memory, leaving 2039 bytes for local variables. Maximum is 2048 bytes.
        """.trimIndent()
        val r = ArduinoCli.parseCompile(out)
        assertTrue(r.ok)
        assertTrue(r.errors.isEmpty())
        assertEquals(924, r.size!!.programBytes)
        assertEquals(2, r.size!!.programPct)
        assertEquals(9, r.size!!.dataBytes)
    }

    @Test fun `a failed compile collects file-line-col diagnostics and is not ok`() {
        val out = """
            /home/pi/blink/blink.ino: In function 'void setup()':
            /home/pi/blink/blink.ino:3:3: error: 'pinMod' was not declared in this scope
               pinMod(13, OUTPUT);
               ^~~~~~
            Error during build: exit status 1
        """.trimIndent()
        val r = ArduinoCli.parseCompile(out)
        assertFalse(r.ok)
        assertEquals(1, r.errors.size)
        val e = r.errors.first()
        assertEquals("/home/pi/blink/blink.ino", e.file)
        assertEquals(3, e.line)
        assertEquals(3, e.col)
        assertTrue(e.message.contains("pinMod"))
    }

    @Test fun `a successful avrdude upload is ok`() {
        val out = """
            avrdude: writing flash (924 bytes):
            avrdude: 924 bytes of flash written
            avrdude: verifying ...
            avrdude: 924 bytes of flash verified
            avrdude done.  Thank you.
        """.trimIndent()
        assertTrue(ArduinoCli.parseUpload(out).ok)
    }

    @Test fun `an upload to a missing device is not ok`() {
        val out = """
            avrdude: ser_open(): can't open device "/dev/ttyACM0": No such file or directory
            Error: uploading error: exit status 1
        """.trimIndent()
        assertFalse(ArduinoCli.parseUpload(out).ok)
    }

    @Test fun `an esptool verified upload is ok`() {
        val out = "Writing at 0x00010000... (100 %)\nHash of data verified.\nLeaving...\nHard resetting via RTS pin..."
        assertTrue(ArduinoCli.parseUpload(out).ok)
    }
}
