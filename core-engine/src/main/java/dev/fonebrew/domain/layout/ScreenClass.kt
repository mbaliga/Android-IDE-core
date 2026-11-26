package dev.fonebrew.domain.layout

/** Coarse size/context class a layout is chosen for. Never derived from pixels alone. */
enum class ScreenClass { PHONE, TABLET, DESKTOP, ULTRAWIDE }

/**
 * Everything the resolver may look at. Sizes are **dp** (pixels divided by density) - a 5120 px
 * wide panel is 5120 dp at density 1.0 and 1707 dp at 3.0, which is the whole point.
 */
data class ScreenInputs(
    val widthDp: Float,
    val heightDp: Float,
    /** The window is on a non-default or presentation-flagged display (a monitor, not the handset). */
    val isExternalDisplay: Boolean,
    val hasHardwareKeyboard: Boolean,
    val hasPointer: Boolean,
) {
    val aspect: Float get() = if (heightDp > 0f) widthDp / heightDp else 0f
    val smallestDp: Float get() = minOf(widthDp, heightDp)

    /** A desktop-style context: a monitor, or a keyboard AND a pointing device together. */
    val isDesktopContext: Boolean get() = isExternalDisplay || (hasHardwareKeyboard && hasPointer)
}

/**
 * PROPOSED thresholds - **not facts, not owner-approved**. They are starting points that the unit
 * table pins so a change is deliberate; adjust after the owner has docked a real phone and seen
 * what density the RedMagic actually reports on the G9. Window-size-class boundaries (600 / 840)
 * echo the public Material guidance but are not claimed to be right for this app.
 */
object ScreenClassThresholds {
    /** PROPOSED: below this smallest-dimension a window is a PHONE (unless docked and wide, below). */
    const val TABLET_MIN_SMALLEST_DP = 600f

    /** PROPOSED: a docked/external window at least this wide is never PHONE; it is at least TABLET. */
    const val DOCKED_MIN_WIDTH_DP = 840f

    /** PROPOSED: width at which a desktop-context window becomes DESKTOP. */
    const val DESKTOP_MIN_WIDTH_DP = 1000f

    /** PROPOSED: width at which a very wide desktop-context window may become ULTRAWIDE... */
    const val ULTRAWIDE_MIN_WIDTH_DP = 1600f

    /** PROPOSED: ...provided its aspect ratio (width / height) is at least this (21:9 = 2.33, 32:9 = 3.56). */
    const val ULTRAWIDE_MIN_ASPECT = 2.4f
}

/** Pure resolver. Order matters and is documented in docs/design/docked-layout.md. */
object ScreenClassResolver {
    fun resolve(i: ScreenInputs): ScreenClass {
        val t = ScreenClassThresholds
        if (i.isDesktopContext) {
            if (i.widthDp >= t.ULTRAWIDE_MIN_WIDTH_DP && i.aspect >= t.ULTRAWIDE_MIN_ASPECT) return ScreenClass.ULTRAWIDE
            if (i.widthDp >= t.DESKTOP_MIN_WIDTH_DP) return ScreenClass.DESKTOP
            if (i.widthDp >= t.DOCKED_MIN_WIDTH_DP) return ScreenClass.TABLET
        }
        return if (i.smallestDp >= t.TABLET_MIN_SMALLEST_DP) ScreenClass.TABLET else ScreenClass.PHONE
    }

    /** Convenience for callers holding pixels: converts with [densityDpi] first (160 = density 1.0). */
    fun resolveFromPixels(
        widthPx: Int,
        heightPx: Int,
        densityDpi: Int,
        isExternalDisplay: Boolean,
        hasHardwareKeyboard: Boolean,
        hasPointer: Boolean,
    ): ScreenClass {
        val d = if (densityDpi > 0) densityDpi / 160f else 1f
        return resolve(ScreenInputs(widthPx / d, heightPx / d, isExternalDisplay, hasHardwareKeyboard, hasPointer))
    }
}
