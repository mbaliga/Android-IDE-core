package dev.fonebrew.domain.mode

import dev.aarso.interactionmode.InteractionMode

/**
 * Mode-aware default for the three message-bubble gesture toggles (THREAD_TOPOLOGY_PLAN.md WP4
 * / Settings → General → Gestures: verdict drag, quote/reply drag, radial fan). Per this lane's
 * brief (bifurcation wave 1, lane R): a gesture toggle the user has never explicitly touched
 * defaults OFF when the interaction mode is REGULAR — the tappable parity surfaces (chevrons,
 * the long-press sheet) are primary there — and ON in ASOC, the shipped gesture-first default,
 * unchanged. An explicit per-gesture choice always wins over this default, in either mode; see
 * [resolve]. Mirrors the "never override an explicit choice" shape
 * [dev.aarso.interactionmode.ModeDefaults] already uses at the mode-selection layer itself.
 *
 * See [dev.fonebrew.data.SessionStore] for where the three gesture toggles' storage and
 * `hasExplicitChoice` (`SharedPreferences.contains`) actually live.
 */
object GestureModeDefaults {

    /** The toggle's value before any explicit choice has been made. */
    fun defaultFor(mode: InteractionMode): Boolean = mode == InteractionMode.ASOC

    /** The effective toggle value: [explicitValue] when [hasExplicitChoice], else [defaultFor]. */
    fun resolve(hasExplicitChoice: Boolean, explicitValue: Boolean, mode: InteractionMode): Boolean =
        if (hasExplicitChoice) explicitValue else defaultFor(mode)
}
