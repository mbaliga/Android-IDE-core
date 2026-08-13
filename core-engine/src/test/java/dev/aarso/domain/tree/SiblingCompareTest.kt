package dev.aarso.domain.tree

import dev.aarso.domain.MessageNode
import dev.aarso.domain.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SiblingCompareTest {

    private var clock = 0L
    private fun node(
        id: String,
        parentId: String?,
        content: String = id,
        role: Role = Role.USER,
        modelId: String? = null,
    ) = MessageNode(id = id, parentId = parentId, role = role, content = content, modelId = modelId, createdAt = clock++)

    /**
     *   root
     *   └── a
     *       ├── b1 -> c1 (2 turns, model M1)
     *       └── b2       (1 turn)
     */
    private fun sampleTree() = MessageTree(
        listOf(
            node("root", null, role = Role.SYSTEM),
            node("a", "root"),
            node("b1", "a", content = "first alt opens here", modelId = "M1"),
            node("b2", "a", content = "second alt opens here"),
            node("c1", "b1", content = "reply", role = Role.ASSISTANT, modelId = "M1"),
        ),
    )

    @Test
    fun build_oneAlternativePerChild() {
        val compare = SiblingCompares.build(sampleTree(), "a", activeChildId = null)
        assertEquals(listOf("b1", "b2"), compare.alternatives.map { it.childId })
    }

    @Test
    fun build_leafIdDescendsToTheAlternativesTip() {
        val compare = SiblingCompares.build(sampleTree(), "a", activeChildId = null)
        val b1 = compare.alternatives.first { it.childId == "b1" }
        assertEquals("c1", b1.leafId)
        val b2 = compare.alternatives.first { it.childId == "b2" }
        assertEquals("b2", b2.leafId) // a leaf itself, descendToLeaf(b2) == b2
    }

    @Test
    fun build_turnCountCountsChildThroughLeafInclusive() {
        val compare = SiblingCompares.build(sampleTree(), "a", activeChildId = null)
        assertEquals(2, compare.alternatives.first { it.childId == "b1" }.turnCount) // b1, c1
        assertEquals(1, compare.alternatives.first { it.childId == "b2" }.turnCount) // b2
    }

    @Test
    fun build_isActiveMarksOnlyTheGivenChild() {
        val compare = SiblingCompares.build(sampleTree(), "a", activeChildId = "b2")
        assertFalse(compare.alternatives.first { it.childId == "b1" }.isActive)
        assertTrue(compare.alternatives.first { it.childId == "b2" }.isActive)
    }

    @Test
    fun build_nullActiveChild_noneMarkedActive() {
        val compare = SiblingCompares.build(sampleTree(), "a", activeChildId = null)
        assertTrue(compare.alternatives.none { it.isActive })
    }

    @Test
    fun build_previewIsTheChildsOwnContent() {
        val compare = SiblingCompares.build(sampleTree(), "a", activeChildId = null)
        assertEquals("first alt opens here", compare.alternatives.first { it.childId == "b1" }.preview)
    }

    @Test
    fun build_previewTruncatesLongContent() {
        val tree = MessageTree(
            listOf(
                node("root", null, role = Role.SYSTEM),
                node("x1", "root", content = "y".repeat(300)),
                node("x2", "root", content = "short"),
            ),
        )
        val compare = SiblingCompares.build(tree, "root", activeChildId = null)
        val long = compare.alternatives.first { it.childId == "x1" }.preview
        assertEquals(161, long.length) // 160 chars + ellipsis
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun build_modelIdsAreDistinctAndInFirstUseOrder() {
        val compare = SiblingCompares.build(sampleTree(), "a", activeChildId = null)
        assertEquals(listOf("M1"), compare.alternatives.first { it.childId == "b1" }.modelIds)
        assertTrue(compare.alternatives.first { it.childId == "b2" }.modelIds.isEmpty())
    }

    @Test
    fun build_lastUpdatedAtIsTheLeafsCreatedAt() {
        val tree = sampleTree()
        val c1 = tree.node("c1")!!
        val compare = SiblingCompares.build(tree, "a", activeChildId = null)
        assertEquals(c1.createdAt, compare.alternatives.first { it.childId == "b1" }.lastUpdatedAt)
    }

    @Test
    fun build_notABranchPoint_singleChild_oneAlternative() {
        val tree = MessageTree(
            listOf(
                node("root", null, role = Role.SYSTEM),
                node("only", "root"),
            ),
        )
        val compare = SiblingCompares.build(tree, "root", activeChildId = null)
        assertEquals(1, compare.alternatives.size)
    }

    @Test
    fun build_unknownBranchNode_emptyAlternatives() {
        val compare = SiblingCompares.build(sampleTree(), "nope", activeChildId = null)
        assertTrue(compare.alternatives.isEmpty())
    }

    @Test
    fun build_branchNodeIdIsPreserved() {
        val compare = SiblingCompares.build(sampleTree(), "a", activeChildId = null)
        assertEquals("a", compare.branchNodeId)
    }
}
