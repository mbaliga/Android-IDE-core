package dev.fonebrew.domain.git

import dev.fonebrew.domain.git.GitPublishSupport.headers
import dev.fonebrew.domain.git.GitPublishSupport.repoBase
import org.json.JSONObject

/**
 * Open and fetch pull requests for agent-proposed changes. Pure request builders + parsers (no I/O).
 * GitHub: `POST/GET /repos/{o}/{r}/pulls`. Gitea/Forgejo: same paths, but their create option has no
 * `draft` field (swagger), so a draft request there is typed Unsupported rather than silently non-draft.
 */
object GitPullRequestApi {

    data class PullRequest(
        val number: Int,
        val htmlUrl: String,
        /** Host state, e.g. "open" / "closed". */
        val state: String,
        val draft: Boolean,
        val merged: Boolean,
    )

    /** POST a pull request from [head] into [base]. [draft] defaults to true (a human promotes it). */
    fun create(
        host: GitHost,
        title: String,
        body: String,
        head: String,
        base: String,
        token: String,
        draft: Boolean = true,
    ): GitBuild {
        if (title.isBlank()) return GitBuild.Invalid("pull request title is empty")
        GitBranchApi.validate(head)?.let { return GitBuild.Invalid("head: $it") }
        GitBranchApi.validate(base)?.let { return GitBuild.Invalid("base: $it") }
        if (host.kind == GitHostKind.GITEA && draft) {
            return GitBuild.Unsupported(host.kind, "pull request draft flag")
        }
        val json = JSONObject().put("title", title).put("body", body).put("head", head).put("base", base)
        if (host.kind == GitHostKind.GITHUB) json.put("draft", draft)
        return GitBuild.Ready(GitRequest("POST", "${repoBase(host)}/pulls", headers(host, token, json = true), json.toString()))
    }

    /** GET a pull request by number. */
    fun get(host: GitHost, number: Int, token: String): GitBuild {
        if (number <= 0) return GitBuild.Invalid("pull request number must be positive")
        return GitBuild.Ready(GitRequest("GET", "${repoBase(host)}/pulls/$number", headers(host, token), null))
    }

    /** Parse a create (201) response. */
    fun parseCreated(status: Int, body: String): GitApiResult<PullRequest> = parse(status, body, setOf(201))

    /** Parse a get (200) response. */
    fun parseFetched(status: Int, body: String): GitApiResult<PullRequest> = parse(status, body, setOf(200))

    private fun parse(status: Int, body: String, ok: Set<Int>): GitApiResult<PullRequest> =
        GitPublishSupport.parseObject(status, body, ok, "pull request") { o ->
            val number = o.optInt("number", 0)
            val url = o.optString("html_url")
            val state = o.optString("state")
            if (number <= 0 || url.isEmpty() || state.isEmpty()) null
            else PullRequest(number, url, state, o.optBoolean("draft", false), o.optBoolean("merged", false))
        }

    /**
     * PR description. Says plainly that a model proposed the change, names the engine, and states
     * whether it ran in the cloud (a watched object). Contains no links. [summary] is the caller's
     * own text and goes under its own heading.
     */
    fun body(engineName: String, cloud: Boolean, summary: String? = null): String {
        val engine = engineName.trim().ifEmpty { "unnamed engine" }
        val where = if (cloud) {
            "cloud engine (a watched object: the repository content it was shown was sent to that provider)"
        } else {
            "on-device engine (nothing was sent to a cloud provider)"
        }
        return buildString {
            append("This change was proposed by an AI model, not written by a person.\n\n")
            append("- Engine: ").append(engine).append('\n')
            append("- Ran on: ").append(where).append('\n')
            append("- Review it like any other contribution.\n")
            val s = summary?.trim().orEmpty()
            if (s.isNotEmpty()) append("\n## Summary\n\n").append(s).append('\n')
        }
    }
}
