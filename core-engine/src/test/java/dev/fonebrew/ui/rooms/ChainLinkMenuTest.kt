package dev.fonebrew.ui.rooms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainLinkMenuTest {

    @Test fun `chain link row menu is Open alone - no other row action exists`() {
        val items = chainLinkMenuItems()
        assertEquals(1, items.size)
        assertEquals("open", items.single().id)
        assertEquals("Open", items.single().label)
    }

    @Test fun `not destructive, not disabled`() {
        val item = chainLinkMenuItems().single()
        assertTrue(item.enabled)
        assertTrue(!item.destructive)
    }
}
