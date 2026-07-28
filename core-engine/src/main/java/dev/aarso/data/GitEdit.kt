package dev.aarso.data

import dev.aarso.domain.diff.LineDiff
import dev.aarso.domain.git.GitContentsApi
import dev.aarso.domain.git.GitHost
import org.json.JSONObject

/**
 * Single-file edit → **review** → commit: the writing half of the coding assistant
 * (docs/design/coding-assistant.md step 3). It [open]s a file *with its blob sha*
 * (the sha is required to update it on the host), exposes a local [unified] diff /
 * [preview] so the change can be shown to the user, and [commit]s the approved new
 * text on the host's branch via the contents API.
 *
 * No silent writes: this class performs only the commit the caller has already had
 * the user approve, and it refuses a no-op so an empty commit is never created. It
 * talks ONLY to the user's host. The network round-trip is owner-verified — there is
 * no host to hit in CI — but the request shape and sha threading are JVM-tested
 * against a fake [GitTransport].
 */
class GitEdit(private val transport: GitTransport) {

    /** A file fetched for editing: its current [text] and the [sha] needed to PUT an update. */
    data class FileState(val path: String, val text: String, val sha: String)

    /** GET a file's text + blob sha at the host's branch. */
    suspend fun open(host: GitHost, token: String, path: String): Result<FileState> = runCatching {
        val r = transport.execute(GitContentsApi.getFile(host, path, token))
        if (r.code !in 200..299) error("HTTP ${r.code}")
        val sha = JSONObject(r.body).optString("sha").ifBlank { error("no sha for $path") }
        val text = decodeContentsBody(r.body) ?: error("could not decode ${path.substringAfterLast('/')}")
        FileState(path, text, sha)
    }

    /** A glanceable "+n −m" for a proposed change — computed locally, no network. */
    fun preview(file: FileState, newText: String): LineDiff.Stat = LineDiff.stat(file.text, newText)

    /** The full unified diff of a proposed change, with `a/<path>`/`b/<path>` headers. */
    fun unified(file: FileState, newText: String): String =
        LineDiff.unified(file.text, newText, oldPath = "a/${file.path}", newPath = "b/${file.path}")

    /**
     * Commit [newText] over [file] on the host's branch; returns the new commit sha.
     * Rejects an unchanged [newText] so we never push an empty commit. The caller is
     * expected to have shown [unified] and gotten the user's approval first.
     */
    suspend fun commit(
        host: GitHost,
        token: String,
        file: FileState,
        newText: String,
        message: String,
    ): Result<String> = runCatching {
        require(newText != file.text) { "no changes to commit" }
        val r = transport.execute(
            GitContentsApi.putFile(host, file.path, newText, message, file.sha, token),
        )
        if (r.code !in 200..299) error("HTTP ${r.code}")
        // GitHub and Gitea both return { "commit": { "sha": ... } }.
        JSONObject(r.body).optJSONObject("commit")?.optString("sha").orEmpty()
    }
}
