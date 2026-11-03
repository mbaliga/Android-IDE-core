package dev.fonebrew.ui.theme

import androidx.compose.ui.graphics.toArgb
import dev.aarso.crashrecovery.CrashRecoveryStyle
import dev.aarso.hyle.theme.darkHyleColors
import dev.aarso.hyle.theme.lightHyleColors

/**
 * The app's own accent, translated for the design-system-agnostic crash-recovery module
 * (`CrashRecoveryStyle` is deliberately plain `@ColorInt Int` accent pairs, with no Hyle
 * dependency, so any consumer in the constellation can supply its own look) — so a crash still
 * reads as Fonebrew's violet, not the module's unrelated generic default.
 *
 * MERGE-NOTE: the launch line's original file called a `CrashRecoveryStyle(background=, surface=,
 * foreground=, muted=, accent=, danger=, traceText=)` constructor that no longer exists on the
 * pinned crash-recovery module (see `hyle-design-system/crash-recovery/.../CrashRecoveryStyle.kt`)
 * — it now only takes a light/dark accent pair and supplies its own neutral paper/ink surface.
 * Rebuilt on the current API using the two Hyle palettes' resolved violet/onViolet per mode
 * rather than reaching for individual tokens, so this keeps tracking the palette automatically.
 */
val FonebrewCrashRecoveryStyle: CrashRecoveryStyle = CrashRecoveryStyle.accent(
    light = lightHyleColors().violet.toArgb(),
    onLight = lightHyleColors().onViolet.toArgb(),
    dark = darkHyleColors().violet.toArgb(),
    onDark = darkHyleColors().onViolet.toArgb(),
)
