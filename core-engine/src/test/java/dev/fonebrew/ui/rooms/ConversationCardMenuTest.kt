package dev.fonebrew.ui.rooms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationCardMenuTest {

    @Test fun `default card - not starred, no lineage - Open, Star, Assign to project`() {
        val items = conversationCardMenuItems(bookmarked = false, hasLineageSource = false)
        assertEquals(listOf("open", "toggle_star", "assign_project"), items.map { it.id })
        assertEquals("Star", items[1].label)
    }

    @Test fun `starred card offers Remove star, not Star`() {
        val items = conversationCardMenuItems(bookmarked = true, hasLineageSource = false)
        assertEquals("Remove star", items.single { it.id == "toggle_star" }.label)
    }

    @Test fun `a card with a spawn-fork lineage gains Open source conversation, last`() {
        val items = conversationCardMenuItems(bookmarked = false, hasLineageSource = true)
        assertEquals(listOf("open", "toggle_star", "assign_project", "open_source"), items.map { it.id })
    }

    @Test fun `no destructive item - the card has no delete flow to call`() {
        assertTrue(conversationCardMenuItems(bookmarked = true, hasLineageSource = true).none { it.destructive })
    }

    @Test fun `every item is enabled - nothing on this card is ever disabled`() {
        assertFalse(conversationCardMenuItems(bookmarked = false, hasLineageSource = true).any { !it.enabled })
    }
}
