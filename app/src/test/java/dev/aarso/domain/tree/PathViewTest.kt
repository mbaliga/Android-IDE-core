package dev.aarso.domain.tree

import dev.aarso.domain.MessageNode
import dev.aarso.domain.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathViewTest {

    private var clock = 0L
    private fun node(id: String, parentId: String?) =
        MessageNode(id, parentId, Role.USER, id, createdAt = clock++)

    /**
     *   root ── a ─┬── b1 ── c
     *              └── b2
     */
    private fun sampleTree() = MessageTree(
        listOf(
            node("root", null),
            node("a", "root"),
            node("b1", "a"),
            node("b2", "a"),
            node("c", "b1"),
        ),
    )

    @Test
    fun annotate_marksTheBranchPointAndActiveChoice() {
        val steps = PathView.annotate(sampleTree(), "c")
        assertEquals(listOf("root", "a", "b1", "c"), steps.map { it.node.id })

        val a = steps.first { it.node.id == "a" }
        assertEquals(2, a.alternativeCount)
        assertEquals(1, a.activeAlternative) // path took b1, the first child
        assertTrue(a.isBranchPoint)

        val root = steps.first { it.node.id == "root" }
        assertEquals(1, root.alternativeCount)
        assertFalse(root.isBranchPoint)
    }

    @Test
    fun annotate_reflectsTheOtherBranchWhenLeafChanges() {
        val a = PathView.annotate(sampleTree(), "b2").first { it.node.id == "a" }
        assertEquals(2, a.alternativeCount)
        assertEquals(2, a.activeAlternative) // path now takes b2, the second child
    }

    @Test
    fun annotate_leafHasNoActiveContinuation() {
        val leaf = PathView.annotate(sampleTree(), "c").last()
        assertEquals("c", leaf.node.id)
        assertEquals(0, leaf.activeAlternative)
        assertEquals(0, leaf.alternativeCount)
    }
}
