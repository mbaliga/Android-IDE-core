package dev.aarso.domain.builds

import dev.aarso.domain.git.GitContentsApi
import dev.aarso.domain.git.GitHost
import dev.aarso.domain.git.GitHostKind
import dev.aarso.domain.git.GitRequest
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Builds REST requests + parsers for **CI workflow dispatch** and **run listing** on
 * GitHub Actions and Gitea Actions. Pure, like [BuildsApi]: it builds [GitRequest]s
 * and parses JSON; network execution stays in GitTransport. JVM-tested.
 *
 * A trigger is POST dispatches; results are read via [listRuns] (polling, not push).
 * The design (docs/design/coding-assistant.md) treats CI as the test runner: the
 * owner's own workflows are the oracle, not local tool execution.
 */
object CiTrigger {

    // ---- request builders ----

    /** GET the repo's workflow definitions ("*.yml" files in ".github/workflows/"). */
    fun listWorkflows(host: GitHost, token: String): GitRequest =
        GitRequest("GET", "${actionsBase(host)}/workflows", headers(host, token), null)

    /**
     * POST a `workflow_dispatch` event — triggers a run for [workflowId] on [ref].
     * [workflowId] may be the workflow file name (e.g. `ci.yml`) or its numeric id.
     * Requires the workflow to have `on: workflow_dispatch:` in its YAML.
     */
    fun dispatch(host: GitHost, workflowId: String, ref: String, token: String): GitRequest {
        val body = JSONObject().apply { put("ref", ref) }.toString()
        return GitRequest(
            "POST",
            "${actionsBase(host)}/workflows/${enc(workflowId)}/dispatches",
            headers(host, token),
            body,
        )
    }

    /**
     * GET recent workflow runs, optionally filtered by [workflowId] and [branch].
     * Pass null to list all runs across all workflows.
     */
    fun listRuns(
        host: GitHost,
        token: String,
        workflowId: String? = null,
        branch: String? = null,
    ): GitRequest {
        val base = "${actionsBase(host)}/runs"
        val params = buildList {
            if (workflowId != null) add("workflow_id=${enc(workflowId)}")
            if (branch != null) add("branch=${enc(branch)}")
        }
        val url = if (params.isEmpty()) base else "$base?${params.joinToString("&")}"
        return GitRequest("GET", url, headers(host, token), null)
    }

    // ---- parsers ----

    /** Parse the `GET /actions/workflows` response. */
    fun parseWorkflows(json: String): List<Workflow> {
        val arr = runCatching { JSONObject(json).optJSONArray("workflows") }.getOrNull()
            ?: return emptyList()
        val out = ArrayList<Workflow>()
        for (i in 0 until arr.length()) {
            val w = arr.optJSONObject(i) ?: continue
            val id = w.opt("id")?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: continue
            out.add(
                Workflow(
                    id = id,
                    name = w.optString("name"),
                    path = w.optString("path"),
                    state = w.optString("state"),
                ),
            )
        }
        return out
    }

    /** Parse a `GET /actions/runs` response into a flat run list. */
    fun parseRuns(json: String, kind: GitHostKind): List<WorkflowRun> {
        val arr = runCatching { JSONObject(json).optJSONArray("workflow_runs") }.getOrNull()
            ?: return emptyList()
        val out = ArrayList<WorkflowRun>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val conclusion = r.optString("conclusion").takeIf { it.isNotBlank() }
            out.add(
                WorkflowRun(
                    id = r.optLong("id"),
                    name = r.optString("name"),
                    workflowName = r.optString("display_title").ifEmpty { r.optString("name") },
                    headBranch = r.optString("head_branch"),
                    event = r.optString("event"),
                    status = r.optString("status"),
                    conclusion = conclusion,
                    createdAt = r.optString("created_at").ifEmpty { r.optString("run_started_at") },
                    updatedAt = r.optString("updated_at"),
                    htmlUrl = r.optString("html_url"),
                ),
            )
        }
        return out
    }

    // ---- helpers ----

    private fun actionsBase(host: GitHost) =
        "${GitContentsApi.apiBase(host)}/repos/${host.owner}/${host.repo}/actions"

    private fun headers(host: GitHost, token: String): Map<String, String> = mapOf(
        "Authorization" to when (host.kind) {
            GitHostKind.GITHUB -> "Bearer $token"
            GitHostKind.GITEA -> "token $token"
        },
        "Accept" to when (host.kind) {
            GitHostKind.GITHUB -> "application/vnd.github+json"
            GitHostKind.GITEA -> "application/json"
        },
    )

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

/** A CI workflow definition (a `.yml` file in `.github/workflows/` or equivalent). */
data class Workflow(
    val id: String,
    val name: String,
    val path: String,   // e.g. .github/workflows/ci.yml
    val state: String,  // active / disabled_manually / etc.
)

/** One workflow run instance (a triggered invocation). */
data class WorkflowRun(
    val id: Long,
    val name: String,
    val workflowName: String,
    val headBranch: String,
    val event: String,          // push / pull_request / workflow_dispatch / …
    val status: String,         // queued / in_progress / completed
    val conclusion: String?,    // success / failure / cancelled / timed_out / …; null while running
    val createdAt: String,
    val updatedAt: String,
    val htmlUrl: String,
)
