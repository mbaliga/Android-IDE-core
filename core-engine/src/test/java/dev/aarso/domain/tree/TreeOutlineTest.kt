package dev.aarso.domain.tree

import dev.aarso.domain.MessageNode
import dev.aarso.domain.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeOutlineTest {

    private var clock = 0L
    private fun node(id: String, parentId: String?) =
        MessageNode(id, parentId, Role.USER, id, createdAt = clock++)

    /** root ── a ─┬── b1 ── c   └── b2 */
    private fun tree() = MessageTree(
        listOf(node("root", null), node("a", "root"), node("b1", "a"), node("b2", "a"), node("c", "b1")),
    )

    @Test
    fun depthFirstOrderWithDepths() {
        val rows = TreeOutline.build(tree(), activeLeafId = "c")
        assertEquals(
            listOf("root" to 0, "a" to 1, "b1" to 2, "c" to 3, "b2" to 2),
            rows.map { it.node.id to it.depth },
        )
    }

    @Test
    fun marksActivePathAndBranchPoint() {
        val rows = TreeOutline.build(tree(), activeLeafId = "c").associateBy { it.node.id }
        assertTrue(rows.getValue("c").onActivePath)
        assertFalse(rows.getValue("b2").onActivePath)
        assertTrue(rows.getValue("a").isBranchPoint)
        assertTrue(rows.getValue("c").isLeaf)
    }
}
