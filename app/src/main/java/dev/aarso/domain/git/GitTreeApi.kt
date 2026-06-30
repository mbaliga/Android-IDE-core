package dev.aarso.domain.git

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Builds the Git Data API requests (GitHub; Gitea/Forgejo mirror it) for committing **many files
 * as one commit** — the squashed alternative to the Contents API's one-commit-per-file. Pure data,
 * like [GitContentsApi], so request construction is JVM-testable and [dev.aarso.data.GitTransport]
 * stays a thin adapter. Flow: get-ref → get-commit (for its tree) → create-tree (base_tree + new
 * blobs) → create-commit → update-ref.
 */
object GitTreeApi {

    /** GET the branch ref → its head commit sha. (GitHub: `git/ref`; Gitea: `git/refs`.) */
    fun getRef(host: GitHost, token: String): GitRequest {
        val seg = if (host.kind == GitHostKind.GITHUB) "git/ref/heads" else "git/refs/heads"
        return GitRequest("GET", "${repoBase(host)}/$seg/${enc(host.branch)}", headers(host, token), null)
    }

    /** GET a commit → its tree sha. */
    fun getCommit(host: GitHost, sha: String, token: String): GitRequest =
        GitRequest("GET", "${repoBase(host)}/git/commits/${enc(sha)}", headers(host, token), null)

    /** POST a new tree over [baseTreeSha] with one blob entry per (path, content). */
    fun createTree(host: GitHost, baseTreeSha: String, files: List<Pair<String, String>>, token: String): GitRequest {
        val tree = JSONArray()
        for ((path, content) in files) {
            tree.put(
                JSONObject()
                    .put("path", path)
                    .put("mode", "100644")
                    .put("type", "blob")
                    .put("content", content),
            )
        }
        val body = JSONObject().put("base_tree", baseTreeSha).put("tree", tree)
        return GitRequest("POST", "${repoBase(host)}/git/trees", jsonHeaders(host, token), body.toString())
    }

    /** POST a commit pointing at [treeSha] with [parentSha]. */
    fun createCommit(host: GitHost, message: String, treeSha: String, parentSha: String, token: String): GitRequest {
        val author = JSONObject().put("name", host.authorName).put("email", host.authorEmail)
        val body = JSONObject()
            .put("message", message)
            .put("tree", treeSha)
            .put("parents", JSONArray().put(parentSha))
            .put("author", author)
        return GitRequest("POST", "${repoBase(host)}/git/commits", jsonHeaders(host, token), body.toString())
    }

    /** PATCH the branch ref to [commitSha] (fast-forward; no force). */
    fun updateRef(host: GitHost, commitSha: String, token: String): GitRequest {
        val body = JSONObject().put("sha", commitSha).put("force", false)
        return GitRequest(
            "PATCH",
            "${repoBase(host)}/git/refs/heads/${enc(host.branch)}",
            jsonHeaders(host, token),
            body.toString(),
        )
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

    private fun jsonHeaders(host: GitHost, token: String) = headers(host, token) + ("Content-Type" to "application/json")

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
