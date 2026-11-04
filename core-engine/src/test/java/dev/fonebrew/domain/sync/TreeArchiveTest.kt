package dev.fonebrew.domain.sync

import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeArchiveTest {

    private val nodes = listOf(
        MessageNode("r1", null, Role.USER, "hello 😊 \"quoted\"\nline2", null, 1_000L),
        MessageNode(
            "a1", "r1", Role.ASSISTANT, "hi back", "qwen2:7b", 1_001L,
            tokenCounts = mapOf("qwen2" to 3),
            metadata = mapOf("agent" to "Skeptic", "council" to "r1"),
        ),
        MessageNode("r2", null, Role.SYSTEM, "another root", null, 2_000L),
    )

    @Test fun `round-trips the tree as a set of node files`() {
        val files = TreeArchive.write(nodes)
        val back = TreeArchive.read(files)
        assertEquals(nodes.toSet(), back.toSet())
    }

    @Test fun `writes a manifest and one file per node`() {
        val files = TreeArchive.write(nodes)
        assertTrue(files.containsKey(TreeArchive.MANIFEST_PATH))
        assertTrue(files.containsKey("aarso/nodes/r1.json"))
        assertTrue(files.containsKey("aarso/nodes/a1.json"))
        // manifest + 3 nodes
        assertEquals(4, files.size)
    }

    @Test fun `read ignores non-node files`() {
        val files = TreeArchive.write(nodes) + ("README.md" to "not a node")
        assertEquals(nodes.size, TreeArchive.read(files).size)
    }

    @Test fun `backup plan creates only the files missing on the remote`() {
        val files = TreeArchive.write(nodes)
        // Manifest + r1 already on the remote; a1 and r2 are new.
        val existing = setOf(TreeArchive.MANIFEST_PATH, "aarso/nodes/r1.json")
        val plan = TreeBackup.plan(files, existing)
        assertEquals(setOf("aarso/nodes/a1.json", "aarso/nodes/r2.json"), plan.keys)
        // A fully-synced tree plans nothing.
        assertTrue(TreeBackup.plan(files, files.keys).isEmpty())
    }

    @Test fun `import plan orders parent-before-child, skips existing, drops orphans`() {
        // r1 exists locally; a1 (child of r1) and r2 are new; x1's parent is missing.
        val orphan = MessageNode("x1", "ghost", Role.ASSISTANT, "orphan", null, 9L)
        val plan = TreeBackup.importPlan(nodes + orphan, existingIds = setOf("r1"))
        assertEquals(listOf("a1", "r2"), plan.toInsert.map { it.id })
        assertEquals(listOf("x1"), plan.orphans.map { it.id })
    }

    @Test fun `import plan is child-after-parent even when input is shuffled`() {
        val root = MessageNode("p", null, Role.USER, "p", null, 1L)
        val child = MessageNode("c", "p", Role.ASSISTANT, "c", null, 2L)
        val plan = TreeBackup.importPlan(listOf(child, root), emptySet())
        assertEquals(listOf("p", "c"), plan.toInsert.map { it.id })
    }

    // docs/design/objects-3d.md §8: "Export-everything includes the directory."
    @Test fun `object3d files round-trip through the archive as base64 under their own prefix`() {
        val files = mapOf(
            "ab12.glb" to byteArrayOf(1, 2, 3, 4, 5),
            "cd34.json" to "{\"schemaVersion\":\"1.0.0\"}".toByteArray(Charsets.UTF_8),
        )
        val archived = TreeArchive.writeObject3dFiles(files)

        assertEquals(setOf("aarso/objects3d/ab12.glb", "aarso/objects3d/cd34.json"), archived.keys)
        val back = TreeArchive.readObject3dFiles(archived)
        assertEquals(files.keys, back.keys)
        assertTrue(files["ab12.glb"].contentEquals(back["ab12.glb"]))
        assertTrue(files["cd34.json"].contentEquals(back["cd34.json"]))
    }

    @Test fun `readObject3dFiles ignores node files and the manifest`() {
        val nodeFiles = TreeArchive.write(nodes)
        val object3dFiles = TreeArchive.writeObject3dFiles(mapOf("x.glb" to byteArrayOf(9)))
        assertEquals(setOf("x.glb"), TreeArchive.readObject3dFiles(nodeFiles + object3dFiles).keys)
    }
}
