package dev.fonebrew.watch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class WatchPaletteTest {

    private fun hyleColorFile(): File {
        val candidates = listOf(
            File("../hyle-design-system/hyle/src/main/java/dev/aarso/hyle/theme/Color.kt"),
            File("hyle-design-system/hyle/src/main/java/dev/aarso/hyle/theme/Color.kt"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("Hyle's Color.kt not found — initialise the submodule (git submodule update --init --recursive); tried ${candidates.map { it.absolutePath }}")
    }

    /** `val Name = Color(0xAARRGGBB)` lines of Hyle's palette → name to ARGB. */
    private fun hyleTokens(): Map<String, Long> =
        Regex("""^val (\w+) = Color\(0x([0-9A-Fa-f]{8})\)""", RegexOption.MULTILINE)
            .findAll(hyleColorFile().readText())
            .associate { it.groupValues[1] to it.groupValues[2].toLong(16) }

    @Test
    fun everyMirroredToken_equalsHylesOwnValue_soDriftOnEitherSideFailsTheGate() {
        val hyle = hyleTokens()
        val mirror = mapOf(
            "Ink" to WatchPalette.INK, "Raised" to WatchPalette.RAISED, "Inset" to WatchPalette.INSET, "Outline" to WatchPalette.OUTLINE,
            "TextHigh" to WatchPalette.PAPER, "TextMid" to WatchPalette.PAPER_MID, "TextDisabled" to WatchPalette.PAPER_FAINT,
            "Violet" to WatchPalette.BREW, "VioletHover" to WatchPalette.BREW_BRIGHT, "VioletPressed" to WatchPalette.BREW_DEEP,
            "VioletDim" to WatchPalette.BREW_DIM, "OnViolet" to WatchPalette.ON_BREW,
        )
        for ((hyleName, ours) in mirror) {
            val theirs = hyle[hyleName] ?: throw AssertionError("Hyle no longer defines '$hyleName' — the mirror in WatchPalette.kt is orphaned")
            assertEquals("WatchPalette drifted from Hyle's $hyleName", theirs, ours)
        }
        assertEquals("the mirror covers every WatchPalette token", WatchPalette.ALL.size, mirror.size)
    }

    @Test
    fun hylesSemanticColours_areNotMirrored() {
        // No success-green / warning-amber / error-red on the wrist (colour-blind rule).
        val src = File("src/main/kotlin/dev/fonebrew/watch/core/WatchPalette.kt").takeIf { it.isFile }
            ?: File("watchbrew/src/main/kotlin/dev/fonebrew/watch/core/WatchPalette.kt")
        val code = src.readLines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("/*") || it.trim().startsWith("//") }.joinToString("\n")
        for (forbidden in listOf("Success", "Warning", "ErrorRed", "ERROR", "GREEN", "RED", "AMBER")) {
            assertFalse("WatchPalette code mentions $forbidden", code.contains(forbidden))
        }
    }

    // ---- colour-blind rule: no palette entry sits in a red or green hue band ----

    private fun hsv(argb: Long): Triple<Double, Double, Double> {
        val r = ((argb shr 16) and 0xFF) / 255.0
        val g = ((argb shr 8) and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val h = when {
            d == 0.0 -> 0.0
            mx == r -> 60 * (((g - b) / d) % 6)
            mx == g -> 60 * ((b - r) / d + 2)
            else -> 60 * ((r - g) / d + 4)
        }.let { if (it < 0) it + 360 else it }
        return Triple(h, if (mx == 0.0) 0.0 else d / mx, mx)
    }

    @Test
    fun noPaletteEntryIsARedOrGreen() {
        for ((name, argb) in WatchPalette.ALL) {
            val (h, s, _) = hsv(argb)
            if (s < 0.25) continue // near-neutral greys carry no hue to confuse
            val redBand = h >= 335 || h <= 25
            val greenBand = h in 70.0..170.0
            val amberBand = h in 25.0..70.0
            assertFalse("$name (hue ${h.toInt()}) is in a red/green/amber band", redBand || greenBand || amberBand)
        }
    }

    // ---- legibility ----

    private fun luminance(argb: Long): Double {
        fun channel(v: Long): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel((argb shr 16) and 0xFF) + 0.7152 * channel((argb shr 8) and 0xFF) + 0.0722 * channel(argb and 0xFF)
    }

    private fun contrast(a: Long, b: Long): Double {
        val (hi, lo) = luminance(a).let { la -> luminance(b).let { lb -> max(la, lb) to min(la, lb) } }
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun textOnEachSurface_meetsContrast() {
        assertTrue(contrast(WatchPalette.PAPER, WatchPalette.INK) >= 7.0)
        assertTrue(contrast(WatchPalette.PAPER, WatchPalette.RAISED) >= 7.0)
        assertTrue(contrast(WatchPalette.PAPER_MID, WatchPalette.INK) >= 4.5)
        assertTrue(contrast(WatchPalette.PAPER_MID, WatchPalette.RAISED) >= 4.5)
        assertTrue("accent on ink", contrast(WatchPalette.BREW, WatchPalette.INK) >= 4.5)
        assertTrue("ink on accent", contrast(WatchPalette.ON_BREW, WatchPalette.BREW) >= 4.5)
    }

    @Test
    fun everyColourIsOpaque() {
        for ((name, argb) in WatchPalette.ALL) assertEquals("$name alpha", 0xFFL, (argb shr 24) and 0xFF)
    }
}
