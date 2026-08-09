package dev.aarso.ui.spatial

/**
 * The centre bar's three degradation stages (owner-set, in order of preference):
 * icons **and** labels on every tab; then label on the **selected** tab only (icons
 * everywhere); then icons alone. Which stage applies is pure arithmetic over measured
 * widths — kept here, free of Compose types, so the JVM gate pins the ladder down while
 * the render itself stays owner-verified.
 */
enum class CenterTabStage { FULL, SELECTED_LABEL, ICONS_ONLY }

object CenterTabLayout {

    /** Width one tab slot needs: paddings + icon, plus the label and its gap if shown. */
    fun slotWidth(iconPx: Float, labelPx: Float?, gapPx: Float, paddingPx: Float): Float =
        paddingPx * 2 + iconPx + (labelPx?.let { gapPx + it } ?: 0f)

    /**
     * Picks the widest stage that fits. [labelWidthsPx] holds each tab's measured label
     * width in slot order; [selected] indexes the active tab (out-of-range values fall
     * back to labelling nothing extra, which can only make things fit sooner).
     */
    fun stage(
        availablePx: Float,
        labelWidthsPx: List<Float>,
        selected: Int,
        iconPx: Float,
        gapPx: Float,
        paddingPx: Float,
    ): CenterTabStage {
        val full = labelWidthsPx.sumOf { slotWidth(iconPx, it, gapPx, paddingPx).toDouble() }.toFloat()
        if (full <= availablePx) return CenterTabStage.FULL

        val selectedOnly = labelWidthsPx.indices.sumOf { i ->
            slotWidth(iconPx, labelWidthsPx.getOrNull(i).takeIf { i == selected }, gapPx, paddingPx).toDouble()
        }.toFloat()
        if (selectedOnly <= availablePx) return CenterTabStage.SELECTED_LABEL

        return CenterTabStage.ICONS_ONLY
    }
}
