package dev.fonebrew.domain.git

import dev.fonebrew.domain.git.GitPublishSupport.headers
import dev.fonebrew.domain.git.GitPublishSupport.repoBase
import org.json.JSONObject

/**
 * Create a branch from a base commit sha - the first step of publishing agent work for review
 * (branch -> commit -> pull request). Pure like [GitTreeApi]. GitHub uses `POST git/refs`; the
 * Gitea/Forgejo swagger has no `POST git/refs`, so it uses `POST branches` instead.
 */
object GitBranchApi {

    const val MAX_NAME_LENGTH = 200
    private const val AGENT_PREFIX = "fonebrew/agent/"
    private val FORBIDDEN_CHARS = " ~^:?*[\\\u007f".toSet()
    private val SHA = Regex("^([0-9a-fA-F]{40}|[0-9a-fA-F]{64})$")

    /** Null when [name] is a valid branch name by git's ref rules (check-ref-format) plus our bound;
     *  otherwise the human-readable reason. */
    fun validate(name: String): String? {
        if (name.isEmpty()) return "branch name is empty"
        if (name.length > MAX_NAME_LENGTH) return "branch name is longer than $MAX_NAME_LENGTH characters"
        if (name == "@") return "branch name cannot be '@'"
        if (name.startsWith("-")) return "branch name cannot start with '-'"
        if (name.startsWith("/")) return "branch name cannot start with '/'"
        if (name.endsWith("/")) return "branch name cannot end with '/'"
        if (name.endsWith(".")) return "branch name cannot end with '.'"
        if (name.contains("..")) return "branch name cannot contain '..'"
        if (name.contains("//")) return "branch name cannot contain '//'"
        if (name.contains("@{")) return "branch name cannot contain '@{'"
        if (name.any { it.code < 0x20 || it in FORBIDDEN_CHARS }) {
            return "branch name contains a space, control or forbidden character (~ ^ : ? * [ \\)"
        }
        for (part in name.split('/')) {
            if (part.startsWith(".")) return "a branch name component cannot start with '.'"
            if (part.endsWith(".lock")) return "a branch name component cannot end with '.lock'"
        }
        return null
    }

    /** Deterministic agent branch name `fonebrew/agent/<slug>-<shortId>`. [slug] is lowercased and
     *  reduced to `[a-z0-9-]` (<= 40 chars, "change" if nothing survives); [shortId] is reduced to
     *  `[a-z0-9]` (<= 8 chars, "0" if nothing survives). The caller supplies the id - no clock or RNG here. */
    fun suggestName(slug: String, shortId: String): String {
        val s = slug.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
            .joinToString("").replace(Regex("-+"), "-").trim('-').take(40).trim('-').ifEmpty { "change" }
        val id = shortId.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(8).ifEmpty { "0" }
        return "$AGENT_PREFIX$s-$id"
    }

    /** POST a new branch [name] pointing at [baseSha] (a full 40- or 64-hex commit sha). */
    fun createBranch(host: GitHost, name: String, baseSha: String, token: String): GitBuild {
        validate(name)?.let { return GitBuild.Invalid(it) }
        if (!SHA.matches(baseSha)) return GitBuild.Invalid("base sha must be a full 40 or 64 character hex commit id")
        val (path, body) = when (host.kind) {
            GitHostKind.GITHUB -> "git/refs" to JSONObject().put("ref", "refs/heads/$name").put("sha", baseSha)
            // old_ref_name: "Name of the old branch/tag/commit to create from" (Forgejo/Gitea swagger).
            GitHostKind.GITEA -> "branches" to JSONObject().put("new_branch_name", name).put("old_ref_name", baseSha)
        }
        return GitBuild.Ready(GitRequest("POST", "${repoBase(host)}/$path", headers(host, token, json = true), body.toString()))
    }

    /** Parse the create-branch response (201) into the created branch name. */
    fun parseCreated(host: GitHost, status: Int, body: String): GitApiResult<String> =
        GitPublishSupport.parseObject(status, body, setOf(201), "create-branch") { o ->
            when (host.kind) {
                GitHostKind.GITHUB -> o.optString("ref").removePrefix("refs/heads/").takeIf { it.isNotEmpty() && o.optString("ref").startsWith("refs/heads/") }
                GitHostKind.GITEA -> o.optString("name").takeIf { it.isNotEmpty() }
            }
        }
}
