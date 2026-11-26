package dev.fonebrew.domain.workspace.ignore

/** Supplies the text of the `.gitignore` in directory [dirRel] ("" is the root), or null if none. */
fun interface IgnoreSource {
    fun read(dirRel: String): String?

    companion object {
        val NONE = IgnoreSource { null }
    }
}

/** Why a path is (or is not) ignored: the pattern that decided it, where it came from, which line. */
data class IgnoreDecision(
    val ignored: Boolean,
    /** The deciding pattern as written; null when no pattern matched at all. */
    val pattern: String?,
    /** `.gitignore` directory, `"<defaults>"` or `"<root excludes>"`; null when nothing matched. */
    val source: String?,
    val line: Int?,
    /** The (ancestor) path the decision was made on, when a parent directory is what excluded this path. */
    val decidedOn: String? = null,
)

/**
 * gitignore semantics over root-relative `/`-separated paths, per `man gitignore`: nested
 * `.gitignore` files (deeper wins), negation, directory-only (`foo/`), anchored (a slash anywhere
 * but the end), `**` forms, backslash escapes, trailing-space rule, blank and `#` lines, and the
 * rule that a file cannot be re-included once a parent directory is excluded. Case-sensitive.
 *
 * Layering, lowest to highest precedence: [defaults] (see [DefaultIgnores]), [userExcludes] (the
 * per-root excludes setting), then every `.gitignore` from the root down to the path's own
 * directory. All three use identical pattern syntax. Not modelled (named in the design note):
 * `.git/info/exclude`, `core.excludesFile`, `core.ignorecase`.
 *
 * Parsed files are cached per directory; call [invalidate] when a `.gitignore` changes.
 */
class IgnoreMatcher(
    private val source: IgnoreSource = IgnoreSource.NONE,
    defaults: List<String> = emptyList(),
    userExcludes: List<String> = emptyList(),
    private val useGitignoreFiles: Boolean = true,
) {
    private class Pat(
        val text: String,
        val line: Int,
        val negated: Boolean,
        val dirOnly: Boolean,
        val anchored: Boolean,
        val glob: Wildmatch,
    )

    private class PatSet(val label: String, val pats: List<Pat>)

    private val low: List<PatSet> = buildList {
        if (defaults.isNotEmpty()) add(PatSet("<defaults>", parse(defaults.joinToString("\n"))))
        if (userExcludes.isNotEmpty()) add(PatSet("<root excludes>", parse(userExcludes.joinToString("\n"))))
    }

    private val cache = HashMap<String, PatSet?>()

    fun invalidate(dirRel: String? = null) {
        synchronized(cache) { if (dirRel == null) cache.clear() else cache.remove(dirRel) }
    }

    private fun setFor(dirRel: String): PatSet? = synchronized(cache) {
        if (cache.containsKey(dirRel)) return cache[dirRel]
        val text = source.read(dirRel)
        val set = if (text == null) null else PatSet(if (dirRel.isEmpty()) ".gitignore" else "$dirRel/.gitignore", parse(text))
        cache[dirRel] = set
        set
    }

    fun isIgnored(relPath: String, isDir: Boolean): Boolean = explain(relPath, isDir).ignored

    /** Full decision with provenance. A path under an ignored directory reports that directory's decision. */
    fun explain(relPath: String, isDir: Boolean): IgnoreDecision {
        val rel = relPath.trim('/')
        if (rel.isEmpty()) return IgnoreDecision(false, null, null, null)
        val parts = rel.split('/')
        var prefix = ""
        for (k in parts.indices) {
            val last = k == parts.size - 1
            prefix = if (k == 0) parts[0] else prefix + "/" + parts[k]
            val dirFlag = if (last) isDir else true
            val d = decide(prefix, dirFlag)
            if (d.ignored) return if (last) d else d.copy(decidedOn = prefix)
        }
        return IgnoreDecision(false, null, null, null)
    }

    private fun decide(path: String, isDir: Boolean): IgnoreDecision {
        // Highest precedence first: the deepest .gitignore dir, up to the root, then low sets.
        val slash = path.lastIndexOf('/')
        val parentDir = if (slash < 0) "" else path.substring(0, slash)
        if (useGitignoreFiles) {
            var dir: String? = parentDir
            while (dir != null) {
                val set = setFor(dir)
                if (set != null) {
                    val sub = if (dir.isEmpty()) path else path.substring(dir.length + 1)
                    hit(set, sub, isDir)?.let { return it }
                }
                dir = if (dir.isEmpty()) null else dir.substringBeforeLast('/', "")
            }
        }
        for (set in low.asReversed()) hit(set, path, isDir)?.let { return it }
        return IgnoreDecision(false, null, null, null)
    }

    private fun hit(set: PatSet, sub: String, isDir: Boolean): IgnoreDecision? {
        val base = sub.substringAfterLast('/')
        for (idx in set.pats.indices.reversed()) {
            val p = set.pats[idx]
            if (p.dirOnly && !isDir) continue
            val ok = if (p.anchored) p.glob.matches(sub) else p.glob.matches(base)
            if (ok) return IgnoreDecision(!p.negated, p.text, set.label, p.line)
        }
        return null
    }

    companion object {
        private fun parse(text: String): List<Pat> {
            val out = ArrayList<Pat>()
            var lineNo = 0
            for (raw in text.split('\n')) {
                lineNo++
                var line = raw
                if (line.endsWith("\r")) line = line.dropLast(1)
                if (line.isEmpty() || line[0] == '#') continue
                line = trimTrailingSpaces(line)
                if (line.isEmpty()) continue
                val written = line
                var negated = false
                if (line[0] == '!') { negated = true; line = line.substring(1) }
                var dirOnly = false
                if (line.endsWith("/")) { dirOnly = true; line = line.dropLast(1) }
                if (line.isEmpty()) continue
                val anchored = line.contains('/')
                if (anchored && line.startsWith("/")) line = line.substring(1)
                if (line.isEmpty()) continue
                out.add(Pat(written, lineNo, negated, dirOnly, anchored, Wildmatch.compile(line, pathname = anchored)))
            }
            return out
        }

        /** Spaces at the end are dropped unless a backslash quotes them (`foo\ ` keeps its space). */
        private fun trimTrailingSpaces(s: String): String {
            var lastSpace = -1
            var i = 0
            while (i < s.length) {
                when (s[i]) {
                    ' ' -> if (lastSpace < 0) lastSpace = i
                    '\\' -> { i++; if (i >= s.length) return s; lastSpace = -1 }
                    else -> lastSpace = -1
                }
                i++
            }
            return if (lastSpace >= 0) s.substring(0, lastSpace) else s
        }
    }
}
