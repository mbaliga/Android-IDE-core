package dev.aarso.ui.spatial

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * S6 seam — the top "Project" room (Board / List / Waterfall + Incidents) is the paid
 * Studio project-management experience. The open core's spatial nav renders whatever an
 * above-core layer installs here; in the bare core it shows [ProjectRoomLocked], so core
 * never references the Studio `ProjectRoom` directly. The Studio layer installs its room
 * via [install] at startup (registration carves out with the Studio module later — see
 * `docs/EXTRACTION_PLAN.md` §3).
 */
object ProjectRoomSlot {
    /** Installed by the Studio layer; null in the bare open core. */
    var content: (@Composable (onClose: () -> Unit) -> Unit)? = null
        private set

    fun install(content: @Composable (onClose: () -> Unit) -> Unit) {
        this.content = content
    }
}

/** Shown in the bare open core when no Studio project room is installed. */
@Composable
fun ProjectRoomLocked(onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Project planning", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Tasks (Board / List / Waterfall) and Incidents are part of the Studio layer.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        TextButton(onClick = onClose, modifier = Modifier.padding(top = 16.dp)) { Text("Close") }
    }
}
