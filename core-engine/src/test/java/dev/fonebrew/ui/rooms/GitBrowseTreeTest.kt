package dev.fonebrew.ui.rooms

import dev.aarso.hyle.component.HyleTreeIconKind
import dev.fonebrew.data.GitBrowse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitBrowseTreeTest {

    private fun dir(name: String, path: String = name) = GitBrowse.Entry(name, path, isDir = true)
    private fun file(name: String, path: String = name) = GitBrowse.Entry(name, path, isDir = false)

    @Test fun `no directories fetched yet - empty forest`() {
        assertTrue(buildGitBrowseForest(emptyMap()).isEmpty())
    }

    @Test fun `root only - files and dirs map straight across`() {
        val forest = buildGitBrowseForest(mapOf("" to listOf(dir("app"), file("README.md"))))
        assertEquals(listOf("app", "README.md"), forest.map { it.label })
        assertEquals(HyleTreeIconKind.FOLDER, forest[0].iconKind)
        assertTrue(forest[0].hasChildren)
        assertEquals(HyleTreeIconKind.FILE, forest[1].iconKind)
        assertFalse(forest[1].hasChildren)
    }

    @Test fun `unfetched directory still shows a chevron - hasChildren true, children empty`() {
        val forest = buildGitBrowseForest(mapOf("" to listOf(dir("src"))))
        val src = forest.single()
        assertTrue(src.hasChildren)
        assertTrue(src.children.isEmpty())
    }

    @Test fun `fetched directory nests its entries as children`() {
        val forest = buildGitBrowseForest(
            mapOf(
                "" to listOf(dir("src")),
                "src" to listOf(file("Main.kt", "src/Main.kt")),
            ),
        )
        val src = forest.single()
        assertEquals(listOf("Main.kt"), src.children.map { it.label })
        assertEquals("src/Main.kt", src.children.single().id)
    }

    @Test fun `nested fetch three levels deep round-trips id and label per node`() {
        val forest = buildGitBrowseForest(
            mapOf(
                "" to listOf(dir("a")),
                "a" to listOf(dir("a/b", "a/b")),
                "a/b" to listOf(file("c.txt", "a/b/c.txt")),
            ),
        )
        val a = forest.single()
        val b = a.children.single()
        val c = b.children.single()
        assertEquals("a", a.id)
        assertEquals("a/b", b.id)
        assertEquals("a/b/c.txt", c.id)
        assertFalse(c.hasChildren)
    }

    @Test fun `entries stay sorted the way GitBrowse already sorted them`() {
        // GitBrowse.list sorts dirs-first-then-alpha itself; the forest builder must not reorder.
        val entries = listOf(dir("z-dir"), dir("a-dir"), file("b-file"))
        val forest = buildGitBrowseForest(mapOf("" to entries))
        assertEquals(listOf("z-dir", "a-dir", "b-file"), forest.map { it.label })
    }

    @Test fun `directory menu offers Expand when collapsed`() {
        val items = gitBrowseMenuItems(isDir = true, expanded = false)
        assertEquals(listOf("toggle" to "Expand", "copy_path" to "Copy path"), items.map { it.id to it.label })
        assertTrue(items.none { it.destructive })
    }

    @Test fun `directory menu offers Collapse when expanded`() {
        val items = gitBrowseMenuItems(isDir = true, expanded = true)
        assertEquals("Collapse", items.first().label)
    }

    @Test fun `file menu offers Open, not Expand-Collapse`() {
        val items = gitBrowseMenuItems(isDir = false, expanded = false)
        assertEquals(listOf("open" to "Open", "copy_path" to "Copy path"), items.map { it.id to it.label })
    }

    @Test fun `no menu item is destructive - GitBrowse has no delete flow to call`() {
        assertTrue(gitBrowseMenuItems(isDir = true, expanded = true).none { it.destructive })
        assertTrue(gitBrowseMenuItems(isDir = false, expanded = false).none { it.destructive })
    }
}
