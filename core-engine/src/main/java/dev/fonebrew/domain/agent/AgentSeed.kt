package dev.fonebrew.domain.agent

/** One thing the user picked from the repo tree to seed the agent's context. */
data class ContextPick(val path: String, val isDir: Boolean)

data class SeedResult(val files: Map<String, String>, val notes: List<String>)

/**
 * Turns the user's picks into injected context files, and says so in [SeedResult.notes] (the log
 * shows them). Folders expand over the read-only repo seam to a bounded depth/count. Every path is
 * confined; every file goes through the workspace's size and binary guards; nothing is silently
 * dropped (refusals are listed with their reason).
 */
object AgentSeed {
    const val MAX_FILES = 40
    const val MAX_DEPTH = 3

    suspend fun build(ws: AgentWorkspace, picks: List<ContextPick>): SeedResult {
        val notes = mutableListOf<String>()
        val files = LinkedHashMap<String, String>()
        val refused = mutableListOf<String>()
        var budget = ws.budgets.maxContextBytes / 2
        var omitted = 0

        suspend fun add(path: String) {
            if (path in files) return
            if (files.size >= MAX_FILES) { omitted++; return }
            when (val f = ws.fetch(path)) {
                is Fetch.Text -> {
                    val n = f.text.toByteArray(Charsets.UTF_8).size
                    if (n > budget) { omitted++; refused += "$path (over the seed byte budget)" } else { budget -= n; files[path] = f.text }
                }
                is Fetch.Missing -> refused += "$path (not found)"
                is Fetch.Refused -> refused += f.reason
            }
        }

        suspend fun walk(dir: String, depth: Int) {
            when (val r = ws.listDir(dir)) {
                is RepoOutcome.Ok -> for (e in r.value.sortedBy { it.path }) {
                    if (AgentPaths.confine(e.path) !is AgentPaths.Result.Ok) continue
                    if (e.isDir) { if (depth < MAX_DEPTH) walk(e.path, depth + 1) } else add(e.path)
                }
                is RepoOutcome.Missing -> refused += "$dir/ (not found)"
                is RepoOutcome.Failed -> refused += "$dir/ (${r.reason})"
            }
        }

        for (pick in picks) {
            when (val c = AgentPaths.confine(pick.path, allowRoot = pick.isDir)) {
                is AgentPaths.Result.Rejected -> refused += "${pick.path} (${c.reason})"
                is AgentPaths.Result.Ok -> if (pick.isDir) walk(c.path, 1) else add(c.path)
            }
        }
        val total = files.values.sumOf { it.toByteArray(Charsets.UTF_8).size }
        if (picks.isNotEmpty()) {
            notes += "injected ${files.size} picked file(s), $total bytes: ${files.keys.joinToString(", ").ifEmpty { "(none)" }}"
            if (omitted > 0) notes += "$omitted more file(s) left out by the seed file/byte caps"
            if (refused.isNotEmpty()) notes += "not injected: ${refused.joinToString("; ")}"
        }
        return SeedResult(files, notes)
    }
}
