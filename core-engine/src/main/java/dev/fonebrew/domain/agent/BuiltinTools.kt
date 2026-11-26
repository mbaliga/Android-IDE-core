package dev.fonebrew.domain.agent

import dev.fonebrew.domain.diff.ChangeOp

/**
 * The built-in tool set over an [AgentWorkspace]. Read tools are READ_LOCAL; the two proposal
 * tools are also READ_LOCAL because they only add to an in-memory pending change set - the review
 * sheet and the publish sheet are the real consent gates for writing. A later work package
 * registers `terminal_exec` / `git_*` into the same [ToolRegistry]; the loop does not change.
 */
object BuiltinTools {
    const val MAX_LIST_ENTRIES = 200
    const val MAX_SEARCH_HITS = 30
    const val MAX_READ_LINES = 400

    fun registry(ws: AgentWorkspace): ToolRegistry = ToolRegistry()
        .register(listDir(ws)).register(readFile(ws)).register(search(ws))
        .register(proposeChange(ws)).register(proposeDelete(ws)).register(finish(ws))

    private inline fun <T> confined(raw: String, allowRoot: Boolean = false, f: (String) -> T, err: (String) -> T): T =
        when (val r = AgentPaths.confine(raw, allowRoot)) {
            is AgentPaths.Result.Ok -> f(r.path)
            is AgentPaths.Result.Rejected -> err(r.reason)
        }

    private fun listDir(ws: AgentWorkspace) = ToolSpec(
        "list_dir", SideEffect.READ_LOCAL, "list a directory of the repo ('' or . for the root)",
        listOf(ParamSpec("path", required = false)),
    ) { a ->
        confined(a.text("path"), allowRoot = true, f = { p ->
            when (val r = ws.listDir(p)) {
                is RepoOutcome.Missing -> ToolResult.error("no such directory: ${p.ifEmpty { "(root)" }}")
                is RepoOutcome.Failed -> ToolResult.error("could not list ${p.ifEmpty { "(root)" }}: ${r.reason}")
                is RepoOutcome.Ok -> {
                    val sorted = r.value.sortedWith(compareByDescending<RepoEntry> { it.isDir }.thenBy { it.name.lowercase() })
                    val shown = sorted.take(MAX_LIST_ENTRIES)
                    val text = shown.joinToString("\n") { (if (it.isDir) "dir  " else "file ") + it.name + if (it.isDir) "/" else "" } +
                        if (sorted.size > shown.size) "\n... ${sorted.size - shown.size} more entries not shown" else ""
                    ToolResult.ok("${sorted.size} entries in ${p.ifEmpty { "(root)" }}", text.ifEmpty { "(empty directory)" })
                }
            }
        }, err = { ToolResult.error(it) })
    }

    private fun readFile(ws: AgentWorkspace) = ToolSpec(
        "read_file", SideEffect.READ_LOCAL, "read a text file; optional 1-based inclusive line range start..end",
        listOf(ParamSpec("path"), ParamSpec("start", ParamType.INT, required = false), ParamSpec("end", ParamType.INT, required = false)),
    ) { a ->
        confined(a.text("path"), f = { p ->
            when (val f = ws.fetch(p)) {
                is Fetch.Missing -> ToolResult.error("no such file: $p")
                is Fetch.Refused -> ToolResult.error(f.reason)
                is Fetch.Text -> {
                    val lines = f.text.split("\n").let { if (it.size > 1 && it.last().isEmpty()) it.dropLast(1) else it }
                    val start = (a.int("start") ?: 1).coerceAtLeast(1)
                    val end = (a.int("end") ?: lines.size).coerceAtMost(lines.size)
                    if (lines.isEmpty()) ToolResult.ok("$p is empty", "(empty file)")
                    else if (start > end) ToolResult.error("line range ${a.int("start") ?: 1}..${a.int("end") ?: lines.size} is outside $p (${lines.size} lines)")
                    else {
                        val cappedEnd = minOf(end, start + MAX_READ_LINES - 1)
                        val shown = lines.subList(start - 1, cappedEnd)
                        val note = if (cappedEnd < end) " (capped at $MAX_READ_LINES lines; ask for the next range)" else ""
                        ToolResult.ok("$p lines $start-$cappedEnd of ${lines.size}$note", shown.joinToString("\n"))
                    }
                }
            }
        }, err = { ToolResult.error(it) })
    }

    private fun search(ws: AgentWorkspace) = ToolSpec(
        "search", SideEffect.READ_LOCAL,
        "case-insensitive text search over paths you have listed and files you have read (not the whole repo)",
        listOf(ParamSpec("query")),
    ) { a ->
        val q = a.text("query").trim()
        if (q.length < 2 || q.length > 100) return@ToolSpec ToolResult.error("query must be 2 to 100 characters")
        val hits = mutableListOf<String>()
        var total = 0
        for (path in ws.listedPaths) if (path.contains(q, ignoreCase = true)) { total++; if (hits.size < MAX_SEARCH_HITS) hits += "path  $path" }
        for ((path, text) in ws.fetchedFiles) {
            text.split("\n").forEachIndexed { i, line ->
                if (line.contains(q, ignoreCase = true)) { total++; if (hits.size < MAX_SEARCH_HITS) hits += "$path:${i + 1}: ${line.trim().take(160)}" }
            }
        }
        val scope = "searched ${ws.listedPaths.size} listed paths and ${ws.fetchedFiles.size} fetched files; anything else is not searched"
        ToolResult.ok(
            "$total match(es) for \"$q\" ($scope)",
            (if (hits.isEmpty()) "(no matches)" else hits.joinToString("\n")) +
                if (total > hits.size) "\n... ${total - hits.size} more matches not shown" else "",
        )
    }

    /** New file bodies end with a newline unless the file being replaced did not (keeps the diff honest). */
    internal fun normalizeEnding(old: String, body: String): String =
        if (old.isNotEmpty() && !old.endsWith("\n")) body else if (body.endsWith("\n")) body else body + "\n"

    private fun proposeChange(ws: AgentWorkspace) = ToolSpec(
        "propose_change", SideEffect.READ_LOCAL,
        "propose the COMPLETE new content of one file (new or existing); nothing is written until the user approves",
        listOf(ParamSpec("path")), takesBody = true,
    ) { a ->
        confined(a.text("path"), f = { p ->
            val body = a.body.orEmpty()
            if (body.isBlank()) return@confined ToolResult.error("propose_change: content is empty; use propose_delete to remove a file")
            val old = when (val f = ws.fetch(p)) {
                is Fetch.Missing -> ""
                is Fetch.Refused -> return@confined ToolResult.error(f.reason)
                is Fetch.Text -> f.text
            }
            val new = normalizeEnding(old, body)
            if (new == old) return@confined ToolResult.error("$p already has exactly this content; nothing to propose")
            ws.propose(p, old, new)?.let { return@confined ToolResult.error(it) }
            val st = ws.pendingStat(p)
            ToolResult.ok("proposed ${if (old.isEmpty()) "new file" else "change to"} $p (+${st?.added ?: 0} -${st?.removed ?: 0}); pending your review")
        }, err = { ToolResult.error(it) })
    }

    private fun proposeDelete(ws: AgentWorkspace) = ToolSpec(
        "propose_delete", SideEffect.READ_LOCAL, "propose deleting one file; nothing is deleted until the user approves",
        listOf(ParamSpec("path")),
    ) { a ->
        confined(a.text("path"), f = { p ->
            when (val f = ws.fetch(p)) {
                is Fetch.Missing -> ToolResult.error("no such file to delete: $p")
                is Fetch.Refused -> ToolResult.error("${f.reason}; deleting it cannot be shown in review, so it is not supported")
                is Fetch.Text -> {
                    if (f.text.isEmpty()) return@confined ToolResult.error("$p is empty; an empty-file deletion cannot be shown in review")
                    ws.propose(p, f.text, "")?.let { return@confined ToolResult.error(it) }
                    ToolResult.ok("proposed deleting $p; pending your review")
                }
            }
        }, err = { ToolResult.error(it) })
    }

    private fun finish(ws: AgentWorkspace) = ToolSpec(
        "finish", SideEffect.READ_LOCAL, "end the run with a short summary of what you did and why",
        listOf(ParamSpec("summary")), terminal = true,
    ) { a ->
        ws.setFinish(a.text("summary").take(1_000))
        ToolResult.ok("finished")
    }

    /** Kept for tests: does a pending entry for [path] have this op. */
    internal fun opOf(ws: AgentWorkspace, path: String): ChangeOp? = ws.changeSet().changes.firstOrNull { it.path == path }?.op
}
