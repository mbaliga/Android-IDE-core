package dev.fonebrew.ui.spatial

import androidx.compose.runtime.snapshots.Snapshot
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ProjectRoomSlot.content] must be Compose snapshot state (§"observable seams",
 * CORE_PHASES.md P1) so a mid-session `install` recomposes [dev.fonebrew.ui.spatial.
 * SpatialRoot] without a restart. This asserts the actual mechanism recomposition relies
 * on — a snapshot-apply notification — fires on install, without needing a Compose UI
 * test harness (none is set up in this JVM-only gate).
 */
class ProjectRoomSlotTest {

    @Test
    fun `install writes through snapshot state`() {
        var wroteSnapshotState = false
        val snapshot = Snapshot.takeMutableSnapshot(null, { wroteSnapshotState = true })
        try {
            snapshot.enter { ProjectRoomSlot.install { } }
            snapshot.apply().check()
        } finally {
            snapshot.dispose()
        }
        assertTrue(
            "installing a Project room content should write through Compose snapshot state " +
                "(the mechanism recomposition subscribes to)",
            wroteSnapshotState,
        )
    }
}
