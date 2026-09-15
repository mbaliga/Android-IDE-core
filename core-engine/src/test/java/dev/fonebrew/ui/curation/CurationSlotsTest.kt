package dev.fonebrew.ui.curation

import androidx.compose.runtime.snapshots.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Same rationale as [dev.fonebrew.ui.spatial.ProjectRoomSlotTest]: asserts the recomposition
 * mechanism (a snapshot-apply notification) fires on install, without needing a Compose UI test
 * harness.
 *
 * **Deliberately not covered here:** the "content absent -> row absent" half of ChatScreen's
 * `TurnActionsSheet` gating (`if (RoundtableSlot.isInstalled)` / `VersionSuggestSlot.content?.let`).
 * Both slots are process-wide singletons with an intentionally *install-only* mutator (no reset —
 * see their own KDoc: a mid-session entitlement unlock must recompose in place, never revert), so
 * asserting an "isInstalled == false" starting state here would be coupled to whichever other test
 * in this same JVM happened to run first — order this file doesn't control and the slot API gives
 * no way to reset for. The "content present -> included" half is real and exercised below (and by
 * the ChatScreen call site's own honest KDoc); the row's actual on/off appearance is Compose
 * rendering with no test harness in this module, so it stays owner-verified per this module's
 * usual rule for render behaviour.
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

    /**
     * The one piece of "content present" shape that *is* fully JVM-testable without touching the
     * live singleton: [RoundtableRequest] is the whole context ChatScreen's "Re-run with…" row
     * hands to whatever Studio installed. `candidateModelIds` defaulting to empty is load-bearing
     * — the type's own KDoc says empty means "let the installed layer pick its own default set,"
     * and the row relies on that default rather than guessing a model set core has no basis for.
     */
    @Test
    fun `RoundtableRequest defaults to no candidate models`() {
        val request = RoundtableRequest(originMsgId = "msg-1")
        assertEquals("msg-1", request.originMsgId)
        assertTrue(request.candidateModelIds.isEmpty())
    }
}
