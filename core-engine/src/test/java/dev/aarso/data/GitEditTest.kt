package dev.aarso.data

import dev.aarso.domain.diff.LineDiff
import dev.aarso.domain.git.GitHost
import dev.aarso.domain.git.GitHostKind
import dev.aarso.domain.git.GitRequest
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class GitEditTest {

    /** Records the requests and replays canned responses, so no network is touched. */
    private class FakeTransport(private vararg val replies: Resp) : GitTransport() {
        val seen = mutableListOf<GitRequest>()
        private var i = 0
        override suspend fun execute(req: GitRequest): Resp {
            seen += req
            return replies[i++]
        }
    }

    private val host = GitHost(
        id = "h1",
        displayName = "mine",
        kind = GitHostKind.GITHUB,
        baseUrl = "",
        owner = "me",
        repo = "proj",
        branch = "work",
        authorName = "Me",
        authorEmail = "me@example.com",
    )

    private fun contentsBody(text: String, sha: String): String =
        JSONObject()
            .put("content", Base64.getEncoder().encodeToString(text.toByteArray()))
            .put("sha", sha)
            .toString()

    @Test fun `open returns the file text and its blob sha`() = runTest {
        val t = FakeTransport(GitTransport.Resp(200, contentsBody("hello\nworld\n", "deadbeef")))
        val file = GitEdit(t).open(host, "tok", "src/a.txt").getOrThrow()
        assertEquals("src/a.txt", file.path)
        assertEquals("hello\nworld\n", file.text)
        assertEquals("deadbeef", file.sha)
        // it GET the right URL on the right branch
        val req = t.seen.single()
        assertEquals("GET", req.method)
        assertTrue(req.url, req.url.contains("/repos/me/proj/contents/src/a.txt"))
        assertTrue(req.url, req.url.contains("ref=work"))
    }

    @Test fun `open fails clearly when the host has no sha`() = runTest {
        val t = FakeTransport(GitTransport.Resp(200, JSONObject().put("content", "").toString()))
        assertTrue(GitEdit(t).open(host, "tok", "x").isFailure)
    }

    @Test fun `preview and unified describe the edit locally with no extra request`() = runTest {
        val t = FakeTransport(GitTransport.Resp(200, contentsBody("a\nb\nc\n", "s1")))
        val edit = GitEdit(t)
        val file = edit.open(host, "tok", "f.txt").getOrThrow()
        val stat = edit.preview(file, "a\nB\nc\n")
        assertEquals(LineDiff.stat("a\nb\nc\n", "a\nB\nc\n"), stat)
        val u = edit.unified(file, "a\nB\nc\n")
        assertTrue(u, u.startsWith("--- a/f.txt\n+++ b/f.txt\n"))
        assertEquals(1, t.seen.size) // only the open() GET; preview/unified are local
    }

    @Test fun `commit PUTs the new content with the sha and returns the new commit sha`() = runTest {
        val t = FakeTransport(
            GitTransport.Resp(200, contentsBody("old\n", "blobsha")),
            GitTransport.Resp(200, JSONObject().put("commit", JSONObject().put("sha", "newcommit")).toString()),
        )
        val edit = GitEdit(t)
        val file = edit.open(host, "tok", "f.txt").getOrThrow()
        val sha = edit.commit(host, "tok", file, "new\n", "edit f").getOrThrow()
        assertEquals("newcommit", sha)

        val put = t.seen[1]
        assertEquals("PUT", put.method)
        assertTrue(put.url.endsWith("/repos/me/proj/contents/f.txt"))
        val body = JSONObject(put.body!!)
        assertEquals("edit f", body.getString("message"))
        assertEquals("blobsha", body.getString("sha"))          // threads the blob sha back
        assertEquals("work", body.getString("branch"))
        assertEquals("new\n", String(Base64.getDecoder().decode(body.getString("content"))))
    }

    @Test fun `commit refuses a no-op (never an empty commit)`() = runTest {
        val t = FakeTransport(GitTransport.Resp(200, contentsBody("same\n", "s")))
        val edit = GitEdit(t)
        val file = edit.open(host, "tok", "f.txt").getOrThrow()
        val res = edit.commit(host, "tok", file, "same\n", "noop")
        assertTrue(res.isFailure)
        assertEquals(1, t.seen.size) // no PUT was attempted
    }

    @Test fun `a non-2xx commit surfaces as a failure`() = runTest {
        val t = FakeTransport(
            GitTransport.Resp(200, contentsBody("old\n", "s")),
            GitTransport.Resp(409, """{"message":"sha mismatch"}"""),
        )
        val edit = GitEdit(t)
        val file = edit.open(host, "tok", "f.txt").getOrThrow()
        val res = edit.commit(host, "tok", file, "new\n", "m")
        assertTrue(res.isFailure)
        assertFalse(res.exceptionOrNull()?.message.isNullOrBlank())
    }
}
