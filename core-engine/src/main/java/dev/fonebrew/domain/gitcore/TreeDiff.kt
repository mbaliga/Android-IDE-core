package dev.fonebrew.domain.gitcore

import dev.fonebrew.domain.diff.LineDiff

enum class ChangeType { ADD, DELETE, MODIFY, TYPE_CHANGE }

/** A file-level difference. Ids are null on the absent side; an id may be unknown (null) for a dirty work-tree file. */
data class FileChange(
    val path: String,
    val type: ChangeType,
    val oldMode: Int?,
    val newMode: Int?,
    val oldId: ObjectId?,
    val newId: ObjectId?,
)

private fun classify(o: FlatEntry?, n: FlatEntry?): ChangeType? = when {
    o == null && n == null -> null
    o == null -> ChangeType.ADD
    n == null -> ChangeType.DELETE
    o.id == n.id && o.mode == n.mode -> null
    FileMode.isSymlink(o.mode) != FileMode.isSymlink(n.mode) || FileMode.isGitlink(o.mode) != FileMode.isGitlink(n.mode) -> ChangeType.TYPE_CHANGE
    else -> ChangeType.MODIFY
}

/** Tree-to-tree diff, pruning identical subtrees by id. Either side null = empty tree. Sorted by path. */
fun ObjectDatabase.diffTrees(a: ObjectId?, b: ObjectId?): List<FileChange> {
    val out = ArrayList<FileChange>()
    fun walk(x: ObjectId?, y: ObjectId?, prefix: String, depth: Int) {
        if (x == y) return
        if (depth > limits.maxTreeDepth) limit("tree nested deeper than ${limits.maxTreeDepth}")
        val ex = x?.let { readTree(it).entries.associateBy { e -> e.name } } ?: emptyMap()
        val ey = y?.let { readTree(it).entries.associateBy { e -> e.name } } ?: emptyMap()
        for (name in (ex.keys + ey.keys).toSortedSet()) {
            val l = ex[name]; val r = ey[name]
            val path = prefix + name
            PathSafety.check(path, limits)
            val lt = l?.isTree == true; val rt = r?.isTree == true
            if (lt || rt) {
                walk(if (lt) l!!.id else null, if (rt) r!!.id else null, "$path/", depth + 1)
                if (!lt && l != null) out.add(FileChange(path, ChangeType.DELETE, l.mode, null, l.id, null))
                if (!rt && r != null) out.add(FileChange(path, ChangeType.ADD, null, r.mode, null, r.id))
            } else {
                val t = classify(l?.let { FlatEntry(canonMode(it.mode), it.id) }, r?.let { FlatEntry(canonMode(it.mode), it.id) }) ?: continue
                out.add(FileChange(path, t, l?.mode?.let(::canonMode), r?.mode?.let(::canonMode), l?.id, r?.id))
            }
        }
    }
    walk(a, b, "", 0)
    return out.sortedBy { it.path }
}

/** HEAD tree vs index (what `git diff --cached` shows). */
fun Repository.diffStaged(): List<FileChange> {
    val head = db.flattenTree(headTreeId())
    val ix = readIndex().stage0()
    return (head.keys + ix.keys).toSortedSet().mapNotNull { p ->
        val o = head[p]; val n = ix[p]?.let { FlatEntry(canonMode(it.mode), it.id) }
        classify(o, n)?.let { FileChange(p, it, o?.mode, n?.mode, o?.id, n?.id) }
    }
}

/** Index vs work tree (what `git diff` shows); new-side ids are hashed from the files. */
fun Repository.diffWorktree(): List<FileChange> {
    val wt = workTreeFs()
    val ix = readIndex()
    val mtime = indexMtimeSeconds()
    val out = ArrayList<FileChange>()
    for (e in ix.entries()) {
        if (e.stage != 0) continue
        val st = wt.lstat(e.path)
        val c = worktreeChange(e, st, wt, mtime) ?: continue
        when (c) {
            Change.DELETED -> out.add(FileChange(e.path, ChangeType.DELETE, e.mode, null, e.id, null))
            else -> {
                val nid = st?.let { wt.hash(e.path, it, algorithm) }
                val nmode = st?.let { wt.modeOf(it) }
                out.add(FileChange(e.path, if (c == Change.TYPE_CHANGED) ChangeType.TYPE_CHANGE else ChangeType.MODIFY, e.mode, nmode, e.id, nid))
            }
        }
    }
    return out.sortedBy { it.path }
}

/**
 * Unified diff for one [FileChange], produced by the app's existing [LineDiff] (so review UI and
 * engine agree). Returns null for binary content (a NUL in the first 8000 bytes) or content too
 * large to diff in memory ([maxBytes]); the caller shows "binary / too large" instead.
 * [newFromWorktree] reads the new side from the work tree rather than the object store.
 */
fun Repository.unifiedDiff(change: FileChange, newFromWorktree: Boolean = false, maxBytes: Int = 2 * 1024 * 1024): String? {
    fun load(id: ObjectId?, fromWork: Boolean): ByteArray? {
        if (fromWork) {
            val wt = workTreeFs()
            val st = wt.lstat(change.path) ?: return ByteArray(0)
            if (st.kind == FileKind.SYMLINK) return (fs.readSymlink(wt.abs(change.path)) ?: "").toByteArray()
            if (st.size > maxBytes) return null
            return fs.readBytes(wt.abs(change.path), maxBytes.toLong() + 1)
        }
        if (id == null) return ByteArray(0)
        val (_, size) = db.peek(id) ?: return ByteArray(0)
        if (size > maxBytes) return null
        return db.readRaw(id)?.data
    }
    val old = load(change.oldId, false) ?: return null
    val new = load(change.newId, newFromWorktree) ?: return null
    fun binary(b: ByteArray) = b.take(8000).any { it == 0.toByte() }
    if (binary(old) || binary(new)) return null
    return LineDiff.unified(String(old, Charsets.UTF_8), String(new, Charsets.UTF_8), "a/${change.path}", "b/${change.path}")
}
