package dev.aarso.data

import dev.aarso.domain.MessageNode
import dev.aarso.domain.Role
import dev.aarso.domain.SamplingParams
import dev.aarso.domain.diff.ChangeOp
import dev.aarso.domain.diff.ChangeSet
import dev.aarso.domain.diff.FileChange
import dev.aarso.domain.git.GitContentsApi
import dev.aarso.domain.git.GitHost
import dev.aarso.domain.git.GitTreeApi
import dev.aarso.inference.EngineProvider
import dev.aarso.inference.ModelRegistry
import org.json.JSONArray
import org.json.JSONObject

/**
 * Wires the headless agentic repo loop (the `domain/ide/RepoWorkLoop` seams) to real Git I/O and a
 * model: read candidate files → ask the model for **complete new file bodies** → a [ChangeSet] →
 * (the caller reviews) → commit each changed file via the Contents API.
 *
 * **Review-first (sovereignty):** this never commits without the caller calling [commit] on an
 * approved subset. Network + generation are owner-verified (no host/model in CI); the model-output
 * parser ([parseFileBlocks]) is the pure, JVM-tested part.
 */
class AgentRepoRunner(
    private val transport: GitTransport,
    private val hostStore: GitHostStore,
    private val registry: ModelRegistry,
    private val engines: EngineProvider,
) {
    data class Proposal(val changeSet: ChangeSet, val filesRead: List<String>, val rawModelOutput: String)

    private fun hostAndToken(): Pair<GitHost, String>? {
        val host = hostStore.hosts.value.firstOrNull() ?: return null
        val token = hostStore.token(host.id) ?: return null
        return host to token
    }

    val hasHost: Boolean get() = hostAndToken() != null

    /** Read [paths] from the connected repo (the model's read-set — shown to the user). */
    suspend fun read(paths: List<String>): Map<String, String> {
        val (host, token) = hostAndToken() ?: return emptyMap()
        val browse = GitBrowse(transport)
        val out = LinkedHashMap<String, String>()
        for (p in paths) browse.read(host, token, p).getOrNull()?.let { out[p] = it }
        return out
    }

    /** Ask [modelId] for a [ChangeSet] addressing [objective] over the read [context]. */
    suspend fun propose(objective: String, context: Map<String, String>, modelId: String): Result<Proposal> = runCatching {
        val spec = registry.byId(modelId) ?: error("model not found")
        val engine = engines.engineFor(spec) ?: error("model not runnable")
        engine.loadModel(spec.modelPath ?: "(dev)", spec.contextWindow)
        val now = System.currentTimeMillis()
        val userMsg = buildString {
            append("Objective:\n").append(objective).append("\n\n")
            if (context.isEmpty()) append("(no files were provided as context)\n\n")
            for ((path, body) in context) {
                append("<<<FILE ").append(path).append(">>>\n").append(body)
                if (!body.endsWith("\n")) append('\n')
                append("<<<END>>>\n\n")
            }
            append("Return the COMPLETE new content for each file you change, each wrapped exactly ")
            append("as <<<FILE path>>> on its own line, the full file body, then <<<END>>> on its ")
            append("own line. Only include files you actually change. No commentary outside the blocks.")
        }
        val msgs = listOf(
            MessageNode("agent-sys", null, Role.SYSTEM, AGENT_SYSTEM, createdAt = now),
            MessageNode("agent-usr", "agent-sys", Role.USER, userMsg, createdAt = now + 1),
        )
        val sb = StringBuilder()
        engine.generate(msgs, SamplingParams()).collect { sb.append(it.text) }
        val raw = sb.toString()
        val changes = parseFileBlocks(raw).map { (path, newText) ->
            FileChange(path = path, oldText = context[path] ?: "", newText = newText)
        }
        Proposal(ChangeSet(changes), context.keys.toList(), raw)
    }

    /**
     * Commit the change set. Prefers **one squashed commit** via the Git tree API; if that fails
     * (e.g. a host that doesn't expose Git Data), falls back to per-file Contents-API commits.
     * Returns the commit id(s).
     */
    suspend fun commit(changeSet: ChangeSet, message: String): Result<List<String>> = runCatching {
        val (host, token) = hostAndToken() ?: error("no Git host connected")
        squashedCommit(host, token, changeSet, message)
            .getOrElse { perFileCommit(host, token, changeSet, message).getOrThrow() }
    }

    /** One commit for the whole change set: get-ref → tree → commit → update-ref. */
    private suspend fun squashedCommit(host: GitHost, token: String, changeSet: ChangeSet, message: String): Result<List<String>> = runCatching {
        val files = changeSet.effective.filter { it.op != ChangeOp.DELETE }.map { it.path to it.newText }
        require(files.isNotEmpty()) { "nothing to commit" }
        suspend fun exec(req: dev.aarso.domain.git.GitRequest): String {
            val r = transport.execute(req)
            if (r.code !in 200..299) error("HTTP ${r.code}: ${r.body.take(180)}")
            return r.body
        }
        val headSha = parseRefSha(exec(GitTreeApi.getRef(host, token)))
        val baseTree = JSONObject(exec(GitTreeApi.getCommit(host, headSha, token))).getJSONObject("tree").getString("sha")
        val newTree = JSONObject(exec(GitTreeApi.createTree(host, baseTree, files, token))).getString("sha")
        val newCommit = JSONObject(exec(GitTreeApi.createCommit(host, message, newTree, headSha, token))).getString("sha")
        exec(GitTreeApi.updateRef(host, newCommit, token))
        listOf(newCommit)
    }

    /** Fallback: one Contents-API commit per file (CREATE/MODIFY; DELETE unsupported there). */
    private suspend fun perFileCommit(host: GitHost, token: String, changeSet: ChangeSet, message: String): Result<List<String>> = runCatching {
        val edit = GitEdit(transport)
        val ids = mutableListOf<String>()
        for (fc in changeSet.effective) {
            when (fc.op) {
                ChangeOp.CREATE -> {
                    val r = transport.execute(GitContentsApi.putFile(host, fc.path, fc.newText, message, null, token))
                    if (r.code !in 200..299) error("create ${fc.path}: HTTP ${r.code}")
                    ids += JSONObject(r.body).optJSONObject("commit")?.optString("sha").orEmpty()
                }
                ChangeOp.MODIFY -> {
                    val state = edit.open(host, token, fc.path).getOrThrow()
                    ids += edit.commit(host, token, state, fc.newText, message).getOrThrow()
                }
                ChangeOp.DELETE -> error("delete isn't supported via the Contents API yet (${fc.path})")
            }
        }
        ids
    }

    private fun parseRefSha(body: String): String {
        val trimmed = body.trimStart()
        val obj = if (trimmed.startsWith("[")) JSONArray(body).getJSONObject(0) else JSONObject(body)
        return obj.getJSONObject("object").getString("sha")
    }

    companion object {
        private const val AGENT_SYSTEM =
            "You are a precise coding agent working on the user's own repository. You make the " +
                "smallest change that fully meets the objective. You output complete file bodies " +
                "only, in the requested block format, and nothing else."

        private val BLOCK = Regex("<<<FILE\\s+(.+?)>>>\\r?\\n(.*?)<<<END>>>", RegexOption.DOT_MATCHES_ALL)

        /** Parse the model's `<<<FILE path>>> … <<<END>>>` blocks into (path, newText) pairs. Pure. */
        fun parseFileBlocks(raw: String): List<Pair<String, String>> =
            BLOCK.findAll(raw).map { m ->
                val path = m.groupValues[1].trim()
                val body = m.groupValues[2].removeSuffix("\n").removeSuffix("\r")
                path to body
            }.filter { it.first.isNotEmpty() }.toList()
    }
}
