package dev.fonebrew.data

import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.Role
import dev.fonebrew.domain.SamplingParams
import dev.fonebrew.domain.diff.ChangeOp
import dev.fonebrew.domain.diff.ChangeSet
import dev.fonebrew.domain.diff.FileChange
import dev.fonebrew.domain.git.GitContentsApi
import dev.fonebrew.domain.cost.PricingBook
import dev.fonebrew.domain.cost.UsagePricing
import dev.fonebrew.domain.cost.UsageReport
import dev.fonebrew.domain.git.AgentTarget
import dev.fonebrew.domain.git.GitHost
import dev.fonebrew.domain.ledger.LedgerCapture
import dev.fonebrew.domain.ledger.LedgerEntry
import dev.fonebrew.domain.ledger.Tier
import dev.fonebrew.domain.git.GitTreeApi
import dev.fonebrew.domain.ide.CommitAnchor
import dev.fonebrew.inference.EngineProvider
import dev.fonebrew.inference.ModelRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Wires the headless agentic repo loop (the `domain/ide/RepoWorkLoop` seams) to real Git I/O and a
 * model: read candidate files → ask the model for **complete new file bodies** → a [ChangeSet] →
 * (the caller reviews) → commit each changed file via the Contents API.
 *
 * **Review-first (sovereignty):** this never commits without the caller calling [commit] on an
 * approved subset. Network + generation are owner-verified (no host/model in CI); the model-output
 * parser ([parseFileBlocks]) is the pure, JVM-tested part.
 *
 * **Graph-wave lane D:** [treeRepository], when wired (see [AppContainer][dev.fonebrew.di.
 * AppContainer]), closes the gap [commit]'s own pre-lane-D KDoc named — a successful commit now
 * mints a [CommitAnchor] node per returned sha into the real message tree, giving
 * [dev.fonebrew.domain.thread.ThreadGraphProjector] a queryable anchor to project a COMMIT node
 * from. `null` (the default) preserves the exact old behavior for any caller that doesn't wire a
 * tree store — commit succeeds or fails identically, just with nothing recorded.
 */
class AgentRepoRunner(
    private val transport: GitTransport,
    private val hostStore: GitHostStore,
    private val registry: ModelRegistry,
    private val engines: EngineProvider,
    private val treeRepository: MessageTreeRepository? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGen: () -> String = { UUID.randomUUID().toString() },
    /** Usage-ledger sink ("Myself" record). Wired in AppContainer; the default drops rows. */
    private val appendLedger: suspend (LedgerEntry) -> Unit = {},
    /** Price lookup by engine tokenizer id - the same book a chat turn prices through. */
    private val priceFor: (String) -> UsagePricing = { PricingBook().priceFor(it) },
    /** Counts a cloud call against the provider's free tier, as chat does. */
    private val recordFreeTier: (providerId: String, tokensIn: Long, tokensOut: Long) -> Unit = { _, _, _ -> },
) {
    data class Proposal(val changeSet: ChangeSet, val filesRead: List<String>, val rawModelOutput: String)

    /** The host the user picked in the Agent tab (only consulted when several are connected). */
    @Volatile var chosenHostId: String? = null

    /** Which host Run/Commit will target - explicit, never "whichever is first" (see [AgentTarget]). */
    fun target(): AgentTarget.Resolution = AgentTarget.resolve(hostStore.hosts.value, chosenHostId)

    private fun hostAndToken(): Pair<GitHost, String>? {
        val host = (target() as? AgentTarget.Resolution.Resolved)?.host ?: return null
        val token = hostStore.token(host.id) ?: return null
        return host to token
    }

    /** True when any Git host is connected (the target may still need choosing - see [target]). */
    val hasHost: Boolean get() = hostStore.hosts.value.isNotEmpty()

    /** Read [paths] from the connected repo (the model's read-set — shown to the user). */
    suspend fun read(paths: List<String>): Map<String, String> {
        val (host, token) = hostAndToken() ?: return emptyMap() // ambiguous/none: the UI gates Run on target()
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
        val now = clock()
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
        // Ledger: a write failure must never fail the proposal itself.
        runCatching {
            recordAgentUsage(
                spec = spec,
                tokenizerId = engine.tokenizerId,
                reported = (engine as? dev.fonebrew.inference.cloud.CloudEngine)?.lastUsage,
                countTokens = { engine.countTokens(it) },
                promptText = msgs.joinToString("\n") { it.content },
                outputText = raw,
                startedAt = now,
                finishedAt = clock(),
                runId = idGen(),
                appendLedger = appendLedger,
                priceFor = priceFor,
                recordFreeTier = recordFreeTier,
            )
        }
        val changes = parseFileBlocks(raw).map { (path, newText) ->
            FileChange(path = path, oldText = context[path] ?: "", newText = newText)
        }
        Proposal(ChangeSet(changes), context.keys.toList(), raw)
    }

    /**
     * Commit the change set. Prefers **one squashed commit** via the Git tree API; if that fails
     * (e.g. a host that doesn't expose Git Data), falls back to per-file Contents-API commits.
     * Returns the commit id(s).
     *
     * **Graph-wave lane D (closes the 2026-08-29 audit gap this KDoc used to document):** every
     * returned sha is now also handed to [recordCommitAnchors], which mints one [CommitAnchor]
     * node per sha and, when [treeRepository] is wired, inserts it — giving the thread graph a
     * real, queryable anchor. Unlike a loop run (`RunLog`/`GraphRunLog` already tag a tree root
     * with `loopRunId` — see `ThreadGraphProjector`'s `RUN_ROOT` kind), this call site had no such
     * anchor before lane D; see [CommitAnchor]'s own KDoc for why the minted node is a fresh,
     * self-standing root rather than attached to an existing conversation node (there is no
     * existing message/run node in scope here to attach it to). A caller that hasn't wired
     * [treeRepository] sees byte-identical behavior to before this lane — [recordCommitAnchors]
     * is a no-op when it's null.
     */
    suspend fun commit(changeSet: ChangeSet, message: String): Result<List<String>> = runCatching {
        val (host, token) = hostAndToken() ?: error("no Git host selected - choose which host to commit to")
        val ids = squashedCommit(transport, host, token, changeSet, message)
            .getOrElse { perFileCommit(transport, host, token, changeSet, message).getOrThrow() }
        recordCommitAnchors(treeRepository, host, ids, message, clock, idGen)
        ids
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

        /**
         * One commit for the whole change set: get-ref → tree → commit → update-ref.
         *
         * **Graph-wave lane D:** moved from a private instance method to `internal` in the
         * companion object, taking [transport] explicitly instead of reading an instance field —
         * [AgentRepoRunner]'s own constructor needs a real [GitHostStore]/[ModelRegistry]/
         * [EngineProvider] (all Android/Context-dependent, none fakeable in a plain JVM test — see
         * `AgentRepoRunnerCommitTest`'s own header comment), so this call site had **zero** test
         * coverage before lane D (`AgentRepoParseTest` only ever exercised [parseFileBlocks]).
         * Behavior is byte-identical to before; only the visibility/shape changed, exactly so a
         * test can drive it with a fake [GitTransport] (the same idiom `GitEditTest` already
         * uses) without needing the rest of [AgentRepoRunner]'s heavy dependencies at all.
         */
        internal suspend fun squashedCommit(transport: GitTransport, host: GitHost, token: String, changeSet: ChangeSet, message: String): Result<List<String>> = runCatching {
            val files = changeSet.effective.filter { it.op != ChangeOp.DELETE }.map { it.path to it.newText }
            // A reviewed deletion is a real change: a tree entry with sha null on the base tree.
            val deletions = changeSet.effective.filter { it.op == ChangeOp.DELETE }.map { it.path }
            require(files.isNotEmpty() || deletions.isNotEmpty()) { "nothing to commit" }
            suspend fun exec(req: dev.fonebrew.domain.git.GitRequest): String {
                val r = transport.execute(req)
                if (r.code !in 200..299) error("HTTP ${r.code}: ${r.body.take(180)}")
                return r.body
            }
            val headSha = parseRefSha(exec(GitTreeApi.getRef(host, token)))
            val baseTree = JSONObject(exec(GitTreeApi.getCommit(host, headSha, token))).getJSONObject("tree").getString("sha")
            val newTree = JSONObject(exec(GitTreeApi.createTree(host, baseTree, files, token, deletions))).getString("sha")
            val newCommit = JSONObject(exec(GitTreeApi.createCommit(host, message, newTree, headSha, token))).getString("sha")
            exec(GitTreeApi.updateRef(host, newCommit, token))
            listOf(newCommit)
        }

        /** Fallback: one Contents-API commit per file (CREATE/MODIFY via PUT, DELETE via the blob sha;
         *  a delete that cannot be applied fails the whole call naming the file - never skipped).
         *  See [squashedCommit]'s own KDoc for why this moved to the companion object in lane D. */
        internal suspend fun perFileCommit(transport: GitTransport, host: GitHost, token: String, changeSet: ChangeSet, message: String): Result<List<String>> = runCatching {
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
                    ChangeOp.DELETE -> {
                        val g = transport.execute(GitContentsApi.getFile(host, fc.path, token))
                        if (g.code !in 200..299) error("delete ${fc.path}: could not read it (HTTP ${g.code})")
                        val sha = JSONObject(g.body).optString("sha").ifBlank { error("delete ${fc.path}: no blob sha") }
                        val r = transport.execute(GitContentsApi.deleteFile(host, fc.path, message, sha, token))
                        if (r.code !in 200..299) error("delete ${fc.path}: HTTP ${r.code}")
                        ids += JSONObject(r.body).optJSONObject("commit")?.optString("sha").orEmpty()
                    }
                }
            }
            ids
        }

        /** The success line for a commit: adds, changes and deletes stated separately (never a bare
         *  file count that could hide a dropped deletion). Counts the effective, approved set. */
        fun describeCommit(changeSet: ChangeSet, ids: List<String>): String {
            val ops = changeSet.effective.groupingBy { it.op }.eachCount()
            return "committed: ${ops[ChangeOp.CREATE] ?: 0} added, ${ops[ChangeOp.MODIFY] ?: 0} changed, " +
                "${ops[ChangeOp.DELETE] ?: 0} deleted (${ids.size} commit${if (ids.size == 1) "" else "s"})"
        }

        /**
         * Writes one `surface = "agent"` ledger row for a finished proposal call. Provider-reported
         * usage when the engine gave any; otherwise the tokenizer's own counts flagged estimated;
         * if neither is known, no row (see [LedgerCapture.agentRun]). On-device is recorded as
         * on-device at zero cost. Companion + explicit deps so a JVM test drives it without Android.
         */
        internal suspend fun recordAgentUsage(
            spec: dev.fonebrew.domain.model.ModelSpec,
            tokenizerId: String,
            reported: UsageReport?,
            countTokens: suspend (String) -> Int,
            promptText: String,
            outputText: String,
            startedAt: Long,
            finishedAt: Long,
            runId: String,
            appendLedger: suspend (LedgerEntry) -> Unit,
            priceFor: (String) -> UsagePricing,
            recordFreeTier: (String, Long, Long) -> Unit,
        ) {
            val tier = if (spec.isOnDevice) Tier.ON_DEVICE else Tier.CLOUD
            val usage = reported?.takeIf { it.totalTokens > 0 }
            val estIn = if (usage == null) runCatching { countTokens(promptText).toLong() }.getOrNull() else null
            val estOut = if (usage == null) runCatching { countTokens(outputText).toLong() }.getOrNull() else null
            val entry = LedgerCapture.agentRun(
                timestampMillis = finishedAt,
                runId = runId,
                projectId = null,
                model = spec.id,
                provider = spec.providerId ?: "on-device",
                tier = tier,
                reported = usage,
                estimatedIn = estIn,
                estimatedOut = estOut,
                latencyMs = finishedAt - startedAt,
                priceMinor = { u -> u.toAdviceCost(priceFor(tokenizerId)).moneyMinor },
            ) ?: return
            if (usage != null) spec.providerId?.let { recordFreeTier(it, usage.inputTokens, usage.outputTokens) }
            appendLedger(entry)
        }

        private fun parseRefSha(body: String): String {
            val trimmed = body.trimStart()
            val obj = if (trimmed.startsWith("[")) JSONArray(body).getJSONObject(0) else JSONObject(body)
            return obj.getJSONObject("object").getString("sha")
        }

        /**
         * **Graph-wave lane D.** Mints one [CommitAnchor] node per entry in [ids] (a squashed
         * commit returns exactly one; the per-file fallback returns one per file — see
         * [CommitAnchor.node]'s own KDoc for why this is one-call-per-sha, not a single node) and,
         * when [treeRepository] is non-null, inserts each into the real message tree. A no-op
         * (mints nothing, inserts nothing) when [treeRepository] is null — the default, and
         * exactly the pre-lane-D behavior for a caller that hasn't wired one.
         *
         * `internal` + companion-object (not an instance method) for the same testability reason
         * [squashedCommit]/[perFileCommit] moved: a test can call this directly with a fake
         * [MessageTreeRepository] (real fake DAOs, no Room/Android — the same idiom
         * `ThreadObserverTest` already uses) without constructing a full [AgentRepoRunner].
         */
        internal suspend fun recordCommitAnchors(
            treeRepository: MessageTreeRepository?,
            host: GitHost,
            ids: List<String>,
            message: String,
            clock: () -> Long,
            idGen: () -> String,
        ) {
            val repo = treeRepository ?: return
            val repoRef = CommitAnchor.repoRef(host)
            for (sha in ids) {
                repo.insert(CommitAnchor.node(id = idGen(), sha = sha, repoRef = repoRef, createdAt = clock(), label = message))
            }
        }
    }
}
