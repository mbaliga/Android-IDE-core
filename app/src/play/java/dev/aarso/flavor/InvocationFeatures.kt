package dev.aarso.flavor

import android.content.Context

/**
 * What this distribution flavor ships of the §7 invocation layer and the Play
 * compliance surface. PLAY: share-sheet, text-selection, and assist-gesture
 * stay; the overlay bubble and screen-capture OCR (SYSTEM_ALERT_WINDOW,
 * specialUse FGS, MediaProjection) are sideload-only. Output flagging is on
 * (Play GenAI policy: in-app reporting).
 */
object InvocationFeatures {
    const val BUBBLE_AVAILABLE: Boolean = false
    const val SCREEN_CAPTURE_AVAILABLE: Boolean = false

    const val FLAG_OUTPUT_ENABLED: Boolean = true

    /**
     * Where a user-sent output report goes; empty = generic share sheet.
     * OWNER INPUT PENDING: set the reporting address before the Play listing
     * goes live (docs/play/genai-declaration.md).
     */
    const val FLAG_REPORT_EMAIL: String = ""

    fun startBubble(@Suppress("UNUSED_PARAMETER") context: Context) = Unit
    fun stopBubble(@Suppress("UNUSED_PARAMETER") context: Context) = Unit
}
