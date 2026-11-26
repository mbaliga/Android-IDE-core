package dev.fonebrew.domain.git

import dev.fonebrew.domain.git.GitPublishSupport.headers
import dev.fonebrew.domain.git.GitPublishSupport.repoBase
import org.json.JSONArray
import org.json.JSONObject

/** One normalised CI verdict. [glyph] + [label] are the stable, non-hue way to show it - the UI
 *  must use these and never colour alone. */
enum class CiLevel(val glyph: String, val label: String) {
    NONE("○", "No CI results"),
    PENDING("◐", "CI running or incomplete"),
    PASSING("✓", "CI passing"),
    FAILING("✗", "CI failing"),
}

/**
 * The ONE pure CI model. Built by [GitActionsApi.ciState] from check runs and commit statuses.
 * Absence is never success: nothing reported = [CiLevel.NONE]. Anything not positively recognised
 * counts as pending, never passing.
 */
data class CiState(
    val level: CiLevel,
    val total: Int,
    val passing: Int,
    val pending: Int,
    val failing: Int,
    val failingNames: List<String>,
    /** True when the host reported more results than we received, so we could not see all of them. */
    val truncated: Boolean = false,
) {
    val glyph: String get() = level.glyph
    val label: String get() = level.label

    /** Glyph + label + counts, e.g. "✗ CI failing (1 failing, 2 passing, 0 pending)". */
    fun describe(): String =
        if (level == CiLevel.NONE) "$glyph $label"
        else "$glyph $label ($failing failing, $passing passing, $pending pending)" + if (truncated) ", partial" else ""

    companion object {
        val NONE = CiState(CiLevel.NONE, 0, 0, 0, 0, emptyList())
    }
}

/**
 * Builders + parsers for CI on a commit: workflow runs, check runs, combined status, and
 * `workflow_dispatch`. Pure (no I/O). GitHub: Actions + Checks + Statuses REST. Gitea/Forgejo:
 * runs, combined status and dispatch exist in the swagger; **check runs do not** -> Unsupported.
 * Gitea run `status` strings are not enumerated by its spec, so Gitea runs are kept raw and never
 * folded into [CiState] (unknown stays unknown).
 */
object GitActionsApi {

    data class WorkflowRun(
        val id: Long,
        val name: String,
        /** Host status string as reported (GitHub: queued/in_progress/completed; Gitea: raw). */
        val status: String,
        /** GitHub conclusion when completed; empty if absent or on Gitea. */
        val conclusion: String,
        val headSha: String,
        val htmlUrl: String,
    )

    data class CheckRun(val name: String, val status: String, val conclusion: String)

    data class CommitStatus(val context: String, val state: String)

    data class CheckRunsPage(val totalCount: Int, val runs: List<CheckRun>)
    data class StatusPage(val totalCount: Int, val statuses: List<CommitStatus>)

    private val SHA = Regex("^[0-9a-fA-F]{7,64}$")
    private const val PAGE = 100

    /** GET recent workflow runs for [branch] and/or [sha] (at least one required). */
    fun listRuns(host: GitHost, branch: String?, sha: String?, token: String, limit: Int = 10): GitBuild {
        if (branch.isNullOrBlank() && sha.isNullOrBlank()) return GitBuild.Invalid("give a branch or a sha")
        if (!sha.isNullOrBlank() && !SHA.matches(sha)) return GitBuild.Invalid("sha must be 7-64 hex characters")
        val n = limit.coerceIn(1, PAGE)
        val q = mutableListOf<String>()
        when (host.kind) {
            GitHostKind.GITHUB -> {
                if (!branch.isNullOrBlank()) q += "branch=${GitPublishSupport.enc(branch)}"
                if (!sha.isNullOrBlank()) q += "head_sha=$sha"
                q += "per_page=$n"
            }
            GitHostKind.GITEA -> {
                if (!branch.isNullOrBlank()) q += "ref=${GitPublishSupport.enc("refs/heads/$branch")}"
                if (!sha.isNullOrBlank()) q += "head_sha=$sha"
                q += "limit=$n"
            }
        }
        return GitBuild.Ready(GitRequest("GET", "${repoBase(host)}/actions/runs?${q.joinToString("&")}", headers(host, token), null))
    }

    /** GET the check runs for [sha]. GitHub only. */
    fun checkRuns(host: GitHost, sha: String, token: String): GitBuild {
        if (host.kind != GitHostKind.GITHUB) return GitBuild.Unsupported(host.kind, "check runs")
        if (!SHA.matches(sha)) return GitBuild.Invalid("sha must be 7-64 hex characters")
        return GitBuild.Ready(GitRequest("GET", "${repoBase(host)}/commits/$sha/check-runs?per_page=$PAGE", headers(host, token), null))
    }

    /** GET the combined commit status for [sha]. */
    fun combinedStatus(host: GitHost, sha: String, token: String): GitBuild {
        if (!SHA.matches(sha)) return GitBuild.Invalid("sha must be 7-64 hex characters")
        val page = if (host.kind == GitHostKind.GITHUB) "per_page=$PAGE" else "limit=$PAGE"
        return GitBuild.Ready(GitRequest("GET", "${repoBase(host)}/commits/$sha/status?$page", headers(host, token), null))
    }

    /** POST a `workflow_dispatch` for [workflow] (file name such as `ci.yml`, or numeric id) on [ref]. */
    fun dispatch(host: GitHost, workflow: String, ref: String, token: String, inputs: Map<String, String> = emptyMap()): GitBuild {
        if (workflow.isBlank() || workflow.contains('/')) return GitBuild.Invalid("workflow must be a file name or id")
        GitBranchApi.validate(ref)?.let { return GitBuild.Invalid("ref: $it") }
        if (inputs.size > 25) return GitBuild.Invalid("at most 25 workflow inputs")
        val body = JSONObject().put("ref", ref)
        if (inputs.isNotEmpty()) body.put("inputs", JSONObject(inputs))
        return GitBuild.Ready(
            GitRequest(
                "POST",
                "${repoBase(host)}/actions/workflows/${GitPublishSupport.enc(workflow)}/dispatches",
                headers(host, token, json = true),
                body.toString(),
            ),
        )
    }

    /** Dispatch success: 204 (classic), 200/201 (newer, may carry run details). */
    fun parseDispatch(status: Int, body: String): GitApiResult<Unit> =
        if (status in setOf(200, 201, 204)) GitApiResult.Ok(Unit) else GitApiResult.Err(GitPublishSupport.error(status, body))

    fun parseRuns(host: GitHost, status: Int, body: String): GitApiResult<List<WorkflowRun>> =
        GitPublishSupport.parseObject(status, body, setOf(200), "workflow runs") { o ->
            val arr = o.optJSONArray("workflow_runs") ?: return@parseObject null
            arr.objects().map { r ->
                when (host.kind) {
                    GitHostKind.GITHUB -> WorkflowRun(
                        r.optLong("id"), r.optString("name"), r.optString("status"), r.optString("conclusion"),
                        r.optString("head_sha"), r.optString("html_url"),
                    )
                    GitHostKind.GITEA -> WorkflowRun(
                        r.optLong("id"), r.optString("title"), r.optString("status"), "",
                        r.optString("commit_sha"), r.optString("html_url"),
                    )
                }
            }
        }

    fun parseCheckRuns(status: Int, body: String): GitApiResult<CheckRunsPage> =
        GitPublishSupport.parseObject(status, body, setOf(200), "check runs") { o ->
            val arr = o.optJSONArray("check_runs") ?: return@parseObject null
            CheckRunsPage(
                o.optInt("total_count", arr.length()),
                arr.objects().map { CheckRun(it.optString("name"), it.optString("status"), it.optString("conclusion")) },
            )
        }

    /** GitHub names the per-status field `state`, Gitea `status`; both are read. */
    fun parseCombinedStatus(status: Int, body: String): GitApiResult<StatusPage> =
        GitPublishSupport.parseObject(status, body, setOf(200), "commit status") { o ->
            val arr = o.optJSONArray("statuses") ?: return@parseObject null
            StatusPage(
                o.optInt("total_count", arr.length()),
                arr.objects().map { CommitStatus(it.optString("context"), it.optString("state").ifEmpty { it.optString("status") }) },
            )
        }

    /**
     * Fold check runs and commit statuses into one [CiState]. Either side may be null (not fetched /
     * unsupported). Rules: any failing -> FAILING; else any pending or unrecognised or truncated ->
     * PENDING; else nothing reported -> NONE; else PASSING.
     */
    fun ciState(checks: CheckRunsPage?, statuses: StatusPage?): CiState {
        var passing = 0
        var pending = 0
        val failing = mutableListOf<String>()
        checks?.runs?.forEach { c ->
            when (classifyCheck(c)) {
                Verdict.PASS -> passing++
                Verdict.FAIL -> failing += c.name.ifEmpty { "(unnamed check)" }
                Verdict.WAIT -> pending++
            }
        }
        statuses?.statuses?.forEach { s ->
            when (classifyState(s.state)) {
                Verdict.PASS -> passing++
                Verdict.FAIL -> failing += s.context.ifEmpty { "(unnamed status)" }
                Verdict.WAIT -> pending++
            }
        }
        val seen = passing + pending + failing.size
        val truncated = (checks != null && checks.totalCount > checks.runs.size) ||
            (statuses != null && statuses.totalCount > statuses.statuses.size)
        val level = when {
            failing.isNotEmpty() -> CiLevel.FAILING
            pending > 0 || truncated -> CiLevel.PENDING
            seen == 0 -> CiLevel.NONE
            else -> CiLevel.PASSING
        }
        return CiState(level, seen, passing, pending, failing.size, failing, truncated)
    }

    private enum class Verdict { PASS, FAIL, WAIT }

    private fun classifyCheck(c: CheckRun): Verdict {
        if (c.status != "completed") return Verdict.WAIT
        return when (c.conclusion) {
            "success", "neutral", "skipped" -> Verdict.PASS
            "failure", "timed_out", "cancelled", "action_required", "startup_failure", "stale" -> Verdict.FAIL
            else -> Verdict.WAIT
        }
    }

    private fun classifyState(state: String): Verdict = when (state) {
        "success" -> Verdict.PASS
        "failure", "error" -> Verdict.FAIL
        else -> Verdict.WAIT // "pending" and anything unrecognised (e.g. Gitea "warning") stay unknown
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
}
