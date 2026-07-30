package dev.aarso.domain.builds

import dev.aarso.domain.git.GitHost
import dev.aarso.domain.git.GitHostKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CiTriggerTest {

    private val github = GitHost(
        id = "g", displayName = "Test", kind = GitHostKind.GITHUB, baseUrl = "",
        owner = "owner", repo = "repo", branch = "main",
        authorName = "A", authorEmail = "a@b.com",
    )
    private val gitea = GitHost(
        id = "t", displayName = "Test", kind = GitHostKind.GITEA, baseUrl = "https://git.example.com",
        owner = "owner", repo = "repo", branch = "main",
        authorName = "A", authorEmail = "a@b.com",
    )

    // ── listWorkflows ─────────────────────────────────────────────────────────

    @Test fun `listWorkflows builds correct GitHub URL and auth`() {
        val req = CiTrigger.listWorkflows(github, "tok")
        assertEquals("GET", req.method)
        assertEquals("https://api.github.com/repos/owner/repo/actions/workflows", req.url)
        assertEquals("Bearer tok", req.headers["Authorization"])
        assertEquals("application/vnd.github+json", req.headers["Accept"])
        assertNull(req.body)
    }

    @Test fun `listWorkflows builds correct Gitea URL`() {
        val req = CiTrigger.listWorkflows(gitea, "tok")
        assertEquals("GET", req.method)
        assertEquals("https://git.example.com/api/v1/repos/owner/repo/actions/workflows", req.url)
        assertEquals("token tok", req.headers["Authorization"])
        assertEquals("application/json", req.headers["Accept"])
    }

    // ── dispatch ──────────────────────────────────────────────────────────────

    @Test fun `dispatch builds POST with workflow file name and ref body`() {
        val req = CiTrigger.dispatch(github, "ci.yml", "main", "tok")
        assertEquals("POST", req.method)
        assertTrue(req.url.endsWith("/actions/workflows/ci.yml/dispatches"))
        assertTrue(req.body?.contains("\"ref\":\"main\"") == true)
        assertEquals("Bearer tok", req.headers["Authorization"])
    }

    @Test fun `dispatch URL-encodes workflow id`() {
        val req = CiTrigger.dispatch(github, "my workflow.yml", "dev", "tok")
        assertTrue(req.url.contains("my%20workflow.yml"))
    }

    @Test fun `dispatch builds Gitea URL`() {
        val req = CiTrigger.dispatch(gitea, "ci.yml", "main", "tok")
        assertTrue(req.url.startsWith("https://git.example.com/api/v1/repos/owner/repo/actions"))
        assertTrue(req.url.endsWith("/dispatches"))
    }

    // ── listRuns ─────────────────────────────────────────────────────────────

    @Test fun `listRuns with no filter builds plain runs URL`() {
        val req = CiTrigger.listRuns(github, "tok")
        assertEquals("GET", req.method)
        assertEquals("https://api.github.com/repos/owner/repo/actions/runs", req.url)
    }

    @Test fun `listRuns with workflowId adds query param`() {
        val req = CiTrigger.listRuns(github, "tok", workflowId = "ci.yml")
        assertTrue(req.url.contains("workflow_id=ci.yml"))
    }

    @Test fun `listRuns with branch adds query param`() {
        val req = CiTrigger.listRuns(github, "tok", branch = "dev")
        assertTrue(req.url.contains("branch=dev"))
    }

    @Test fun `listRuns with both filters includes both params`() {
        val req = CiTrigger.listRuns(github, "tok", workflowId = "ci.yml", branch = "main")
        assertTrue(req.url.contains("workflow_id=ci.yml"))
        assertTrue(req.url.contains("branch=main"))
    }

    // ── parseWorkflows ────────────────────────────────────────────────────────

    @Test fun `parseWorkflows extracts id name path state`() {
        val json = """{"total_count":1,"workflows":[
            {"id":123,"name":"CI","path":".github/workflows/ci.yml","state":"active"}
        ]}"""
        val result = CiTrigger.parseWorkflows(json)
        assertEquals(1, result.size)
        assertEquals("123", result[0].id)
        assertEquals("CI", result[0].name)
        assertEquals(".github/workflows/ci.yml", result[0].path)
        assertEquals("active", result[0].state)
    }

    @Test fun `parseWorkflows returns empty list for empty array`() {
        assertEquals(0, CiTrigger.parseWorkflows("""{"total_count":0,"workflows":[]}""").size)
    }

    @Test fun `parseWorkflows skips entries without id`() {
        val json = """{"total_count":2,"workflows":[
            {"name":"CI","path":".github/workflows/ci.yml","state":"active"},
            {"id":456,"name":"Deploy","path":".github/workflows/deploy.yml","state":"active"}
        ]}"""
        val result = CiTrigger.parseWorkflows(json)
        assertEquals(1, result.size)
        assertEquals("456", result[0].id)
    }

    // ── parseRuns ─────────────────────────────────────────────────────────────

    @Test fun `parseRuns extracts completed run with conclusion`() {
        val json = """{"total_count":1,"workflow_runs":[
            {"id":1,"name":"CI","display_title":"CI","head_branch":"main","event":"push",
             "status":"completed","conclusion":"success","created_at":"2024-01-01T00:00:00Z",
             "updated_at":"2024-01-01T00:01:00Z","html_url":"https://github.com"}
        ]}"""
        val runs = CiTrigger.parseRuns(json, GitHostKind.GITHUB)
        assertEquals(1, runs.size)
        assertEquals(1L, runs[0].id)
        assertEquals("completed", runs[0].status)
        assertEquals("success", runs[0].conclusion)
        assertEquals("main", runs[0].headBranch)
        assertEquals("push", runs[0].event)
        assertEquals("2024-01-01T00:00:00Z", runs[0].createdAt)
    }

    @Test fun `parseRuns sets conclusion to null for in-progress run`() {
        val json = """{"total_count":1,"workflow_runs":[
            {"id":2,"name":"CI","display_title":"CI","head_branch":"main","event":"push",
             "status":"in_progress","conclusion":"","created_at":"2024-01-02T00:00:00Z",
             "updated_at":"2024-01-02T00:01:00Z","html_url":"https://github.com"}
        ]}"""
        val runs = CiTrigger.parseRuns(json, GitHostKind.GITHUB)
        assertEquals(1, runs.size)
        assertEquals("in_progress", runs[0].status)
        assertNull(runs[0].conclusion)
    }

    @Test fun `parseRuns returns empty list for empty array`() {
        assertEquals(0, CiTrigger.parseRuns("""{"total_count":0,"workflow_runs":[]}""", GitHostKind.GITHUB).size)
    }

    @Test fun `parseRuns uses display_title when available`() {
        val json = """{"total_count":1,"workflow_runs":[
            {"id":3,"name":"CI","display_title":"feat: add login","head_branch":"feature","event":"push",
             "status":"completed","conclusion":"failure","created_at":"2024-01-03T00:00:00Z",
             "updated_at":"2024-01-03T00:01:00Z","html_url":"https://github.com"}
        ]}"""
        val runs = CiTrigger.parseRuns(json, GitHostKind.GITHUB)
        assertEquals("feat: add login", runs[0].workflowName)
    }

    @Test fun `parseRuns falls back to name when display_title is blank`() {
        val json = """{"total_count":1,"workflow_runs":[
            {"id":4,"name":"Deploy","display_title":"","head_branch":"main","event":"workflow_dispatch",
             "status":"queued","conclusion":"","created_at":"2024-01-04T00:00:00Z",
             "updated_at":"2024-01-04T00:01:00Z","html_url":"https://github.com"}
        ]}"""
        val runs = CiTrigger.parseRuns(json, GitHostKind.GITHUB)
        assertEquals("Deploy", runs[0].workflowName)
    }
}
