package dev.aarso.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure colour math for the theme engine: derive a full interaction ramp from a
 * single user-chosen accent, and keep everything WCAG-AA legible. No Android or
 * Compose-runtime dependencies beyond the [Color] value class, so it is plain
 * JVM-testable.
 */

/** The derived accent ramp: base + interaction states + the on-accent text colour. */
data class AccentRamp(
    val base: Color,
    val hover: Color,
    val pressed: Color,
    val dim: Color,
    val on: Color,
)

/**
 * Derive a ramp from one accent, in HSL: keep hue/chroma, move lightness for
 * hover (+) and pressed (−), build a low-chroma "dim" selection tint for the
 * mode, and pick an on-accent text colour that clears AA (4.5:1).
 */
fun deriveRamp(accent: Color, dark: Boolean): AccentRamp {
    val hsl = accent.toHsl()
    val h = hsl[0]
    val s = hsl[1]
    val l = hsl[2]
    val base = clampForContrast(accent, if (dark) Ink else LInk)
    val hover = hslColor(h, s, (l + 0.08f).coerceIn(0f, 1f))
    val pressed = hslColor(h, s, (l - 0.14f).coerceIn(0f, 1f))
    val dim = if (dark) hslColor(h, (s * 0.55f).coerceIn(0f, 1f), 0.20f)
    else hslColor(h, (s * 0.45f).coerceIn(0f, 1f), 0.90f)
    return AccentRamp(base, hover, pressed, dim, onColorFor(base))
}

/** On-accent text colour that clears AA (4.5:1) against [bg]. Prefers the brand
 *  near-black for warmth, then falls back to pure black/white — one of which always
 *  clears AA for any colour. */
fun onColorFor(bg: Color): Color {
    val candidates = listOf(Color(0xFF160F2E), Color.Black, Color.White)
    return candidates.firstOrNull { contrastRatio(bg, it) >= 4.5f }
        ?: candidates.maxByOrNull { contrastRatio(bg, it) }!!
}

/** Nudge an accent's lightness until it clears [minRatio] against [bg] (control visibility). */
fun clampForContrast(accent: Color, bg: Color, minRatio: Float = 3f): Color {
    if (contrastRatio(accent, bg) >= minRatio) return accent
    val hsl = accent.toHsl()
    val goLighter = relativeLuminance(bg) < 0.5f
    var l = hsl[2]
    repeat(22) {
        l = (l + if (goLighter) 0.04f else -0.04f).coerceIn(0f, 1f)
        val c = hslColor(hsl[0], hsl[1], l)
        if (contrastRatio(c, bg) >= minRatio) return c
    }
    return hslColor(hsl[0], hsl[1], l)
}

// ── colour space helpers ────────────────────────────────────────────────────

/** WCAG relative luminance of [c]. */
fun relativeLuminance(c: Color): Float {
    fun lin(ch: Float): Float =
        if (ch <= 0.03928f) ch / 12.92f else Math.pow(((ch + 0.055) / 1.055), 2.4).toFloat()
    return 0.2126f * lin(c.red) + 0.7152f * lin(c.green) + 0.0722f * lin(c.blue)
}

/** WCAG contrast ratio between [a] and [b] (1..21). */
fun contrastRatio(a: Color, b: Color): Float {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    val hi = max(la, lb)
    val lo = min(la, lb)
    return (hi + 0.05f) / (lo + 0.05f)
}

/** [h in 0..360, s in 0..1, l in 0..1]. */
fun Color.toHsl(): FloatArray {
    val r = red
    val g = green
    val b = blue
    val mx = max(r, max(g, b))
    val mn = min(r, min(g, b))
    val d = mx - mn
    val l = (mx + mn) / 2f
    var h = 0f
    var s = 0f
    if (d != 0f) {
        s = d / (1f - abs(2f * l - 1f))
        h = when (mx) {
            r -> ((g - b) / d) % 6f
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        } * 60f
        if (h < 0f) h += 360f
    }
    return floatArrayOf(h, s, l)
}

/** Build a [Color] from HSL. */
fun hslColor(h: Float, s: Float, l: Float): Color {
    val c = (1f - abs(2f * l - 1f)) * s
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = l - c / 2f
    val (r, g, b) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color((r + m).coerceIn(0f, 1f), (g + m).coerceIn(0f, 1f), (b + m).coerceIn(0f, 1f))
}

/** Format a colour as "#RRGGBB" (alpha dropped). */
fun Color.toHexRgb(): String {
    val r = (red * 255f).toInt().coerceIn(0, 255)
    val g = (green * 255f).toInt().coerceIn(0, 255)
    val b = (blue * 255f).toInt().coerceIn(0, 255)
    return "#%02X%02X%02X".format(r, g, b)
}

/** Parse "#RRGGBB" or "#AARRGGBB" (or without '#'); null if malformed. */
fun parseHexColor(hex: String): Color? = runCatching {
    val h = hex.trim().removePrefix("#")
    val argb = when (h.length) {
        6 -> 0xFF000000L or h.toLong(16)
        8 -> h.toLong(16)
        else -> return null
    }
    Color(argb)
}.getOrNull()
