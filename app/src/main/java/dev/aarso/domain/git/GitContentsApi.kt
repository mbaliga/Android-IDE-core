package dev.aarso.domain.git

import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64

/** A built HTTP request — pure data, so request construction is JVM-testable and
 *  the network execution ([dev.aarso.data.GitTransport]) stays a thin adapter. */
data class GitRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String?,
)

/**
 * Builds the REST "contents API" requests for GitHub and Gitea/Forgejo (they share
 * the shape). Pure: no Android, no network, no Keystore — the token is passed in.
 * The differences between hosts (API base, auth scheme, Accept header) live here in
 * one legible place.
 */
object GitContentsApi {

    fun apiBase(host: GitHost): String = when (host.kind) {
        GitHostKind.GITHUB -> "https://api.github.com"
        GitHostKind.GITEA -> host.baseUrl.trimEnd('/') + "/api/v1"
    }

    /** GET the repo's branches — also the cheapest "does my token/URL work?" probe. */
    fun listBranches(host: GitHost, token: String): GitRequest =
        GitRequest("GET", "${repoBase(host)}/branches", commonHeaders(host, token), null)

    /** GET a file's content + sha at the host's branch. */
    fun getFile(host: GitHost, path: String, token: String): GitRequest =
        GitRequest(
            "GET",
            "${repoBase(host)}/contents/${encodePath(path)}?ref=${enc(host.branch)}",
            commonHeaders(host, token),
            null,
        )

    /** GET a directory's listing (a JSON array of entries: name/path/type). [path]
     *  blank = the repo root. */
    fun listDir(host: GitHost, path: String, token: String): GitRequest {
        val seg = if (path.isBlank()) "" else "/${encodePath(path)}"
        return GitRequest(
            "GET",
            "${repoBase(host)}/contents$seg?ref=${enc(host.branch)}",
            commonHeaders(host, token),
            null,
        )
    }

    /** PUT (create or, with [sha], update) a file. [content] is raw text; we base64 it. */
    fun putFile(host: GitHost, path: String, content: String, message: String, sha: String?, token: String): GitRequest {
        val author = JSONObject().put("name", host.authorName).put("email", host.authorEmail)
        val body = JSONObject()
            .put("message", message)
            .put("content", Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8)))
            .put("branch", host.branch)
            .put("author", author)
            .put("committer", author)
        if (sha != null) body.put("sha", sha)
        return GitRequest(
            "PUT",
            "${repoBase(host)}/contents/${encodePath(path)}",
            commonHeaders(host, token) + ("Content-Type" to "application/json"),
            body.toString(),
        )
    }

    private fun repoBase(host: GitHost) = "${apiBase(host)}/repos/${host.owner}/${host.repo}"

    private fun commonHeaders(host: GitHost, token: String): Map<String, String> = mapOf(
        "Authorization" to when (host.kind) {
            GitHostKind.GITHUB -> "Bearer $token"
            GitHostKind.GITEA -> "token $token"
        },
        "Accept" to when (host.kind) {
            GitHostKind.GITHUB -> "application/vnd.github+json"
            GitHostKind.GITEA -> "application/json"
        },
    )

    /** Encode each path segment but keep the slashes that structure the repo path. */
    private fun encodePath(path: String): String =
        path.trim('/').split('/').joinToString("/") { enc(it) }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
