package dev.aarso.domain.ide

import dev.aarso.domain.git.GitHost
import dev.aarso.domain.git.GitHostKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ScaffoldPublishApiTest {

    private val github = GitHost(
        id = "gh", displayName = "GitHub", kind = GitHostKind.GITHUB, baseUrl = "",
        owner = "acme", repo = "ignored", branch = "main",
        authorName = "Dev", authorEmail = "dev@x.y",
    )

    private val spec = ProjectScaffold.AppSpec("Demo", "com.example.demo", idea = "x")

    @Test fun `createRepo posts to user repos with auto_init false`() {
        val r = ScaffoldPublishApi.createRepo(github, "demo", private = true, token = "tok")
        assertEquals("POST", r.method)
        assertEquals("https://api.github.com/user/repos", r.url)
        assertEquals("Bearer tok", r.headers["Authorization"])
        val body = JSONObject(r.body!!)
        assertEquals("demo", body.getString("name"))
        assertTrue(body.getBoolean("private"))
        assertFalse(body.getBoolean("auto_init"))
    }

    @Test fun `publishRequests is one create-PUT per file, content intact, on main`() {
        val files = ProjectScaffold.generate(spec)
        val reqs = ScaffoldPublishApi.publishRequests(github, "demo", files, "tok")
        assertEquals(files.size, reqs.size)

        // Each request creates a file (PUT, no sha) targeting the new repo.
        reqs.forEach { assertEquals("PUT", it.method) }
        assertTrue(reqs.all { it.url.contains("/repos/acme/demo/contents/") })

        // Spot-check a known file round-trips its content via the base64 payload.
        val manifest = files.first { it.path == "settings.gradle.kts" }
        val req = reqs[files.indexOf(manifest)]
        val body = JSONObject(req.body!!)
        assertFalse(body.has("sha")) // create, not update
        assertEquals("main", body.getString("branch"))
        val decoded = String(Base64.getDecoder().decode(body.getString("content")), Charsets.UTF_8)
        assertEquals(manifest.content, decoded)
    }
}
