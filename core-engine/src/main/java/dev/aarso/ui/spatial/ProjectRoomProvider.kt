package dev.aarso.ui.spatial

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * S6 seam — the top "Project" room (Board / List / Waterfall + Incidents) is the paid
 * Studio project-management experience. The open core's spatial nav renders whatever an
 * above-core layer installs here; in the bare core it falls back to
 * [dev.aarso.ui.rooms.ProductRoomFree] (the free To-do floor), so core never references
 * the Studio `ProjectRoom` directly. The Studio layer installs its room via [install] at
 * startup (registration carves out with the Studio module later — see
 * `docs/EXTRACTION_PLAN.md` §3).
 *
 * [content] is Compose snapshot state (not a plain `var`) so a mid-session install —
 * Studio's license activation unlocking live, brief §4.3/§7.4 — recomposes [SpatialRoot]
 * in place instead of needing a restart.
 */
object ProjectRoomSlot {
    /** Installed by the Studio layer; null in the bare open core. */
    var content: (@Composable (onClose: () -> Unit) -> Unit)? by mutableStateOf(null)
        private set

    fun install(content: @Composable (onClose: () -> Unit) -> Unit) {
        this.content = content
    }
}
