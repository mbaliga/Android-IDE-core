package dev.fonebrew.domain.agent

import dev.fonebrew.domain.diff.ChangeSet
import dev.fonebrew.domain.diff.FileChange
import dev.fonebrew.domain.diff.LineDiff

data class RepoEntry(val name: String, val path: String, val isDir: Boolean, val size: Long? = null)

sealed interface RepoOutcome<out T> {
    data class Ok<T>(val value: T) : RepoOutcome<T>
    data object Missing : RepoOutcome<Nothing>
    data class Failed(val reason: String) : RepoOutcome<Nothing>
}

/** The only repo seam: read-only. The real adapter talks to the user's own Git host. */
interface AgentRepo {
    suspend fun list(path: String): RepoOutcome<List<RepoEntry>>
    suspend fun read(path: String): RepoOutcome<String>
}

sealed interface Fetch {
    data class Text(val text: String) : Fetch
    data object Missing : Fetch
    data class Refused(val reason: String) : Fetch
}

/**
 * The agent's working state for one run: what it has listed and fetched, and the pending
 * proposal. Writing happens ONLY here, in memory; the repo is never written from a tool.
 */
class AgentWorkspace(private val repo: AgentRepo, val budgets: AgentBudgets = AgentBudgets()) {
    private val fetched = LinkedHashMap<String, String>()
    private val listed = LinkedHashSet<String>()
    private class Pending(val oldText: String, val newText: String)
    private val pending = LinkedHashMap<String, Pending>()
    var finishSummary: String? = null
        private set

    val filesRead: List<String> get() = fetched.keys.toList()
    val listedPaths: Set<String> get() = listed
    val fetchedFiles: Map<String, String> get() = fetched

    suspend fun listDir(path: String): RepoOutcome<List<RepoEntry>> {
        val r = repo.list(path)
        if (r is RepoOutcome.Ok) r.value.forEach { listed += if (it.isDir) it.path + "/" else it.path }
        return r
    }

    /** Fetch [path] through the size and binary guards; cached after the first success. */
    suspend fun fetch(path: String): Fetch {
        fetched[path]?.let { return Fetch.Text(it) }
        return when (val r = repo.read(path)) {
            is RepoOutcome.Missing -> Fetch.Missing
            is RepoOutcome.Failed -> Fetch.Refused("could not read $path: ${r.reason}")
            is RepoOutcome.Ok -> {
                val bytes = r.value.toByteArray(Charsets.UTF_8).size
                when {
                    looksBinary(r.value) -> Fetch.Refused("$path looks like a binary file; the agent reads text files only")
                    bytes > budgets.maxFileBytes -> Fetch.Refused("$path is $bytes bytes, over the ${budgets.maxFileBytes} byte per-file cap")
                    else -> { fetched[path] = r.value; Fetch.Text(r.value) }
                }
            }
        }
    }

    fun propose(path: String, oldText: String, newText: String): String? {
        val others = pending.filterKeys { it != path }
        if (path !in pending && pending.size >= budgets.maxProposedFiles) {
            return "too many proposed files (cap ${budgets.maxProposedFiles})"
        }
        val total = others.values.sumOf { it.newText.toByteArray(Charsets.UTF_8).size } + newText.toByteArray(Charsets.UTF_8).size
        if (total > budgets.maxProposedBytes) return "proposal would exceed the ${budgets.maxProposedBytes} byte total cap"
        pending[path] = Pending(oldText, newText)
        return null
    }

    fun pendingStat(path: String): LineDiff.Stat? = pending[path]?.let { LineDiff.stat(it.oldText, it.newText) }
    fun hasPending(path: String) = path in pending

    fun setFinish(summary: String) { finishSummary = summary }

    /** The proposal so far, as the reviewable [ChangeSet] (no-ops are dropped by the set itself). */
    fun changeSet(): ChangeSet = ChangeSet(pending.map { (p, v) -> FileChange(p, v.oldText, v.newText) })

    companion object {
        fun looksBinary(text: String): Boolean {
            if (text.isEmpty()) return false
            if (text.indexOf('\u0000') >= 0) return true
            val sample = text.take(8_000)
            val bad = sample.count { it == '�' || (it.code < 0x20 && it != '\n' && it != '\r' && it != '\t' && it != '\u000c') }
            return bad * 50 > sample.length // more than 2% junk
        }
    }
}
