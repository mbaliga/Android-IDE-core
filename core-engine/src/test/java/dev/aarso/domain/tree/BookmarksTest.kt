package dev.aarso.domain.tree

import org.junit.Assert.assertEquals
import org.junit.Test

class BookmarksTest {

    @Test fun `toggle adds then removes`() {
        val empty = emptySet<String>()
        val one = Bookmarks.toggle(empty, "a")
        assertEquals(setOf("a"), one)
        val two = Bookmarks.toggle(one, "b")
        assertEquals(setOf("a", "b"), two)
        val back = Bookmarks.toggle(two, "a")
        assertEquals(setOf("b"), back)
    }

    @Test fun `filter keeps only bookmarked roots, order preserved`() {
        val s = { id: String -> Conversations.Summary(id, id, emptyList(), 0L, 1, id) }
        val all = listOf(s("a"), s("b"), s("c"))
        assertEquals(listOf("a", "c"), Bookmarks.filter(all, setOf("a", "c")).map { it.rootId })
        assertEquals(emptyList<String>(), Bookmarks.filter(all, emptySet()).map { it.rootId })
    }
}
