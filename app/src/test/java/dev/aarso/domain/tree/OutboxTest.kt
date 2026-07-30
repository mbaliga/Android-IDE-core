package dev.aarso.domain.tree

import dev.aarso.domain.MessageNode
import dev.aarso.domain.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [Outbox] — the pure "unanswered turn" derivation over [MessageTree]
 * fixtures (daily-driver.md W3), mirroring [ConversationsTest]'s fixture-building style.
 */
class OutboxTest {

    private val t0 = 1_700_000_000_000L

    private fun node(
        id: String,
        parentId: String?,
        role: Role,
        content: String = id,
        createdAt: Long,
        metadata: Map<String, String> = emptyMap(),
    ) = MessageNode(
        id = id,
        parentId = parentId,
        role = role,
        content = content,
        createdAt = createdAt,
        metadata = metadata,
    )

    // ---- basic derivation ----

    @Test
    fun `a fully answered turn is not in the outbox`() {
        val user = node("u1", null, Role.USER, createdAt = t0)
        val asst = node("a1", "u1", Role.ASSISTANT, createdAt = t0 + 1)
        val tree = MessageTree(listOf(user, asst))

        assertEquals(emptyList<Outbox.UnansweredTurn>(), Outbox.unanswered(tree))
    }

    @Test
    fun `a user turn with no children at all is unanswered`() {
        val user = node("u1", null, Role.USER, createdAt = t0)
        val tree = MessageTree(listOf(user))

        val result = Outbox.unanswered(tree)
        assertEquals(1, result.size)
        assertEquals("u1", result.single().userNodeId)
        assertEquals("u1", result.single().rootId)
        assertEquals(t0, result.single().createdAt)
    }

    @Test
    fun `an assistant node with no children is not unanswered - only USER turns count`() {
        val user = node("u1", null, Role.USER, createdAt = t0)
        val asst = node("a1", "u1", Role.ASSISTANT, createdAt = t0 + 1)
        // asst itself has no children either, but it isn't a USER turn so it never counts.
        val tree = MessageTree(listOf(user, asst))

        assertTrue(Outbox.unanswered(tree).none { it.userNodeId == "a1" })
    }

    @Test
    fun `multiple unanswered turns across conversations are all reported, oldest first`() {
        val u1 = node("u1", null, Role.USER, createdAt = t0 + 10) // newer
        val u2 = node("u2", null, Role.USER, createdAt = t0) // older
        val tree = MessageTree(listOf(u1, u2))

        val result = Outbox.unanswered(tree)
        assertEquals(listOf("u2", "u1"), result.map { it.userNodeId })
    }

    @Test
    fun `an answered turn deeper in a conversation does not hide a later unanswered one`() {
        // root(system) -> u1(answered) -> a1 -> u2(unanswered, the tail)
        val root = node("root", null, Role.SYSTEM, createdAt = t0)
        val u1 = node("u1", "root", Role.USER, createdAt = t0 + 1)
        val a1 = node("a1", "u1", Role.ASSISTANT, createdAt = t0 + 2)
        val u2 = node("u2", "a1", Role.USER, createdAt = t0 + 3)
        val tree = MessageTree(listOf(root, u1, a1, u2))

        val result = Outbox.unanswered(tree)
        assertEquals(listOf("u2"), result.map { it.userNodeId })
        assertEquals("root", result.single().rootId)
    }

    // ---- excludeNodeId (the in-flight generation) ----

    @Test
    fun `excludeNodeId hides the turn that is actively generating`() {
        val user = node("u1", null, Role.USER, createdAt = t0)
        val tree = MessageTree(listOf(user))

        assertEquals(emptyList<Outbox.UnansweredTurn>(), Outbox.unanswered(tree, excludeNodeId = "u1"))
    }

    @Test
    fun `excludeNodeId only hides the matching node, not other unanswered turns`() {
        val u1 = node("u1", null, Role.USER, createdAt = t0)
        val u2 = node("u2", null, Role.USER, createdAt = t0 + 1)
        val tree = MessageTree(listOf(u1, u2))

        val result = Outbox.unanswered(tree, excludeNodeId = "u1")
        assertEquals(listOf("u2"), result.map { it.userNodeId })
    }

    // ---- unansweredOnPath ----

    @Test
    fun `unansweredOnPath returns the turn when the path's tail is unanswered`() {
        val root = node("root", null, Role.SYSTEM, createdAt = t0)
        val user = node("u1", "root", Role.USER, createdAt = t0 + 1)
        val tree = MessageTree(listOf(root, user))

        val result = Outbox.unansweredOnPath(tree, leafId = "u1")
        assertEquals("u1", result?.userNodeId)
    }

    @Test
    fun `unansweredOnPath returns null when the path's tail already has a reply`() {
        val user = node("u1", null, Role.USER, createdAt = t0)
        val asst = node("a1", "u1", Role.ASSISTANT, createdAt = t0 + 1)
        val tree = MessageTree(listOf(user, asst))

        assertNull(Outbox.unansweredOnPath(tree, leafId = "a1"))
    }

    @Test
    fun `unansweredOnPath returns null for an unknown leaf id`() {
        val user = node("u1", null, Role.USER, createdAt = t0)
        val tree = MessageTree(listOf(user))

        assertNull(Outbox.unansweredOnPath(tree, leafId = "does-not-exist"))
    }

    @Test
    fun `unansweredOnPath respects excludeNodeId for the in-flight turn`() {
        val user = node("u1", null, Role.USER, createdAt = t0)
        val tree = MessageTree(listOf(user))

        assertNull(Outbox.unansweredOnPath(tree, leafId = "u1", excludeNodeId = "u1"))
    }

    // ---- council-tagged replies still count as "answered" ----

    @Test
    fun `a user turn with a council fan-out reply is not unanswered`() {
        val user = node("u1", null, Role.USER, createdAt = t0)
        val council = node("c1", "u1", Role.ASSISTANT, createdAt = t0 + 1, metadata = mapOf("agent" to "Scout"))
        val tree = MessageTree(listOf(user, council))

        assertEquals(emptyList<Outbox.UnansweredTurn>(), Outbox.unanswered(tree))
    }
}
