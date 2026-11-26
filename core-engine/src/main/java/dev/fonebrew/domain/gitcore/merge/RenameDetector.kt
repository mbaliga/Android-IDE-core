package dev.fonebrew.domain.gitcore.merge

import dev.fonebrew.domain.gitcore.*

/** A detected rename (or, never here, copy) from [from] in the base to [to] on one side. */
data class Rename(val from: String, val to: String, val exact: Boolean, /** 0..100 */ val similarity: Int)

class RenameResult(val renames: Map<String, Rename>, /** True when inexact matching was skipped because the candidate sets exceeded the bound. */ val inexactSkipped: Boolean)

/**
 * Rename detection for one side of a merge: which paths deleted from [base] reappear (under a new
 * name) in [side].
 *
 * 1. EXACT: a deleted and an added path with the same blob id and the same file kind. When several
 *    pair up, the same basename wins, then the same directory, then path order.
 * 2. INEXACT, similarity >= [minSimilarity] percent (default 50, git's default): the share of the
 *    larger file's bytes that lie in lines present in both, counted by line multiset. Pairs are
 *    taken greedily by descending score, ties by same basename, then path order.
 *
 * Cost bound: inexact matching compares every remaining deleted file with every remaining added
 * file, so it only runs when `deleted * added <= maxPairs` (default 160_000, e.g. 400 x 400) and
 * only for blobs up to [maxBlobBytes]; otherwise it is skipped and [RenameResult.inexactSkipped] is
 * set - the merge then treats those paths as a delete plus an add (a conservative outcome, never a
 * wrong one). Symlinks and gitlinks only ever match exactly.
 */
internal fun detectRenames(
    repo: Repository,
    base: Map<String, FlatEntry>,
    side: Map<String, FlatEntry>,
    minSimilarity: Int = 50,
    maxPairs: Long = 160_000L,
    maxBlobBytes: Long = 1L shl 20,
): RenameResult {
    val deleted = base.keys.filter { it !in side }.sorted()
    val added = side.keys.filter { it !in base }.sorted()
    val out = LinkedHashMap<String, Rename>()
    if (deleted.isEmpty() || added.isEmpty()) return RenameResult(out, false)

    fun kind(m: Int) = if (FileMode.isSymlink(m)) 1 else if (FileMode.isGitlink(m)) 2 else 0
    fun base(p: String) = p.substringAfterLast('/')
    fun dir(p: String) = p.substringBeforeLast('/', "")

    val usedAdded = HashSet<String>()
    val usedDeleted = HashSet<String>()

    // exact: group added paths by (id, kind)
    val addedByKey = HashMap<Pair<ObjectId, Int>, MutableList<String>>()
    for (p in added) addedByKey.getOrPut(side.getValue(p).id to kind(side.getValue(p).mode)) { ArrayList() }.add(p)
    for (d in deleted) {
        val e = base.getValue(d)
        val cands = addedByKey[e.id to kind(e.mode)]?.filter { it !in usedAdded } ?: continue
        if (cands.isEmpty()) continue
        val pick = cands.minWithOrNull(compareBy<String> { if (base(it) == base(d)) 0 else 1 }.thenBy { if (dir(it) == dir(d)) 0 else 1 }.thenBy { it })!!
        usedAdded.add(pick); usedDeleted.add(d)
        out[d] = Rename(d, pick, true, 100)
    }

    val remD = deleted.filter { it !in usedDeleted && kind(base.getValue(it).mode) == 0 }
    val remA = added.filter { it !in usedAdded && kind(side.getValue(it).mode) == 0 }
    if (remD.isEmpty() || remA.isEmpty()) return RenameResult(out, false)
    if (remD.size.toLong() * remA.size > maxPairs) return RenameResult(out, true)

    class Profile(val size: Long, val lines: Map<String, Int>)
    fun profile(e: FlatEntry): Profile? {
        val peek = repo.db.peek(e.id) ?: return null
        if (peek.second > maxBlobBytes) return null
        val data = repo.db.readRaw(e.id)?.data ?: return null
        if (RawTextBinary.looksBinary(data)) return null
        val m = HashMap<String, Int>()
        for (l in dev.fonebrew.domain.gitcore.textmerge.RawText.splitLines(dev.fonebrew.domain.gitcore.textmerge.RawText.fromBytes(data))) m.merge(l, 1, Int::plus)
        return Profile(data.size.toLong(), m)
    }
    val pd = remD.associateWith { profile(base.getValue(it)) }
    val pa = remA.associateWith { profile(side.getValue(it)) }
    class Cand(val d: String, val a: String, val score: Int)
    val cands = ArrayList<Cand>()
    for (d in remD) {
        val x = pd[d] ?: continue
        for (a in remA) {
            val y = pa[a] ?: continue
            var common = 0L
            val (small, big) = if (x.lines.size <= y.lines.size) x.lines to y.lines else y.lines to x.lines
            for ((line, c) in small) { val c2 = big[line] ?: continue; common += minOf(c, c2).toLong() * line.length }
            val denom = maxOf(x.size, y.size)
            val score = if (denom == 0L) 100 else (common * 100 / denom).toInt()
            if (score >= minSimilarity) cands.add(Cand(d, a, score))
        }
    }
    cands.sortWith(compareByDescending<Cand> { it.score }.thenBy { if (base(it.a) == base(it.d)) 0 else 1 }.thenBy { it.d }.thenBy { it.a })
    for (c in cands) {
        if (c.d in usedDeleted || c.a in usedAdded) continue
        usedDeleted.add(c.d); usedAdded.add(c.a)
        out[c.d] = Rename(c.d, c.a, false, c.score)
    }
    return RenameResult(out, false)
}

private object RawTextBinary {
    fun looksBinary(b: ByteArray) = dev.fonebrew.domain.gitcore.textmerge.RawText.looksBinary(b)
}
