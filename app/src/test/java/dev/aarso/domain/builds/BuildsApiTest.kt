package dev.aarso.domain.builds

import dev.aarso.domain.git.GitHost
import dev.aarso.domain.git.GitHostKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildsApiTest {

    private val github = GitHost(
        id = "gh", displayName = "GitHub", kind = GitHostKind.GITHUB, baseUrl = "",
        owner = "acme", repo = "mobile-llm", branch = "main",
        authorName = "x", authorEmail = "x@y.z",
    )

    @Test fun `release request targets the host releases endpoint`() {
        val r = BuildsApi.listReleases(github, "tok")
        assertEquals("GET", r.method)
        assertEquals("https://api.github.com/repos/acme/mobile-llm/releases", r.url)
        assertEquals("Bearer tok", r.headers["Authorization"])
    }

    @Test fun `checks request uses check-runs on GitHub and status on Gitea`() {
        assertTrue(BuildsApi.checks(github, "abc123", "t").url.endsWith("/commits/abc123/check-runs"))
        val gitea = github.copy(kind = GitHostKind.GITEA, baseUrl = "https://gitea.example.com")
        val g = BuildsApi.checks(gitea, "abc123", "t")
        assertTrue(g.url.startsWith("https://gitea.example.com/api/v1/"))
        assertTrue(g.url.endsWith("/commits/abc123/status"))
        assertEquals("token t", g.headers["Authorization"])
    }

    @Test fun `parses apk assets out of releases and ignores non-apk`() {
        val json = """
            [
              {"tag_name":"v2.0","target_commitish":"main","published_at":"2026-06-17T00:00:00Z",
               "assets":[
                 {"id":42,"name":"aarso-sd.apk","browser_download_url":"https://x/aarso-sd.apk","size":66060288},
                 {"id":43,"name":"mapping.txt","browser_download_url":"https://x/mapping.txt","size":1024}
               ]},
              {"tag_name":"v1.9","target_commitish":"main","published_at":"2026-06-10T00:00:00Z",
               "assets":[{"id":40,"name":"aarso-sd.apk","browser_download_url":"https://x/old.apk","size":65000000}]}
            ]
        """.trimIndent()
        val builds = BuildsApi.parseReleases(json)
        assertEquals(2, builds.size) // the .txt asset is skipped
        assertEquals("v2.0", builds[0].version)
        assertEquals("main", builds[0].branch)
        assertEquals("aarso-sd.apk", builds[0].name)
        assertEquals(66060288L, builds[0].sizeBytes)
        assertEquals(BuildSource.RELEASE_ASSET, builds[0].source)
    }

    @Test fun `parses apks from a dist-branch contents listing`() {
        val contents = """
            [
              {"type":"file","name":"aarso-sd.apk","sha":"a1b2c3d4e5","size":66060288,"download_url":"https://x/raw/aarso-sd.apk"},
              {"type":"file","name":"README.md","sha":"f00","size":12,"download_url":"https://x/raw/README.md"},
              {"type":"dir","name":"sub","sha":"d00","size":0,"download_url":""}
            ]
        """.trimIndent()
        val builds = BuildsApi.parseDistBranchApks(contents, "apk-dist")
        assertEquals(1, builds.size)
        assertEquals("apk-dist", builds[0].branch)
        assertEquals("a1b2c3d", builds[0].version) // short sha
        assertEquals(BuildSource.DIST_BRANCH, builds[0].source)
    }

    @Test fun `github check-runs summarise to a verdict`() {
        val green = """{"check_runs":[{"status":"completed","conclusion":"success"},{"status":"completed","conclusion":"success"}]}"""
        assertEquals(CheckConclusion.SUCCESS, BuildsApi.parseChecks(green, GitHostKind.GITHUB).conclusion)

        val failing = """{"check_runs":[{"status":"completed","conclusion":"success"},{"status":"completed","conclusion":"failure"}]}"""
        val f = BuildsApi.parseChecks(failing, GitHostKind.GITHUB)
        assertEquals(CheckConclusion.FAILURE, f.conclusion)
        assertEquals(1, f.failed)

        val running = """{"check_runs":[{"status":"in_progress","conclusion":""}]}"""
        assertEquals(CheckConclusion.PENDING, BuildsApi.parseChecks(running, GitHostKind.GITHUB).conclusion)

        assertEquals(CheckConclusion.NONE, BuildsApi.parseChecks("""{"check_runs":[]}""", GitHostKind.GITHUB).conclusion)
    }

    @Test fun `gitea combined status maps to a verdict`() {
        val s = """{"state":"success","statuses":[{"status":"success"},{"status":"success"}]}"""
        val summary = BuildsApi.parseChecks(s, GitHostKind.GITEA)
        assertEquals(CheckConclusion.SUCCESS, summary.conclusion)
        assertEquals(2, summary.passed)
        assertEquals(CheckConclusion.FAILURE, BuildsApi.parseChecks("""{"state":"failure","statuses":[{}]}""", GitHostKind.GITEA).conclusion)
    }
}
