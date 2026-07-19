package dev.aarso.ui.hyle

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Touch feedback, one vocabulary so it isn't re-invented per screen. minSdk 31 covers every
 * constant used here (CONFIRM/REJECT/GESTURE_END landed in API 30). Render/gesture behaviour is
 * owner-verified on device (CI never launches the app).
 */
class HyleHaptics internal constructor(private val view: View) {
    /** A control was pressed (button, chip, swatch). */
    fun tap() = view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)

    /** An action completed successfully (download finished, commit landed). */
    fun confirm() = view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)

    /** An action failed or was refused. */
    fun reject() = view.performHapticFeedback(HapticFeedbackConstants.REJECT)

    /** A drag settled into place (a spatial room parked, a slider/color-picker thumb released). */
    fun settle() = view.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
}

@Composable
fun rememberHyleHaptics(): HyleHaptics {
    val view = LocalView.current
    return remember(view) { HyleHaptics(view) }
}
