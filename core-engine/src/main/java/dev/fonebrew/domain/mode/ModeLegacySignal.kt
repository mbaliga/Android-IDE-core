package dev.fonebrew.domain.mode

/**
 * Pure resolution of the "legacy signal" [com.asystemofcells.interactionmode.ModeDefaults.defaultFor]
 * takes: whether this install predates the Regular/asoc bifurcation (2026-09-15 ruling), so an
 * install already living in the rooms model isn't silently dropped into an unfamiliar layout on
 * its next launch. `com.asystemofcells:interaction-mode` deliberately leaves "predates the bifurcation"
 * entirely to the app (see that module's own [com.asystemofcells.interactionmode.PrefsInteractionModeStore]
 * KDoc) — Fonebrew's answer: an install that had already completed onboarding, or already seen
 * the one-shot [dev.fonebrew.ui.spatial.SpatialMapOverlay] teaching, before the bifurcation
 * shipped was necessarily already living in the (then-only) spatial-rooms shell. Either fact
 * alone is sufficient; a fresh install with neither defaults to REGULAR via
 * [com.asystemofcells.interactionmode.ModeDefaults] downstream of this.
 */
object ModeLegacySignal {

    fun resolve(onboardingDone: Boolean, spatialMapSeen: Boolean): Boolean =
        onboardingDone || spatialMapSeen
}
