package dev.fonebrew.spike.fb4

import androidx.compose.ui.graphics.Color

/**
 * Copy of the dark-register tokens in hyle-design-system (theme/Color.kt). A copy, not a
 * dependency: the spike must not need a private submodule or :core-engine to build.
 * Meaning is never carried by hue alone (owner is red-green colourblind): every state below also
 * has a glyph and/or a text label in ConversationsRoom.
 */
object Palette {
    val Ink = Color(0xFF0E0F12)
    val Raised = Color(0xFF16181D)
    val Inset = Color(0xFF20242B)
    val Outline = Color(0xFF262A31)
    val TextHigh = Color(0xFFECEDEF)
    val TextMid = Color(0xFF9CA3AF)
    val Violet = Color(0xFF8E7BFF)
    val VioletDim = Color(0xFF2A2541)
    val Cyan = Color(0xFF08FED5)
    val Warning = Color(0xFFF78819)
}
