package dev.aarso.data

import dev.aarso.domain.git.GitContentsApi
import dev.aarso.domain.git.GitHost
import dev.aarso.domain.sync.TreeArchive
import dev.aarso.domain.sync.TreeBackup
import org.json.JSONArray

/**
 * Tree-sovereignty v1 (docs/design/tree-sovereignty.md): back up the append-only
 * message tree to a Git repo the user owns, as open node files. Append-only ⇒ we
 * only ever create the files not already on the remote — no updates, no conflicts.
 *
 * Talks ONLY to the user's host. Secrets are never written (keys live in the
 * Keystore, never in the tree). Network is owner-verified — there's no host in CI.
 */
class GitBackup(
    private val repository: MessageTreeRepository,
    private val transport: GitTransport,
) {
    data class Report(val created: Int, val skipped: Int, val failed: Int)
    data class PullReport(val imported: Int, val alreadyHad: Int, val orphans: Int)

    suspend fun backUp(host: GitHost, token: String): Result<Report> = runCatching {
        val files = TreeArchive.write(repository.tree().allNodes())
        val existing = remoteExisting(host, token)
        val toCreate = TreeBackup.plan(files, existing)
        var created = 0
        var failed = 0
        for ((path, content) in toCreate) {
            val req = GitContentsApi.putFile(host, path, content, "Aarso backup: $path", sha = null, token = token)
            val r = transport.execute(req)
            if (r.code in 200..299) created++ else failed++
        }
        Report(created = created, skipped = files.size - toCreate.size, failed = failed)
    }

    /**
     * Restore = **union import**: read the node files from the repo and add any node
     * we don't already have, parent-before-child (the tree tolerates extra branches/
     * roots by design). Existing nodes are never touched (append-only).
     */
    suspend fun pull(host: GitHost, token: String): Result<PullReport> = runCatching {
        val files = LinkedHashMap<String, String>()
        val dir = transport.execute(GitContentsApi.listDir(host, "aarso/nodes", token))
        if (dir.code !in 200..299) error("HTTP ${dir.code} listing nodes — nothing to restore")
        val arr = runCatching { JSONArray(dir.body) }.getOrNull() ?: JSONArray()
        for (i in 0 until arr.length()) {
            val path = arr.getJSONObject(i).optString("path")
            if (path.isBlank() || !path.endsWith(".json")) continue
            val f = transport.execute(GitContentsApi.getFile(host, path, token))
            if (f.code in 200..299) decodeContentsBody(f.body)?.let { files[path] = it }
        }
        val incoming = TreeArchive.read(files)
        val existing = repository.tree().allNodes().map { it.id }.toSet()
        val plan = TreeBackup.importPlan(incoming, existing)
        for (node in plan.toInsert) repository.insert(node)
        PullReport(imported = plan.toInsert.size, alreadyHad = incoming.size - plan.toInsert.size - plan.orphans.size, orphans = plan.orphans.size)
    }

    /** Repo-relative paths already present (the nodes dir listing + the manifest). */
    private suspend fun remoteExisting(host: GitHost, token: String): Set<String> {
        val out = HashSet<String>()
        val dir = transport.execute(GitContentsApi.listDir(host, "aarso/nodes", token))
        if (dir.code in 200..299) {
            runCatching { JSONArray(dir.body) }.getOrNull()?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.getJSONObject(i).optString("path").takeIf { it.isNotBlank() }?.let(out::add)
                }
            }
        }
        val manifest = transport.execute(GitContentsApi.getFile(host, TreeArchive.MANIFEST_PATH, token))
        if (manifest.code in 200..299) out += TreeArchive.MANIFEST_PATH
        return out
    }
}
