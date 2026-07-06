package dev.aarso.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every shipped accent preset must stay legible in BOTH modes: the derived base
 * clears 3:1 against the mode's ground (a UI-component minimum), and the on-accent
 * text clears AA (4.5:1). Mirrors ACCENT_PRESETS in ThemePicker.
 */
class PresetContrastTest {

    private val presets = listOf(
        "#8E7BFF", "#4DA3FF", "#2DD4BF", "#5BD16A",
        "#F2B53B", "#FF7A66", "#FF7AB6", "#9AA7B8",
    )

    @Test fun `presets are AA-legible in dark and light`() {
        presets.forEach { hex ->
            val accent = parseHexColor(hex)!!
            listOf(true to Ink, false to LInk).forEach { (dark, bg) ->
                val ramp = deriveRamp(accent, dark)
                assertTrue(
                    "$hex base fails 3:1 on ${if (dark) "dark" else "light"}",
                    contrastRatio(ramp.base, bg) >= 3f - 0.01f,
                )
                assertTrue(
                    "$hex on-colour fails AA",
                    contrastRatio(ramp.base, ramp.on) >= 4.5f,
                )
            }
        }
    }
}
