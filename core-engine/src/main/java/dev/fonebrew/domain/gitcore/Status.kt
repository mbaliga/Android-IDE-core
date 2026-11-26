package dev.fonebrew.domain.gitcore

enum class Change(val code: Char) { ADDED('A'), MODIFIED('M'), DELETED('D'), TYPE_CHANGED('T'), UNTRACKED('?'), IGNORED('!'), CONFLICTED('U') }

/**
 * One path's state. [staged] is HEAD vs index; [unstaged] is index vs work tree (or
 * UNTRACKED / IGNORED for paths the index does not know). Glyph [Change.code] comes first for
 * display - state is never carried by colour alone.
 */
data class StatusEntry(val path: String, val staged: Change?, val unstaged: Change?)

class Status(val entries: List<StatusEntry>) {
    val staged get() = entries.filter { it.staged != null }
    val unstaged get() = entries.filter { it.unstaged != null && it.unstaged != Change.UNTRACKED && it.unstaged != Change.IGNORED }
    val untracked get() = entries.filter { it.unstaged == Change.UNTRACKED }
    val ignored get() = entries.filter { it.unstaged == Change.IGNORED }
    val conflicted get() = entries.filter { it.staged == Change.CONFLICTED }

    /** True when nothing tracked differs anywhere (untracked/ignored files do not count). */
    val isClean get() = entries.none { it.staged != null || (it.unstaged != null && it.unstaged != Change.UNTRACKED && it.unstaged != Change.IGNORED) }

    /** `git status --porcelain=v1`-shaped lines (XY path), for tests and a plain-text view. */
    fun porcelain(): List<String> = entries.map { e ->
        when {
            e.staged == Change.CONFLICTED -> "UU ${e.path}"
            e.unstaged == Change.UNTRACKED -> "?? ${e.path}"
            e.unstaged == Change.IGNORED -> "!! ${e.path}"
            else -> "${e.staged?.code ?: ' '}${e.unstaged?.code ?: ' '} ${e.path}"
        }
    }
}

internal fun sameEntry(a: FlatEntry?, b: FlatEntry?): Boolean = (a == null && b == null) || (a != null && b != null && a.id == b.id && a.mode == b.mode)

private fun kindOf(mode: Int) = if (FileMode.isSymlink(mode)) 1 else if (FileMode.isGitlink(mode)) 2 else 0

/**
 * Index-vs-work-tree state of one tracked path, racy-timestamp safe: stat equality only counts
 * as "unchanged" when the entry's mtime is strictly older than the index file's own mtime;
 * anything else (and any entry with no recorded stat) is decided by hashing the content.
 */
internal fun Repository.worktreeChange(e: IndexEntry, st: FileStat?, wt: WorkTreeFs, indexMtimeSeconds: Long): Change? {
    if (FileMode.isGitlink(e.mode)) return if (st == null) Change.DELETED else null
    if (st == null) return Change.DELETED
    if (st.kind != FileKind.FILE && st.kind != FileKind.SYMLINK) return Change.TYPE_CHANGED
    val mode = wt.modeOf(st)
    if (kindOf(mode) != kindOf(e.mode)) return Change.TYPE_CHANGED
    val noStat = e.mtimeSeconds == 0L && e.size == 0L && e.ctimeSeconds == 0L
    val statSame = !noStat && (st.size and 0xffff_ffffL) == e.size &&
        (st.mtimeSeconds and 0xffff_ffffL) == e.mtimeSeconds && st.mtimeNanos == e.mtimeNanos
    val racy = e.mtimeSeconds >= indexMtimeSeconds
    if (statSame && !racy) {
        return if (config.getBoolean("core", null, "filemode", true) && FileMode.isFile(e.mode) && (mode != canonMode(e.mode))) Change.MODIFIED else null
    }
    val h = wt.hash(wt.rel(e), st, algorithm) ?: return Change.MODIFIED
    if (h != e.id) return Change.MODIFIED
    return if (config.getBoolean("core", null, "filemode", true) && FileMode.isFile(e.mode) && mode != canonMode(e.mode)) Change.MODIFIED else null
}

private fun WorkTreeFs.rel(e: IndexEntry) = e.path

fun Repository.readIndex(): GitIndex = GitIndex.read(fs, indexPath, limits)

internal fun Repository.indexMtimeSeconds(): Long = fs.stat(indexPath)?.mtimeSeconds ?: 0L

internal fun Repository.workTreeFs() = WorkTreeFs(fs, requireWorkTree(), limits)

/**
 * HEAD vs index vs work tree. Untracked files are listed individually (not collapsed to
 * directories). Ignored files appear only when [includeIgnored] and the [IgnoreRules] port says so.
 * Nested repositories (a directory holding its own `.git`) are reported as `dir/` untracked.
 */
fun Repository.status(includeUntracked: Boolean = true, includeIgnored: Boolean = false): Status {
    val head = db.flattenTree(headTreeId())
    val ix = readIndex()
    val wt = workTreeFs()
    val indexMtime = indexMtimeSeconds()
    val out = java.util.TreeMap<String, StatusEntry>()
    val untrackedOut = java.util.TreeMap<String, StatusEntry>()
    val ignoredOut = java.util.TreeMap<String, StatusEntry>()
    val stage0 = ix.stage0()

    for (p in ix.conflictedPaths()) out[p] = StatusEntry(p, Change.CONFLICTED, null)
    for ((p, e) in stage0) {
        val h = head[p]
        val staged = when {
            h == null -> Change.ADDED
            sameEntry(h, FlatEntry(canonMode(e.mode), e.id)) -> null
            kindOf(h.mode) != kindOf(e.mode) -> Change.TYPE_CHANGED
            else -> Change.MODIFIED
        }
        val unstaged = worktreeChange(e, wt.lstat(p), wt, indexMtime)
        if (staged != null || unstaged != null) out[p] = StatusEntry(p, staged, unstaged)
    }
    for (p in head.keys) if (!ix.contains(p)) out[p] = StatusEntry(p, Change.DELETED, null)

    if (includeUntracked || includeIgnored) {
        val tracked = HashSet<String>(); val trackedDirs = HashSet<String>()
        for (e in ix.entries()) {
            tracked.add(e.path)
            var s = e.path.indexOf('/')
            while (s >= 0) { trackedDirs.add(e.path.substring(0, s)); s = e.path.indexOf('/', s + 1) }
        }
        fun walk(rel: String) {
            val dirAbs = if (rel.isEmpty()) wt.root else wt.abs(rel)
            for (name in fs.list(dirAbs)) {
                if (PathSafety.isGitDirName(name)) continue
                val childRel = if (rel.isEmpty()) name else "$rel/$name"
                val st = wt.lstat(childRel) ?: continue
                when (st.kind) {
                    FileKind.DIRECTORY -> {
                        if (childRel in tracked) continue // gitlink
                        if (childRel !in trackedDirs && fs.stat("${wt.abs(childRel)}/.git") != null) {
                            if (includeUntracked) untrackedOut["$childRel/"] = StatusEntry("$childRel/", null, Change.UNTRACKED)
                        } else if (childRel !in trackedDirs && ignore.isIgnored(childRel, true)) {
                            if (includeIgnored) ignoredOut["$childRel/"] = StatusEntry("$childRel/", null, Change.IGNORED)
                        } else walk(childRel)
                    }
                    FileKind.FILE, FileKind.SYMLINK -> if (childRel !in tracked) {
                        if (ignore.isIgnored(childRel, false)) { if (includeIgnored) ignoredOut[childRel] = StatusEntry(childRel, null, Change.IGNORED) }
                        else if (includeUntracked) untrackedOut[childRel] = StatusEntry(childRel, null, Change.UNTRACKED)
                    }
                    else -> {}
                }
            }
        }
        walk("")
    }
    // Same grouping as `git status --porcelain`: tracked changes, then untracked, then ignored.
    return Status(out.values.toList() + untrackedOut.values + ignoredOut.values)
}

/** Tracked paths whose content or mode is changed in the index or work tree (the "dirty" set). */
fun Repository.localChanges(): List<String> = status(includeUntracked = false).entries.filter { it.staged != null || (it.unstaged != null) }.map { it.path }
