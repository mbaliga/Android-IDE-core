package dev.fonebrew.widget

/**
 * What a home-screen widget may print about the user's own work (docs/design/widgets-integration.md).
 * A home screen is glanced at by anyone holding the phone, so the two fields that are the user's own
 * words are **off until the user turns them on, per widget**: task titles (Next up) and loop and step
 * names (Loop runs). Everything else a widget shows (counts, due words, states, model names, download
 * file names, provenance) is not user-authored content.
 *
 * Pure data; persisted by [dev.fonebrew.data.WidgetPrivacyStore]. [OFF] is also what an unreadable
 * store resolves to: when in doubt, private.
 */
data class WidgetPrivacy(
    val showTaskTitles: Boolean = false,
    val showLoopNames: Boolean = false,
) {
    companion object {
        val OFF = WidgetPrivacy()
        val ALL_SHOWN = WidgetPrivacy(showTaskTitles = true, showLoopNames = true)
    }
}
