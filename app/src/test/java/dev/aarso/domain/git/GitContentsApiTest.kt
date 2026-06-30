package dev.aarso.domain.git

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class GitContentsApiTest {

    private val gh = GitHost("1", "mine", GitHostKind.GITHUB, "", "me", "notes", "main", "Me", "me@x.com")
    private val gitea = GitHost("2", "self", GitHostKind.GITEA, "https://git.example.com/", "me", "notes", "trunk", "Me", "me@x.com")

    @Test fun `github api base is fixed, gitea derives from instance`() {
        assertEquals("https://api.github.com", GitContentsApi.apiBase(gh))
        assertEquals("https://git.example.com/api/v1", GitContentsApi.apiBase(gitea))
    }

    @Test fun `getFile builds the right url, ref and auth per host`() {
        val r = GitContentsApi.getFile(gh, "aarso/nodes/a b.json", "tok")
        assertEquals("GET", r.method)
        assertEquals("https://api.github.com/repos/me/notes/contents/aarso/nodes/a%20b.json?ref=main", r.url)
        assertEquals("Bearer tok", r.headers["Authorization"])
        assertEquals("application/vnd.github+json", r.headers["Accept"])

        val g = GitContentsApi.getFile(gitea, "x.json", "tok")
        assertEquals("https://git.example.com/api/v1/repos/me/notes/contents/x.json?ref=trunk", g.url)
        assertEquals("token tok", g.headers["Authorization"])
    }

    @Test fun `putFile base64-encodes content and carries identity + sha`() {
        val r = GitContentsApi.putFile(gh, "f.txt", "hello", "msg", sha = "abc", token = "tok")
        assertEquals("PUT", r.method)
        assertEquals("application/json", r.headers["Content-Type"])
        val body = JSONObject(r.body!!)
        assertEquals("msg", body.getString("message"))
        assertEquals("main", body.getString("branch"))
        assertEquals("abc", body.getString("sha"))
        assertEquals("hello", String(Base64.getDecoder().decode(body.getString("content"))))
        assertEquals("me@x.com", body.getJSONObject("committer").getString("email"))
    }

    @Test fun `putFile without sha omits it (a create)`() {
        val r = GitContentsApi.putFile(gh, "f.txt", "x", "msg", sha = null, token = "tok")
        assertTrue(!JSONObject(r.body!!).has("sha"))
    }

    @Test fun `listDir targets the contents endpoint at the branch`() {
        val r = GitContentsApi.listDir(gh, "aarso/nodes", "tok")
        assertEquals("GET", r.method)
        assertEquals("https://api.github.com/repos/me/notes/contents/aarso/nodes?ref=main", r.url)
    }
}
