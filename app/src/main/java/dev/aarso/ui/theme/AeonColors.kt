package dev.aarso.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The full Hyle palette as a *runtime* value rather than compile-time constants,
 * so the app can switch dark/light and re-tint to the user's chosen accent
 * (cognitive-sovereignty: the user makes the surface their own). Neutrals come
 * from the mode; the violet ramp + accent semantics are derived from the accent
 * via [deriveRamp]. Read it in composition as `LocalHyleColors.current`.
 */
data class HyleColors(
    val ink: Color,
    val raised: Color,
    val inset: Color,
    val outline: Color,
    val hairline: Color,
    val textHigh: Color,
    val textMid: Color,
    val textDisabled: Color,
    val violet: Color,
    val violetHover: Color,
    val violetPressed: Color,
    val violetDim: Color,
    val onViolet: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
)

/** Default accent = the canonical Aarso violet (#8E7BFF). */
val DefaultAccent = Violet

/** Dark palette. The canonical violet keeps its hand-tuned ramp verbatim (zero
 *  visual change by default); any other accent derives its ramp. */
fun darkHyleColors(accent: Color = DefaultAccent): HyleColors {
    val ramp = if (accent == Violet) {
        AccentRamp(Violet, VioletHover, VioletPressed, VioletDim, OnViolet)
    } else {
        deriveRamp(accent, dark = true)
    }
    return HyleColors(
        ink = Ink, raised = Raised, inset = Inset, outline = Outline, hairline = Hairline,
        textHigh = TextHigh, textMid = TextMid, textDisabled = TextDisabled,
        violet = ramp.base, violetHover = ramp.hover, violetPressed = ramp.pressed,
        violetDim = ramp.dim, onViolet = ramp.on,
        success = Success, warning = Warning, error = ErrorRed,
    )
}

/** Light palette. The default violet maps to its darker light-mode variant. */
fun lightHyleColors(accent: Color = DefaultAccent): HyleColors {
    val baseAccent = if (accent == Violet) LViolet else accent
    val ramp = deriveRamp(baseAccent, dark = false)
    return HyleColors(
        ink = LInk, raised = LSurface1, inset = LSurface2, outline = LOutline, hairline = LHairline,
        textHigh = LTextHigh, textMid = LTextMid, textDisabled = LTextDisabled,
        violet = ramp.base, violetHover = ramp.hover, violetPressed = ramp.pressed,
        violetDim = ramp.dim, onViolet = ramp.on,
        success = SuccessDeep, warning = Warning, error = ErrorRed,
    )
}

val LocalHyleColors = staticCompositionLocalOf { darkHyleColors() }
