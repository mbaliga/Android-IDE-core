package dev.aarso.domain.sync

import dev.aarso.domain.git.GitContentsApi
import dev.aarso.domain.git.GitHost
import dev.aarso.domain.git.GitRequest
import dev.aarso.domain.loop.Loop
import org.json.JSONArray

/**
 * Syncs loop **definitions** to the user's own Git host as standard BPMN 2.0 files (P6,
 * docs/build-plan.md; complements `GitBackup`/`TreeArchive`, which already sync the message
 * tree). Sovereignty: a loop is an open `.bpmn` you own and can edit in any BPMN tool — Aarso
 * is just one lens over it. Pure request builders over [GitContentsApi] (like `BuildsApi`); the
 * transport + token live in the data layer (owner-verified — no host in CI). JVM-tested.
 */
object LoopSync {

    /** Loops live together under one directory so they read as a folder of `.bpmn` files. */
    const val LOOPS_DIR = "loops"

    /** The conventional path for a loop's BPMN file. */
    fun pathFor(loopId: String): String = "$LOOPS_DIR/$loopId.bpmn"

    /** The loop id encoded in a `loops/<id>.bpmn` path, or null if it isn't one. */
    fun idForPath(path: String): String? {
        val name = path.substringAfterLast('/')
        if (!path.startsWith("$LOOPS_DIR/") || !name.endsWith(".bpmn")) return null
        return name.removeSuffix(".bpmn")
    }

    /**
     * Push (create or update) [loop]'s BPMN to the host. Requires the loop to carry serialised
     * BPMN ([Loop.bpmnXml]); [sha] is the existing file's blob sha when updating (null to create).
     */
    fun push(host: GitHost, loop: Loop, sha: String?, token: String): GitRequest {
        val xml = loop.bpmnXml ?: error("loop ${loop.id} has no BPMN to sync")
        return GitContentsApi.putFile(
            host = host,
            path = pathFor(loop.id),
            content = xml,
            message = "Sync loop: ${loop.name.ifBlank { loop.id }}",
            sha = sha,
            token = token,
        )
    }

    /** Fetch one loop's BPMN file. */
    fun fetch(host: GitHost, loopId: String, token: String): GitRequest =
        GitContentsApi.getFile(host, pathFor(loopId), token)

    /** List the loops directory (to discover what's on the host). */
    fun list(host: GitHost, token: String): GitRequest =
        GitContentsApi.listDir(host, LOOPS_DIR, token)

    /** Parse a contents-API directory listing into the loop ids it contains (skips non-`.bpmn`). */
    fun parseLoopIds(listingJson: String): List<String> {
        val arr = runCatching { JSONArray(listingJson) }.getOrNull() ?: return emptyList()
        val ids = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val name = arr.optJSONObject(i)?.optString("name").orEmpty()
            if (name.endsWith(".bpmn")) ids += name.removeSuffix(".bpmn")
        }
        return ids
    }

    /**
     * Which local loops need pushing: those with BPMN that are absent from [remoteIds]. (Content
     * comparison for *changed* loops needs the remote blob sha/body and is the transport's job;
     * this is the cheap "what's missing remotely" plan callers start from.)
     */
    fun toPush(local: List<Loop>, remoteIds: Set<String>): List<Loop> =
        local.filter { it.bpmnXml != null && it.id !in remoteIds }

    /** Which remote loop ids aren't held locally (candidates to pull). */
    fun toPull(localIds: Set<String>, remoteIds: List<String>): List<String> =
        remoteIds.filter { it !in localIds }
}
