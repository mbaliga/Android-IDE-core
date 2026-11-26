package dev.fonebrew.domain.agent

/**
 * Path confinement for the multi-step agent: every path a model names is checked here before any
 * tool touches it. Repo-relative only. Pure; JVM-tested with the attack shapes.
 */
object AgentPaths {

    sealed interface Result {
        /** [path] is normalised: segments joined by '/', no leading slash, "" only for the root. */
        data class Ok(val path: String) : Result
        data class Rejected(val reason: String) : Result
    }

    const val MAX_PATH_LENGTH = 400
    private val ENCODED = Regex("%(2e|2f|5c)", RegexOption.IGNORE_CASE)
    private val DRIVE = Regex("^[A-Za-z]:")

    /** Normalise [raw]. [allowRoot] lets "", "." and "/"-free root listings through (list_dir only). */
    fun confine(raw: String, allowRoot: Boolean = false): Result {
        val s = raw.trim()
        if (s.isEmpty() || s == ".") {
            return if (allowRoot) Result.Ok("") else Result.Rejected("path is empty")
        }
        if (s.length > MAX_PATH_LENGTH) return Result.Rejected("path is longer than $MAX_PATH_LENGTH characters")
        if (s.any { it.code < 0x20 || it.code == 0x7f }) return Result.Rejected("path contains a control character")
        if (s.contains('\\')) return Result.Rejected("backslashes are not allowed in a repo path")
        if (s.startsWith("/") || s.startsWith("~") || DRIVE.containsMatchIn(s)) {
            return Result.Rejected("absolute paths are not allowed; use a repo-relative path")
        }
        if (ENCODED.containsMatchIn(s)) return Result.Rejected("encoded dots or slashes are not allowed")
        val segs = mutableListOf<String>()
        for (seg in s.split('/')) {
            when {
                seg.isEmpty() || seg == "." -> continue
                seg.all { it == '.' } -> return Result.Rejected("'..' is not allowed: paths cannot leave the repo root")
                seg.equals(".git", ignoreCase = true) || seg.equals("git~1", ignoreCase = true) ->
                    return Result.Rejected(".git is off limits to the agent")
                seg.endsWith(" ") || seg.endsWith(".") ->
                    return Result.Rejected("a path segment cannot end with a space or a dot")
                else -> segs += seg
            }
        }
        if (segs.isEmpty()) return if (allowRoot) Result.Ok("") else Result.Rejected("path is empty")
        return Result.Ok(segs.joinToString("/"))
    }
}
