package dev.fonebrew.domain.gitcore.merge

import dev.fonebrew.domain.gitcore.*

/**
 * All best common ancestors of [a] and [b]: the common ancestors that are not themselves an
 * ancestor of another common ancestor (a criss-cross history has more than one). Order is
 * deterministic (newest committer time first, then id). Empty means unrelated histories.
 */
fun Repository.mergeBases(a: ObjectId, b: ObjectId): List<ObjectId> {
    val ofA = ancestors(a)
    val ofB = ancestors(b)
    val common = ofA.filter { it in ofB }.toSet()
    if (common.isEmpty()) return emptyList()
    // everything reachable from a common ancestor's PARENTS is not a best base
    val shadowed = HashSet<ObjectId>()
    val stack = ArrayDeque<ObjectId>()
    for (c in common) db.readCommit(c).parents.forEach(stack::add)
    while (stack.isNotEmpty()) {
        val id = stack.removeLast()
        if (!shadowed.add(id)) continue
        db.readCommit(id).parents.forEach(stack::add)
    }
    return (common - shadowed)
        .sortedWith(compareByDescending<ObjectId> { db.readCommit(it).committer.epochSeconds }.thenBy { it.hex })
}

/** The tree to use as the merge base, and how it was obtained. */
class BaseTree(
    val tree: ObjectId?,
    /** The base commits that went into it; one normally, several for a criss-cross, none for unrelated histories. */
    val commits: List<ObjectId>,
    /** True when [tree] is a merge of several bases (the recursive virtual base). */
    val virtual: Boolean,
    /** True when the bound was hit and only the first bases were merged. */
    val truncated: Boolean,
)

/**
 * Chooses the base tree for merging [ours] and [theirs]. One best base is used as is. For a
 * criss-cross history the best bases are merged pairwise (newest first) into a virtual base
 * whose own conflicts stay in it as marker text (as git's recursive strategies do); the virtual
 * base's own base is found the same way, to depth [maxDepth], and at most [maxBases] bases are
 * folded in - beyond either bound the remaining bases are ignored and [BaseTree.truncated] says so.
 * Virtual merge commits are written as ordinary (unreferenced) commit objects with a fixed
 * identity and the newer parent's time, so the result is deterministic and the recursion can
 * reuse the commit machinery.
 */
fun Repository.baseTreeFor(ours: ObjectId, theirs: ObjectId, maxDepth: Int = 6, maxBases: Int = 16): BaseTree =
    baseTreeFor(ours, theirs, maxDepth, maxBases, 0)

private fun Repository.baseTreeFor(ours: ObjectId, theirs: ObjectId, maxDepth: Int, maxBases: Int, depth: Int): BaseTree {
    var bases = mergeBases(ours, theirs)
    if (bases.isEmpty()) return BaseTree(null, emptyList(), false, false)
    if (bases.size == 1) return BaseTree(db.readCommit(bases[0]).tree, bases, false, false)
    var truncated = false
    if (bases.size > maxBases) { bases = bases.take(maxBases); truncated = true }
    if (depth >= maxDepth) return BaseTree(db.readCommit(bases[0]).tree, bases.take(1), false, true)
    var cur = bases[0]
    for (next in bases.drop(1)) {
        val inner = baseTreeFor(cur, next, maxDepth, maxBases, depth + 1)
        if (inner.truncated) truncated = true
        val r = mergeFlatTrees(
            this, inner.tree, db.readCommit(cur).tree, db.readCommit(next).tree,
            MergeLabels("Temporary merge branch 1", "Temporary merge branch 2", "merged common ancestors"),
            MergeSettings(style = dev.fonebrew.domain.gitcore.textmerge.MarkerStyle.MERGE),
        )
        val tree = GitIndex.fromTree(r.virtualBaseTree()).writeTree(db)
        val a = db.readCommit(cur); val b = db.readCommit(next)
        val sig = Signature("virtual merge base", "virtual@localhost", maxOf(a.committer.epochSeconds, b.committer.epochSeconds), 0)
        cur = db.write(Commit(tree, listOf(cur, next), sig, sig, "virtual merge base\n"))
    }
    return BaseTree(db.readCommit(cur).tree, bases, true, truncated)
}
