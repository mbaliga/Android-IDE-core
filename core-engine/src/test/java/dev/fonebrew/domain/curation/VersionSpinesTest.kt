package dev.fonebrew.domain.curation

import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.Role
import dev.fonebrew.domain.tree.MessageTree
import org.junit.Assert.assertEquals
import org.junit.Test

class VersionSpinesTest {

    private fun node(id: String, parentId: String?, createdAt: Long) = MessageNode(
        id = id, parentId = parentId, role = Role.USER, content = id, createdAt = createdAt,
    )

    // r -> a -> b -> c (version at c)
    //        -> d        (sibling branch, not on c's spine)
    private fun tree() = MessageTree(
        listOf(
            node("r", null, 0), node("a", "r", 1), node("b", "a", 2), node("c", "b", 3),
            node("d", "a", 4),
        ),
    )

    @Test fun `every ancestor of a version tip is on its spine, including the tip itself`() {
        val ids = VersionSpines.computeIds(tree(), listOf(Version("v1", "c", "name", at = 0L)))
        assertEquals(setOf("r", "a", "b", "c"), ids)
    }

    @Test fun `a sibling branch not leading to the tip is excluded`() {
        val ids = VersionSpines.computeIds(tree(), listOf(Version("v1", "c", "name", at = 0L)))
        assertEquals(false, "d" in ids)
    }

    @Test fun `multiple versions union their spines`() {
        val ids = VersionSpines.computeIds(
            tree(),
            listOf(Version("v1", "c", "n1", at = 0L), Version("v2", "d", "n2", at = 1L)),
        )
        assertEquals(setOf("r", "a", "b", "c", "d"), ids)
    }

    @Test fun `no versions means an empty spine set`() {
        assertEquals(emptySet<String>(), VersionSpines.computeIds(tree(), emptyList()))
    }

    @Test fun `a version whose tip id is unknown to the tree contributes nothing, not a crash`() {
        val ids = VersionSpines.computeIds(tree(), listOf(Version("v1", "does-not-exist", "n", at = 0L)))
        assertEquals(emptySet<String>(), ids)
    }
}
