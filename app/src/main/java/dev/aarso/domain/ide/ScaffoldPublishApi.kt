package dev.aarso.domain.ide

import dev.aarso.domain.git.GitContentsApi
import dev.aarso.domain.git.GitHost
import dev.aarso.domain.git.GitHostKind
import dev.aarso.domain.git.GitRequest
import org.json.JSONObject

/**
 * The IDE last mile: turn a [ProjectScaffold] file set into the REST requests that
 * **create a new repo on the user's own host and push the files into it** — closing the
 * loop idea → repo → the host's CI builds the APK → `BuildsApi` lists it → install.
 *
 * Pure request-building, like [GitContentsApi]/[dev.aarso.domain.builds.BuildsApi]: no
 * network here. The execution (and the real side effect of creating a repo on the
 * account) lives in the data layer and stays gated behind explicit user confirmation.
 *
 * Repo is created with `auto_init = false` so it has no branches yet; the first
 * [GitContentsApi.putFile] on `main` creates the branch + file, and the rest append —
 * which is why every scaffold file (README included) can be pushed without a sha clash.
 */
object ScaffoldPublishApi {

    /** POST a new repository under the authenticated user. GitHub + Gitea share `/user/repos`. */
    fun createRepo(host: GitHost, name: String, private: Boolean, token: String): GitRequest {
        val body = JSONObject()
            .put("name", name)
            .put("private", private)
            .put("auto_init", false)
        return GitRequest(
            "POST",
            "${GitContentsApi.apiBase(host)}/user/repos",
            headers(host, token) + ("Content-Type" to "application/json"),
            body.toString(),
        )
    }

    /**
     * One PUT-contents request per scaffold file, targeting [repoName] on `main`.
     * Reuses [GitContentsApi.putFile] (sha = null ⇒ create). Order is preserved so the
     * first request creates the branch.
     */
    fun publishRequests(
        host: GitHost,
        repoName: String,
        files: List<ProjectScaffold.File>,
        token: String,
    ): List<GitRequest> {
        val target = host.copy(repo = repoName, branch = "main")
        return files.map { f ->
            GitContentsApi.putFile(target, f.path, f.content, "scaffold: ${f.path}", null, token)
        }
    }

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
