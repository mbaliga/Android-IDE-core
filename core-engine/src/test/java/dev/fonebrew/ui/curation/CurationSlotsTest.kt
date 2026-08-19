package dev.fonebrew.ui.curation

import androidx.compose.runtime.snapshots.Snapshot
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Same rationale as [dev.fonebrew.ui.spatial.ProjectRoomSlotTest]: asserts the recomposition
 * mechanism (a snapshot-apply notification) fires on install, without needing a Compose UI test
 * harness.
 */
class CurationSlotsTest {

    @Test
    fun `installing Roundtable content writes through snapshot state`() {
        var wroteSnapshotState = false
        val snapshot = Snapshot.takeMutableSnapshot(null, { wroteSnapshotState = true })
        try {
            snapshot.enter { RoundtableSlot.install { _, _ -> } }
            snapshot.apply().check()
        } finally {
            snapshot.dispose()
        }
        assertTrue(
            "installing Roundtable content should write through Compose snapshot state",
            wroteSnapshotState,
        )
    }

    @Test
    fun `isInstalled reflects whether content has been installed`() {
        val snapshot = Snapshot.takeMutableSnapshot()
        try {
            snapshot.enter { VersionSuggestSlot.install { } }
            snapshot.apply().check()
        } finally {
            snapshot.dispose()
        }
        assertTrue(VersionSuggestSlot.isInstalled)
    }
}
