package dev.aarso.ui.spatial

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the centre bar's three-stage degradation ladder (owner-set): icons+labels for
 * all, then the selected tab's label only, then icons alone — decided purely from the
 * measured widths, so this JVM gate covers the decision even though the render itself
 * is owner-verified.
 */
class CenterTabLayoutTest {

    // Icon 22, gap 6, padding 14 → an unlabelled slot is 50; a labelled slot is 56+label.
    private val icon = 22f
    private val gap = 6f
    private val pad = 14f
    private val labels = listOf(90f, 60f, 110f) // Conversation / Terminal / Background tasks

    private fun stage(available: Float, selected: Int = 0) =
        CenterTabLayout.stage(available, labels, selected, icon, gap, pad)

    @Test
    fun `wide bar shows every label`() {
        // Full need: 3*(28+22+6) + 90+60+110 = 428.
        assertEquals(CenterTabStage.FULL, stage(428f))
        assertEquals(CenterTabStage.FULL, stage(1000f))
    }

    @Test
    fun `one pixel short of full falls to the selected label only`() {
        assertEquals(CenterTabStage.SELECTED_LABEL, stage(427f))
    }

    @Test
    fun `selected-label stage needs the selected tab's own width, so who is selected matters`() {
        // Selected-only need: 3*50 + gap + label(sel). Terminal (60) → 216; Background (110) → 266.
        assertEquals(CenterTabStage.SELECTED_LABEL, stage(216f, selected = 1))
        assertEquals(CenterTabStage.ICONS_ONLY, stage(216f, selected = 2))
    }

    @Test
    fun `too narrow for any label leaves icons alone`() {
        assertEquals(CenterTabStage.ICONS_ONLY, stage(150f))
        assertEquals(CenterTabStage.ICONS_ONLY, stage(0f))
    }

    @Test
    fun `out-of-range selection labels nothing extra instead of crashing`() {
        assertEquals(CenterTabStage.SELECTED_LABEL, stage(150.1f, selected = -1))
    }

    @Test
    fun `slot width arithmetic is the documented sum`() {
        assertEquals(50f, CenterTabLayout.slotWidth(icon, null, gap, pad))
        assertEquals(146f, CenterTabLayout.slotWidth(icon, 90f, gap, pad))
    }
}
