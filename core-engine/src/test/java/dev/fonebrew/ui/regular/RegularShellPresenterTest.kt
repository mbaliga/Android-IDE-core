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

    // ── Global search routing (lane C1) ──────────────────────────────────────────────────────

    @Test fun `a conversation hit lands on Chat carrying the query to the find bar`() {
        val l = RegularShellPresenter.landingForConversation("kv cache")
        assertEquals(RegularDestination.CHAT, l.destination)
        assertEquals("kv cache", l.pendingFind)
        assertFalse(l.showLoops)
    }

    @Test fun `a blank query carries no find text`() {
        assertNull(RegularShellPresenter.landingForConversation("  ").pendingFind)
    }

    @Test fun `a loop hit opens the Loops overlay with the loop id`() {
        val l = RegularShellPresenter.landingForLoop("loop-7")
        assertTrue(l.showLoops)
        assertEquals("loop-7", l.pendingLoopId)
    }

    @Test fun `a task hit lands on Projects with the task id`() {
        val l = RegularShellPresenter.landingForTask("task-3")
        assertEquals(RegularDestination.PROJECTS, l.destination)
        assertEquals("task-3", l.pendingTaskId)
        assertFalse(l.showLoops)
    }

    /** The audit defect was a rendered search affordance wired to ChatsRoom/ChatScreen's
     *  empty-lambda default. Pin that the shell passes real handlers and mounts the overlay. */
    @Test fun `RegularShell wires every search entry and mounts the shared overlay`() {
        val src = java.io.File(
            "src/main/java/dev/fonebrew/ui/regular/RegularShell.kt",
        ).readText()
        assertTrue(src.contains("onOpenSearch = { searchOpen = true }"))
        assertTrue(src.contains("onSearchAllChats = { query ->"))
        assertTrue(src.contains("SearchOverlay("))
        assertTrue(!src.contains("onOpenSearch = {}") && !src.contains("onSearchAllChats = {}"))
    }
}
