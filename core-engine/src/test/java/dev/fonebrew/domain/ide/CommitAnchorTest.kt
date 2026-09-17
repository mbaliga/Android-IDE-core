package dev.fonebrew.domain.ide

import dev.fonebrew.domain.Role
import dev.fonebrew.domain.git.GitHost
import dev.fonebrew.domain.git.GitHostKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitAnchorTest {

    private val host = GitHost(
        id = "h1", displayName = "mine", kind = GitHostKind.GITHUB, baseUrl = "",
        owner = "mbaliga", repo = "android-ide-core", branch = "main",
        authorName = "Me", authorEmail = "me@example.com",
    )

    @Test fun `repoRef formats owner slash repo at branch`() {
        assertEquals("mbaliga/android-ide-core@main", CommitAnchor.repoRef(host))
    }

    @Test fun `metadata carries both keys when repoRef is present`() {
        val meta = CommitAnchor.metadata("abc123", "me/proj@main")
        assertEquals(
            mapOf(CommitAnchor.SHA_KEY to "abc123", CommitAnchor.REPO_KEY to "me/proj@main"),
            meta,
        )
    }

    @Test fun `metadata omits the repo key rather than fabricating a blank one`() {
        assertEquals(mapOf(CommitAnchor.SHA_KEY to "abc123"), CommitAnchor.metadata("abc123", null))
        assertEquals(mapOf(CommitAnchor.SHA_KEY to "abc123"), CommitAnchor.metadata("abc123", ""))
        assertEquals(mapOf(CommitAnchor.SHA_KEY to "abc123"), CommitAnchor.metadata("abc123", "   "))
    }

    @Test fun `metadata rejects a blank sha — it is the one identity fact`() {
        assertThrows(IllegalArgumentException::class.java) { CommitAnchor.metadata("", "me/proj@main") }
    }

    @Test fun `node mints a fresh SYSTEM root by default, carrying the sha and repo metadata`() {
        val node = CommitAnchor.node(id = "n1", sha = "abc123", repoRef = "me/proj@main", createdAt = 1_000L)
        assertEquals("n1", node.id)
        assertNull(node.parentId)
        assertEquals(Role.SYSTEM, node.role)
        assertEquals(1_000L, node.createdAt)
        assertEquals("abc123", node.metadata[CommitAnchor.SHA_KEY])
        assertEquals("me/proj@main", node.metadata[CommitAnchor.REPO_KEY])
        assertTrue(node.content.contains("abc123")) // real data (the sha), never a fabricated summary
    }

    @Test fun `node accepts an explicit parentId for a future chat-integrated caller`() {
        val node = CommitAnchor.node(id = "n1", sha = "abc123", repoRef = null, createdAt = 1_000L, parentId = "msg-42")
        assertEquals("msg-42", node.parentId)
    }

    @Test fun `node uses the given label as content verbatim when supplied`() {
        val node = CommitAnchor.node(id = "n1", sha = "abc123", repoRef = null, createdAt = 1_000L, label = "Fix off-by-one")
        assertEquals("Fix off-by-one", node.content)
    }

    @Test fun `node falls back to a generated description when label is blank`() {
        val node = CommitAnchor.node(id = "n1", sha = "abc123", repoRef = null, createdAt = 1_000L, label = "   ")
        assertEquals("Committed abc123", node.content)
    }
}
