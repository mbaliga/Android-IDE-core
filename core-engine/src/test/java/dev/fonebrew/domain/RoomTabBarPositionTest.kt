package dev.fonebrew.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RoomTabBarPositionTest {

    @Test fun `set stores a room's override`() {
        val next = RoomTabBarPosition.set(emptyMap(), "chat", "BOTTOM")
        assertEquals("BOTTOM", next["chat"])
    }

    @Test fun `set with a null position clears an existing override`() {
        val withOverride = mapOf("chat" to "BOTTOM", "tree" to "TOP")
        val next = RoomTabBarPosition.set(withOverride, "chat", null)
        assertFalse(next.containsKey("chat"))
        assertEquals("TOP", next["tree"]) // unrelated rooms untouched
    }

    @Test fun `set with a null position on a room with no override is a no-op`() {
        val next = RoomTabBarPosition.set(mapOf("tree" to "TOP"), "chat", null)
        assertEquals(mapOf("tree" to "TOP"), next)
    }

    @Test fun `effective prefers the room override over the universal default`() {
        val overrides = mapOf("project" to "BOTTOM")
        assertEquals("BOTTOM", RoomTabBarPosition.effective(overrides, "project", "TOP"))
    }

    @Test fun `effective falls back to the universal default when a room has no override`() {
        assertEquals("TOP", RoomTabBarPosition.effective(emptyMap(), "project", "TOP"))
    }

    @Test fun `a cleared override falls back to the universal default`() {
        val withOverride = mapOf("chat" to "BOTTOM")
        val cleared = RoomTabBarPosition.set(withOverride, "chat", null)
        assertEquals("TOP", RoomTabBarPosition.effective(cleared, "chat", "TOP"))
    }
}
