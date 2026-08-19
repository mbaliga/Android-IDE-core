package dev.fonebrew.domain.tree

import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeForkTest {

    private var clock = 0L
    private fun node(
        id: String,
        parentId: String?,
        role: Role = Role.USER,
        metadata: Map<String, String> = emptyMap(),
    ) = MessageNode(id = id, parentId = parentId, role = role, content = "content-$id", createdAt = clock++, metadata = metadata)

    /**
     *   root
     *   └── a
     *       ├── b1
     *       │   └── c
     *       └── b2
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

    private fun fixedIdGen(vararg ids: String): () -> String {
        var i = 0
        return { ids[i++] }
    }

    @Test
    fun copySubtree_copiesTheAncestorChain_parentFirst() {
        val result = TreeFork.copySubtree(
            sampleTree(), fromNodeId = "c", srcRootId = "root", now = 100L,
            idGen = fixedIdGen("r2", "a2", "b12", "c2"),
        )
        assertEquals(listOf("r2", "a2", "b12", "c2"), result.nodes.map { it.id })
        assertEquals(listOf(null, "r2", "a2", "b12"), result.nodes.map { it.parentId })
    }

    @Test
    fun copySubtree_preservesContentRoleAndCreatedAt() {
        val original = sampleTree()
        val result = TreeFork.copySubtree(original, "c", "root", now = 100L, idGen = fixedIdGen("r2", "a2", "b12", "c2"))
        val bySrc = original.pathToRoot("c").associateBy { it.id }
        result.nodes.zip(listOf("root", "a", "b1", "c")).forEach { (copy, srcId) ->
            val src = bySrc.getValue(srcId)
            assertEquals(src.content, copy.content)
            assertEquals(src.role, copy.role)
            assertEquals(src.createdAt, copy.createdAt)
        }
    }

    @Test
    fun copySubtree_newRoot_isParentless() {
        val result = TreeFork.copySubtree(sampleTree(), "a", "root", now = 1L)
        assertNull(result.nodes.first().parentId)
        assertEquals(result.nodes.first().id, result.newRootId)
    }

    @Test
    fun copySubtree_newRoot_carriesLineageMetadata() {
        val result = TreeFork.copySubtree(sampleTree(), "b1", srcRootId = "root", now = 555L)
        val meta = result.nodes.first().metadata
        assertEquals(TreeFork.LineageKind.FORK.name, meta[TreeFork.LINEAGE_KIND_KEY])
        assertEquals("root", meta[TreeFork.LINEAGE_SRC_ROOT_KEY])
        assertEquals("b1", meta[TreeFork.LINEAGE_SRC_NODE_KEY])
        assertEquals("555", meta[TreeFork.LINEAGE_AT_KEY])
    }

    @Test
    fun copySubtree_nonRootCopiedNodes_carryNoLineageMetadata() {
        val result = TreeFork.copySubtree(sampleTree(), "c", "root", now = 1L)
        result.nodes.drop(1).forEach { copy ->
            assertTrue(TreeFork.LINEAGE_KIND_KEY !in copy.metadata)
        }
    }

    @Test
    fun copySubtree_idsAreFreshAndDistinctFromSource() {
        val result = TreeFork.copySubtree(sampleTree(), "c", "root", now = 1L)
        val sourceIds = setOf("root", "a", "b1", "c")
        result.nodes.forEach { assertTrue(it.id !in sourceIds) }
        assertEquals(result.nodes.size, result.nodes.map { it.id }.distinct().size)
    }

    @Test
    fun copySubtree_idMap_coversEveryOriginalOnThePath() {
        val result = TreeFork.copySubtree(sampleTree(), "c", "root", now = 1L)
        assertEquals(setOf("root", "a", "b1", "c"), result.idMap.keys)
    }

    @Test
    fun copySubtree_forkingTheRoot_producesASingleNodeChain() {
        val result = TreeFork.copySubtree(sampleTree(), "root", "root", now = 1L)
        assertEquals(1, result.nodes.size)
        assertNull(result.nodes.single().parentId)
    }

    @Test
    fun copySubtree_unknownNode_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            TreeFork.copySubtree(sampleTree(), "nope", "root", now = 1L)
        }
    }

    @Test
    fun copySubtree_remapsCouncilBackrefWithinTheCopiedChain() {
        val tree = MessageTree(
            listOf(
                node("root", null, Role.SYSTEM),
                node("u1", "root"),
                node("v1", "u1", Role.ASSISTANT, metadata = mapOf("council" to "u1", "agent" to "voice-a")),
            ),
        )
        val result = TreeFork.copySubtree(tree, "v1", "root", now = 1L, idGen = fixedIdGen("r2", "u12", "v12"))
        val copiedVoice = result.nodes.last()
        assertEquals("u12", copiedVoice.metadata["council"])
    }

    @Test
    fun copySubtree_leavesACouncilBackrefOutsideTheCopyUntouched() {
        // "mid"'s council backref points at an id that isn't part of the forked ancestor chain
        // (it lives elsewhere in the source tree) — the remap must leave it verbatim, not null it
        // out or drop the key, since that id is still a real, resolvable node in the source tree.
        val tree = MessageTree(
            listOf(
                node("root", null, Role.SYSTEM),
                node("u1", "root"),
                node("mid", "u1", Role.ASSISTANT, metadata = mapOf("council" to "outside-id")),
            ),
        )
        val result = TreeFork.copySubtree(tree, "mid", "root", now = 1L)
        assertEquals("outside-id", result.nodes.last().metadata["council"])
    }

    @Test
    fun copySubtree_isDeterministicGivenTheSameIdGen() {
        // One shared tree: sampleTree() stamps createdAt from the test class's own clock counter,
        // so calling it twice would (correctly) produce two trees with different timestamps — not
        // a TreeFork bug, just not what "same inputs" means here.
        val tree = sampleTree()
        val a = TreeFork.copySubtree(tree, "c", "root", now = 42L, idGen = fixedIdGen("x1", "x2", "x3", "x4"))
        val b = TreeFork.copySubtree(tree, "c", "root", now = 42L, idGen = fixedIdGen("x1", "x2", "x3", "x4"))
        assertEquals(a.nodes, b.nodes)
    }

    @Test
    fun copySubtree_defaultIdGen_producesUniqueIds() {
        val result = TreeFork.copySubtree(sampleTree(), "c", "root", now = 1L)
        assertNotEquals(result.nodes[0].id, result.nodes[1].id)
    }
}
