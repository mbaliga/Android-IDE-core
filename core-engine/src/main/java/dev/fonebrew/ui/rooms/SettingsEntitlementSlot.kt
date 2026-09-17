package dev.fonebrew.ui.rooms

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * S-new seam: license/entitlement status (subscription state, "validate now", "deactivate this
 * device") is a paid-Studio-layer concern — the bare open core has no concept of a license and
 * never will (every feature this repo ships works with nothing installed). This is
 * [SettingsRoom]'s counterpart to [dev.fonebrew.ui.spatial.ProjectRoomSlot] /
 * [dev.fonebrew.ui.curation.RoundtableSlot] / [dev.fonebrew.ui.curation.VersionSuggestSlot]:
 * a static, install-only Compose slot the paid layer fills at its own startup, so **core never
 * references a concrete license implementation, and — critically — SettingsRoom.kt never needs
 * another edit to surface one.** (`extraGlobalRows` on [SettingsRoom] is a *parameter*-shaped
 * seam of the same intent, but it only works if whatever mounts [SettingsRoom] threads Studio
 * content through it — today's one call site, `SpatialRoot.kt`, doesn't, and being core-owned
 * itself it isn't a place the Studio layer can safely start passing rows without touching a
 * core file. This slot needs none of that: [content] is read directly inside `AboutSettings`.)
 *
 * The installed content owns its own validate-now / deactivate affordances and whatever license
 * manager backs them — this seam only carries the row itself, not a state contract, exactly
 * like [dev.fonebrew.ui.spatial.ProjectRoomSlot] carries a whole room without core knowing its
 * shape.
 *
 * Rendered inline at the end of `AboutSettings` (right after the version/build blurb).
 * [content] is `null` in the bare open core, so nothing renders there — not even a placeholder
 * row — leaving no visible trace a paid layer could ever exist.
 */
object SettingsEntitlementSlot {
    /** Installed by the Studio layer; null in the bare open core. */
    var content: (@Composable () -> Unit)? by mutableStateOf(null)
        private set

    fun install(content: @Composable () -> Unit) {
        this.content = content
    }

    val isInstalled: Boolean get() = content != null
}
