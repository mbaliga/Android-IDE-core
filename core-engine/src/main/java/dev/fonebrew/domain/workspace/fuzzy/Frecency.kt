package dev.fonebrew.domain.workspace.fuzzy

import kotlin.math.pow

/** One persisted frecency record: the score as of [lastAtMs] (decay is applied lazily on read). */
data class FrecencyEntry(val path: String, val score: Double, val lastAtMs: Long)

/**
 * Frecency-ranked recent files: every visit adds 1 to an exponentially decaying score
 * (half-life [halfLifeMs], default a week), so a file opened ten times last month ranks below one
 * opened twice today. Bounded to [maxEntries]; the lowest decayed score is evicted first (ties:
 * the lexicographically larger path), so eviction and ranking are deterministic. Not thread-safe;
 * callers confine it to one thread or lock.
 */
class Frecency(
    private val maxEntries: Int = 200,
    private val halfLifeMs: Long = 7L * 24 * 3600 * 1000,
    initial: List<FrecencyEntry> = emptyList(),
) {
    private val map = LinkedHashMap<String, FrecencyEntry>()

    init { initial.forEach { map[it.path] = it }; trim(initial.maxOfOrNull { it.lastAtMs } ?: 0L, null) }

    private fun decayed(e: FrecencyEntry, now: Long): Double {
        val dt = (now - e.lastAtMs).coerceAtLeast(0L)
        return e.score * 0.5.pow(dt.toDouble() / halfLifeMs)
    }

    fun visit(path: String, nowMs: Long) {
        val old = map[path]
        val s = (if (old == null) 0.0 else decayed(old, nowMs)) + 1.0
        map[path] = FrecencyEntry(path, s, maxOf(nowMs, old?.lastAtMs ?: 0L))
        trim(nowMs, path)
    }

    fun score(path: String, nowMs: Long): Double = map[path]?.let { decayed(it, nowMs) } ?: 0.0

    fun remove(path: String) { map.remove(path) }

    fun rename(from: String, to: String) {
        val e = map.remove(from) ?: return
        map[to] = e.copy(path = to)
    }

    fun top(n: Int, nowMs: Long): List<Pair<String, Double>> =
        map.values.map { it.path to decayed(it, nowMs) }
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
            .take(n)

    fun entries(): List<FrecencyEntry> = map.values.toList()
    val size: Int get() = map.size

    /** Never evicts [keep] (the entry just visited), so a fresh visit always survives its own insertion. */
    private fun trim(now: Long, keep: String?) {
        while (map.size > maxEntries) {
            val victim = map.values.filter { it.path != keep }.minWithOrNull(compareBy<FrecencyEntry> { decayed(it, now) }.thenByDescending { it.path }) ?: break
            map.remove(victim.path)
        }
    }
}
