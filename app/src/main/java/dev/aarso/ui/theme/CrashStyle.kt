package dev.aarso.ui.theme

import dev.aarso.hyle.theme.*
import androidx.compose.ui.graphics.toArgb
import dev.aarso.crashrecovery.CrashRecoveryStyle

/**
 * The app's own dark palette, translated for the design-system-agnostic crash-recovery module
 * (`CrashRecoveryStyle` is deliberately plain `@ColorInt Int`, with no Hyle dependency, so any
 * consumer in the constellation can supply its own look) — so a crash still reads as Fonebrew,
 * not the module's unrelated generic default.
 */
val FonebrewCrashRecoveryStyle: CrashRecoveryStyle = CrashRecoveryStyle(
    background = Ink.toArgb(),
    surface = Raised.toArgb(),
    foreground = TextHigh.toArgb(),
    muted = TextMid.toArgb(),
    accent = Violet.toArgb(),
    danger = ErrorRed.toArgb(),
    traceText = TextMid.toArgb(),
)
