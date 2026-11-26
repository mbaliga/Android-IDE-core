package dev.fonebrew.domain.remote.term

import org.junit.Assert.assertEquals
import org.junit.Test

class WcWidthTest {
    @Test fun `ascii and latin are one cell`() {
        for (c in listOf('a', 'Z', '0', '~', 'é', 'ß')) assertEquals("$c", 1, WcWidth.of(c.code))
    }

    @Test fun `cjk hangul fullwidth and emoji are two cells`() {
        for (cp in listOf(0x4E2D, 0x3042, 0xAC00, 0xFF21, 0x1F600, 0x20000, 0x1F680)) {
            assertEquals("U+${cp.toString(16)}", 2, WcWidth.of(cp))
        }
    }

    @Test fun `combining marks and zero width format characters are zero cells`() {
        for (cp in listOf(0x0301, 0x0300, 0x200B, 0x200D, 0xFE0F, 0x20D0, 0x093C)) {
            assertEquals("U+${cp.toString(16)}", 0, WcWidth.of(cp))
        }
    }

    @Test fun `soft hyphen and unknown private use stay one cell`() {
        assertEquals(1, WcWidth.of(0xAD))
        assertEquals(1, WcWidth.of(0xE000))
    }
}
