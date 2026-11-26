package dev.fonebrew.domain.agent

/** In-memory repo: [files] maps path -> text. Directories are implied by path prefixes. */
class FakeRepo(val files: Map<String, String>, private val failing: Set<String> = emptySet()) : AgentRepo {
    val reads = mutableListOf<String>()
    val lists = mutableListOf<String>()

    override suspend fun list(path: String): RepoOutcome<List<RepoEntry>> {
        lists += path
        if (path in failing) return RepoOutcome.Failed("HTTP 500")
        val prefix = if (path.isEmpty()) "" else "$path/"
        val under = files.keys.filter { it.startsWith(prefix) }
        if (under.isEmpty() && path.isNotEmpty()) return RepoOutcome.Missing
        val names = under.map { it.removePrefix(prefix).substringBefore('/') to it.removePrefix(prefix).contains('/') }.distinct()
        return RepoOutcome.Ok(names.map { (n, dir) -> RepoEntry(n, prefix + n, dir, if (dir) null else files[prefix + n]?.length?.toLong()) })
    }

    override suspend fun read(path: String): RepoOutcome<String> {
        reads += path
        if (path in failing) return RepoOutcome.Failed("HTTP 500")
        return files[path]?.let { RepoOutcome.Ok(it) } ?: RepoOutcome.Missing
    }
}
