package dev.aarso.domain.tree

import dev.aarso.domain.MessageNode
import dev.aarso.domain.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationsTest {

    private fun node(
        id: String,
        parentId: String?,
        role: Role = Role.USER,
        content: String = id,
        createdAt: Long,
        modelId: String? = null,
    ) = MessageNode(
        id = id,
        parentId = parentId,
        role = role,
        content = content,
        modelId = modelId,
        createdAt = createdAt,
    )

    /**
     * Two conversations:
     *   r1 (user, t=0) ── a1 (model-x, t=1) ── a2 (user, t=2)
     *                  └─ b1 (model-y, t=5)        <- newest branch of r1
     *   r2 (user, t=3) ── c1 (model-x, t=4)
     */
    private fun forest() = MessageTree(
        listOf(
            node("r1", null, Role.USER, "tell me about the long monsoon season please today", 0),
            node("a1", "r1", Role.ASSISTANT, "…", 1, modelId = "model-x"),
            node("a2", "a1", Role.USER, "more", 2),
            node("b1", "r1", Role.ASSISTANT, "…", 5, modelId = "model-y"),
            node("r2", null, Role.USER, "short one", 3),
            node("c1", "r2", Role.ASSISTANT, "…", 4, modelId = "model-x"),
        ),
    )

    @Test
    fun oneSummaryPerRoot_newestActivityFirst() {
        val summaries = Conversations.summarize(forest())
        assertEquals(listOf("r1", "r2"), summaries.map { it.rootId })
        assertEquals(5, summaries[0].lastUpdatedAt)
        assertEquals(4, summaries[1].lastUpdatedAt)
    }

    @Test
    fun titleIsFirstUserWordsTruncated() {
        val summaries = Conversations.summarize(forest())
        // 9 words -> first 8 + ellipsis.
        assertEquals("tell me about the long monsoon season please…", summaries[0].title)
        assertEquals("short one", summaries[1].title)
    }

    @Test
    fun modelIdsAreDistinctInFirstUseOrder() {
        val summaries = Conversations.summarize(forest())
        assertEquals(listOf("model-x", "model-y"), summaries[0].modelIds)
        assertEquals(listOf("model-x"), summaries[1].modelIds)
    }

    @Test
    fun latestLeafFollowsNewestChildren() {
        val summaries = Conversations.summarize(forest())
        // r1's newest child is b1 (t=5), itself a leaf.
        assertEquals("b1", summaries[0].latestLeafId)
        assertEquals("c1", summaries[1].latestLeafId)
    }

    @Test
    fun nodeCountCoversTheWholeSubtree() {
        val summaries = Conversations.summarize(forest())
        assertEquals(4, summaries[0].nodeCount)
        assertEquals(2, summaries[1].nodeCount)
    }

    @Test
    fun rootOfResolvesAnyNodeToItsConversation() {
        val tree = forest()
        assertEquals("r1", Conversations.rootOf(tree, "a2"))
        assertEquals("r2", Conversations.rootOf(tree, "c1"))
        assertEquals("r1", Conversations.rootOf(tree, "r1"))
        assertNull(Conversations.rootOf(tree, "nope"))
    }

    @Test
    fun createdMillisIsEarliestTurnInSubtree() {
        val summaries = Conversations.summarize(forest())
        assertEquals(0, summaries[0].createdMillis) // r1 opened at t=0
        assertEquals(3, summaries[1].createdMillis) // r2 opened at t=3
    }

    @Test
    fun branchCountIsLeafTipCount() {
        val summaries = Conversations.summarize(forest())
        // r1 has two leaf tips (a2 and b1); r2 is linear (one tip, c1).
        assertEquals(2, summaries[0].branchCount)
        assertEquals(1, summaries[1].branchCount)
    }

    @Test
    fun hasTextWhenAnyNonImageTurnPresent() {
        val summaries = Conversations.summarize(forest())
        assertTrue(summaries[0].hasText)
        assertFalse(summaries[0].hasImage)
    }

    @Test
    fun imageOnlyChatHasImageNotText() {
        val tree = MessageTree(
            listOf(
                node("img", null, Role.ASSISTANT, "a sunset", 0)
                    .copy(metadata = mapOf(Conversations.IMAGE_KEY to "/p/sunset.png")),
            ),
        )
        val s = Conversations.summarize(tree).single()
        assertTrue(s.hasImage)
        assertFalse(s.hasText)
        assertEquals(1, s.branchCount)
    }

    @Test
    fun emptyTreeSummarizesToNothing() {
        assertEquals(emptyList<Conversations.Summary>(), Conversations.summarize(MessageTree(emptyList())))
    }

    @Test
    fun imageNodesFiltersAcrossConversationsNewestFirst() {
        val tree = MessageTree(
            listOf(
                node("r1", null, Role.USER, "draw a mirror", 0),
                MessageNode(
                    id = "i1", parentId = "r1", role = Role.ASSISTANT, content = "",
                    modelId = "sd:local", createdAt = 1,
                    metadata = mapOf(Conversations.IMAGE_KEY to "/img/a.png"),
                ),
                node("r2", null, Role.USER, "another", 2),
                MessageNode(
                    id = "i2", parentId = "r2", role = Role.ASSISTANT, content = "",
                    modelId = "sd:local", createdAt = 3,
                    metadata = mapOf(Conversations.IMAGE_KEY to "/img/b.png"),
                ),
                node("t1", "r2", Role.ASSISTANT, "text turn", 4),
            ),
        )
        assertEquals(listOf("i2", "i1"), Conversations.imageNodes(tree).map { it.id })
    }

    @Test
    fun imageNodesEmptyWhenNoImageTurns() {
        assertEquals(0, Conversations.imageNodes(forest()).size)
    }
}
