package dev.aarso.data

import dev.aarso.domain.bpmn.BpmnArchive
import dev.aarso.domain.git.GitContentsApi
import dev.aarso.domain.git.GitHost
import dev.aarso.domain.loop.Loop
import dev.aarso.domain.loop.LoopConfigBpmn
import dev.aarso.domain.sync.LoopSync
import org.json.JSONObject

/**
 * Wires the pure [LoopSync] request builders through [GitTransport] to actually push/pull loop
 * `.bpmn` files to the user's Git host (P6 made real). Loops are open BPMN you own — this keeps
 * a folder of them in your repo. Network round-trips are **owner-verified** (no host in CI); the
 * pure path/plan logic is JVM-tested in `LoopSyncTest`.
 */
class LoopSyncRepo(
    private val transport: GitTransport,
    private val gitHostStore: GitHostStore,
    private val loopStore: LoopStore,
) {
    private fun hostAndToken(): Pair<GitHost, String> {
        val host = gitHostStore.hosts.value.firstOrNull() ?: error("connect a Git host first (Settings → Git & coding)")
        val token = gitHostStore.token(host.id) ?: error("no token for ${host.displayName}")
        return host to token
    }

    /** Push every local loop that carries BPMN (create or update by sha). Returns the count. */
    suspend fun push(): Result<Int> = runCatching {
        val (host, token) = hostAndToken()
        var n = 0
        for (loop in loopStore.loops.value) {
            if (loop.bpmnXml == null) continue
            val sha = existingSha(host, token, LoopSync.pathFor(loop.id))
            val put = transport.execute(LoopSync.push(host, loop, sha, token))
            if (put.code !in 200..299) error("push '${loop.name}': HTTP ${put.code}")
            n++
        }
        n
    }

    /** Pull remote loops not held locally into the [LoopStore]. Returns the count added. */
    suspend fun pull(): Result<Int> = runCatching {
        val (host, token) = hostAndToken()
        val list = transport.execute(LoopSync.list(host, token))
        if (list.code == 404) return@runCatching 0 // no loops/ dir yet
        if (list.code !in 200..299) error("list loops: HTTP ${list.code}")

        val remoteIds = LoopSync.parseLoopIds(list.body)
        val localIds = loopStore.loops.value.map { it.id }.toSet()
        var n = 0
        for (id in LoopSync.toPull(localIds, remoteIds)) {
            val get = transport.execute(LoopSync.fetch(host, id, token))
            if (get.code !in 200..299) continue
            val xml = decodeContentsBody(get.body) ?: continue
            val name = runCatching { LoopConfigBpmn.fromGraph(BpmnArchive.read(xml)).name }.getOrNull()
            loopStore.save(Loop(id = id, name = name?.ifBlank { id } ?: id, bpmnXml = xml))
            n++
        }
        n
    }

    /** The blob sha of a file if it already exists, else null (→ create). */
    private suspend fun existingSha(host: GitHost, token: String, path: String): String? {
        val r = transport.execute(GitContentsApi.getFile(host, path, token))
        if (r.code !in 200..299) return null
        return runCatching { JSONObject(r.body).optString("sha").ifBlank { null } }.getOrNull()
    }
}
