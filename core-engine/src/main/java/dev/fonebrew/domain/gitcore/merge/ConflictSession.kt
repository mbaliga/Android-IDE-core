package dev.fonebrew.domain.gitcore.merge

import dev.fonebrew.domain.gitcore.*
import dev.fonebrew.domain.gitcore.textmerge.Choice
import dev.fonebrew.domain.gitcore.textmerge.ConflictMarkers
import dev.fonebrew.domain.gitcore.textmerge.MarkerParseError
import dev.fonebrew.domain.gitcore.textmerge.MarkerStyle
import dev.fonebrew.domain.gitcore.textmerge.RawText
import dev.fonebrew.domain.gitcore.textmerge.ResolutionException
import dev.fonebrew.domain.gitcore.textmerge.Resolver
import dev.fonebrew.domain.gitcore.textmerge.Segment

enum class SessionErrorKind { UNKNOWN_PATH, REGION_OUT_OF_RANGE, NOT_A_TEXT_CONFLICT, MARKERS_MALFORMED, UNRESOLVED_REGIONS, MARKERS_REMAIN, BASE_UNAVAILABLE, VERSION_MISSING }

class SessionException(val kind: SessionErrorKind, message: String, val path: String? = null) : RuntimeException(message)

/** How far one file is. Always shown with a glyph and a word, never by colour alone. */
enum class FileResolution(val glyph: String, val label: String) {
    /** Nothing chosen yet. */
    UNRESOLVED("○", "unresolved"),
    /** Some regions chosen, some open. */
    PARTIAL("◐", "partly resolved"),
    /** Every region chosen (or the file has no markers left); not yet written and staged. */
    READY("●", "ready to mark resolved"),
    /** Written and staged: the index holds a stage-0 entry. */
    RESOLVED("✓", "resolved"),
    /** The file's markers cannot be read (nested, unterminated, ...); fix by hand or take a whole version. */
    UNREADABLE("!", "markers unreadable"),
}

/** Which whole version to take for a file. */
enum class WholeFile { OURS, THEIRS, BASE }

class RegionView(
    val index: Int,
    val oursText: String,
    val theirsText: String,
    /** Null when the ancestor is not known for this region (merge-style markers). */
    val baseText: String?,
    val choice: Choice?,
)

class ConflictFile(
    val path: String,
    /** git's two-letter state from the index stages: UU, AA, UD, DU, UA, AU, DD. */
    val code: String,
    val resolution: FileResolution,
    val regionCount: Int,
    val resolvedRegions: Int,
    /** True when the work tree file can be resolved region by region. */
    val textual: Boolean,
    val oursLabel: String?,
    val theirsLabel: String?,
)

data class Position(val path: String, val region: Int)

/**
 * A presenter-ready view over a repository's unmerged files. Pure: it reads the index and the
 * work tree files, keeps the user's per-region choices in memory, and only [markResolved] /
 * [resolveWith] write (the file, then `add`). It parses the file as it is now, so edits made by
 * the user, a terminal or an agent re-enter through [reload]; a malformed marker structure is
 * reported (UNREADABLE), never guessed at.
 */
class ConflictSession private constructor(private val repo: Repository) {
    private class FileState(
        val path: String,
        val stages: Map<Int, IndexEntry>,
    ) {
        var segments: List<Segment>? = null
        var parseError: MarkerParseError? = null
        var oursLabel: String? = null
        var theirsLabel: String? = null
        var baseLabel: String? = null
        var markerSize = 7
        val choices = HashMap<Int, Choice>()
        var resolved = false
        var binary = false
    }

    private val states = LinkedHashMap<String, FileState>()

    companion object {
        /** Opens a session over every conflicted path currently in the index. */
        fun open(repo: Repository): ConflictSession = ConflictSession(repo).also { it.refresh() }
    }

    /** Re-reads the index and every unresolved file from disk. Choices for unchanged regions are dropped. */
    fun refresh() {
        val ix = repo.readIndex()
        val prev = LinkedHashMap(states)
        states.clear()
        val byPath = ix.entries().filter { it.stage != 0 }.groupBy { it.path }
        for (p in byPath.keys.sorted()) {
            val fs = FileState(p, byPath.getValue(p).associateBy { it.stage })
            states[p] = fs
            load(fs)
        }
        // paths that were conflicted and are gone from the index are resolved ones: keep them listed
        for ((p, old) in prev) if (p !in states) { old.resolved = true; states[p] = old }
    }

    /** Re-reads one file from disk (after an external edit); its choices are discarded. */
    fun reload(path: String) { val s = state(path); s.choices.clear(); load(s) }

    private fun load(s: FileState) {
        s.segments = null; s.parseError = null; s.binary = false
        val ours = s.stages[2]; val theirs = s.stages[3]
        if (ours == null || theirs == null) return                       // delete/modify and friends: whole-file only
        val wt = repo.workTreeFs()
        val st = wt.lstat(s.path)
        if (st == null || st.kind != FileKind.FILE) return
        if (st.size > 8L * 1024 * 1024) return
        val bytes = repo.fs.readBytes(wt.abs(s.path), st.size + 1)
        if (RawText.looksBinary(bytes)) { s.binary = true; return }
        try {
            val parsed = ConflictMarkers.parse(RawText.fromBytes(bytes), s.markerSize)
            s.segments = parsed.segments
            s.oursLabel = parsed.oursLabel; s.theirsLabel = parsed.theirsLabel; s.baseLabel = parsed.baseLabel
        } catch (e: MarkerParseError) { s.parseError = e }
    }

    private fun state(path: String) = states[path] ?: throw SessionException(SessionErrorKind.UNKNOWN_PATH, "'${PathSafety.display(path)}' is not a conflicted path", path)

    private fun code(s: FileState): String {
        val k = s.stages.keys
        return when {
            k == setOf(1, 2, 3) -> "UU"; k == setOf(2, 3) -> "AA"; k == setOf(1, 2) -> "UD"
            k == setOf(1, 3) -> "DU"; k == setOf(3) -> "UA"; k == setOf(2) -> "AU"; k == setOf(1) -> "DD"
            else -> "UU"
        }
    }

    private fun regionsOf(s: FileState) = s.segments.orEmpty().filterIsInstance<Segment.Conflict>().map { it.region }

    private fun resolutionOf(s: FileState): FileResolution {
        if (s.resolved) return FileResolution.RESOLVED
        if (s.parseError != null) return FileResolution.UNREADABLE
        val regs = regionsOf(s)
        if (s.segments == null) return FileResolution.UNRESOLVED       // whole-file kinds
        if (regs.isEmpty()) return FileResolution.READY               // no markers left in the file
        val n = regs.count { it.index in s.choices }
        return when { n == 0 -> FileResolution.UNRESOLVED; n < regs.size -> FileResolution.PARTIAL; else -> FileResolution.READY }
    }

    val files: List<ConflictFile> get() = states.values.map { s ->
        val regs = regionsOf(s)
        ConflictFile(s.path, code(s), resolutionOf(s), regs.size, regs.count { it.index in s.choices }, s.segments != null, s.oursLabel, s.theirsLabel)
    }

    fun file(path: String): ConflictFile { state(path); return files.first { it.path == path } }

    /** True when no file is left unresolved (every one is RESOLVED). */
    val isComplete: Boolean get() = states.values.all { it.resolved }

    val unresolvedCount: Int get() = states.values.count { !it.resolved }

    fun regions(path: String): List<RegionView> {
        val s = state(path)
        return regionsOf(s).map { RegionView(it.index, it.oursText(), it.theirsText(), it.baseText(), s.choices[it.index]) }
    }

    /** The first open region at or after [from] (file order, then region order); null when none is left. */
    fun next(from: Position? = null): Position? = scan(from, +1)

    /** The last open region before [from]. */
    fun previous(from: Position? = null): Position? = scan(from, -1)

    private fun scan(from: Position?, dir: Int): Position? {
        val all = ArrayList<Position>()
        for (s in states.values) if (!s.resolved) for (r in regionsOf(s)) if (r.index !in s.choices) all.add(Position(s.path, r.index))
        if (all.isEmpty()) return null
        if (from == null) return if (dir > 0) all.first() else all.last()
        val order = states.keys.toList()
        fun key(p: Position) = order.indexOf(p.path).toLong() * 1_000_000L + p.region
        val k = key(from)
        return if (dir > 0) all.firstOrNull { key(it) > k } else all.lastOrNull { key(it) < k }
    }

    fun choose(path: String, region: Int, choice: Choice) {
        val s = state(path)
        val regs = regionsOf(s)
        if (s.segments == null) throw SessionException(SessionErrorKind.NOT_A_TEXT_CONFLICT, "'${PathSafety.display(path)}' cannot be resolved region by region (${if (s.parseError != null) "its markers are unreadable" else "not a text conflict"})", path)
        val r = regs.firstOrNull { it.index == region } ?: throw SessionException(SessionErrorKind.REGION_OUT_OF_RANGE, "no region $region in '${PathSafety.display(path)}'", path)
        if (choice == Choice.Base && r.base == null) throw SessionException(SessionErrorKind.BASE_UNAVAILABLE, "region $region has no known base (merge-style markers); take the whole base version with resolveWith", path)
        s.choices[region] = choice
        s.resolved = false
    }

    fun chooseAll(path: String, choice: Choice) { for (r in regionsOf(state(path))) choose(path, r.index, choice) }

    fun clearChoice(path: String, region: Int) { state(path).choices.remove(region) }

    /** What the file would contain now; still-open regions keep their markers. */
    fun preview(path: String): String {
        val s = state(path)
        val segs = s.segments ?: throw SessionException(SessionErrorKind.NOT_A_TEXT_CONFLICT, "'${PathSafety.display(path)}' is not a text conflict", path)
        return RawText.toUtf8Text(Resolver.apply(segs, s.choices, if (regionsOf(s).any { it.base != null }) MarkerStyle.DIFF3 else MarkerStyle.MERGE, s.markerSize, s.oursLabel ?: "ours", s.baseLabel ?: "base", s.theirsLabel ?: "theirs").raw)
    }

    /** Writes the resolved text and stages it (like `git add`). Refuses while regions are open or markers remain. */
    fun markResolved(path: String) {
        val s = state(path)
        val segs = s.segments ?: throw SessionException(SessionErrorKind.NOT_A_TEXT_CONFLICT, "'${PathSafety.display(path)}' is not a text conflict; use resolveWith", path)
        val open = regionsOf(s).filter { it.index !in s.choices }.map { it.index }
        if (open.isNotEmpty()) throw SessionException(SessionErrorKind.UNRESOLVED_REGIONS, "regions still open in '${PathSafety.display(path)}': ${open.joinToString(", ")}", path)
        val res = try { Resolver.apply(segs, s.choices) } catch (e: ResolutionException) { throw SessionException(SessionErrorKind.BASE_UNAVAILABLE, e.message ?: "resolution failed", path) }
        val left = Resolver.leftoverMarkers(res.raw, s.markerSize)
        if (left.isNotEmpty()) throw SessionException(SessionErrorKind.MARKERS_REMAIN, "conflict markers remain at line ${left.first().line} of '${PathSafety.display(path)}'", path)
        val wt = repo.workTreeFs()
        val exec = (s.stages[2] ?: s.stages[3])?.isExecutable == true
        wt.writeFile(path, res.bytes(), exec)
        repo.add(listOf(path))
        s.resolved = true
    }

    /** Takes a whole version (a missing version means the file is deleted) and stages the result. */
    fun resolveWith(path: String, which: WholeFile) {
        val s = state(path)
        val stage = when (which) { WholeFile.BASE -> 1; WholeFile.OURS -> 2; WholeFile.THEIRS -> 3 }
        val entry = s.stages[stage]
        val wt = repo.workTreeFs()
        if (entry == null) {
            // that side has no file: resolving to it removes the path
            wt.delete(path)
            val ix = repo.readIndex(); ix.remove(path); ix.write(repo.fs, repo.indexPath)
        } else {
            val flat = FlatEntry(canonMode(entry.mode), entry.id)
            val st = wt.lstat(path)
            if (st?.kind == FileKind.DIRECTORY) throw SessionException(SessionErrorKind.VERSION_MISSING, "'${PathSafety.display(path)}' is a directory in the work tree", path)
            repo.writeEntry(wt, path, flat)
            repo.add(listOf(path))
        }
        s.resolved = true
        s.choices.clear()
    }
}
