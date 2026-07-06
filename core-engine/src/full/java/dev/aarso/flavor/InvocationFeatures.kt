package dev.aarso.flavor

import android.content.Context
import dev.aarso.service.OverlayService

/**
 * What this distribution flavor ships of the §7 invocation layer and the Play
 * compliance surface. FULL: every tier, no output-flagging requirement.
 */
object InvocationFeatures {
    const val BUBBLE_AVAILABLE: Boolean = true
    const val SCREEN_CAPTURE_AVAILABLE: Boolean = true

    /** Play GenAI policy affordance — not required in the sideload build. */
    const val FLAG_OUTPUT_ENABLED: Boolean = false

    /** Where a user-sent output report goes; empty = generic share sheet. */
    const val FLAG_REPORT_EMAIL: String = ""

    fun startBubble(context: Context) = OverlayService.start(context)
    fun stopBubble(context: Context) = OverlayService.stop(context)
}
