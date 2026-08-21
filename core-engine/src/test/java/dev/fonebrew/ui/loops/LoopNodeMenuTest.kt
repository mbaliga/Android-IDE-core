package dev.fonebrew.ui.loops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopNodeMenuTest {

    @Test fun `two items - Connect then Delete, Edit is deliberately absent`() {
        val items = loopNodeMenuItems()
        assertEquals(listOf("connect", "delete"), items.map { it.id })
        assertTrue(items.none { it.id == "edit" })
    }

    @Test fun `only Delete is destructive`() {
        val items = loopNodeMenuItems()
        assertEquals(false, items.single { it.id == "connect" }.destructive)
        assertEquals(true, items.single { it.id == "delete" }.destructive)
    }

    @Test fun `both items enabled`() {
        assertTrue(loopNodeMenuItems().all { it.enabled })
    }
}
