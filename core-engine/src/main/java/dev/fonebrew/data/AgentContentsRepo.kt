package dev.fonebrew.data

import dev.fonebrew.domain.agent.AgentRepo
import dev.fonebrew.domain.agent.RepoEntry
import dev.fonebrew.domain.agent.RepoOutcome
import dev.fonebrew.domain.git.GitContentsApi
import dev.fonebrew.domain.git.GitHost
import org.json.JSONArray

/**
 * The agent's read-only view of the user's own repo over the Contents API (the host the user
 * picked, at its branch). Reads only; the token is used for request headers and never appears in
 * an error string. Network behaviour is owner-verified; JVM tests drive it with a fake transport.
 */
class AgentContentsRepo(
    private val transport: GitTransport,
    private val host: GitHost,
    private val token: String,
) : AgentRepo {

    override suspend fun list(path: String): RepoOutcome<List<RepoEntry>> = try {
        val r = transport.execute(GitContentsApi.listDir(host, path, token))
        when {
            r.code == 404 -> RepoOutcome.Missing
            r.code !in 200..299 -> RepoOutcome.Failed("HTTP ${r.code}")
            !r.body.trimStart().startsWith("[") -> RepoOutcome.Failed("not a directory")
            else -> {
                val arr = JSONArray(r.body)
                RepoOutcome.Ok((0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { o ->
                    RepoEntry(
                        name = o.optString("name"), path = o.optString("path"),
                        isDir = o.optString("type") == "dir",
                        size = if (o.has("size")) o.optLong("size") else null,
                    )
                }.filter { it.name.isNotEmpty() && it.path.isNotEmpty() })
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        RepoOutcome.Failed(e.message?.take(120) ?: e::class.java.simpleName)
    }

    override suspend fun read(path: String): RepoOutcome<String> = try {
        val r = transport.execute(GitContentsApi.getFile(host, path, token))
        when {
            r.code == 404 -> RepoOutcome.Missing
            r.code !in 200..299 -> RepoOutcome.Failed("HTTP ${r.code}")
            else -> decodeContentsBody(r.body)?.let { RepoOutcome.Ok(it) }
                ?: RepoOutcome.Failed("not a readable file (directory, symlink or too large for the contents API)")
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        RepoOutcome.Failed(e.message?.take(120) ?: e::class.java.simpleName)
    }
}
