package dev.aarso.ui.theme

import androidx.compose.ui.graphics.Color

// Aeon token structure, remapped to Aarso's dark register (§12 of the spatial
// redesign brief). One accent: the Aarso violet — Aeon blue is retired, and
// green/amber exist only as fit-check / warning semantics, never as a second
// accent.

// Surfaces (Swiss-minimal: few steps; edges carried by type, not borders).
val Ink = Color(0xFF0E0F12)        // base surface
val Raised = Color(0xFF16181D)     // raised card
val Inset = Color(0xFF20242B)      // inputs, scroll track
val Outline = Color(0xFF262A31)    // divider/border
val Hairline = Color(0x24ECEDEF)   // crisp 1dp edges on OLED (TextHigh @ 14%)

// Text.
val TextHigh = Color(0xFFECEDEF)
val TextMid = Color(0xFF9CA3AF)
val TextDisabled = Color(0xFF4A4E57)

// The violet ramp, derived from the one Aarso violet (#8E7BFF = Primary 700).
val Violet = Color(0xFF8E7BFF)         // Primary 700 — default
val VioletHover = Color(0xFF9F8FFF)    // Primary 500 — lightened ~15%
val VioletPressed = Color(0xFF7262CC)  // Primary 900 — darkened ~20%
val VioletDim = Color(0xFF2A2541)      // Primary 200 — violet @20% over Ink (selection)
val OnViolet = Color(0xFF160F2E)

// Semantics (not accents).
val Success = Color(0xFF3AB700)        // fit: comfortable
val SuccessDeep = Color(0xFF009828)
val Warning = Color(0xFFF78819)        // fit: tight · watched markers
val WarningDim = Color(0xFF2E2310)
val ErrorRed = Color(0xFFEE322C)

// Light scheme (secondary; the app defaults to dark).
val LInk = Color(0xFFF9F9F9)
val LSurface1 = Color(0xFFFFFFFF)
val LSurface2 = Color(0xFFEEF0F4)
val LViolet = Color(0xFF5B45D6)
val LOutline = Color(0xFFD5D7DB)
val LHairline = Color(0x1F2F3238)   // near-black text @ ~12% — crisp 1dp edges on light
val LTextHigh = Color(0xFF1A1C1F)
val LTextMid = Color(0xFF5A5E66)
val LTextDisabled = Color(0xFFA9ADB5)
