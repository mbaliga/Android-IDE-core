package dev.aarso.domain.library

import dev.aarso.domain.tree.Conversations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationProjectionTest {

    private fun summary(
        rootId: String,
        title: String = rootId,
        lastUpdatedAt: Long = 10,
        createdMillis: Long = 1,
        branchCount: Int = 1,
        hasText: Boolean = true,
        hasImage: Boolean = false,
    ) = Conversations.Summary(
        rootId = rootId,
        title = title,
        modelIds = emptyList(),
        lastUpdatedAt = lastUpdatedAt,
        nodeCount = 1,
        latestLeafId = rootId,
        hasImage = hasImage,
        createdMillis = createdMillis,
        branchCount = branchCount,
        hasText = hasText,
    )

    @Test
    fun carriesTreeFactsThrough() {
        val s = summary("c1", title = "Hello", lastUpdatedAt = 99, createdMillis = 7, branchCount = 3)
        val out = ConversationProjection.from(s, starred = false, projectId = null, useCount = 0)
        assertEquals("c1", out.id)
        assertEquals("Hello", out.title)
        assertEquals(99, out.lastActivityMillis)
        assertEquals(7, out.createdMillis)
        assertEquals(3, out.branchCount)
    }

    @Test
    fun sessionFactsAreApplied() {
        val s = summary("c1")
        val out = ConversationProjection.from(s, starred = true, projectId = "Monsoon", useCount = 4)
        assertTrue(out.starred)
        assertEquals("Monsoon", out.projectId)
        assertEquals(4, out.useCount)
    }

    @Test
    fun useCountNeverNegative() {
        val out = ConversationProjection.from(summary("c1"), starred = false, projectId = null, useCount = -3)
        assertEquals(0, out.useCount)
    }

    @Test
    fun kindsUseContainsSemantics() {
        val mixed = ConversationProjection.from(
            summary("c1", hasText = true, hasImage = true), false, null, 0,
        )
        assertEquals(setOf(ConvKind.TEXT, ConvKind.IMAGE), mixed.kinds)

        val imageOnly = ConversationProjection.from(
            summary("c2", hasText = false, hasImage = true), false, null, 0,
        )
        assertEquals(setOf(ConvKind.IMAGE), imageOnly.kinds)
        assertFalse(ConvKind.TEXT in imageOnly.kinds)
    }

    @Test
    fun emptyChatStillListsUnderText() {
        val out = ConversationProjection.from(
            summary("c1", hasText = false, hasImage = false), false, null, 0,
        )
        assertEquals(setOf(ConvKind.TEXT), out.kinds)
    }

    @Test
    fun fromAllResolvesEachRowsSessionFacts() {
        val summaries = listOf(summary("a"), summary("b"), summary("c"))
        val out = ConversationProjection.fromAll(
            summaries = summaries,
            starredRoots = setOf("b"),
            projects = mapOf("a" to "Proj-A"),
            opens = mapOf("a" to 2, "c" to 5),
        )
        assertEquals(listOf("a", "b", "c"), out.map { it.id }) // order preserved
        assertEquals("Proj-A", out[0].projectId)
        assertEquals(2, out[0].useCount)
        assertTrue(out[1].starred)
        assertEquals(0, out[1].useCount) // b never opened -> honest 0
        assertEquals(5, out[2].useCount)
    }
}
