package dev.aarso.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The default palettes (dark + light, canonical violet) must clear WCAG AA: body
 * text ≥ 4.5:1, and the accent ≥ 3:1 as a UI component. A guard so neither the
 * tokens nor the ramp math regress legibility.
 */
class ContrastTest {

    private fun assertAA(c: HyleColors, label: String) {
        assertTrue("$label textHigh < 4.5", contrastRatio(c.textHigh, c.ink) >= 4.5f)
        assertTrue("$label textMid < 4.5", contrastRatio(c.textMid, c.ink) >= 4.5f)
        assertTrue("$label onViolet on violet < 4.5", contrastRatio(c.onViolet, c.violet) >= 4.5f)
        assertTrue("$label violet < 3:1 UI", contrastRatio(c.violet, c.ink) >= 3f)
        assertTrue("$label error < 3:1 UI", contrastRatio(c.error, c.ink) >= 3f)
    }

    @Test fun `default dark palette clears AA`() = assertAA(darkHyleColors(), "dark")

    @Test fun `default light palette clears AA`() = assertAA(lightHyleColors(), "light")
}
