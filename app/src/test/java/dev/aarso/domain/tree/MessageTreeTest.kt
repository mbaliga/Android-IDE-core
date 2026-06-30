package dev.aarso.domain.tree

import dev.aarso.domain.MessageNode
import dev.aarso.domain.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure tree algorithms — the spine of the app (handoff §2).
 * These run on the JVM with no Android/Room dependency.
 */
class MessageTreeTest {

    private var clock = 0L
    private fun node(
        id: String,
        parentId: String?,
        role: Role = Role.USER,
    ) = MessageNode(
        id = id,
        parentId = parentId,
        role = role,
        content = id,
        createdAt = clock++,
    )

    /**
     * Tree used across tests:
     *
     *   root
     *   └── a
     *       ├── b1   (branch)
     *       │   └── c
     *       └── b2   (branch)
     */
    private fun sampleTree() = MessageTree(
        listOf(
            node("root", null, Role.SYSTEM),
            node("a", "root"),
            node("b1", "a"),
            node("b2", "a"),
            node("c", "b1"),
        ),
    )

    @Test
    fun pathToRoot_returnsRootFirstOrder() {
        val path = sampleTree().pathToRoot("c").map { it.id }
        assertEquals(listOf("root", "a", "b1", "c"), path)
    }

    @Test
    fun pathToRoot_unknownLeaf_isEmpty() {
        assertTrue(sampleTree().pathToRoot("nope").isEmpty())
    }

    @Test
    fun conversation_endsAtChosenLeaf() {
        val convo = sampleTree().conversation("b2")
        assertEquals("b2", convo.leaf?.id)
        assertEquals("root", convo.root?.id)
        assertEquals(listOf("root", "a", "b2"), convo.nodes.map { it.id })
    }

    @Test
    fun branchPoints_areNodesWithMoreThanOneChild() {
        assertEquals(listOf("a"), sampleTree().branchPoints().map { it.id })
    }

    @Test
    fun childrenOf_returnsDirectChildrenOldestFirst() {
        assertEquals(listOf("b1", "b2"), sampleTree().childrenOf("a").map { it.id })
    }

    @Test
    fun siblingsOf_returnsTheAlternativeSetIncludingSelf() {
        assertEquals(listOf("b1", "b2"), sampleTree().siblingsOf("b1").map { it.id }.sorted())
    }

    @Test
    fun roots_areParentlessNodes() {
        assertEquals(listOf("root"), sampleTree().roots().map { it.id })
    }

    @Test
    fun pathToRoot_danglingParent_throws() {
        val tree = MessageTree(listOf(node("orphan", "missing")))
        assertThrows(IllegalStateException::class.java) { tree.pathToRoot("orphan") }
    }

    @Test
    fun pathToRoot_cycle_throws() {
        // x -> y -> x : corruption, must surface rather than loop forever.
        val tree = MessageTree(
            listOf(
                MessageNode("x", "y", Role.USER, "x", createdAt = 0),
                MessageNode("y", "x", Role.USER, "y", createdAt = 1),
            ),
        )
        assertThrows(IllegalStateException::class.java) { tree.pathToRoot("x") }
    }

    @Test
    fun descendToLeaf_followsMostRecentChild() {
        // children of "a" are b1 (older) and b2 (newer); descend picks b2.
        assertEquals("b2", sampleTree().descendToLeaf("a"))
        assertEquals("c", sampleTree().descendToLeaf("b1"))
    }

    @Test
    fun descendToLeaf_ofALeaf_isItself() {
        assertEquals("c", sampleTree().descendToLeaf("c"))
    }

    @Test
    fun descendToLeaf_unknownNode_isNull() {
        assertEquals(null, sampleTree().descendToLeaf("nope"))
    }

    @Test
    fun child_appendsUnderParent() {
        val parent = node("p", null)
        val created = Nodes.child(
            parent = parent,
            role = Role.ASSISTANT,
            content = "hi",
            now = 42L,
            modelId = "qwen3-14b",
            idGen = { "fixed-id" },
        )
        assertEquals("fixed-id", created.id)
        assertEquals("p", created.parentId)
        assertEquals("qwen3-14b", created.modelId)
        assertEquals(42L, created.createdAt)
    }
}
