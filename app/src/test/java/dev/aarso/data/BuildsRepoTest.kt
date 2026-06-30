package dev.aarso.data

import dev.aarso.domain.builds.Build
import dev.aarso.domain.builds.BuildSource
import dev.aarso.domain.builds.CheckConclusion
import dev.aarso.domain.builds.ChecksSummary
import dev.aarso.domain.git.GitHost
import dev.aarso.domain.git.GitHostKind
import dev.aarso.domain.git.GitRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-testable behaviour of [BuildsRepo].
 *
 * [BuildsRepo] accepts a `(String) -> String?` token provider so tests can pass a
 * plain lambda without touching Android's SharedPreferences or Keystore. Network
 * calls cannot run in CI (there is no host); those paths are owner-verified on
 * device.
 *
 * Coverage:
 *  - no-token guard: returns empty / NONE without calling the transport
 *  - findApkUrl: pure logic, no I/O
 *  - HTTP error path: gracefully returns empty
 *  - happy-path release parse: end-to-end via a stub transport
 */
class BuildsRepoTest {

    private val host = GitHost(
        id = "gh1", displayName = "My GitHub", kind = GitHostKind.GITHUB,
        baseUrl = "", owner = "acme", repo = "mobile-llm", branch = "main",
        authorName = "Dev", authorEmail = "dev@example.com",
    )

    // ---------------------------------------------------------------------------
    // Transport stubs
    // ---------------------------------------------------------------------------

    /** Fails the test immediately if any network call is attempted. */
    private class BombTransport : GitTransport() {
        override suspend fun execute(req: GitRequest): Resp =
            error("GitTransport.execute must not be called in this test scenario")
    }

    /** Always returns [code] + [body] regardless of the request. */
    private class ConstTransport(private val code: Int, private val body: String) : GitTransport() {
        override suspend fun execute(req: GitRequest): Resp = Resp(code, body)
    }

    /**
     * Returns [first] for the first call (releases endpoint) and [second] for all
     * subsequent calls (dist-branch listing). Index-based — no URL parsing in the stub.
     */
    private class TwoCallTransport(
        private val first: GitTransport.Resp,
        private val second: GitTransport.Resp,
    ) : GitTransport() {
        private var n = 0
        override suspend fun execute(req: GitRequest): Resp = if (n++ == 0) first else second
    }

    // ---------------------------------------------------------------------------
    // No-token guard
    // ---------------------------------------------------------------------------

    @Test fun `listBuilds returns empty list when no token is stored`() = runTest {
        val repo = BuildsRepo(BombTransport()) { null }
        assertTrue(repo.listBuilds(host).isEmpty())
    }

    @Test fun `checks returns NONE when no token is stored`() = runTest {
        val repo = BuildsRepo(BombTransport()) { null }
        val summary = repo.checks(host, sampleBuild())
        assertEquals(ChecksSummary.NONE, summary)
        assertEquals(CheckConclusion.NONE, summary.conclusion)
    }

    // ---------------------------------------------------------------------------
    // findApkUrl — pure logic
    // ---------------------------------------------------------------------------

    @Test fun `findApkUrl returns the URL when present`() {
        val url = "https://github.com/acme/mobile-llm/releases/download/v1.0/aarso-sd.apk"
        val repo = BuildsRepo(BombTransport()) { null }
        assertEquals(url, repo.findApkUrl(sampleBuild().copy(downloadUrl = url)))
    }

    @Test fun `findApkUrl returns null for a blank download URL`() {
        val repo = BuildsRepo(BombTransport()) { null }
        assertNull(repo.findApkUrl(sampleBuild().copy(downloadUrl = "")))
    }

    @Test fun `findApkUrl returns null for a whitespace-only URL`() {
        val repo = BuildsRepo(BombTransport()) { null }
        assertNull(repo.findApkUrl(sampleBuild().copy(downloadUrl = "   ")))
    }

    // ---------------------------------------------------------------------------
    // HTTP error path
    // ---------------------------------------------------------------------------

    @Test fun `listBuilds returns empty list when host returns 404`() = runTest {
        val repo = BuildsRepo(ConstTransport(404, """{"message":"Not Found"}""")) { "tok" }
        assertTrue(repo.listBuilds(host).isEmpty())
    }

    // ---------------------------------------------------------------------------
    // Happy-path release parse
    // ---------------------------------------------------------------------------

    @Test fun `listBuilds includes APK releases on HTTP 200`() = runTest {
        val releasesJson = """
            [
              {"tag_name":"v2.0","target_commitish":"main","published_at":"2026-06-17T00:00:00Z",
               "assets":[{"id":42,"name":"aarso-sd.apk",
                 "browser_download_url":"https://x/aarso-sd.apk","size":66060288}]}
            ]
        """.trimIndent()
        // Releases endpoint succeeds; dist-branch listing returns 404 (no dist branch).
        val repo = BuildsRepo(
            TwoCallTransport(
                first = GitTransport.Resp(200, releasesJson),
                second = GitTransport.Resp(404, ""),
            ),
        ) { "tok" }
        val builds = repo.listBuilds(host)
        assertEquals(1, builds.size)
        assertEquals("v2.0", builds[0].version)
        assertEquals(BuildSource.RELEASE_ASSET, builds[0].source)
    }

    @Test fun `listBuilds includes dist-branch APKs when no releases`() = runTest {
        val distJson = """
            [
              {"type":"file","name":"aarso-sd.apk","sha":"a1b2c3d4e5",
               "size":66060288,"download_url":"https://x/raw/aarso-sd.apk"}
            ]
        """.trimIndent()
        // Releases endpoint returns 404; dist-branch listing succeeds.
        val repo = BuildsRepo(
            TwoCallTransport(
                first = GitTransport.Resp(404, ""),
                second = GitTransport.Resp(200, distJson),
            ),
        ) { "tok" }
        val builds = repo.listBuilds(host)
        assertEquals(1, builds.size)
        assertEquals(BuildSource.DIST_BRANCH, builds[0].source)
        assertEquals("a1b2c3d", builds[0].version)
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun sampleBuild() = Build(
        id = "b1", version = "v1.0", name = "aarso-sd.apk", branch = "main",
        createdAt = "2026-06-17T00:00:00Z",
        downloadUrl = "https://example.com/aarso-sd.apk",
        sizeBytes = 66_060_288L,
        source = BuildSource.RELEASE_ASSET,
    )
}
