package dev.aarso.domain.reliability

import dev.aarso.domain.MessageNode
import dev.aarso.domain.Role
import dev.aarso.domain.tree.MessageTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [PartialReplyRecovery.plan] — pure function: given a tree + a set of
 * orphan checkpoint files, which recovery actions result (daily-driver.md W3). Exercises the
 * "which nodes get inserted, which files get 'deleted'" contract directly: [plan] never touches
 * I/O itself, so "deleted" here just means every [PartialReplyRecovery.Action] in the result
 * names its checkpoint — the caller (ChatViewModel) is what actually deletes the file, always,
 * for every action.
 */
class PartialReplyRecoveryTest {

    private val t0 = 1_700_000_000_000L

    private fun node(id: String, parentId: String?, role: Role, createdAt: Long = t0) =
        MessageNode(id = id, parentId = parentId, role = role, content = "x", createdAt = createdAt)

    @Test
    fun `an unanswered user node with checkpoint text is recovered as a partial stopped assistant node`() {
        val user = node("u1", null, Role.USER)
        val tree = MessageTree(listOf(user))
        val checkpoint = PartialReply(userNodeId = "u1", modelId = "local:qwen", text = "partial reply text", updatedAt = t0)

        val actions = PartialReplyRecovery.plan(tree, listOf(checkpoint), now = t0 + 1_000) { "recovered-1" }

        assertEquals(1, actions.size)
        val action = actions.single()
        assertEquals(checkpoint, action.checkpoint)
        val inserted = action.insert
        assertNotNull(inserted)
        inserted!!
        assertEquals("recovered-1", inserted.id)
        assertEquals("u1", inserted.parentId)
        assertEquals(Role.ASSISTANT, inserted.role)
        assertEquals("partial reply text", inserted.content)
        assertEquals("local:qwen", inserted.modelId)
        assertEquals(t0 + 1_000, inserted.createdAt)
        assertEquals("true", inserted.metadata["partial"])
        // Reuses the codebase's existing truncated-reply styling convention.
        assertEquals("true", inserted.metadata["stopped"])
    }

    @Test
    fun `a checkpoint whose user turn already has a reply is discarded, not double-inserted`() {
        val user = node("u1", null, Role.USER)
        val existingReply = node("a1", "u1", Role.ASSISTANT, createdAt = t0 + 1)
        val tree = MessageTree(listOf(user, existingReply))
        val checkpoint = PartialReply(userNodeId = "u1", modelId = "m", text = "stale text", updatedAt = t0)

        val actions = PartialReplyRecovery.plan(tree, listOf(checkpoint), now = t0 + 5_000)

        assertEquals(1, actions.size)
        assertNull("stale checkpoint must not insert a second reply", actions.single().insert)
        // The checkpoint is still named in the action so the caller deletes the now-stale file.
        assertEquals(checkpoint, actions.single().checkpoint)
    }

    @Test
    fun `a checkpoint whose user node does not exist in the tree is discarded`() {
        val tree = MessageTree(emptyList())
        val checkpoint = PartialReply(userNodeId = "ghost", modelId = "m", text = "orphaned", updatedAt = t0)

        val actions = PartialReplyRecovery.plan(tree, listOf(checkpoint), now = t0)

        assertEquals(1, actions.size)
        assertNull(actions.single().insert)
    }

    @Test
    fun `a checkpoint with blank text is discarded even though its turn is unanswered`() {
        val user = node("u1", null, Role.USER)
        val tree = MessageTree(listOf(user))
        val checkpoint = PartialReply(userNodeId = "u1", modelId = "m", text = "   ", updatedAt = t0)

        val actions = PartialReplyRecovery.plan(tree, listOf(checkpoint), now = t0)

        assertNull(actions.single().insert)
    }

    @Test
    fun `a checkpoint pointing at a non-USER node is discarded`() {
        val asst = node("a1", null, Role.ASSISTANT)
        val tree = MessageTree(listOf(asst))
        val checkpoint = PartialReply(userNodeId = "a1", modelId = "m", text = "text", updatedAt = t0)

        val actions = PartialReplyRecovery.plan(tree, listOf(checkpoint), now = t0)

        assertNull(actions.single().insert)
    }

    @Test
    fun `multiple checkpoints each get their own independent action`() {
        val u1 = node("u1", null, Role.USER)
        val u2 = node("u2", null, Role.USER, createdAt = t0 + 1)
        val existingReply = node("a2", "u2", Role.ASSISTANT, createdAt = t0 + 2)
        val tree = MessageTree(listOf(u1, u2, existingReply))
        val checkpoints = listOf(
            PartialReply(userNodeId = "u1", modelId = "m", text = "recoverable", updatedAt = t0),
            PartialReply(userNodeId = "u2", modelId = "m", text = "stale", updatedAt = t0),
        )

        val actions = PartialReplyRecovery.plan(tree, checkpoints, now = t0 + 10)

        assertEquals(2, actions.size)
        val byUser = actions.associateBy { it.checkpoint.userNodeId }
        assertNotNull(byUser.getValue("u1").insert)
        assertNull(byUser.getValue("u2").insert)
    }

    @Test
    fun `default idGen produces a non-blank unique id when none is supplied`() {
        val user = node("u1", null, Role.USER)
        val tree = MessageTree(listOf(user))
        val checkpoint = PartialReply(userNodeId = "u1", modelId = "m", text = "text", updatedAt = t0)

        val inserted = PartialReplyRecovery.plan(tree, listOf(checkpoint), now = t0).single().insert
        assertTrue(inserted != null && inserted.id.isNotBlank())
    }

    @Test
    fun `empty checkpoint list plans no actions`() {
        val tree = MessageTree(emptyList())
        assertEquals(emptyList<PartialReplyRecovery.Action>(), PartialReplyRecovery.plan(tree, emptyList(), now = t0))
    }
}
