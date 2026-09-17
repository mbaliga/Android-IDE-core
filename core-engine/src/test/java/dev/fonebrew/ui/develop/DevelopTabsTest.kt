package dev.fonebrew.ui.develop

import androidx.compose.runtime.snapshots.Snapshot
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same observable-seam contract as [dev.fonebrew.ui.spatial.ProjectRoomSlotTest], for the
 *  S2 Develop-tabs seam. */
class DevelopTabsTest {

    @Test
    fun `install writes through snapshot state`() {
        var wroteSnapshotState = false
        val snapshot = Snapshot.takeMutableSnapshot(null, { wroteSnapshotState = true })
        try {
            snapshot.enter { DevelopTabs.install { emptyList() } }
            snapshot.apply().check()
        } finally {
            snapshot.dispose()
        }
        assertTrue(
            "installing a Develop tabs provider should write through Compose snapshot state " +
                "(the mechanism recomposition subscribes to)",
            wroteSnapshotState,
        )
    }
}
