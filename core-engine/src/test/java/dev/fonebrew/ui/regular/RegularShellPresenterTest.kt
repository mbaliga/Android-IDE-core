package dev.fonebrew.ui.regular

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
}
