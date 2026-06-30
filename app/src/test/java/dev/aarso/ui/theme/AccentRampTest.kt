package dev.aarso.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure colour math for the theme engine — no Android, plain JVM. */
class AccentRampTest {

    @Test fun `contrast of black on white is ~21`() {
        val r = contrastRatio(Color.White, Color.Black)
        assertTrue("expected ~21, got $r", r > 20.9f && r <= 21.1f)
    }

    @Test fun `luminance bounds`() {
        assertEquals(1f, relativeLuminance(Color.White), 0.001f)
        assertEquals(0f, relativeLuminance(Color.Black), 0.001f)
    }

    @Test fun `on-accent text clears AA against its accent`() {
        // Sample a spread of hues; the chosen on-colour must clear 4.5:1.
        listOf(0xFF8E7BFF, 0xFF0387D3, 0xFF3AB700, 0xFFEE322C, 0xFFF78819, 0xFFFFFF00, 0xFF111111)
            .forEach { argb ->
                val accent = Color(argb)
                val on = onColorFor(accent)
                assertTrue("on-colour failed AA for ${argb.toString(16)}", contrastRatio(accent, on) >= 4.5f)
            }
    }

    @Test fun `clampForContrast lifts a low-contrast accent above 3 to 1 on its ground`() {
        // A near-black accent on the dark ground is invisible; clamp must fix it.
        val dim = Color(0xFF15131C)
        val fixed = clampForContrast(dim, Ink, minRatio = 3f)
        assertTrue(contrastRatio(fixed, Ink) >= 3f - 0.01f)
    }

    @Test fun `derived ramp keeps hover lighter and pressed darker`() {
        val ramp = deriveRamp(Color(0xFF8E7BFF), dark = true)
        assertTrue(relativeLuminance(ramp.hover) >= relativeLuminance(ramp.base))
        assertTrue(relativeLuminance(ramp.pressed) <= relativeLuminance(ramp.base))
    }

    @Test fun `hsl round-trips a mid colour`() {
        val c = Color(0xFF8E7BFF)
        val hsl = c.toHsl()
        val back = hslColor(hsl[0], hsl[1], hsl[2])
        assertEquals(c.red, back.red, 0.02f)
        assertEquals(c.green, back.green, 0.02f)
        assertEquals(c.blue, back.blue, 0.02f)
    }

    @Test fun `parseHexColor handles 6 and 8 digit and rejects junk`() {
        assertEquals(Color(0xFF8E7BFF), parseHexColor("#8E7BFF"))
        assertEquals(Color(0xFF8E7BFF), parseHexColor("8E7BFF"))
        assertEquals(Color(0x808E7BFF), parseHexColor("#808E7BFF"))
        assertNull(parseHexColor("nope"))
        assertNull(parseHexColor("#FFF"))
    }
}
