package dev.aarso.domain.builds

import dev.aarso.domain.git.GitContentsApi
import dev.aarso.domain.git.GitHost
import dev.aarso.domain.git.GitHostKind
import dev.aarso.domain.git.GitRequest
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Builds the REST requests + response parsers for **app distribution** — listing the
 * APKs your repo's CI produced and their test status, so Aarso can show and install
 * them in-app (docs/design/app-distribution.md). Pure, like [GitContentsApi]: it
 * builds [GitRequest]s and parses JSON; the network execution stays in GitTransport.
 *
 * Two APK sources are parsed (the ones actually in use): **Release assets** and a
 * **dist-branch** file (`apk-dist` → `aarso-sd.apk`, read via the contents API). CI
 * **checks** become the build's test badge. JVM-tested against sample host JSON.
 */
object BuildsApi {

    // ---- request builders ----

    /** GET the repo's releases (each carries the APK assets). Same path on both hosts. */
    fun listReleases(host: GitHost, token: String): GitRequest =
        GitRequest("GET", "${repoBase(host)}/releases", headers(host, token), null)

    /** GET the CI verdict for a commit/branch ref. GitHub = check-runs; Gitea = combined status. */
    fun checks(host: GitHost, ref: String, token: String): GitRequest {
        val path = when (host.kind) {
            GitHostKind.GITHUB -> "commits/${enc(ref)}/check-runs"
            GitHostKind.GITEA -> "commits/${enc(ref)}/status"
        }
        return GitRequest("GET", "${repoBase(host)}/$path", headers(host, token), null)
    }

    // ---- parsers ----

    /** All `.apk` assets across the repo's releases, newest-release-first as returned. */
    fun parseReleases(json: String): List<Build> {
        val arr = JSONArray(json)
        val out = ArrayList<Build>()
        for (i in 0 until arr.length()) {
            val rel = arr.getJSONObject(i)
            val version = rel.optString("tag_name").ifEmpty { rel.optString("name") }
            val branch = rel.optString("target_commitish")
            val created = rel.optString("published_at").ifEmpty { rel.optString("created_at") }
            val assets = rel.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                val name = a.optString("name")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                out.add(
                    Build(
                        id = a.opt("id")?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: "$version/$name",
                        version = version.ifEmpty { name },
                        name = name,
                        branch = branch,
                        createdAt = created,
                        downloadUrl = a.optString("browser_download_url"),
                        sizeBytes = a.optLong("size"),
                        source = BuildSource.RELEASE_ASSET,
                    ),
                )
            }
        }
        return out
    }

    /** APK files on a dist-branch, from a contents-API directory listing (`GitContentsApi.listDir`). */
    fun parseDistBranchApks(contentsJson: String, branch: String): List<Build> {
        val arr = JSONArray(contentsJson)
        val out = ArrayList<Build>()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            if (e.optString("type") != "file") continue
            val name = e.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val sha = e.optString("sha")
            out.add(
                Build(
                    id = sha.ifEmpty { "$branch/$name" },
                    version = if (sha.length >= 7) sha.substring(0, 7) else branch,
                    name = name,
                    branch = branch,
                    createdAt = "",
                    downloadUrl = e.optString("download_url"),
                    sizeBytes = e.optLong("size"),
                    source = BuildSource.DIST_BRANCH,
                ),
            )
        }
        return out
    }

    /** Summarise a CI response into the build's test badge. Handles both host shapes. */
    fun parseChecks(json: String, kind: GitHostKind): ChecksSummary = when (kind) {
        GitHostKind.GITHUB -> parseGithubCheckRuns(json)
        GitHostKind.GITEA -> parseGiteaStatus(json)
    }

    private fun parseGithubCheckRuns(json: String): ChecksSummary {
        val runs = JSONObject(json).optJSONArray("check_runs") ?: return ChecksSummary.NONE
        var passed = 0
        var failed = 0
        var pending = 0
        for (i in 0 until runs.length()) {
            val r = runs.getJSONObject(i)
            val status = r.optString("status") // queued / in_progress / completed
            val conclusion = r.optString("conclusion") // success / failure / ...
            when {
                status != "completed" -> pending++
                conclusion == "success" -> passed++
                conclusion in FAILED_CONCLUSIONS -> failed++
                else -> {} // neutral / skipped — not counted
            }
        }
        return summarise(passed, failed, pending)
    }

    private fun parseGiteaStatus(json: String): ChecksSummary {
        val obj = JSONObject(json)
        val total = obj.optJSONArray("statuses")?.length() ?: 0
        return when (obj.optString("state")) {
            "success" -> ChecksSummary(total, 0, 0, CheckConclusion.SUCCESS)
            "pending" -> ChecksSummary(0, 0, total, CheckConclusion.PENDING)
            "failure", "error" -> ChecksSummary(0, total, 0, CheckConclusion.FAILURE)
            else -> ChecksSummary.NONE
        }
    }

    private fun summarise(passed: Int, failed: Int, pending: Int): ChecksSummary {
        val conclusion = when {
            failed > 0 -> CheckConclusion.FAILURE
            pending > 0 -> CheckConclusion.PENDING
            passed > 0 -> CheckConclusion.SUCCESS
            else -> CheckConclusion.NONE
        }
        return ChecksSummary(passed, failed, pending, conclusion)
    }

    private val FAILED_CONCLUSIONS = setOf("failure", "timed_out", "cancelled", "action_required")

    private fun repoBase(host: GitHost) = "${GitContentsApi.apiBase(host)}/repos/${host.owner}/${host.repo}"

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
