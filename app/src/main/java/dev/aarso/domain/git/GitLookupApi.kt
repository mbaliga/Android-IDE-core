package dev.aarso.domain.git

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure request builder + response parser for the token-first connect flow:
 * paste PAT → who am I → list repos → pick one. Follows the same pattern as
 * [GitContentsApi] — pure data in/out, no network, no Android, token passed in.
 */
object GitLookupApi {

    data class UserInfo(
        val login: String,
        val name: String,   // blank if the user hasn't set it
        val email: String,  // blank if the user's email is private
    )

    data class RepoInfo(
        val owner: String,
        val name: String,
        val defaultBranch: String = "main",
        val isPrivate: Boolean = false,
    ) {
        val fullName: String get() = "$owner/$name"
    }

    /** GET the authenticated user's identity. */
    fun whoAmI(kind: GitHostKind, baseUrl: String, token: String): GitRequest =
        GitRequest("GET", "${base(kind, baseUrl)}/user", headers(kind, token), null)

    /**
     * GET the authenticated user's repositories, sorted by last-pushed (most
     * recently active first). Returns up to 100 for GitHub, 50 for Gitea.
     */
    fun listRepos(kind: GitHostKind, baseUrl: String, token: String): GitRequest {
        val url = when (kind) {
            GitHostKind.GITHUB -> "${base(kind, baseUrl)}/user/repos?sort=pushed&per_page=100"
            GitHostKind.GITEA  -> "${base(kind, baseUrl)}/repos/search?limit=50&sort=updated"
        }
        return GitRequest("GET", url, headers(kind, token), null)
    }

    fun parseUser(json: String, kind: GitHostKind): UserInfo {
        val o = JSONObject(json)
        return when (kind) {
            GitHostKind.GITHUB -> UserInfo(
                login = o.optString("login"),
                name  = o.optString("name"),
                email = o.optString("email"),
            )
            GitHostKind.GITEA -> UserInfo(
                login = o.optString("login"),
                name  = o.optString("full_name"),
                email = o.optString("email"),
            )
        }
    }

    fun parseRepos(json: String, kind: GitHostKind): List<RepoInfo> {
        val arr: JSONArray = when (kind) {
            GitHostKind.GITHUB -> JSONArray(json)
            GitHostKind.GITEA  -> JSONObject(json).optJSONArray("data") ?: JSONArray()
        }
        return (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            val ownerLogin = r.optJSONObject("owner")?.optString("login") ?: ""
            RepoInfo(
                owner         = ownerLogin,
                name          = r.optString("name"),
                defaultBranch = r.optString("default_branch", "main"),
                isPrivate     = r.optBoolean("private", false),
            )
        }
    }

    private fun base(kind: GitHostKind, baseUrl: String): String = when (kind) {
        GitHostKind.GITHUB -> "https://api.github.com"
        GitHostKind.GITEA  -> baseUrl.trimEnd('/') + "/api/v1"
    }

    private fun headers(kind: GitHostKind, token: String): Map<String, String> = mapOf(
        "Authorization" to when (kind) {
            GitHostKind.GITHUB -> "Bearer $token"
            GitHostKind.GITEA  -> "token $token"
        },
        "Accept" to when (kind) {
            GitHostKind.GITHUB -> "application/vnd.github+json"
            GitHostKind.GITEA  -> "application/json"
        },
    )
}
