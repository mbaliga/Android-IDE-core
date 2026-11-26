package dev.fonebrew.domain.gitcore

// Index and work-tree operations: add, remove, move, restore. Checkout/reset/commit live in
// CheckoutOps.kt and Branches.kt. Every operation validates paths through PathSafety, writes
// through WorkTreeFs (never through a symlink), and leaves the index consistent on failure
// (the index file is replaced once, at the end, via a lock + rename).

private fun normSpec(spec: String): String {
    var s = spec
    while (s.startsWith("./")) s = s.substring(2)
    s = s.trimEnd('/')
    return if (s == ".") "" else s
}

private fun matches(path: String, spec: String) = spec.isEmpty() || path == spec || path.startsWith("$spec/")

private fun Repository.entryFor(wt: WorkTreeFs, rel: String, st: FileStat, prev: IndexEntry?): IndexEntry {
    val mode = wt.modeOf(st)
    val id: ObjectId = when (st.kind) {
        FileKind.SYMLINK -> {
            val t = fs.readSymlink(wt.abs(rel)) ?: throw GitException(GitErrorKind.IO, "cannot read symlink '${PathSafety.display(rel)}'")
            db.write(ObjectType.BLOB, t.toByteArray(Charsets.UTF_8))
        }
        else -> if (st.size <= limits.streamThreshold) {
            db.write(ObjectType.BLOB, fs.readBytes(wt.abs(rel), st.size + 1))
        } else db.writeBlobFromStream(fs, st.size) { fs.openRead(wt.abs(rel)) }
    }
    return IndexEntry(
        rel, id, canonMode(mode), 0, st.size and 0xffff_ffffL, st.ctimeSeconds, st.ctimeNanos, st.mtimeSeconds, st.mtimeNanos,
        st.dev and 0xffff_ffffL, st.ino and 0xffff_ffffL, st.uid, st.gid, prev?.assumeValid ?: false,
    )
}

class AddResult(val staged: List<String>, val removed: List<String>, val skippedIgnored: List<String>)

/**
 * Stages [specs] (files, directories, or `.`). A path gone from the work tree stages its deletion.
 * Ignored untracked files are skipped and reported unless [includeIgnored]. An unknown pathspec is
 * NOT_FOUND. Nested repositories are skipped.
 */
fun Repository.add(specs: List<String>, includeIgnored: Boolean = false): AddResult {
    val wt = workTreeFs()
    val ix = readIndex()
    val staged = ArrayList<String>(); val removed = ArrayList<String>(); val skipped = ArrayList<String>()

    fun stage(rel: String, st: FileStat) {
        PathSafety.check(rel, limits)
        val prev = ix.get(rel)
        val existingTracked = prev != null
        if (!existingTracked && ignore.isIgnored(rel, false) && !includeIgnored) { skipped.add(rel); return }
        val e = entryFor(wt, rel, st, prev)
        if (prev == null || prev.id != e.id || prev.mode != e.mode || prev.stage != 0) staged.add(rel)
        ix.put(e)
    }

    fun walk(rel: String) {
        val dirAbs = if (rel.isEmpty()) wt.root else wt.abs(rel)
        for (name in fs.list(dirAbs)) {
            if (PathSafety.isGitDirName(name)) continue
            val child = if (rel.isEmpty()) name else "$rel/$name"
            val st = wt.lstat(child) ?: continue
            when (st.kind) {
                FileKind.DIRECTORY -> {
                    val tracked = ix.pathsUnder(child).isNotEmpty()
                    if (!tracked && fs.stat("${wt.abs(child)}/.git") != null) continue // nested repository
                    if (!tracked && ignore.isIgnored(child, true) && !includeIgnored) { skipped.add("$child/"); continue }
                    walk(child)
                }
                FileKind.FILE, FileKind.SYMLINK -> stage(child, st)
                else -> {}
            }
        }
    }

    for (raw in specs) {
        val spec = normSpec(raw)
        if (spec.isNotEmpty()) { PathSafety.check(spec, limits); wt.requireRealAncestors(spec) }
        val st = if (spec.isEmpty()) wt.lstat("")?.let { it } ?: fs.stat(wt.root) else wt.lstat(spec)
        val trackedMatches = ix.entries().filter { matches(it.path, spec) }
        when {
            st == null -> {
                if (trackedMatches.isEmpty()) throw GitException(GitErrorKind.NOT_FOUND, "pathspec '${PathSafety.display(raw)}' did not match any files", listOf(PathSafety.display(raw)))
                for (e in trackedMatches.map { it.path }.distinct()) { if (ix.remove(e)) removed.add(e) }
            }
            st.kind == FileKind.DIRECTORY -> {
                if (spec.isNotEmpty() && ix.get(spec) != null && FileMode.isGitlink(ix.get(spec)!!.mode)) continue
                walk(spec)
                for (e in trackedMatches) {
                    if (FileMode.isGitlink(e.mode)) continue
                    val s2 = wt.lstat(e.path)
                    if (s2 == null || s2.kind == FileKind.DIRECTORY) { if (ix.remove(e.path)) removed.add(e.path) }
                }
            }
            st.kind == FileKind.FILE || st.kind == FileKind.SYMLINK -> stage(spec, st)
            else -> throw GitException(GitErrorKind.INVALID_ARGUMENT, "'${PathSafety.display(raw)}' is not a regular file, directory or symlink")
        }
    }
    ix.write(fs, indexPath)
    return AddResult(staged.distinct(), removed.distinct(), skipped)
}

/**
 * `git rm`: drops tracked paths from the index and (unless [cached]) deletes the files.
 * Refuses (DIRTY_TREE, listing paths) when a file has staged or unstaged changes unless [force].
 * A directory needs [recursive].
 */
fun Repository.remove(specs: List<String>, cached: Boolean = false, force: Boolean = false, recursive: Boolean = false): List<String> {
    val wt = workTreeFs()
    val ix = readIndex()
    val head = db.flattenTree(headTreeId())
    val indexMtime = indexMtimeSeconds()
    val targets = LinkedHashSet<IndexEntry>()
    for (raw in specs) {
        val spec = normSpec(raw)
        if (spec.isEmpty()) throw GitException(GitErrorKind.INVALID_ARGUMENT, "refusing to remove the whole tree; name paths")
        PathSafety.check(spec, limits)
        val hit = ix.entries().filter { it.stage == 0 && matches(it.path, spec) }
        if (hit.isEmpty()) throw GitException(GitErrorKind.NOT_FOUND, "pathspec '${PathSafety.display(raw)}' did not match any tracked files", listOf(PathSafety.display(raw)))
        if (hit.size > 1 || hit[0].path != spec) if (!recursive) throw GitException(GitErrorKind.INVALID_ARGUMENT, "'${PathSafety.display(raw)}' is a directory: pass recursive")
        targets.addAll(hit)
    }
    if (!force) {
        val dirty = targets.filter { e ->
            val staged = !sameEntry(head[e.path], FlatEntry(canonMode(e.mode), e.id))
            val unstaged = !cached && worktreeChange(e, wt.lstat(e.path), wt, indexMtime).let { it != null && it != Change.DELETED }
            if (cached) false else staged || unstaged
        }.map { it.path }
        if (dirty.isNotEmpty()) throw GitException(GitErrorKind.DIRTY_TREE, "files have local modifications (use force): ${dirty.joinToString(", ") { PathSafety.display(it) }}", dirty)
    }
    for (e in targets) {
        ix.remove(e.path)
        if (!cached) wt.delete(e.path)
    }
    ix.write(fs, indexPath)
    return targets.map { it.path }
}

/** `git mv`: renames a tracked file or directory in the work tree and the index. */
fun Repository.move(from: String, to: String, force: Boolean = false) {
    val wt = workTreeFs()
    val ix = readIndex()
    val src = normSpec(from); val dst = normSpec(to)
    PathSafety.check(src, limits); PathSafety.check(dst, limits)
    wt.requireRealAncestors(src); wt.requireRealAncestors(dst)
    if (dst == src || dst.startsWith("$src/")) throw GitException(GitErrorKind.INVALID_ARGUMENT, "cannot move a path into itself")
    val moving = ix.entries().filter { it.stage == 0 && matches(it.path, src) }
    if (moving.isEmpty()) throw GitException(GitErrorKind.NOT_FOUND, "'${PathSafety.display(from)}' is not tracked", listOf(PathSafety.display(from)))
    if (wt.lstat(src) == null) throw GitException(GitErrorKind.NOT_FOUND, "'${PathSafety.display(from)}' does not exist in the work tree", listOf(PathSafety.display(from)))
    val dstSt = wt.lstat(dst)
    if ((dstSt != null || ix.contains(dst) || ix.pathsUnder(dst).isNotEmpty()) && !force) {
        throw GitException(GitErrorKind.DIRTY_TREE, "destination '${PathSafety.display(to)}' already exists", listOf(PathSafety.display(to)))
    }
    wt.ensureParents(dst)
    if (dstSt != null) {
        if (dstSt.kind == FileKind.DIRECTORY) fs.deleteRecursively(wt.abs(dst)) else fs.delete(wt.abs(dst))
    }
    fs.rename(wt.abs(src), wt.abs(dst))
    wt.pruneEmptyDirs(src)
    for (e in moving) {
        ix.remove(e.path)
        val np = dst + e.path.substring(src.length)
        ix.put(e.copy(path = np))
    }
    ix.write(fs, indexPath)
}

private fun Repository.pathMatches(paths: List<String>): (String) -> Boolean {
    val specs = paths.map { normSpec(it).also { s -> if (s.isNotEmpty()) PathSafety.check(s, limits) } }
    if (specs.isEmpty()) throw GitException(GitErrorKind.INVALID_ARGUMENT, "no paths given")
    return { p -> specs.any { matches(p, it) } }
}

/**
 * Overwrites work-tree files from the INDEX (`git restore`). Because that destroys edits, it
 * refuses with DIRTY_TREE (listing the files) unless [discardLocalChanges] is true.
 */
fun Repository.restoreWorktree(paths: List<String>, discardLocalChanges: Boolean = false): List<String> {
    val wt = workTreeFs()
    val ix = readIndex()
    val m = pathMatches(paths)
    val indexMtime = indexMtimeSeconds()
    val targets = ix.entries().filter { it.stage == 0 && m(it.path) }
    if (targets.isEmpty()) throw GitException(GitErrorKind.NOT_FOUND, "no tracked path matches", paths)
    val dirty = targets.filter { e -> worktreeChange(e, wt.lstat(e.path), wt, indexMtime).let { it != null && it != Change.DELETED } }.map { it.path }
    if (dirty.isNotEmpty() && !discardLocalChanges) {
        throw GitException(GitErrorKind.DIRTY_TREE, "would discard local changes in: ${dirty.joinToString(", ") { PathSafety.display(it) }}", dirty)
    }
    val done = ArrayList<String>()
    val newIx = ix.copy()
    for (e in targets) {
        if (FileMode.isGitlink(e.mode)) { fs.mkdirs(wt.abs(e.path)); continue }
        writeEntry(wt, e.path, FlatEntry(e.mode, e.id))
        wt.lstat(e.path)?.let { st -> newIx.put(e.copy(size = st.size and 0xffff_ffffL, mtimeSeconds = st.mtimeSeconds, mtimeNanos = st.mtimeNanos, ctimeSeconds = st.ctimeSeconds, ctimeNanos = st.ctimeNanos, dev = st.dev and 0xffff_ffffL, ino = st.ino and 0xffff_ffffL, uid = st.uid, gid = st.gid)) }
        done.add(e.path)
    }
    newIx.write(fs, indexPath)
    return done
}

/** Resets index entries for [paths] to [source]'s tree (`git restore --staged`). The work tree is untouched. */
fun Repository.restoreStaged(paths: List<String>, source: String = "HEAD"): List<String> {
    val ix = readIndex()
    val m = pathMatches(paths)
    val tree = if (source == "HEAD" && headCommitId() == null) emptyMap() else db.flattenTree(treeOf(resolve(source)))
    val touched = LinkedHashSet<String>()
    for (e in ix.entries()) if (m(e.path)) touched.add(e.path)
    for (p in tree.keys) if (m(p)) touched.add(p)
    if (touched.isEmpty()) throw GitException(GitErrorKind.NOT_FOUND, "no path matches", paths)
    for (p in touched) {
        val t = tree[p]
        val cur = ix.get(p)
        if (t == null) ix.remove(p)
        else if (cur == null || cur.id != t.id || cur.mode != t.mode || cur.stage != 0) ix.put(IndexEntry(p, t.id, t.mode))
    }
    ix.write(fs, indexPath)
    return touched.toList()
}

/** `git checkout <rev> -- <paths>`: index and work tree take [rev]'s version. Guarded like [restoreWorktree]. */
fun Repository.restorePaths(rev: String, paths: List<String>, discardLocalChanges: Boolean = false): List<String> {
    val wt = workTreeFs()
    val ix = readIndex()
    val m = pathMatches(paths)
    val tree = db.flattenTree(treeOf(resolve(rev)))
    val targets = tree.filter { m(it.key) }
    if (targets.isEmpty()) throw GitException(GitErrorKind.NOT_FOUND, "no path matches in $rev", paths)
    val indexMtime = indexMtimeSeconds()
    val dirty = targets.keys.filter { p ->
        val e = ix.get(p)
        val st = wt.lstat(p)
        if (e == null) st != null // untracked file in the way
        else {
            val c = worktreeChange(e, st, wt, indexMtime)
            c != null && c != Change.DELETED
        }
    }
    if (dirty.isNotEmpty() && !discardLocalChanges) {
        throw GitException(GitErrorKind.DIRTY_TREE, "would overwrite local changes in: ${dirty.joinToString(", ") { PathSafety.display(it) }}", dirty)
    }
    for ((p, t) in targets) {
        if (FileMode.isGitlink(t.mode)) { fs.mkdirs(wt.abs(p)); ix.put(IndexEntry(p, t.id, t.mode)); continue }
        writeEntry(wt, p, t)
        ix.put(statEntry(wt, p, t))
    }
    ix.write(fs, indexPath)
    return targets.keys.toList()
}

internal fun Repository.statEntry(wt: WorkTreeFs, p: String, t: FlatEntry): IndexEntry {
    val st = wt.lstat(p) ?: return IndexEntry(p, t.id, t.mode)
    return IndexEntry(p, t.id, t.mode, 0, st.size and 0xffff_ffffL, st.ctimeSeconds, st.ctimeNanos, st.mtimeSeconds, st.mtimeNanos, st.dev and 0xffff_ffffL, st.ino and 0xffff_ffffL, st.uid, st.gid)
}

/** Materialises one tree entry into the work tree safely. */
internal fun Repository.writeEntry(wt: WorkTreeFs, path: String, t: FlatEntry) {
    when {
        FileMode.isGitlink(t.mode) -> { PathSafety.check(path, limits); wt.ensureParents(path); fs.mkdirs(wt.abs(path)) }
        FileMode.isSymlink(t.mode) -> {
            val raw = db.readRaw(t.id) ?: throw GitException(GitErrorKind.NOT_FOUND, "blob ${t.id.abbrev()} missing")
            if (raw.type != ObjectType.BLOB) malformed("symlink entry is not a blob")
            wt.writeSymlink(path, String(raw.data, Charsets.UTF_8))
        }
        else -> wt.writeFromStore(path, db, t.id, t.mode == FileMode.EXECUTABLE)
    }
}
