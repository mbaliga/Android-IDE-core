package dev.aarso.data

import dev.aarso.domain.git.GitContentsApi
import dev.aarso.domain.git.GitHost
import org.json.JSONArray

/**
 * Read-only repo browse — the first step of the coding assistant
 * (docs/design/coding-assistant.md): list a directory, open a file. Talks only to
 * the user's host (the contents REST API). Network is owner-verified.
 */
class GitBrowse(private val transport: GitTransport) {

    data class Entry(val name: String, val path: String, val isDir: Boolean)

    /** List [path] ("" = repo root); directories first, then files, alphabetical. */
    suspend fun list(host: GitHost, token: String, path: String): Result<List<Entry>> = runCatching {
        val r = transport.execute(GitContentsApi.listDir(host, path, token))
        if (r.code !in 200..299) error("HTTP ${r.code}")
        val arr = JSONArray(r.body)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(o.getString("name"), o.getString("path"), o.optString("type") == "dir")
        }.sortedWith(compareByDescending<Entry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    /** Fetch + decode a file's text. */
    suspend fun read(host: GitHost, token: String, path: String): Result<String> = runCatching {
        val r = transport.execute(GitContentsApi.getFile(host, path, token))
        if (r.code !in 200..299) error("HTTP ${r.code}")
        decodeContentsBody(r.body) ?: error("could not decode ${path.substringAfterLast('/')}")
    }
}
