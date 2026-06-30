package dev.aarso.domain.git

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitTreeApiTest {

    private val gh = GitHost(
        id = "h", displayName = "proj",
        kind = GitHostKind.GITHUB, baseUrl = "https://api.github.com",
        owner = "me", repo = "proj", branch = "main",
        authorName = "Me", authorEmail = "me@example.com",
    )
    private val gitea = gh.copy(kind = GitHostKind.GITEA, baseUrl = "https://git.example.org")

    @Test fun `getRef uses git ref on GitHub and git refs on Gitea`() {
        assertTrue(GitTreeApi.getRef(gh, "t").url.endsWith("/repos/me/proj/git/ref/heads/main"))
        assertTrue(GitTreeApi.getRef(gitea, "t").url.endsWith("/api/v1/repos/me/proj/git/refs/heads/main"))
    }

    @Test fun `createTree carries base_tree and one blob entry per file`() {
        val req = GitTreeApi.createTree(gh, "BASE", listOf("a.txt" to "hello", "dir/b.kt" to "fun x()"), "t")
        assertEquals("POST", req.method)
        assertTrue(req.url.endsWith("/git/trees"))
        val body = JSONObject(req.body!!)
        assertEquals("BASE", body.getString("base_tree"))
        val tree = body.getJSONArray("tree")
        assertEquals(2, tree.length())
        assertEquals("a.txt", tree.getJSONObject(0).getString("path"))
        assertEquals("100644", tree.getJSONObject(0).getString("mode"))
        assertEquals("blob", tree.getJSONObject(0).getString("type"))
        assertEquals("hello", tree.getJSONObject(0).getString("content"))
    }

    @Test fun `createCommit references tree and parent`() {
        val req = GitTreeApi.createCommit(gh, "msg", "TREE", "PARENT", "t")
        val body = JSONObject(req.body!!)
        assertEquals("msg", body.getString("message"))
        assertEquals("TREE", body.getString("tree"))
        assertEquals("PARENT", body.getJSONArray("parents").getString(0))
    }

    @Test fun `updateRef patches the branch ref without force`() {
        val req = GitTreeApi.updateRef(gh, "NEW", "t")
        assertEquals("PATCH", req.method)
        assertTrue(req.url.endsWith("/git/refs/heads/main"))
        val body = JSONObject(req.body!!)
        assertEquals("NEW", body.getString("sha"))
        assertEquals(false, body.getBoolean("force"))
    }

    @Test fun `auth header differs by host kind`() {
        assertEquals("Bearer t", GitTreeApi.getRef(gh, "t").headers["Authorization"])
        assertEquals("token t", GitTreeApi.getRef(gitea, "t").headers["Authorization"])
    }
}
