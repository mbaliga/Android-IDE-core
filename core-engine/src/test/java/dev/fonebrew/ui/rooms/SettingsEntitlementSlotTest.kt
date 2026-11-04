package dev.fonebrew.ui.rooms

import androidx.compose.runtime.snapshots.Snapshot
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Same rationale as [dev.fonebrew.ui.spatial.ProjectRoomSlotTest] /
 * [dev.fonebrew.ui.curation.CurationSlotsTest]: asserts the recomposition mechanism (a
 * snapshot-apply notification) fires on install, without needing a Compose UI test harness.
 */
class SettingsEntitlementSlotTest {

    @Test
    fun `installing entitlement content writes through snapshot state`() {
        var wroteSnapshotState = false
        val snapshot = Snapshot.takeMutableSnapshot(null, { wroteSnapshotState = true })
        try {
            snapshot.enter { SettingsEntitlementSlot.install { } }
            snapshot.apply().check()
        } finally {
            snapshot.dispose()
        }
        assertTrue(
            "installing entitlement content should write through Compose snapshot state",
            wroteSnapshotState,
        )
    }

    @Test
    fun `isInstalled reflects whether content has been installed`() {
        val snapshot = Snapshot.takeMutableSnapshot()
        try {
            snapshot.enter { SettingsEntitlementSlot.install { } }
            snapshot.apply().check()
        } finally {
            snapshot.dispose()
        }
        assertTrue(SettingsEntitlementSlot.isInstalled)
    }
}
