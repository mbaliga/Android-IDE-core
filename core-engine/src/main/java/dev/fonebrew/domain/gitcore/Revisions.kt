package dev.fonebrew.domain.gitcore

import java.util.PriorityQueue

/**
 * Resolves `HEAD`, branch / tag / remote names, full or abbreviated hex ids, with the suffixes
 * `^`, `^N`, `~N`, `^{commit}`, `^{tree}`, `^{}`. Typed errors: NOT_FOUND, INVALID_ARGUMENT.
 */
fun Repository.resolve(spec: String): ObjectId {
    if (spec.isEmpty()) throw GitException(GitErrorKind.INVALID_ARGUMENT, "empty revision")
    // split base from suffix operators
    var i = 0
    while (i < spec.length && spec[i] != '^' && spec[i] != '~') i++
    var cur = resolveBase(spec.substring(0, i))
    while (i < spec.length) {
        when (spec[i]) {
            '~' -> {
                i++
                val st = i
                while (i < spec.length && spec[i].isDigit()) i++
                val n = if (i == st) 1 else spec.substring(st, i).toIntOrNull() ?: throw GitException(GitErrorKind.INVALID_ARGUMENT, "bad ~ count")
                repeat(n) { cur = parentOf(cur, 1) }
            }
            '^' -> {
                i++
                if (i < spec.length && spec[i] == '{') {
                    val close = spec.indexOf('}', i)
                    if (close < 0) throw GitException(GitErrorKind.INVALID_ARGUMENT, "unterminated ^{")
                    when (spec.substring(i + 1, close)) {
                        "", "commit" -> cur = peel(cur).also { if (db.require(it) !is Commit && spec.substring(i + 1, close) == "commit") throw GitException(GitErrorKind.INVALID_ARGUMENT, "not a commit") }
                        "tree" -> cur = treeOf(cur)
                        else -> throw GitException(GitErrorKind.INVALID_ARGUMENT, "unsupported ^{} type")
                    }
                    i = close + 1
                } else {
                    val st = i
                    while (i < spec.length && spec[i].isDigit()) i++
                    val n = if (i == st) 1 else spec.substring(st, i).toIntOrNull() ?: throw GitException(GitErrorKind.INVALID_ARGUMENT, "bad ^ number")
                    cur = if (n == 0) peel(cur) else parentOf(cur, n)
                }
            }
            else -> throw GitException(GitErrorKind.INVALID_ARGUMENT, "bad revision syntax")
        }
    }
    return cur
}

private fun Repository.parentOf(id: ObjectId, n: Int): ObjectId {
    val c = db.require(peel(id)) as? Commit ?: throw GitException(GitErrorKind.INVALID_ARGUMENT, "not a commit")
    return c.parents.getOrNull(n - 1) ?: throw GitException(GitErrorKind.NOT_FOUND, "commit ${id.abbrev()} has no parent $n")
}

private fun Repository.resolveBase(base: String): ObjectId {
    if (base.isEmpty()) throw GitException(GitErrorKind.INVALID_ARGUMENT, "empty revision base")
    ObjectId.fromHexOrNull(base, algorithm)?.let { return it }
    if (base == "HEAD") return refs.resolve("HEAD") ?: throw GitException(GitErrorKind.NOT_FOUND, "HEAD does not point at a commit yet (unborn branch)")
    val candidates = if (base.startsWith("refs/")) listOf(base)
    else listOf(base, "refs/$base", "refs/tags/$base", "refs/heads/$base", "refs/remotes/$base", "refs/remotes/$base/HEAD")
    for (c in candidates) {
        if (!RefName.isValid(c)) continue
        refs.resolve(c)?.let { return it }
    }
    if (base.length >= 4 && base.all { it in '0'..'9' || it in 'a'..'f' }) return db.resolvePrefix(base)
    throw GitException(GitErrorKind.NOT_FOUND, "unknown revision '${PathSafety.display(base)}'")
}

fun Repository.resolveCommit(spec: String): Pair<ObjectId, Commit> {
    val id = peel(resolve(spec))
    val c = db.require(id) as? Commit ?: throw GitException(GitErrorKind.INVALID_ARGUMENT, "'${PathSafety.display(spec)}' is not a commit")
    return id to c
}

data class LogEntry(val id: ObjectId, val commit: Commit)

data class LogOptions(
    val limit: Int = Int.MAX_VALUE,
    /** Follow only each commit's first parent. */
    val firstParent: Boolean = false,
    /** Only commits that change this path (file or directory). Simplified history, see [log]. */
    val path: String? = null,
    /** Commits reachable from these are excluded (`hide..start`). */
    val hide: List<ObjectId> = emptyList(),
)

/**
 * Reverse-chronological walk from [start] (committer time, newest first; ties broken by
 * discovery order). With [LogOptions.path] a commit is listed when its tree entry at the path
 * differs from every parent's (a root commit when the path exists); when a merge is identical to
 * one parent the walk follows only that parent (git's default history simplification).
 * Not implemented: --follow renames, date ranges, topological/author-date orders.
 */
fun Repository.log(start: List<ObjectId>, opts: LogOptions = LogOptions()): List<LogEntry> {
    val hidden = HashSet<ObjectId>()
    if (opts.hide.isNotEmpty()) {
        val stack = ArrayDeque<ObjectId>(); opts.hide.forEach { stack.add(peel(it)) }
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (!hidden.add(id)) continue
            db.readCommit(id).parents.forEach(stack::add)
        }
    }
    var seq = 0L
    data class Item(val id: ObjectId, val commit: Commit, val seq: Long)
    val pq = PriorityQueue<Item>(compareByDescending<Item> { it.commit.committer.epochSeconds }.thenBy { it.seq })
    val seen = HashSet<ObjectId>()
    fun push(id: ObjectId) {
        if (id in hidden || !seen.add(id)) return
        pq.add(Item(id, db.readCommit(id), seq++))
    }
    start.forEach { push(peel(it)) }
    val out = ArrayList<LogEntry>()
    val pathTree = HashMap<ObjectId, ObjectId?>()
    fun entryAt(commit: Commit): ObjectId? = pathTree.getOrPut(commit.tree) { opts.path?.let { pathEntry(commit.tree, it)?.id } }
    while (pq.isNotEmpty() && out.size < opts.limit) {
        val item = pq.poll()
        var follow = item.commit.parents
        if (opts.firstParent) follow = follow.take(1)
        var include = true
        if (opts.path != null) {
            val mine = entryAt(item.commit)
            val parentCommits = item.commit.parents.map { db.readCommit(it) }
            if (parentCommits.isEmpty()) include = mine != null
            else {
                val same = parentCommits.indexOfFirst { entryAt(it) == mine }
                include = same < 0
                if (same >= 0 && !opts.firstParent) follow = listOf(item.commit.parents[same])
            }
        }
        if (include) out.add(LogEntry(item.id, item.commit))
        follow.forEach(::push)
    }
    return out
}

fun Repository.log(startSpec: String = "HEAD", opts: LogOptions = LogOptions()): List<LogEntry> = log(listOf(resolve(startSpec)), opts)

/** The tree entry at [path] under [tree], or null. */
fun Repository.pathEntry(tree: ObjectId, path: String): TreeEntry? {
    var cur = tree
    val parts = path.trim('/').split('/')
    for ((i, p) in parts.withIndex()) {
        val e = db.readTree(cur).find(p) ?: return null
        if (i == parts.lastIndex) return e
        if (!e.isTree) return null
        cur = e.id
    }
    return null
}

/** All ancestors of [id] including itself. */
fun Repository.ancestors(id: ObjectId): Set<ObjectId> {
    val out = LinkedHashSet<ObjectId>()
    val stack = ArrayDeque<ObjectId>(); stack.add(peel(id))
    while (stack.isNotEmpty()) {
        val c = stack.removeLast()
        if (!out.add(c)) continue
        db.readCommit(c).parents.forEach(stack::add)
    }
    return out
}

fun Repository.isAncestor(maybeAncestor: ObjectId, of: ObjectId): Boolean = maybeAncestor in ancestors(of)

/** A common ancestor of [a] and [b] (the most recent one found; not guaranteed unique in criss-cross histories), or null. */
fun Repository.mergeBase(a: ObjectId, b: ObjectId): ObjectId? {
    val ofA = ancestors(a)
    val pq = PriorityQueue<Pair<Long, ObjectId>>(compareByDescending { it.first })
    val seen = HashSet<ObjectId>()
    fun push(id: ObjectId) { if (seen.add(id)) pq.add(db.readCommit(id).committer.epochSeconds to id) }
    push(b)
    while (pq.isNotEmpty()) {
        val (_, id) = pq.poll()
        if (id in ofA) return id
        db.readCommit(id).parents.forEach(::push)
    }
    return null
}

/**
 * Object ids needed to send [tips] to someone who already has [have]: every commit not reachable
 * from [have], plus the trees/blobs of those commits except those that appear in the trees of the
 * [have] tips themselves. May send a few objects the remote already holds (safe); never omits one
 * it needs. For the push lane.
 */
fun Repository.objectsToSend(tips: List<ObjectId>, have: List<ObjectId>): List<ObjectId> {
    val haveSet = HashSet<ObjectId>()
    have.forEach { haveSet.addAll(ancestors(peel(it))) }
    val skip = HashSet<ObjectId>()
    fun markTree(tree: ObjectId) {
        if (!skip.add(tree)) return
        for (e in db.readTree(tree).entries) if (e.isTree) markTree(e.id) else if (!FileMode.isGitlink(e.mode)) skip.add(e.id)
    }
    have.forEach { markTree(db.readCommit(peel(it)).tree) }
    val out = LinkedHashSet<ObjectId>()
    val commits = ArrayList<ObjectId>()
    val stack = ArrayDeque<ObjectId>(); tips.forEach { stack.add(peel(it)) }
    val seenC = HashSet<ObjectId>()
    while (stack.isNotEmpty()) {
        val c = stack.removeLast()
        if (c in haveSet || !seenC.add(c)) continue
        commits.add(c); out.add(c)
        db.readCommit(c).parents.forEach(stack::add)
    }
    fun addTree(t: ObjectId) {
        if (t in skip || !out.add(t)) return
        for (e in db.readTree(t).entries) when {
            e.isTree -> addTree(e.id)
            FileMode.isGitlink(e.mode) -> {}
            e.id !in skip -> out.add(e.id)
        }
    }
    for (c in commits) addTree(db.readCommit(c).tree)
    return out.toList()
}
