package dev.aarso.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** Owner-chosen theme mode. SYSTEM follows the OS dark/light setting. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** M3 scheme built FROM the resolved [HyleColors], so MaterialTheme.colorScheme.*
 *  usages switch in lock-step with the raw Aeon palette. */
private fun schemeFrom(c: HyleColors, dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = c.violet, onPrimary = c.onViolet, primaryContainer = c.violetDim, onPrimaryContainer = c.textHigh,
        secondary = c.textMid, onSecondary = c.ink, secondaryContainer = c.inset, onSecondaryContainer = c.textHigh,
        tertiary = c.warning, tertiaryContainer = WarningDim, onTertiaryContainer = c.textHigh,
        background = c.ink, onBackground = c.textHigh, surface = c.ink, onSurface = c.textHigh,
        surfaceVariant = c.raised, onSurfaceVariant = c.textMid, surfaceContainerHighest = c.inset,
        outline = c.outline, outlineVariant = c.outline, error = c.error,
    )
} else {
    lightColorScheme(
        primary = c.violet, onPrimary = c.onViolet, primaryContainer = c.violetDim, onPrimaryContainer = c.textHigh,
        secondary = c.textMid, onSecondary = c.raised, secondaryContainer = c.inset, onSecondaryContainer = c.textHigh,
        tertiary = c.warning, tertiaryContainer = WarningDim, onTertiaryContainer = c.textHigh,
        background = c.ink, onBackground = c.textHigh, surface = c.ink, onSurface = c.textHigh,
        surfaceVariant = c.raised, onSurfaceVariant = c.textMid, surfaceContainerHighest = c.inset,
        outline = c.outline, outlineVariant = c.outline, error = c.error,
    )
}

/**
 * Aarso theme — Hyle structure, now runtime-switchable. [mode] picks dark/light/
 * system; [accent] re-tints the violet ramp (sovereignty of appearance). The full
 * palette is published via [LocalHyleColors]; the matching M3 scheme is published
 * via MaterialTheme so both styles of colour lookup stay in sync.
 */
@Composable
fun AarsoTheme(
    mode: ThemeMode = ThemeMode.DARK,
    accent: Color = DefaultAccent,
    texture: Float = 0f,
    gradient: Color? = null,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val aeon = if (dark) darkHyleColors(accent) else lightHyleColors(accent)
    CompositionLocalProvider(LocalHyleColors provides aeon) {
        MaterialTheme(
            colorScheme = schemeFrom(aeon, dark),
            typography = HyleTypography,
        ) {
            // The root Surface is load-bearing: it sets LocalContentColor. Without
            // it, any Text without an explicit color renders BLACK (Compose's
            // default) — invisible on the dark register.
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = aeon.ink,
                contentColor = aeon.textHigh,
            ) {
                // Ambient layer ONLY (controls keep solid fills, so contrast is never
                // at the gradient's/grain's mercy): a soft accent→2nd-stop gradient
                // blended heavily toward ink, then the capped grain on top.
                val base = Modifier.fillMaxSize()
                val tinted = if (gradient != null) {
                    base.background(
                        Brush.linearGradient(
                            listOf(lerp(aeon.ink, accent, 0.16f), lerp(aeon.ink, gradient, 0.16f)),
                        ),
                    )
                } else {
                    base
                }
                Box(tinted.hyleTexture(texture, aeon.raised)) { content() }
            }
        }
    }
}
