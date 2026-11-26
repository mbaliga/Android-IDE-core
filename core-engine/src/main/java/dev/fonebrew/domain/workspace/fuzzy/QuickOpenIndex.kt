package dev.fonebrew.domain.workspace.fuzzy

import dev.fonebrew.contracts.workspace.ResourceChangeKind
import dev.fonebrew.domain.workspace.tree.FsEvent
import kotlin.math.ln
import kotlin.math.roundToInt

data class QuickOpenHit(val path: String, val score: Int, val positions: IntArray) {
    override fun equals(other: Any?) = other is QuickOpenHit && other.path == path && other.score == score && other.positions.contentEquals(positions)
    override fun hashCode() = 31 * path.hashCode() + score
}

/** What a changed path turned out to be, answered by the caller's file system access. */
enum class PathKind { FILE, DIRECTORY, GONE }

/**
 * In-memory index of file paths for quick-open (one per root, paths root-relative). Incremental:
 * [applyEvent] folds in create / delete / replace events without a rebuild. Bounded: at most
 * [maxFiles] paths are held (extra ones are refused and [truncated] is set, never silently dropped
 * from view). Query = prefilter + exact DP score ([FuzzyMatcher]) + bounded [TopK], ordered by
 * score then shorter then lexicographic, plus a small capped frecency boost so a file you open all
 * the time wins a near-tie without ever beating a clearly better match.
 *
 * PERFORMANCE TARGET: top-50 over 200,000 paths for a 5-character query well under half a second
 * on a desktop JVM (measured in `QuickOpenPerformanceTest`; a phone figure is owner-measured).
 * Thread-safe via a coarse lock (queries are read-mostly and fast).
 */
class QuickOpenIndex(private val maxFiles: Int = 500_000) {
    private val lock = Any()
    private val paths = ArrayList<String>()
    private val slot = HashMap<String, Int>()

    var truncated: Boolean = false
        private set

    val size: Int get() = synchronized(lock) { paths.size }

    fun rebuild(all: Collection<String>) = synchronized(lock) {
        paths.clear(); slot.clear(); truncated = false
        for (p in all) addLocked(p)
    }

    fun add(path: String): Boolean = synchronized(lock) { addLocked(path) }

    private fun addLocked(path: String): Boolean {
        if (slot.containsKey(path)) return true
        if (paths.size >= maxFiles) { truncated = true; return false }
        slot[path] = paths.size
        paths.add(path)
        return true
    }

    fun remove(path: String): Boolean = synchronized(lock) { removeLocked(path) }

    private fun removeLocked(path: String): Boolean {
        val i = slot.remove(path) ?: return false
        val lastIdx = paths.size - 1
        if (i != lastIdx) { val moved = paths[lastIdx]; paths[i] = moved; slot[moved] = i }
        paths.removeAt(lastIdx)
        return true
    }

    /** Removes every path equal to or under [dir]. */
    fun removeUnder(dir: String): Int = synchronized(lock) {
        val pre = "$dir/"
        val victims = paths.filter { it == dir || it.startsWith(pre) }
        victims.forEach { removeLocked(it) }
        victims.size
    }

    fun renameUnder(from: String, to: String): Int = synchronized(lock) {
        val pre = "$from/"
        val victims = paths.filter { it == from || it.startsWith(pre) }
        victims.forEach { removeLocked(it) }
        victims.forEach { addLocked(to + it.substring(from.length)) }
        victims.size
    }

    /**
     * Folds one watcher event in. [kindOf] asks the file system what [FsEvent]'s path is now;
     * [walkDir] lists the files under a newly appeared directory (the caller applies ignore rules).
     * Events for ignored paths should be filtered by the caller before they get here.
     */
    fun applyEvent(e: FsEvent, kindOf: (String) -> PathKind, walkDir: (String) -> List<String>) {
        val rel = if (e.dirRel.isEmpty()) e.name else "${e.dirRel}/${e.name}"
        when (e.kind) {
            ResourceChangeKind.DELETED -> removeUnder(rel)
            else -> when (kindOf(rel)) {
                PathKind.GONE -> removeUnder(rel)
                PathKind.FILE -> add(rel)
                PathKind.DIRECTORY -> if (e.kind == ResourceChangeKind.CREATED) walkDir(rel).forEach { add(it) }
            }
        }
    }

    /**
     * Top [k] matches. An empty query returns the frecency-ranked recent files (needs [frecency]),
     * each with score = its rounded boost and no positions.
     */
    fun query(raw: String, k: Int = 50, nowMs: Long = 0L, frecency: Frecency? = null): List<QuickOpenHit> {
        val q = FuzzyQuery.parse(raw)
        if (q.isEmpty) {
            if (frecency == null) return emptyList()
            return synchronized(lock) {
                frecency.top(k * 2, nowMs).filter { slot.containsKey(it.first) }.take(k)
                    .map { QuickOpenHit(it.first, boost(it.second), IntArray(0)) }
            }
        }
        val order = Comparator<QuickOpenHit> { a, b -> FuzzyMatcher.compare(a.score, a.path, b.score, b.path) }
        val top = TopK(k, order)
        synchronized(lock) {
            for (p in paths) {
                val m = FuzzyMatcher.match(q, p) ?: continue
                val b = if (frecency == null) 0 else boost(frecency.score(p, nowMs))
                top.offer(QuickOpenHit(p, m.score + b, m.positions))
            }
        }
        return top.toSortedList()
    }

    companion object {
        const val FRECENCY_CAP = 24
        fun boost(frecencyScore: Double): Int = minOf(FRECENCY_CAP, (6.0 * ln(1.0 + frecencyScore)).roundToInt())
    }
}
