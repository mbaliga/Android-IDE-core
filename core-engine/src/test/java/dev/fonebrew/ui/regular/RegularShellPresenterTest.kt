package dev.fonebrew.ui.regular

import dev.fonebrew.ui.regular.RegularShellPresenter.BackAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegularShellPresenterTest {

    @Test fun `destinations are in the shipped tab order, Chat first`() {
        assertEquals(
            listOf(
                RegularDestination.CHAT,
                RegularDestination.CHATS,
                RegularDestination.PROJECTS,
                RegularDestination.DEVELOP,
                RegularDestination.SETTINGS,
            ),
            RegularShellPresenter.destinations,
        )
    }

    @Test fun `destinationForIndex maps every valid tab-bar index`() {
        RegularShellPresenter.destinations.forEachIndexed { index, destination ->
            assertEquals(destination, RegularShellPresenter.destinationForIndex(index))
        }
    }

    @Test fun `destinationForIndex is null for an out-of-range index`() {
        assertNull(RegularShellPresenter.destinationForIndex(-1))
        assertNull(RegularShellPresenter.destinationForIndex(5))
        assertNull(RegularShellPresenter.destinationForIndex(99))
    }

    @Test fun `indexForDestination is the inverse of destinationForIndex`() {
        RegularShellPresenter.destinations.forEachIndexed { index, destination ->
            assertEquals(index, RegularShellPresenter.indexForDestination(destination))
        }
    }

    @Test fun `backTargetsChat is false only for the Chat tab itself`() {
        assertFalse(RegularShellPresenter.backTargetsChat(RegularDestination.CHAT))
        assertTrue(RegularShellPresenter.backTargetsChat(RegularDestination.CHATS))
        assertTrue(RegularShellPresenter.backTargetsChat(RegularDestination.PROJECTS))
        assertTrue(RegularShellPresenter.backTargetsChat(RegularDestination.DEVELOP))
        assertTrue(RegularShellPresenter.backTargetsChat(RegularDestination.SETTINGS))
    }

    @Test fun `back closes the topmost layer first - search, then overlays, then tab reset, then exit`() {
        // Search wins over everything beneath it, whatever tab or overlay is also up.
        assertEquals(BackAction.CLOSE_SEARCH, RegularShellPresenter.backAction(true, true, RegularDestination.SETTINGS))
        assertEquals(BackAction.CLOSE_SEARCH, RegularShellPresenter.backAction(true, false, RegularDestination.CHAT))
        // An overlay (Tree/Loops) closes before the tab resets.
        assertEquals(BackAction.CLOSE_OVERLAY, RegularShellPresenter.backAction(false, true, RegularDestination.DEVELOP))
        // Then a non-home tab goes back to Chat.
        assertEquals(BackAction.GO_CHAT, RegularShellPresenter.backAction(false, false, RegularDestination.CHATS))
        // Only the home tab falls through to the system default.
        assertEquals(BackAction.FALL_THROUGH, RegularShellPresenter.backAction(false, false, RegularDestination.CHAT))
    }

    @Test fun `exactly one back action is ever active for any state`() {
        for (search in listOf(false, true)) for (overlay in listOf(false, true)) for (d in RegularDestination.entries) {
            val action = RegularShellPresenter.backAction(search, overlay, d)
            val active = RegularShellPresenter.BackAction.entries.count { it == action }
            assertEquals(1, active)
        }
    }

    @Test fun `search hits land where the Spatial shell sends them`() {
        val p = RegularShellPresenter
        assertEquals(
            RegularShellPresenter.RegularLanding.Tab(RegularDestination.CHAT),
            p.landingFor(RegularShellPresenter.SearchLanding.CONVERSATION),
        )
        assertEquals(
            RegularShellPresenter.RegularLanding.Tab(RegularDestination.PROJECTS),
            p.landingFor(RegularShellPresenter.SearchLanding.TASK),
        )
        // Loops is not a Regular tab: it is a full-screen overlay, so there is no tab to switch to.
        assertEquals(RegularShellPresenter.RegularLanding.LoopsOverlay, p.landingFor(RegularShellPresenter.SearchLanding.LOOP))
    }
}
