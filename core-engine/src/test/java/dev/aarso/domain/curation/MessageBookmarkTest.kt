package dev.aarso.domain.curation

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageBookmarkTest {

    private fun bm(id: String, msgId: String, at: Long, blockIndex: Int? = null) = MessageBookmark(
        id = id,
        ref = MessageRef(msgId, blockIndex),
        kind = BookmarkKind.REFERENCE,
        at = at,
    )

    @Test fun `forMessage returns only bookmarks pinned to that message, newest first`() {
        val bookmarks = listOf(
            bm("b1", "m1", at = 1L),
            bm("b2", "m2", at = 2L),
            bm("b3", "m1", at = 3L),
        )
        assertEquals(listOf("b3", "b1"), MessageBookmarks.forMessage(bookmarks, "m1").map { it.id })
    }

    @Test fun `a block-level bookmark is still found by its parent message id`() {
        val bookmarks = listOf(bm("b1", "m1", at = 1L, blockIndex = 2))
        assertEquals(listOf("b1"), MessageBookmarks.forMessage(bookmarks, "m1").map { it.id })
    }

    @Test fun `forMessage on an unbookmarked message returns empty`() {
        val bookmarks = listOf(bm("b1", "m1", at = 1L))
        assertEquals(emptyList<MessageBookmark>(), MessageBookmarks.forMessage(bookmarks, "m2"))
    }

    @Test fun `mergeAddWins unions two disjoint sets`() {
        val a = listOf(bm("b1", "m1", at = 1L))
        val b = listOf(bm("b2", "m2", at = 2L))
        val merged = MessageBookmarks.mergeAddWins(a, b)
        assertEquals(setOf("b1", "b2"), merged.map { it.id }.toSet())
    }

    @Test fun `mergeAddWins on the same id keeps the newer entry`() {
        val a = listOf(bm("b1", "m1", at = 1L).copy(note = "old"))
        val b = listOf(bm("b1", "m1", at = 5L).copy(note = "new"))
        val merged = MessageBookmarks.mergeAddWins(a, b)
        assertEquals(1, merged.size)
        assertEquals("new", merged.single().note)
    }

    @Test fun `mergeAddWins never drops an entry`() {
        val a = listOf(bm("b1", "m1", at = 1L), bm("b2", "m1", at = 2L))
        val b = listOf(bm("b3", "m2", at = 3L))
        assertEquals(3, MessageBookmarks.mergeAddWins(a, b).size)
    }
}
