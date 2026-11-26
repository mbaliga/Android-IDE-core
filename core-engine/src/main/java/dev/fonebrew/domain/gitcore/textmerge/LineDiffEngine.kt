package dev.fonebrew.domain.gitcore.textmerge

/** A maximal run of differing lines: `a[aStart,aEnd)` became `b[bStart,bEnd)`. Either side may be empty. */
internal data class Hunk(val aStart: Int, val aEnd: Int, val bStart: Int, val bEnd: Int) {
    val aLen get() = aEnd - aStart
    val bLen get() = bEnd - bStart
}

/**
 * Line diff for the merge. Deterministic, no hidden randomness.
 *
 * 1. Common prefix and suffix are stripped.
 * 2. A minimal edit script is found with the O(ND) shortest-edit-script algorithm (Myers, 1986,
 *    public paper). If the edit distance exceeds [MAX_D] (very different huge inputs) the range is
 *    decomposed instead at lines that occur exactly once on both sides and keep their relative
 *    order (the patience idea: longest increasing subsequence of those anchors) and each gap is
 *    solved again; a gap with no anchor and too costly for the bound becomes one replace hunk.
 *    That fallback is coarser (bigger conflicts), never wrong: every line is accounted for.
 * 3. Each group of changed lines is slid down across identical neighbours as far as it goes (and
 *    absorbs a group it touches), so an ambiguous insertion lands where git places it.
 */
internal object LineDiffEngine {
    const val MAX_D = 1500

    fun hunks(a: List<String>, b: List<String>): List<Hunk> {
        if (a.isEmpty() && b.isEmpty()) return emptyList()
        val ids = HashMap<String, Int>()
        fun id(s: String) = ids.getOrPut(s) { ids.size }
        val ai = IntArray(a.size) { id(a[it]) }
        val bi = IntArray(b.size) { id(b[it]) }
        val chgA = BooleanArray(a.size)
        val chgB = BooleanArray(b.size)
        diffRange(ai, 0, ai.size, bi, 0, bi.size, chgA, chgB)
        slide(ai, chgA, chgB)
        slide(bi, chgB, chgA)
        return toHunks(chgA, chgB)
    }

    private fun diffRange(a: IntArray, aLo0: Int, aHi0: Int, b: IntArray, bLo0: Int, bHi0: Int, chgA: BooleanArray, chgB: BooleanArray) {
        var aLo = aLo0; var aHi = aHi0; var bLo = bLo0; var bHi = bHi0
        while (aLo < aHi && bLo < bHi && a[aLo] == b[bLo]) { aLo++; bLo++ }
        while (aLo < aHi && bLo < bHi && a[aHi - 1] == b[bHi - 1]) { aHi--; bHi-- }
        if (aLo == aHi) { for (j in bLo until bHi) chgB[j] = true; return }
        if (bLo == bHi) { for (i in aLo until aHi) chgA[i] = true; return }
        if (myers(a, aLo, aHi, b, bLo, bHi, chgA, chgB)) return
        // too different for the bounded exact search: split at unique shared lines
        val anchors = uniqueAnchors(a, aLo, aHi, b, bLo, bHi)
        if (anchors.isEmpty()) {
            for (i in aLo until aHi) chgA[i] = true
            for (j in bLo until bHi) chgB[j] = true
            return
        }
        var pa = aLo; var pb = bLo
        for ((x, y) in anchors) {
            diffRange(a, pa, x, b, pb, y, chgA, chgB)
            pa = x + 1; pb = y + 1
        }
        diffRange(a, pa, aHi, b, pb, bHi, chgA, chgB)
    }

    /** Anchors: (indexInA, indexInB) of lines unique on both sides, as a longest increasing subsequence in B. */
    private fun uniqueAnchors(a: IntArray, aLo: Int, aHi: Int, b: IntArray, bLo: Int, bHi: Int): List<Pair<Int, Int>> {
        val countA = HashMap<Int, Int>(); val posA = HashMap<Int, Int>()
        for (i in aLo until aHi) { countA.merge(a[i], 1, Int::plus); posA[a[i]] = i }
        val countB = HashMap<Int, Int>(); val posB = HashMap<Int, Int>()
        for (j in bLo until bHi) { countB.merge(b[j], 1, Int::plus); posB[b[j]] = j }
        val pairs = ArrayList<Pair<Int, Int>>()
        for (i in aLo until aHi) {
            val v = a[i]
            if (countA[v] == 1 && countB[v] == 1) pairs.add(i to posB[v]!!)
        }
        if (pairs.isEmpty()) return emptyList()
        // LIS on the B index (pairs are already in A order)
        val tails = ArrayList<Int>()          // index into pairs of the smallest tail for each length
        val prev = IntArray(pairs.size) { -1 }
        for (k in pairs.indices) {
            val y = pairs[k].second
            var lo = 0; var hi = tails.size
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (pairs[tails[mid]].second < y) lo = mid + 1 else hi = mid }
            if (lo > 0) prev[k] = tails[lo - 1]
            if (lo == tails.size) tails.add(k) else tails[lo] = k
        }
        val out = ArrayList<Pair<Int, Int>>()
        var k = tails.last()
        while (k >= 0) { out.add(pairs[k]); k = prev[k] }
        out.reverse()
        return out
    }

    /** Bounded shortest-edit-script. Returns false (marking nothing) when the distance exceeds [MAX_D]. */
    private fun myers(a: IntArray, aLo: Int, aHi: Int, b: IntArray, bLo: Int, bHi: Int, chgA: BooleanArray, chgB: BooleanArray): Boolean {
        val n = aHi - aLo; val m = bHi - bLo
        val dmax = minOf(n + m, MAX_D)
        val off = dmax + 1
        val v = IntArray(2 * dmax + 3)
        val trace = ArrayList<IntArray>()
        var found = -1
        search@ for (d in 0..dmax) {
            // snapshot of diagonals -d-1 .. d+1 as they stand before round d
            trace.add(v.copyOfRange(off - d - 1, off + d + 2))
            var k = -d
            while (k <= d) {
                var x = if (k == -d || (k != d && v[off + k - 1] < v[off + k + 1])) v[off + k + 1] else v[off + k - 1] + 1
                var y = x - k
                while (x < n && y < m && a[aLo + x] == b[bLo + y]) { x++; y++ }
                v[off + k] = x
                if (x >= n && y >= m) { found = d; break@search }
                k += 2
            }
        }
        if (found < 0) return false
        var x = n; var y = m
        for (d in found downTo 1) {
            val snap = trace[d]
            fun at(k: Int) = snap[k + d + 1]
            val k = x - y
            val prevK = if (k == -d || (k != d && at(k - 1) < at(k + 1))) k + 1 else k - 1
            val prevX = at(prevK)
            val prevY = prevX - prevK
            while (x > prevX && y > prevY) { x--; y-- }
            if (x == prevX) chgB[bLo + prevY] = true else chgA[aLo + prevX] = true
            x = prevX; y = prevY
        }
        return true
    }

    /**
     * Normalises where each group of changed lines sits when several placements are equally
     * minimal. A group slides up as far as identical neighbours allow, then down as far as they
     * allow (absorbing any group it touches); among those placements the lowest one where the
     * OTHER file also has changes at the same point wins (so a deletion and an insertion that
     * belong together stay one replace hunk), else the lowest placement overall. This mirrors
     * where git places hunks, which is what the interop tests compare against.
     */
    private fun slide(ids: IntArray, chg: BooleanArray, other: BooleanArray) {
        val n = ids.size
        // aligned[u]: the other file has a run of changes right after u unchanged lines
        val aligned = BooleanArray(other.count { !it } + 2)
        run {
            var u = 0; var j = 0
            while (j < other.size) { if (other[j]) { aligned[u] = true; while (j < other.size && other[j]) j++ } else { u++; j++ } }
        }
        var cb = 0                 // changed lines before the current group
        var i = 0
        while (i < n) {
            if (!chg[i]) { i++; continue }
            var s = i; var e = i
            while (e < n && chg[e]) e++
            fun up() {
                s--; e--
                chg[s] = true; chg[e] = false
                while (s > 0 && chg[s - 1]) { s--; cb-- }
            }
            while (s > 0 && ids[s - 1] == ids[e - 1]) up()
            var endMatch = if (aligned[s - cb]) e else -1
            while (e < n && ids[s] == ids[e]) {
                chg[s] = false; chg[e] = true
                s++; e++
                while (e < n && chg[e]) e++
                if (aligned[s - cb]) endMatch = e
            }
            if (endMatch != -1) while (e > endMatch && s > 0 && ids[s - 1] == ids[e - 1]) up()
            cb += e - s
            i = e
        }
    }

    private fun toHunks(chgA: BooleanArray, chgB: BooleanArray): List<Hunk> {
        val out = ArrayList<Hunk>()
        var i = 0; var j = 0
        val n = chgA.size; val m = chgB.size
        while (i < n || j < m) {
            val ca = i < n && chgA[i]; val cb = j < m && chgB[j]
            if (!ca && !cb) { i++; j++; continue }
            val si = i; val sj = j
            while (i < n && chgA[i]) i++
            while (j < m && chgB[j]) j++
            out.add(Hunk(si, i, sj, j))
        }
        return out
    }
}
