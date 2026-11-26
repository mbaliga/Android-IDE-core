package dev.fonebrew.domain.gitcore

class CheckoutResult(val updated: List<String>, val removed: List<String>, val headBranch: String?, val headId: ObjectId)

enum class ResetMode { SOFT, MIXED, HARD }

private fun Repository.hasLocal(p: String, e: IndexEntry?, wt: WorkTreeFs, indexMtime: Long): Boolean {
    val st = wt.lstat(p)
    return if (e == null) st != null && st.kind != FileKind.DIRECTORY else worktreeChange(e, st, wt, indexMtime) != null
}

private fun WorkTreeFs.hasForeignFiles(rel: String, ignoring: Set<String>, fs: FileSystemPort): Boolean {
    for (n in fs.list(abs(rel))) {
        val child = "$rel/$n"
        val st = lstat(child) ?: continue
        if (st.kind == FileKind.DIRECTORY) { if (hasForeignFiles(child, ignoring, fs)) return true }
        else if (child !in ignoring) return true
    }
    return false
}

/**
 * Moves the work tree and index to [target] (a branch -> HEAD follows it; anything else -> detached).
 *
 * The dirty-tree guard runs over EVERY affected path before the first byte is written. For each
 * path whose target version differs from the index, checkout refuses (DIRTY_TREE, with the exact
 * paths) when: the index differs from HEAD there (staged change), the work-tree file is modified
 * or deleted, or an untracked file/directory is in the way. Paths where the target equals the
 * current index entry are left alone, so unrelated local edits carry across (like git). With
 * [force] the guard is skipped and local changes at affected paths are overwritten - the caller's
 * explicit choice, never a default.
 */
fun Repository.checkout(target: String, force: Boolean = false): CheckoutResult {
    val branchRef = listOf("refs/heads/$target").firstOrNull { RefName.isValid(it) && refs.read(it) != null }
    val (commitId, commit) = if (branchRef != null) {
        val id = refs.resolve(branchRef) ?: throw GitException(GitErrorKind.NOT_FOUND, "branch '$target' has no commit")
        id to db.readCommit(id)
    } else resolveCommit(target)
    val fromDesc = refs.headBranch()?.removePrefix("refs/heads/") ?: headCommitId()?.abbrev() ?: "(unborn)"
    return switchTree(commit.tree, commitId, branchRef, force, "checkout: moving from $fromDesc to ${branchRef?.removePrefix("refs/heads/") ?: commitId.abbrev()}")
}

/** Creates [name] at [startPoint] and switches to it. The branch is only created if the switch is allowed. */
fun Repository.checkoutNewBranch(name: String, startPoint: String = "HEAD", force: Boolean = false): CheckoutResult {
    RefName.requireValidShort(name)
    val ref = "refs/heads/$name"
    if (refs.read(ref) != null) throw GitException(GitErrorKind.REF_CONFLICT, "branch '$name' already exists")
    val (id, commit) = resolveCommit(startPoint)
    val from = refs.headBranch()?.removePrefix("refs/heads/") ?: headCommitId()?.abbrev() ?: "(unborn)"
    // Plan first (guard) by switching the tree; create the ref only afterwards, then point HEAD at it.
    val oldHead = headCommitId()
    val r = switchTree(commit.tree, id, null, force, "checkout: moving from $from to $name", moveHead = false)
    refs.update(ref, id, Expect.Absent, reflogIdentity(), "branch: Created from $startPoint")
    refs.setSymbolic("HEAD", ref)
    refs.logOnly("HEAD", oldHead, id, reflogIdentity(), "checkout: moving from $from to $name")
    return CheckoutResult(r.updated, r.removed, ref, id)
}

private fun Repository.switchTree(
    targetTree: ObjectId, targetCommit: ObjectId, branchRef: String?, force: Boolean, reflogMsg: String, moveHead: Boolean = true,
): CheckoutResult {
    val wt = workTreeFs()
    val ix = readIndex()
    val head = db.flattenTree(headTreeId())
    val tgt = db.flattenTree(targetTree)
    val cur = ix.stage0()
    val indexMtime = indexMtimeSeconds()

    if (ix.hasConflicts && !force) throw GitException(GitErrorKind.DIRTY_TREE, "index has unresolved conflicts: ${ix.conflictedPaths().joinToString(", ")}", ix.conflictedPaths())

    val toRemove = LinkedHashSet<String>()
    val toWrite = LinkedHashMap<String, FlatEntry>()
    for (p in LinkedHashSet<String>().also { it.addAll(cur.keys); it.addAll(tgt.keys) }) {
        val c = cur[p]; val t = tgt[p]
        if (sameEntry(c?.let { FlatEntry(canonMode(it.mode), it.id) }, t)) continue
        if (t == null) toRemove.add(p) else toWrite[p] = t
    }

    if (!force) {
        val conflicts = LinkedHashSet<String>()
        for (p in toRemove + toWrite.keys) {
            val c = cur[p]
            val h = head[p]
            if (!sameEntry(c?.let { FlatEntry(canonMode(it.mode), it.id) }, h)) { conflicts.add(p); continue }
            if (hasLocal(p, c, wt, indexMtime)) conflicts.add(p)
        }
        for (p in toWrite.keys) {
            var slash = p.indexOf('/')
            while (slash >= 0) {
                val anc = p.substring(0, slash)
                val st = wt.lstat(anc)
                if (st != null && st.kind != FileKind.DIRECTORY && anc !in toRemove) conflicts.add(anc)
                if (st?.kind == FileKind.SYMLINK) conflicts.add(anc)
                slash = p.indexOf('/', slash + 1)
            }
            val st = wt.lstat(p)
            if (st?.kind == FileKind.DIRECTORY && cur[p] == null && wt.hasForeignFiles(p, toRemove, fs)) conflicts.add(p)
        }
        if (conflicts.isNotEmpty()) {
            val list = conflicts.toList().sorted()
            throw GitException(GitErrorKind.DIRTY_TREE, "local changes would be overwritten: ${list.joinToString(", ") { PathSafety.display(it) }}", list)
        }
    }

    // Apply: removals, then writes, then index, then HEAD.
    for (p in toRemove) {
        if (FileMode.isGitlink(cur[p]!!.mode)) { fs.deleteRecursivelyIfEmptyDir(wt.abs(p)); continue }
        wt.delete(p)
    }
    val newIx = ix.copy()
    for (p in toRemove) newIx.remove(p)
    for ((p, t) in toWrite) {
        if (force) forceClear(wt, p, toRemove)
        writeEntry(wt, p, t)
        newIx.put(statEntry(wt, p, t))
    }
    newIx.write(fs, indexPath)

    if (moveHead) {
        val oldHead = headCommitId()
        if (branchRef != null) refs.setSymbolic("HEAD", branchRef) else refs.detach("HEAD", targetCommit)
        refs.logOnly("HEAD", oldHead, targetCommit, reflogIdentity(), reflogMsg)
    }
    return CheckoutResult(toWrite.keys.toList(), toRemove.toList(), if (branchRef != null) branchRef else null, targetCommit)
}

private fun FileSystemPort.deleteRecursivelyIfEmptyDir(path: String) {
    if (stat(path)?.kind == FileKind.DIRECTORY && list(path).isEmpty()) delete(path)
}

/** With force, clear whatever blocks a write: a blocking directory at the path, or a file where a directory is needed. */
private fun Repository.forceClear(wt: WorkTreeFs, p: String, removed: Set<String>) {
    var slash = p.indexOf('/')
    while (slash >= 0) {
        val anc = p.substring(0, slash)
        val st = wt.lstat(anc)
        if (st != null && st.kind != FileKind.DIRECTORY) fs.delete(wt.abs(anc))
        slash = p.indexOf('/', slash + 1)
    }
    val st = wt.lstat(p)
    if (st?.kind == FileKind.DIRECTORY) fs.deleteRecursively(wt.abs(p))
}

/**
 * Moves the current branch (or detached HEAD) to [target]. SOFT: only the ref. MIXED: ref + index.
 * HARD: ref + index + work tree, and because that DESTROYS uncommitted work it refuses with
 * DIRTY_TREE (listing every file that would be lost, including untracked files in the way) unless
 * [discardLocalChanges] is explicitly true.
 */
fun Repository.reset(mode: ResetMode, target: String = "HEAD", discardLocalChanges: Boolean = false): ObjectId {
    val (id, commit) = resolveCommit(target)
    val oldHead = headCommitId()
    if (mode == ResetMode.HARD) {
        val wt = workTreeFs()
        val ix = readIndex()
        val head = db.flattenTree(headTreeId())
        val tgt = db.flattenTree(commit.tree)
        val indexMtime = indexMtimeSeconds()
        val lost = LinkedHashSet<String>()
        for (e in ix.entries()) {
            if (e.stage != 0) { lost.add(e.path); continue }
            if (!sameEntry(head[e.path], FlatEntry(canonMode(e.mode), e.id))) lost.add(e.path)
            else if (worktreeChange(e, wt.lstat(e.path), wt, indexMtime) != null) lost.add(e.path)
        }
        for (p in tgt.keys) if (!ix.contains(p) && wt.lstat(p) != null) lost.add(p)
        if (lost.isNotEmpty() && !discardLocalChanges) {
            val l = lost.toList().sorted()
            throw GitException(GitErrorKind.DIRTY_TREE, "hard reset would discard local changes: ${l.joinToString(", ") { PathSafety.display(it) }}", l)
        }
        // Apply: every tracked path ends at target; modified-but-equal files are rewritten too.
        val cur = ix.stage0()
        for (p in cur.keys) if (p !in tgt) wt.delete(p)
        val newIx = GitIndex()
        for ((p, t) in tgt) {
            val c = cur[p]
            val unchanged = c != null && c.stage == 0 && sameEntry(FlatEntry(canonMode(c.mode), c.id), t) && lost.none { it == p } && wt.lstat(p) != null
            if (unchanged) { newIx.put(c!!); continue }
            if (wt.lstat(p)?.kind == FileKind.DIRECTORY) fs.deleteRecursively(wt.abs(p))
            writeEntry(wt, p, t)
            newIx.put(statEntry(wt, p, t))
        }
        newIx.write(fs, indexPath)
    } else if (mode == ResetMode.MIXED) {
        val tgt = db.flattenTree(commit.tree)
        val ix = readIndex()
        val newIx = GitIndex()
        for ((p, t) in tgt) {
            val c = ix.get(p)
            if (c != null && c.id == t.id && c.mode == canonMode(t.mode)) newIx.put(c) else newIx.put(IndexEntry(p, t.id, t.mode))
        }
        newIx.write(fs, indexPath)
    }
    val msg = "reset: moving to $target"
    val branch = refs.headBranch()
    if (branch != null) {
        refs.update(branch, id, if (oldHead == null) Expect.Any else Expect.Id(oldHead), reflogIdentity(), msg)
        refs.logOnly("HEAD", oldHead, id, reflogIdentity(), msg)
    } else {
        refs.detach("HEAD", id)
        refs.logOnly("HEAD", oldHead, id, reflogIdentity(), msg)
    }
    return id
}
