package dev.fonebrew.data

import dev.fonebrew.domain.agent.DispatchPlanner
import dev.fonebrew.domain.agent.EngineBadge
import dev.fonebrew.domain.agent.PublishChoice
import dev.fonebrew.domain.agent.PublishPlan
import dev.fonebrew.domain.agent.PublishPlanner
import dev.fonebrew.domain.diff.ChangeSet
import dev.fonebrew.domain.diff.FileChange
import dev.fonebrew.domain.git.CiLevel
import dev.fonebrew.domain.git.GitHost
import dev.fonebrew.domain.git.GitHostKind
import dev.fonebrew.domain.git.GitRequest
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Publish side of the multi-step agent, driven with a fake transport (no real host). */
class AgentPublisherTest {

    private class FakeTransport(private val respond: (GitRequest) -> GitTransport.Resp) : GitTransport() {
        val seen = mutableListOf<GitRequest>()
        override suspend fun execute(req: GitRequest): GitTransport.Resp { seen += req; return respond(req) }
    }

    private val sha = "a".repeat(40)
    private val token = "SECRET-TOKEN-123"
    private fun host(kind: GitHostKind = GitHostKind.GITHUB) = GitHost(
        "h1", "mine", kind, if (kind == GitHostKind.GITEA) "https://g.example" else "", "me", "proj", "work", "Me", "me@example.com",
    )
    private val approved = ChangeSet(listOf(FileChange("a.txt", "a", "b"), FileChange("gone.txt", "bye", "")))
    private val engine = EngineBadge("Claude X", cloud = true)
    private fun ok(o: JSONObject, code: Int = 200) = GitTransport.Resp(code, o.toString())

    private fun flow(req: GitRequest, prStatus: Int = 201, treeStatus: Int = 200): GitTransport.Resp = when {
        req.method == "GET" && req.url.contains("/git/ref") -> ok(JSONObject().put("object", JSONObject().put("sha", sha)))
        req.method == "POST" && (req.url.endsWith("/git/refs") || req.url.endsWith("/branches")) ->
            ok(JSONObject().put("ref", "refs/heads/fonebrew/agent/x-1").put("name", "fonebrew/agent/x-1"), 201)
        req.method == "GET" && req.url.contains("/git/commits/") -> ok(JSONObject().put("tree", JSONObject().put("sha", "tree1")))
        req.method == "POST" && req.url.endsWith("/git/trees") -> if (treeStatus == 200) ok(JSONObject().put("sha", "tree2")) else GitTransport.Resp(treeStatus, "{}")
        req.method == "POST" && req.url.endsWith("/git/commits") -> ok(JSONObject().put("sha", "commit1"))
        req.method == "POST" && req.url.endsWith("/pulls") ->
            if (prStatus == 201) ok(JSONObject().put("number", 7).put("html_url", "https://h/pr/7").put("state", "open").put("draft", true), 201)
            else GitTransport.Resp(prStatus, """{"message":"Validation Failed"}""")
        else -> GitTransport.Resp(200, "{}")
    }

    private fun plan(choice: PublishChoice, h: GitHost = host()): PublishPlan =
        (PublishPlanner.plan(choice, approved, h) as PublishPlanner.PlanResult.Ready).plan

    @Test fun `direct publish makes one commit on the host branch and opens nothing else`() = runTest {
        val t = FakeTransport(::flow)
        val out = AgentPublisher(t).publish(host(), token, plan(PublishChoice.Direct), "Agent: x", engine, null)
        assertEquals("work", out.branch)
        assertEquals(listOf("commit1"), out.commitIds)
        assertNull(out.pullRequest)
        assertTrue(t.seen.none { it.url.endsWith("/pulls") || it.url.endsWith("/git/refs") || it.url.endsWith("/branches") })
        val tree = JSONObject(t.seen.first { it.url.endsWith("/git/trees") }.body!!).getJSONArray("tree")
        assertEquals(2, tree.length()) // the approved delete is carried too
    }

    @Test fun `branch plus PR creates the branch, commits onto it, then opens a draft PR with an honest body`() = runTest {
        val t = FakeTransport(::flow)
        val p = plan(PublishChoice.BranchAndPr("fonebrew/agent/x-1"))
        val out = AgentPublisher(t).publish(host(), token, p, "Agent: fix", engine, "did a thing")
        assertEquals("fonebrew/agent/x-1", out.branch)
        assertEquals(7, out.pullRequest!!.number)
        assertNull(out.pullRequestProblem)
        val order = t.seen.map { it.method + " " + it.url.substringAfter("/repos/me/proj/") }
        val branchAt = order.indexOfFirst { it == "POST git/refs" }
        val patchAt = order.indexOfFirst { it.startsWith("PATCH git/refs/heads/") }
        val prAt = order.indexOfFirst { it == "POST pulls" }
        assertTrue("branch before commit before PR: $order", branchAt in 0 until patchAt && patchAt < prAt)
        val create = JSONObject(t.seen[branchAt].body!!)
        assertEquals("refs/heads/fonebrew/agent/x-1", create.getString("ref"))
        assertEquals(sha, create.getString("sha"))
        // the commit moved the NEW branch, never the base
        assertTrue(t.seen[patchAt].url.contains("fonebrew%2Fagent%2Fx-1"))
        assertFalse(t.seen.any { it.method == "PATCH" && it.url.endsWith("heads/work") })
        val pr = JSONObject(t.seen[prAt].body!!)
        assertTrue(pr.getBoolean("draft"))
        assertEquals("fonebrew/agent/x-1", pr.getString("head"))
        assertEquals("work", pr.getString("base"))
        assertTrue(pr.getString("body").contains("Claude X"))
        assertTrue(pr.getString("body").contains("watched object"))
        assertTrue(pr.getString("body").contains("did a thing"))
    }

    @Test fun `gitea branch plus PR uses the branches endpoint and a non-draft PR`() = runTest {
        val t = FakeTransport(::flow)
        val h = host(GitHostKind.GITEA)
        val out = AgentPublisher(t).publish(h, token, plan(PublishChoice.BranchAndPr("fonebrew/agent/x-1"), h), "m", EngineBadge("Local", false), null)
        assertTrue(t.seen.any { it.method == "POST" && it.url.endsWith("/branches") })
        assertFalse(JSONObject(t.seen.first { it.url.endsWith("/pulls") }.body!!).has("draft"))
        assertTrue(JSONObject(t.seen.first { it.url.endsWith("/pulls") }.body!!).getString("body").contains("on-device engine"))
        assertEquals(7, out.pullRequest!!.number)
    }

    @Test fun `a commit failure after the branch was made says the branch exists and opens no PR`() = runTest {
        val t = FakeTransport { flow(it, treeStatus = 500) }
        try {
            AgentPublisher(t).publish(host(), token, plan(PublishChoice.BranchAndPr("fonebrew/agent/x-1")), "m", engine, null)
            fail("expected failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("was created"))
            assertTrue(e.message!!.contains("left in place"))
            assertFalse(e.message!!.contains(token))
        }
        assertTrue(t.seen.none { it.url.endsWith("/pulls") })
    }

    @Test fun `a PR failure after the commit reports the branch and commit that exist, without the token`() = runTest {
        val t = FakeTransport { flow(it, prStatus = 422) }
        val out = AgentPublisher(t).publish(host(), token, plan(PublishChoice.BranchAndPr("fonebrew/agent/x-1")), "m", engine, null)
        assertNull(out.pullRequest)
        assertEquals(listOf("commit1"), out.commitIds)
        assertTrue(out.pullRequestProblem!!.contains("exist, but the pull request was not opened"))
        assertFalse(out.pullRequestProblem!!.contains(token))
    }

    @Test fun `a refused branch creation stops before any commit`() = runTest {
        val t = FakeTransport { if (it.method == "POST" && it.url.endsWith("/git/refs")) GitTransport.Resp(422, """{"message":"Reference already exists"}""") else flow(it) }
        try {
            AgentPublisher(t).publish(host(), token, plan(PublishChoice.BranchAndPr("fonebrew/agent/x-1")), "m", engine, null)
            fail("expected failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("could not create branch"))
        }
        assertTrue(t.seen.none { it.url.endsWith("/git/trees") || it.url.endsWith("/pulls") })
    }

    private fun checksJson(vararg runs: Pair<String, String>) = JSONObject().put("total_count", runs.size).put(
        "check_runs", JSONArray(runs.map { JSONObject().put("name", it.first).put("status", "completed").put("conclusion", it.second) }),
    ).toString()

    @Test fun `check CI is exactly one tap - two GETs on GitHub, one on Gitea - and folds to the CiState`() = runTest {
        val t = FakeTransport { r ->
            when {
                r.url.contains("/check-runs") -> GitTransport.Resp(200, checksJson("build" to "success", "lint" to "failure"))
                r.url.contains("/status") -> GitTransport.Resp(200, """{"total_count":0,"statuses":[]}""")
                else -> GitTransport.Resp(404, "{}")
            }
        }
        val s = AgentPublisher(t).checkCi(host(), token, sha)
        assertEquals(CiLevel.FAILING, s.level)
        assertEquals(listOf("lint"), s.failingNames)
        assertEquals(2, t.seen.size)
        assertTrue(t.seen.all { it.method == "GET" })

        val t2 = FakeTransport { GitTransport.Resp(200, """{"total_count":1,"statuses":[{"context":"ci","status":"success"}]}""") }
        val g = AgentPublisher(t2).checkCi(host(GitHostKind.GITEA), token, sha)
        assertEquals(CiLevel.PASSING, g.level)
        assertEquals(1, t2.seen.size)
    }

    @Test fun `check CI with nothing reported is NONE, not passing`() = runTest {
        val t = FakeTransport { if (it.url.contains("/check-runs")) GitTransport.Resp(200, checksJson()) else GitTransport.Resp(200, """{"total_count":0,"statuses":[]}""") }
        assertEquals(CiLevel.NONE, AgentPublisher(t).checkCi(host(), token, sha).level)
    }

    @Test fun `check CI failure is an error carrying no token`() = runTest {
        val t = FakeTransport { GitTransport.Resp(403, """{"message":"Resource not accessible"}""") }
        try { AgentPublisher(t).checkCi(host(), token, sha); fail() } catch (e: IllegalStateException) { assertFalse(e.message!!.contains(token)) }
    }

    @Test fun `run workflow refuses without confirmation and sends nothing`() = runTest {
        val t = FakeTransport { GitTransport.Resp(204, "") }
        val req = (DispatchPlanner.prepare(host(), "ci.yml", "work") as DispatchPlanner.Result.Ready).request
        try { AgentPublisher(t).runWorkflow(req, token, confirmed = false); fail() } catch (_: IllegalStateException) {}
        assertTrue(t.seen.isEmpty())
        AgentPublisher(t).runWorkflow(req, token, confirmed = true)
        val sent = t.seen.single()
        assertEquals("POST", sent.method)
        assertTrue(sent.url.endsWith("/actions/workflows/ci.yml/dispatches"))
        assertEquals("work", JSONObject(sent.body!!).getString("ref"))
    }

    @Test fun `a rejected dispatch is an error naming the host's reason`() = runTest {
        val t = FakeTransport { GitTransport.Resp(422, """{"message":"Workflow does not have workflow_dispatch trigger"}""") }
        val req = (DispatchPlanner.prepare(host(), "ci.yml", "work") as DispatchPlanner.Result.Ready).request
        try { AgentPublisher(t).runWorkflow(req, token, true); fail() } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("workflow_dispatch"))
        }
    }
}
