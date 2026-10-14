package dev.fonebrew.watch.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import dev.fonebrew.watch.core.WatchPalette

/**
 * WatchBrew's theme: Hyle's **colour tokens only** (ink, paper, the brew accent ramp — see
 * [WatchPalette] for the copied constants and the single-source follow-up) mapped onto Wear Compose
 * Material's [Colors]. No Hyle component, type or font is ported (design doc section 3; that is a named
 * follow-up in the Hyle repo, not a silent fork). Wear Compose draws its own components; they simply
 * wear Hyle's colours.
 *
 * Colour-blind rule: there is no success/warning/error hue in this theme. Wear's `error` slot is
 * required, so it is filled with *paper* (an ink/paper inversion): even a stock component that paints
 * "error" cannot paint it red. State is carried by shape and words (core/Glyph.kt), never by this theme.
 */
@Composable
internal fun WatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(colors = WatchColors, content = content)
}

private val WatchColors = Colors(
    primary = Color(WatchPalette.BREW),
    primaryVariant = Color(WatchPalette.BREW_DEEP),
    secondary = Color(WatchPalette.BREW_BRIGHT),
    secondaryVariant = Color(WatchPalette.BREW_DEEP),
    background = Color(WatchPalette.INK),
    surface = Color(WatchPalette.RAISED),
    error = Color(WatchPalette.PAPER),
    onPrimary = Color(WatchPalette.ON_BREW),
    onSecondary = Color(WatchPalette.ON_BREW),
    onBackground = Color(WatchPalette.PAPER),
    onSurface = Color(WatchPalette.PAPER),
    onSurfaceVariant = Color(WatchPalette.PAPER_MID),
    onError = Color(WatchPalette.INK),
)
