package dev.aarso.domain.pm

import dev.aarso.domain.git.GitContentsApi
import dev.aarso.domain.git.GitHost
import dev.aarso.domain.git.GitHostKind
import dev.aarso.domain.git.GitRequest
import org.json.JSONArray
import org.json.JSONObject

/**
 * REST requests + parsers for the [BoardCard] board over a watched Git host's issues.
 * Pure, like [dev.aarso.domain.builds.BuildsApi] and [GitContentsApi]: it builds
 * [GitRequest]s and parses JSON; the network execution stays in GitTransport. GitHub
 * and Gitea/Forgejo share the issue shape, so one code path serves both.
 */
object IssueBoardApi {

    // ---- request builders ----

    /** GET the repo's issues (open + closed). GitHub returns PRs here too — parsing skips them. */
    fun listIssues(host: GitHost, token: String): GitRequest =
        GitRequest(
            "GET",
            "${repoBase(host)}/issues?state=all&per_page=100",
            headers(host, token),
            null,
        )

    /** POST a new issue into [column] (its status label is applied unless BACKLOG/DONE). */
    fun createIssue(host: GitHost, title: String, body: String, column: BoardColumn, token: String): GitRequest {
        val payload = JSONObject().put("title", title).put("body", body)
        column.label?.let { payload.put("labels", JSONArray().put(it)) }
        return GitRequest(
            "POST",
            "${repoBase(host)}/issues",
            headers(host, token) + ("Content-Type" to "application/json"),
            payload.toString(),
        )
    }

    /**
     * PATCH an issue to move it to [target]: rewrites the label set (preserving non-status
     * labels) and flips open/closed. [currentLabels] is the card's present labels.
     */
    fun moveCard(host: GitHost, number: Int, target: BoardColumn, currentLabels: List<String>, token: String): GitRequest {
        val labels = Boards.labelsForMove(currentLabels, target)
        val payload = JSONObject()
            .put("state", if (Boards.isOpenAfter(target)) "open" else "closed")
            .put("labels", JSONArray(labels))
        return GitRequest(
            "PATCH",
            "${repoBase(host)}/issues/$number",
            headers(host, token) + ("Content-Type" to "application/json"),
            payload.toString(),
        )
    }

    // ---- parser ----

    /** Parse an issues listing into board cards, skipping pull requests (GitHub lists them as issues). */
    fun parseIssues(json: String): List<BoardCard> {
        val arr = JSONArray(json)
        val out = ArrayList<BoardCard>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.has("pull_request")) continue // a PR, not a board card
            val labels = ArrayList<String>()
            o.optJSONArray("labels")?.let { la ->
                for (j in 0 until la.length()) {
                    val name = when (val v = la.get(j)) {
                        is JSONObject -> v.optString("name")
                        else -> v.toString()
                    }
                    if (name.isNotBlank()) labels.add(name)
                }
            }
            val assignees = ArrayList<String>()
            o.optJSONArray("assignees")?.let { aa ->
                for (j in 0 until aa.length()) {
                    aa.getJSONObject(j).optString("login").takeIf { it.isNotBlank() }?.let(assignees::add)
                }
            }
            val number = o.optInt("number")
            out.add(
                BoardCard(
                    id = o.opt("id")?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: number.toString(),
                    number = number,
                    title = o.optString("title"),
                    body = o.optString("body"),
                    isOpen = o.optString("state", "open") != "closed",
                    labels = labels,
                    assignees = assignees,
                    updatedAt = o.optString("updated_at"),
                    url = o.optString("html_url"),
                ),
            )
        }
        return out
    }

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
}
