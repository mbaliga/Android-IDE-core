package dev.aarso.data

import dev.aarso.domain.git.GitHost
import dev.aarso.domain.git.GitHostKind
import dev.aarso.domain.git.GitRequest
import dev.aarso.domain.ide.ProjectScaffold
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScaffoldPublishRepoTest {

    private val host = GitHost(
        id = "gh1", displayName = "My GitHub", kind = GitHostKind.GITHUB,
        baseUrl = "", owner = "acme", repo = "x", branch = "main",
        authorName = "Dev", authorEmail = "dev@x.y",
    )
    private val spec = ProjectScaffold.AppSpec("Demo", "com.example.demo", idea = "x")
    private val fileCount = ProjectScaffold.generate(spec).size

    private class BombTransport : GitTransport() {
        override suspend fun execute(req: GitRequest): Resp = error("must not call without token")
    }

    /** Returns [code] for every call; counts how many times it was hit. */
    private class CountingTransport(private val code: Int) : GitTransport() {
        var calls = 0
        override suspend fun execute(req: GitRequest): Resp { calls++; return Resp(code, "{}") }
    }

    /** 2xx for the first call (create repo), [rest] thereafter (the pushes). */
    private class CreateThenTransport(private val rest: Int) : GitTransport() {
        private var n = 0
        override suspend fun execute(req: GitRequest): Resp =
            if (n++ == 0) Resp(201, "{}") else Resp(rest, "{}")
    }

    @Test fun `no token short-circuits before any network`() = runTest {
        val repo = ScaffoldPublishRepo(BombTransport()) { null }
        val r = repo.publish(host, spec, "demo")
        assertFalse(r.ok)
        assertEquals(0, r.pushed)
    }

    @Test fun `invalid spec fails before touching the host`() = runTest {
        val repo = ScaffoldPublishRepo(BombTransport()) { "tok" }
        val r = repo.publish(host, spec.copy(packageId = "nope"), "demo")
        assertFalse(r.ok)
        assertFalse(r.createdRepo)
    }

    @Test fun `happy path creates the repo then pushes every file`() = runTest {
        val transport = CountingTransport(201)
        val repo = ScaffoldPublishRepo(transport) { "tok" }
        val r = repo.publish(host, spec, "demo")
        assertTrue(r.ok)
        assertTrue(r.createdRepo)
        assertEquals(fileCount, r.pushed)
        assertEquals(fileCount, r.total)
        assertEquals(fileCount + 1, transport.calls) // create repo + one per file
    }

    @Test fun `create-repo failure stops before any push`() = runTest {
        val repo = ScaffoldPublishRepo(CountingTransport(422)) { "tok" }
        val r = repo.publish(host, spec, "demo")
        assertFalse(r.ok)
        assertFalse(r.createdRepo)
        assertEquals(0, r.pushed)
    }

    @Test fun `a push failure is reported with how far it got`() = runTest {
        // Repo creates (201) but every push 500s → stops at the first push.
        val repo = ScaffoldPublishRepo(CreateThenTransport(rest = 500)) { "tok" }
        val r = repo.publish(host, spec, "demo")
        assertFalse(r.ok)
        assertTrue(r.createdRepo)
        assertEquals(0, r.pushed)
    }
}
