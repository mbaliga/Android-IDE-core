package dev.fonebrew.domain.git

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Request-shape and parser tests for the publish-side git APIs. Fixtures under
 * `git-contract/` are HAND-AUTHORED from public docs, NOT recorded from a live host.
 */
class GitPublishApiTest {

    private val SECRET = "ghp_SUPERSECRETTOKEN123"
    private val SHA = "aa218f56b14c9653891f9e74264a383fa43fefbd"
    private val gh = GitHost(
        id = "h", displayName = "proj", kind = GitHostKind.GITHUB, baseUrl = "",
        owner = "me", repo = "proj", branch = "main", authorName = "Me", authorEmail = "me@example.com",
    )
    private val gitea = gh.copy(kind = GitHostKind.GITEA, baseUrl = "https://git.example.org/")

    private fun fx(name: String) =
        javaClass.classLoader!!.getResourceAsStream("git-contract/$name")!!.readBytes().toString(Charsets.UTF_8)

    private fun ready(b: GitBuild): GitRequest = (b as GitBuild.Ready).request
    private fun <T> ok(r: GitApiResult<T>): T = (r as GitApiResult.Ok).value
    private fun err(r: GitApiResult<*>): GitApiError = (r as GitApiResult.Err).error

    // ---- branch ----

    @Test fun `createBranch GitHub posts git refs with full ref and sha`() {
        val r = ready(GitBranchApi.createBranch(gh, "fonebrew/agent/x-ab12", SHA, SECRET))
        assertEquals("POST", r.method)
        assertEquals("https://api.github.com/repos/me/proj/git/refs", r.url)
        assertEquals("Bearer $SECRET", r.headers["Authorization"])
        assertEquals("application/vnd.github+json", r.headers["Accept"])
        assertEquals("application/json", r.headers["Content-Type"])
        val b = JSONObject(r.body!!)
        assertEquals("refs/heads/fonebrew/agent/x-ab12", b.getString("ref"))
        assertEquals(SHA, b.getString("sha"))
    }

    @Test fun `createBranch Gitea uses branches endpoint`() {
        val r = ready(GitBranchApi.createBranch(gitea, "feat", SHA, SECRET))
        assertEquals("https://git.example.org/api/v1/repos/me/proj/branches", r.url)
        assertEquals("token $SECRET", r.headers["Authorization"])
        val b = JSONObject(r.body!!)
        assertEquals("feat", b.getString("new_branch_name"))
        assertEquals(SHA, b.getString("old_ref_name"))
    }

    @Test fun `createBranch rejects bad name and bad sha without echoing token`() {
        val bad = GitBranchApi.createBranch(gh, "a b", SHA, SECRET) as GitBuild.Invalid
        assertFalse(bad.toString().contains(SECRET))
        assertTrue(GitBranchApi.createBranch(gh, "ok", "abc", SECRET) is GitBuild.Invalid)
    }

    @Test fun `validate edge cases`() {
        for (good in listOf("main", "fonebrew/agent/x-ab12", "a.b", "feat/1.2", "a@b", "ünï")) assertNull(good, GitBranchApi.validate(good))
        val bad = listOf(
            "", "a b", "a..b", "-x", "/x", "x/", "x.", "a//b", "x.lock", "a/x.lock/b", "a/.hid", ".x", "@", "a@{b",
            "a~b", "a^b", "a:b", "a?b", "a*b", "a[b", "a\\b", "a\tb", "a\u007fb", "x".repeat(201),
        )
        for (b in bad) assertNotNull("should reject '$b'", GitBranchApi.validate(b))
        assertNull(GitBranchApi.validate("x".repeat(200)))
    }

    @Test fun `suggestName is deterministic sanitised and valid`() {
        val n = GitBranchApi.suggestName("Fix the Login Bug!!  (v2)", "AB12-cd")
        assertEquals("fonebrew/agent/fix-the-login-bug-v2-ab12cd", n)
        assertEquals(n, GitBranchApi.suggestName("Fix the Login Bug!!  (v2)", "AB12-cd"))
        assertEquals("fonebrew/agent/change-0", GitBranchApi.suggestName("!!!", "---"))
        val long = GitBranchApi.suggestName("x".repeat(500) + "..", "123456789012")
        assertNull(GitBranchApi.validate(long))
        assertTrue(long.endsWith("-12345678"))
        assertNull(GitBranchApi.validate(GitBranchApi.suggestName("../../etc/passwd .lock", "id")))
    }

    @Test fun `branch created parsers`() {
        assertEquals("fonebrew/agent/x-ab12", ok(GitBranchApi.parseCreated(gh, 201, fx("github-ref-created.json"))))
        assertEquals("fonebrew/agent/x-ab12", ok(GitBranchApi.parseCreated(gitea, 201, fx("gitea-branch.json"))))
        assertEquals(GitApiErrorKind.MALFORMED, err(GitBranchApi.parseCreated(gh, 201, "{}")).kind)
        assertEquals(GitApiErrorKind.MALFORMED, err(GitBranchApi.parseCreated(gh, 201, "")).kind)
        assertEquals(GitApiErrorKind.CONFLICT, err(GitBranchApi.parseCreated(gitea, 409, """{"message":"exists"}""")).kind)
        assertEquals(GitApiErrorKind.UNPROCESSABLE, err(GitBranchApi.parseCreated(gh, 422, """{"message":"Reference already exists"}""")).kind)
    }

    // ---- pull request ----

    @Test fun `create PR GitHub is draft by default`() {
        val r = ready(GitPullRequestApi.create(gh, "T", "B", "feat", "main", SECRET))
        assertEquals("POST", r.method)
        assertEquals("https://api.github.com/repos/me/proj/pulls", r.url)
        val b = JSONObject(r.body!!)
        assertEquals("T", b.getString("title")); assertEquals("B", b.getString("body"))
        assertEquals("feat", b.getString("head")); assertEquals("main", b.getString("base"))
        assertTrue(b.getBoolean("draft"))
        val nd = JSONObject(ready(GitPullRequestApi.create(gh, "T", "B", "feat", "main", SECRET, draft = false)).body!!)
        assertFalse(nd.getBoolean("draft"))
    }

    @Test fun `create PR Gitea has no draft field and draft request is Unsupported`() {
        val u = GitPullRequestApi.create(gitea, "T", "B", "feat", "main", SECRET) as GitBuild.Unsupported
        assertEquals("pull request draft flag", u.capability)
        assertTrue(u.message.contains("Gitea"))
        val r = ready(GitPullRequestApi.create(gitea, "T", "B", "feat", "main", SECRET, draft = false))
        assertEquals("https://git.example.org/api/v1/repos/me/proj/pulls", r.url)
        assertFalse(JSONObject(r.body!!).has("draft"))
    }

    @Test fun `create and get PR validate input`() {
        assertTrue(GitPullRequestApi.create(gh, " ", "B", "feat", "main", SECRET) is GitBuild.Invalid)
        assertTrue(GitPullRequestApi.create(gh, "T", "B", "a..b", "main", SECRET) is GitBuild.Invalid)
        assertTrue(GitPullRequestApi.get(gh, 0, SECRET) is GitBuild.Invalid)
        val g = ready(GitPullRequestApi.get(gh, 42, SECRET))
        assertEquals("GET", g.method); assertNull(g.body)
        assertEquals("https://api.github.com/repos/me/proj/pulls/42", g.url)
    }

    @Test fun `PR parsers`() {
        val pr = ok(GitPullRequestApi.parseCreated(201, fx("github-pr.json")))
        assertEquals(42, pr.number); assertEquals("open", pr.state); assertTrue(pr.draft); assertFalse(pr.merged)
        assertEquals("https://github.com/me/proj/pull/42", pr.htmlUrl)
        assertEquals(3, ok(GitPullRequestApi.parseFetched(200, fx("gitea-pr.json"))).number)
        assertEquals(GitApiErrorKind.MALFORMED, err(GitPullRequestApi.parseCreated(201, """{"number":1}""")).kind)
        assertEquals(GitApiErrorKind.MALFORMED, err(GitPullRequestApi.parseCreated(201, "<html>")).kind)
        assertEquals(GitApiErrorKind.MALFORMED, err(GitPullRequestApi.parseFetched(200, "")).kind)
    }

    @Test fun `PR body is honest about engine and cloud and has no links`() {
        val local = GitPullRequestApi.body("Qwen3 8B (llama.cpp)", cloud = false, summary = "Fix NPE")
        assertTrue(local.contains("proposed by an AI model"))
        assertTrue(local.contains("Qwen3 8B (llama.cpp)"))
        assertTrue(local.contains("on-device"))
        assertTrue(local.contains("## Summary") && local.contains("Fix NPE"))
        val cloud = GitPullRequestApi.body("claude-x", cloud = true)
        assertTrue(cloud.contains("cloud engine") && cloud.contains("watched object"))
        assertFalse(cloud.contains("## Summary"))
        for (b in listOf(local, cloud)) assertFalse(b.contains("http"))
        assertTrue(GitPullRequestApi.body("  ", false).contains("unnamed engine"))
    }

    // ---- actions ----

    @Test fun `listRuns shapes per host and requires a filter`() {
        val g = ready(GitActionsApi.listRuns(gh, "fonebrew/agent/x", SHA, SECRET, limit = 5))
        assertEquals("GET", g.method)
        assertEquals("https://api.github.com/repos/me/proj/actions/runs?branch=fonebrew%2Fagent%2Fx&head_sha=$SHA&per_page=5", g.url)
        val t = ready(GitActionsApi.listRuns(gitea, "feat", null, SECRET))
        assertEquals("https://git.example.org/api/v1/repos/me/proj/actions/runs?ref=refs%2Fheads%2Ffeat&limit=10", t.url)
        assertTrue(GitActionsApi.listRuns(gh, null, " ", SECRET) is GitBuild.Invalid)
        assertTrue(GitActionsApi.listRuns(gh, null, "zz!", SECRET) is GitBuild.Invalid)
        assertTrue(ready(GitActionsApi.listRuns(gh, "b", null, SECRET, limit = 9999)).url.endsWith("per_page=100"))
    }

    @Test fun `checkRuns and combinedStatus shapes and Gitea support matrix`() {
        assertEquals(
            "https://api.github.com/repos/me/proj/commits/$SHA/check-runs?per_page=100",
            ready(GitActionsApi.checkRuns(gh, SHA, SECRET)).url,
        )
        val u = GitActionsApi.checkRuns(gitea, SHA, SECRET) as GitBuild.Unsupported
        assertEquals("check runs", u.capability)
        assertEquals("https://api.github.com/repos/me/proj/commits/$SHA/status?per_page=100", ready(GitActionsApi.combinedStatus(gh, SHA, SECRET)).url)
        assertEquals("https://git.example.org/api/v1/repos/me/proj/commits/$SHA/status?limit=100", ready(GitActionsApi.combinedStatus(gitea, SHA, SECRET)).url)
        assertTrue(GitActionsApi.combinedStatus(gh, "../x", SECRET) is GitBuild.Invalid)
    }

    @Test fun `dispatch request shape`() {
        val r = ready(GitActionsApi.dispatch(gh, "ci.yml", "feat", SECRET, mapOf("k" to "v")))
        assertEquals("POST", r.method)
        assertEquals("https://api.github.com/repos/me/proj/actions/workflows/ci.yml/dispatches", r.url)
        val b = JSONObject(r.body!!)
        assertEquals("feat", b.getString("ref")); assertEquals("v", b.getJSONObject("inputs").getString("k"))
        assertFalse(JSONObject(ready(GitActionsApi.dispatch(gh, "ci.yml", "feat", SECRET)).body!!).has("inputs"))
        assertEquals(
            "https://git.example.org/api/v1/repos/me/proj/actions/workflows/ci.yml/dispatches",
            ready(GitActionsApi.dispatch(gitea, "ci.yml", "feat", SECRET)).url,
        )
        assertTrue(GitActionsApi.dispatch(gh, "a/b.yml", "feat", SECRET) is GitBuild.Invalid)
        assertTrue(GitActionsApi.dispatch(gh, "", "feat", SECRET) is GitBuild.Invalid)
        assertTrue(GitActionsApi.dispatch(gh, "ci.yml", "a b", SECRET) is GitBuild.Invalid)
        assertTrue(GitActionsApi.dispatch(gh, "ci.yml", "f", SECRET, (1..26).associate { "k$it" to "v" }) is GitBuild.Invalid)
    }

    @Test fun `dispatch parser accepts 200 201 204 and maps errors`() {
        for (s in listOf(200, 201, 204)) assertTrue(GitActionsApi.parseDispatch(s, "") is GitApiResult.Ok)
        assertEquals(GitApiErrorKind.NOT_FOUND, err(GitActionsApi.parseDispatch(404, "{}")).kind)
        assertEquals(GitApiErrorKind.UNPROCESSABLE, err(GitActionsApi.parseDispatch(422, """{"message":"Workflow does not have 'workflow_dispatch' trigger"}""")).kind)
    }

    @Test fun `run parsers for both hosts`() {
        val g = ok(GitActionsApi.parseRuns(gh, 200, fx("github-workflow-runs.json"))).single()
        assertEquals(30433642L, g.id); assertEquals("CI", g.name); assertEquals("success", g.conclusion); assertEquals(SHA, g.headSha)
        val t = ok(GitActionsApi.parseRuns(gitea, 200, fx("gitea-runs.json"))).single()
        assertEquals("CI run", t.name); assertEquals("success", t.status); assertEquals("", t.conclusion); assertEquals(SHA, t.headSha)
        assertTrue(ok(GitActionsApi.parseRuns(gh, 200, """{"total_count":0,"workflow_runs":[]}""")).isEmpty())
        assertEquals(GitApiErrorKind.MALFORMED, err(GitActionsApi.parseRuns(gh, 200, "{}")).kind)
        assertEquals(GitApiErrorKind.MALFORMED, err(GitActionsApi.parseRuns(gh, 200, "")).kind)
    }

    // ---- CiState ----

    @Test fun `mixed checks and statuses fold to FAILING with names`() {
        val s = GitActionsApi.ciState(
            ok(GitActionsApi.parseCheckRuns(200, fx("github-check-runs.json"))),
            ok(GitActionsApi.parseCombinedStatus(200, fx("github-status.json"))),
        )
        assertEquals(CiLevel.FAILING, s.level)
        assertEquals(6, s.total); assertEquals(3, s.passing); assertEquals(1, s.pending); assertEquals(2, s.failing)
        assertEquals(listOf("unit-tests", "ci/security"), s.failingNames)
        assertEquals("✗", s.glyph); assertEquals("CI failing", s.label)
        assertTrue(s.describe().startsWith("✗ CI failing"))
    }

    @Test fun `no results is NONE not PASSING even if GitHub says pending with zero statuses`() {
        val empty = ok(GitActionsApi.parseCombinedStatus(200, fx("github-status-empty.json")))
        assertEquals(CiLevel.NONE, GitActionsApi.ciState(CheckRunsPage0, empty).level)
        assertEquals(CiLevel.NONE, GitActionsApi.ciState(null, null).level)
        assertEquals(CiState.NONE, GitActionsApi.ciState(null, null))
    }

    private val CheckRunsPage0 = GitActionsApi.CheckRunsPage(0, emptyList())

    @Test fun `all passing is PASSING, pending and unknown never pass`() {
        fun c(st: String, co: String) = GitActionsApi.CheckRun("n", st, co)
        fun page(vararg r: GitActionsApi.CheckRun) = GitActionsApi.CheckRunsPage(r.size, r.toList())
        assertEquals(CiLevel.PASSING, GitActionsApi.ciState(page(c("completed", "success"), c("completed", "neutral")), null).level)
        assertEquals(CiLevel.PENDING, GitActionsApi.ciState(page(c("completed", "success"), c("queued", "")), null).level)
        assertEquals(CiLevel.PENDING, GitActionsApi.ciState(page(c("completed", "brand_new_value")), null).level)
        assertEquals(CiLevel.FAILING, GitActionsApi.ciState(page(c("completed", "cancelled")), null).level)
        assertEquals(CiLevel.FAILING, GitActionsApi.ciState(page(c("queued", ""), c("completed", "timed_out")), null).level)
    }

    @Test fun `truncated results cannot read as PASSING`() {
        val p = GitActionsApi.CheckRunsPage(250, List(100) { GitActionsApi.CheckRun("n$it", "completed", "success") })
        val s = GitActionsApi.ciState(p, null)
        assertEquals(CiLevel.PENDING, s.level); assertTrue(s.truncated); assertTrue(s.describe().contains("partial"))
    }

    @Test fun `Gitea status uses status field and folds`() {
        val s = GitActionsApi.ciState(null, ok(GitActionsApi.parseCombinedStatus(200, fx("gitea-status.json"))))
        assertEquals(CiLevel.PASSING, s.level)
        val w = GitActionsApi.ciState(null, GitActionsApi.StatusPage(1, listOf(GitActionsApi.CommitStatus("x", "warning"))))
        assertEquals(CiLevel.PENDING, w.level)
    }

    @Test fun `every level has distinct glyph and label`() {
        val levels = CiLevel.values()
        assertEquals(4, levels.map { it.glyph }.toSet().size)
        assertEquals(4, levels.map { it.label }.toSet().size)
        for (l in levels) { assertTrue(l.glyph.isNotBlank()); assertTrue(l.label.isNotBlank()) }
        assertEquals("○", CiLevel.NONE.glyph); assertEquals("◐", CiLevel.PENDING.glyph); assertEquals("✓", CiLevel.PASSING.glyph)
    }

    @Test fun `check and status parsers reject bad bodies`() {
        assertEquals(GitApiErrorKind.MALFORMED, err(GitActionsApi.parseCheckRuns(200, "{}")).kind)
        assertEquals(GitApiErrorKind.MALFORMED, err(GitActionsApi.parseCheckRuns(200, "[1")).kind)
        assertEquals(GitApiErrorKind.MALFORMED, err(GitActionsApi.parseCombinedStatus(200, "")).kind)
    }

    // ---- error mapping + token hygiene ----

    @Test fun `status codes map to typed errors`() {
        fun k(s: Int, b: String = "{}") = GitPublishSupport.error(s, b).kind
        assertEquals(GitApiErrorKind.UNAUTHORIZED, k(401))
        assertEquals(GitApiErrorKind.FORBIDDEN, k(403))
        assertEquals(GitApiErrorKind.RATE_LIMITED, k(403, """{"message":"API rate limit exceeded"}"""))
        assertEquals(GitApiErrorKind.RATE_LIMITED, k(429))
        assertEquals(GitApiErrorKind.NOT_FOUND, k(404))
        assertEquals(GitApiErrorKind.CONFLICT, k(409))
        assertEquals(GitApiErrorKind.UNPROCESSABLE, k(422))
        assertEquals(GitApiErrorKind.SERVER, k(502))
        assertEquals(GitApiErrorKind.OTHER, k(418))
        assertEquals("", GitPublishSupport.error(500, "not json").message)
        assertEquals(200, GitPublishSupport.error(404, """{"message":"${"m".repeat(500)}"}""").message.length)
    }

    @Test fun `token never appears in any toString or error value`() {
        val builds = listOf(
            GitBranchApi.createBranch(gh, "feat", SHA, SECRET),
            GitBranchApi.createBranch(gh, "a b", SHA, SECRET),
            GitBranchApi.createBranch(gitea, "feat", SHA, SECRET),
            GitPullRequestApi.create(gh, "T", "B", "feat", "main", SECRET),
            GitPullRequestApi.create(gitea, "T", "B", "feat", "main", SECRET),
            GitPullRequestApi.get(gh, 1, SECRET),
            GitActionsApi.listRuns(gh, "b", null, SECRET),
            GitActionsApi.checkRuns(gh, SHA, SECRET),
            GitActionsApi.checkRuns(gitea, SHA, SECRET),
            GitActionsApi.combinedStatus(gh, SHA, SECRET),
            GitActionsApi.dispatch(gh, "ci.yml", "main", SECRET),
        )
        for (b in builds) assertFalse(b.toString(), b.toString().contains(SECRET))
        // The Authorization header itself is needed by the transport, but never printed by toString.
        val req = ready(builds[0])
        assertTrue(req.headers.getValue("Authorization").contains(SECRET))
        assertFalse(req.toString().contains(SECRET))
        // Parsers never receive the token; their errors cannot contain it.
        for (e in listOf(401, 403, 404, 422)) assertFalse(GitPublishSupport.error(e, """{"message":"Bad credentials"}""").toString().contains(SECRET))
    }
}
