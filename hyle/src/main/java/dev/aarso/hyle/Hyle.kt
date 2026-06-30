package dev.aarso.hyle

/**
 * Hyle — the material design system (docs/design/material-language.md), staged as
 * its own module per the build plan.
 *
 * The *semantic* (which surfaces are local vs from-elsewhere) lives in the app
 * (`dev.aarso.domain.material`); this module owns the *render side* — the tokens and
 * the contract the renderer obeys. It will grow the Compose `Modifier`s and AGSL
 * shaders next; those are owner-verified on device, so this first cut is deliberately
 * pure data + contract (JVM-tested), no Compose, no colour committed.
 *
 * Hue note: the radiant glow's colour is an **open owner decision**. Both candidates
 * ship as tokens until one is chosen; nothing here hard-codes "the" hue.
 */

/** ARGB colour as a plain Long (0xAARRGGBB), so this module stays Compose-free for now. */
typealias Argb = Long

/**
 * How a surface should be rendered — the hue-independent contract the app's
 * `MaterialClass` is mapped onto (reflective vs radiant).
 */
sealed interface Finish {
    /** Reflective: of-here, inert until touched. Specular only — no emission. */
    data object Reflective : Finish

    /** Radiant: watched / from-elsewhere. Emits its own light at [tint], breathing per [pulse]. */
    data class Radiant(val tint: Argb, val pulse: Pulse = Pulse.WATCHED) : Finish
}

/**
 * "Heartbeat, not weather" — the motion rule. Ambient emission breathes on a slow,
 * regular, low-amplitude cycle that *means* "alive / connected / watched," never
 * aperiodic churn. These are timings only; the shader reads them.
 */
data class Pulse(
    val periodMs: Int,
    val minAlphaPct: Int,
    val maxAlphaPct: Int,
) {
    init {
        require(periodMs > 0) { "periodMs must be > 0" }
        require(minAlphaPct in 0..maxAlphaPct && maxAlphaPct <= 100) { "alpha must satisfy 0 <= min <= max <= 100" }
    }

    companion object {
        /** A calm ~2.4 s breath, like a connected-status light (the BlackBerry-LED model). */
        val WATCHED = Pulse(periodMs = 2400, minAlphaPct = 42, maxAlphaPct = 78)

        /** Fully still — the default when there is nothing to say. */
        val STILL = Pulse(periodMs = 1, minAlphaPct = 100, maxAlphaPct = 100)
    }
}

/**
 * The two open candidates for the radiant hue (see the design doc's Open questions).
 * Neither is "chosen": an uncanny radium yellow-green that reads *other*, or a cold
 * cyan that reads *clinical / monitored*. Deliberately NOT a friendly success-green,
 * whose "all good" valence is wrong for a watched object.
 */
object RadiantHues {
    /** Pale, uncanny yellow-green — the aged radium-watch-dial glow (tunable on device). */
    const val RADIUM: Argb = 0xFFC7EF9E

    /** Cold, clinical cyan. */
    const val COLD_CYAN: Argb = 0xFF35E0FF

    val candidates: List<Argb> = listOf(RADIUM, COLD_CYAN)
}
