package dev.fonebrew.domain.search.semantic

import kotlin.math.sqrt

/**
 * Pure vector arithmetic for semantic search (docs/design/semantic-search.md section 6). Vectors
 * are stored L2-normalised, so cosine similarity is a plain dot product; [cosine] still divides
 * by the norms so it is correct for a vector that was not normalised.
 */
object VectorMath {

    fun dot(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "dimension mismatch: ${a.size} vs ${b.size}" }
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    fun norm(a: FloatArray): Float {
        var s = 0.0
        for (x in a) s += x.toDouble() * x
        return sqrt(s).toFloat()
    }

    /** A new L2-normalised copy; an all-zero (or non-finite) vector comes back all zeros, never NaN. */
    fun normalized(a: FloatArray): FloatArray {
        val n = norm(a)
        if (n == 0f || n.isNaN() || n.isInfinite()) return FloatArray(a.size)
        return FloatArray(a.size) { a[it] / n }
    }

    /** Cosine similarity; 0 when either side has no direction (zero vector). */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        val na = norm(a)
        val nb = norm(b)
        if (na == 0f || nb == 0f) return 0f
        return dot(a, b) / (na * nb)
    }

    fun isFinite(a: FloatArray): Boolean = a.all { !it.isNaN() && !it.isInfinite() }
}

/** One stored chunk vector: [docId] + [chunk] identify it, [vector] is already normalised. */
class VectorEntry(val docId: String, val chunk: Int, val vector: FloatArray)

/** A scored candidate. Ordering for ties is by [docId] then [chunk] so results are deterministic. */
data class ScoredChunk(val docId: String, val chunk: Int, val score: Float)

/** A document's best chunk similarity. */
data class DocSimilarity(val docId: String, val score: Float, val bestChunk: Int)

/**
 * Brute-force top-k over a stream of entries with a bounded min-heap: O(N log k) time, O(k)
 * memory beyond the stream itself. Ties break on (docId, chunk) ascending, so the same input
 * always yields the same output. Entries whose dimension differs from the query are skipped (a
 * different dimension can only come from a different embedder, which the store never mixes in;
 * skipping is the defence in depth, never a silent comparison).
 */
object TopK {

    fun chunks(query: FloatArray, entries: Sequence<VectorEntry>, k: Int): List<ScoredChunk> {
        if (k <= 0 || query.isEmpty()) return emptyList()
        // Min-heap on "worse first": the head is the weakest kept candidate.
        val heap = java.util.PriorityQueue<ScoredChunk>(k + 1, WORST_FIRST)
        for (e in entries) {
            if (e.vector.size != query.size) continue
            val s = VectorMath.dot(query, e.vector)
            if (s.isNaN()) continue
            val cand = ScoredChunk(e.docId, e.chunk, s)
            if (heap.size < k) heap.add(cand)
            else if (WORST_FIRST.compare(cand, heap.peek()) > 0) {
                heap.poll()
                heap.add(cand)
            }
        }
        return heap.sortedWith(BEST_FIRST)
    }

    /** Top [k] documents by their best chunk (max-pooling over chunks). */
    fun docs(query: FloatArray, entries: Sequence<VectorEntry>, k: Int): List<DocSimilarity> {
        if (k <= 0 || query.isEmpty()) return emptyList()
        val best = HashMap<String, ScoredChunk>()
        for (e in entries) {
            if (e.vector.size != query.size) continue
            val s = VectorMath.dot(query, e.vector)
            if (s.isNaN()) continue
            val cand = ScoredChunk(e.docId, e.chunk, s)
            val cur = best[e.docId]
            if (cur == null || BEST_FIRST.compare(cand, cur) < 0) best[e.docId] = cand
        }
        return best.values.sortedWith(BEST_FIRST).take(k).map { DocSimilarity(it.docId, it.score, it.chunk) }
    }

    private val BEST_FIRST: Comparator<ScoredChunk> =
        compareByDescending<ScoredChunk> { it.score }.thenBy { it.docId }.thenBy { it.chunk }
    private val WORST_FIRST: Comparator<ScoredChunk> = BEST_FIRST.reversed()
}
