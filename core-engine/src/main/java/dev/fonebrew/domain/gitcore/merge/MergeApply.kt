package dev.fonebrew.domain.gitcore.merge

import dev.fonebrew.domain.gitcore.*

private fun conflictPathsOf(conflicts: List<PathConflict>): Set<String> {
    val s = LinkedHashSet<String>()
    for (c in conflicts) { c.stages.forEach { s.add(it.path) }; c.work.forEach { s.add(it.first) } }
    return s
}

private fun IndexEntry.flat() = FlatEntry(canonMode(mode), id)

/**
 * Writes a merge/pick/replay result into the index and work tree - the one place that does.
 *
 * Preconditions are checked BEFORE the first byte is written, and refuse with DIRTY_TREE listing
 * every offender: the index must equal HEAD (no staged changes, no conflict stages), and every
 * path the result changes must be untouched in the work tree (no local edit, no untracked file in
 * the way). Unrelated local edits are left alone and carried across.
 *
 * Sequence: journal (status `applying`) -> [beforeWrite] (state files such as MERGE_HEAD) ->
 * deletions -> writes -> index (once) -> journal (status `ready`). A crash anywhere leaves the
 * journal saying `applying`, which [continueMerge] refuses and [abortMerge] restores from.
 */
internal fun Repository.applyMergePlan(
    clean: Map<String, FlatEntry>,
    conflicts: List<PathConflict>,
    op: String,
    orig: ObjectId?,
    beforeWrite: () -> Unit,
): Journal {
    val wt = workTreeFs()
    val ix = readIndex()
    if (ix.hasConflicts) throw GitException(GitErrorKind.DIRTY_TREE, "index has unresolved conflicts: ${ix.conflictedPaths().joinToString(", ") { PathSafety.display(it) }}", ix.conflictedPaths())
    val head = db.flattenTree(headTreeId())
    val cur = ix.stage0()
    val staged = (cur.keys + head.keys).filter { p -> !sameEntry(cur[p]?.flat(), head[p]) }.sorted()
    if (staged.isNotEmpty()) throw GitException(GitErrorKind.DIRTY_TREE, "index differs from HEAD (staged changes): ${staged.joinToString(", ") { PathSafety.display(it) }}", staged)

    val conflictPaths = conflictPathsOf(conflicts)
    val workContent = LinkedHashMap<String, WorkContent>()
    for (c in conflicts) for ((p, w) in c.work) workContent[p] = w
    val stagesByPath = LinkedHashMap<String, MutableList<StageEntry>>()
    for (c in conflicts) for (s in c.stages) stagesByPath.getOrPut(s.path) { ArrayList() }.add(s)

    val touched = LinkedHashSet<String>()
    for (p in (cur.keys + clean.keys + conflictPaths).toSortedSet()) {
        if (p in conflictPaths || !sameEntry(cur[p]?.flat(), clean[p])) touched.add(p)
    }
    val removed = touched.filter { it in cur && it !in clean && it !in workContent }.toSet()

    // ---- guard: nothing written yet
    val indexMtime = indexMtimeSeconds()
    val offenders = LinkedHashSet<String>()
    for (p in touched) {
        val c = cur[p]
        val st = wt.lstat(p)
        if (c == null) { if (st != null && st.kind != FileKind.DIRECTORY) offenders.add(p) }
        else if (worktreeChange(c, st, wt, indexMtime) != null) offenders.add(p)
        val writing = p in clean || p in workContent
        if (writing) {
            var slash = p.indexOf('/')
            while (slash >= 0) {
                val anc = p.substring(0, slash)
                val ast = wt.lstat(anc)
                if (ast != null && ast.kind != FileKind.DIRECTORY && anc !in removed) offenders.add(anc)
                if (ast?.kind == FileKind.SYMLINK) offenders.add(anc)
                slash = p.indexOf('/', slash + 1)
            }
            if (st?.kind == FileKind.DIRECTORY && c == null && hasForeign(fs, wt, p, removed)) offenders.add(p)
        }
    }
    if (offenders.isNotEmpty()) {
        val l = offenders.toList().sorted()
        throw GitException(GitErrorKind.DIRTY_TREE, "local changes would be overwritten by $op: ${l.joinToString(", ") { PathSafety.display(it) }}", l)
    }

    // ---- new index (built in memory first so a failure here changes nothing)
    val newIx = GitIndex()
    val statLater = ArrayList<Pair<String, FlatEntry>>()
    for ((p, e) in clean) {
        val c = cur[p]
        if (p !in touched && c != null) newIx.put(c) else statLater.add(p to e)
    }
    for ((p, list) in stagesByPath) for (s in list) newIx.put(IndexEntry(p, s.entry.id, s.entry.mode, s.stage))

    // ---- journal
    val entries = touched.map { p ->
        val w = workContent[p]
        val intendedWork: ObjectId? = when (w) { is WorkContent.Marked -> w.blobId; is WorkContent.Version -> w.entry.id; null -> clean[p]?.id }
        JournalEntry(p, p in conflictPaths, intendedWork, head[p]?.id, head[p]?.mode, "", idxState(ix, p))
    }
    // idxNow is only known after stat entries exist; compute from the planned entries instead
    val planned = GitIndex()
    for ((p, e) in clean) planned.put(IndexEntry(p, e.id, e.mode))
    for ((p, list) in stagesByPath) for (s in list) planned.put(IndexEntry(p, s.entry.id, s.entry.mode, s.stage))
    val journal = Journal(op, false, orig, entries.map { JournalEntry(it.path, it.conflicted, it.workId, it.priorId, it.priorMode, idxState(planned, it.path), it.idxPrior) })
    journal.write(this)
    beforeWrite()

    // ---- work tree: deletions first (so a file can give way to a directory), then writes
    for (p in removed) {
        val c = cur.getValue(p)
        if (FileMode.isGitlink(c.mode)) { if (fs.stat(wt.abs(p))?.kind == FileKind.DIRECTORY && fs.list(wt.abs(p)).isEmpty()) fs.delete(wt.abs(p)) }
        else wt.delete(p)
    }
    for ((p, e) in clean) if (p in touched && p !in workContent) writeEntry(wt, p, e)
    for ((p, w) in workContent) when (w) {
        is WorkContent.Version -> writeEntry(wt, p, w.entry)
        is WorkContent.Marked -> wt.writeFile(p, w.data, w.mode == FileMode.EXECUTABLE)
    }
    for ((p, e) in statLater) newIx.put(statEntry(wt, p, e))
    newIx.write(fs, indexPath)
    val ready = journal.withReady()
    ready.write(this)
    return ready
}

private fun hasForeign(fs: FileSystemPort, wt: WorkTreeFs, rel: String, ignoring: Set<String>): Boolean {
    for (n in fs.list(wt.abs(rel))) {
        val child = "$rel/$n"
        val st = wt.lstat(child) ?: continue
        if (st.kind == FileKind.DIRECTORY) { if (hasForeign(fs, wt, child, ignoring)) return true }
        else if (child !in ignoring) return true
    }
    return false
}

/** Hash of what the work tree holds at [p] now (null = absent, "dir" marker for a directory). */
internal fun Repository.currentWorkId(wt: WorkTreeFs, p: String): ObjectId? {
    val st = wt.lstat(p) ?: return null
    return wt.hash(p, st, algorithm)
}

class AbortResult(val restored: List<String>)

/**
 * Abandons an unfinished merge or cherry-pick and puts the work tree and index back exactly as
 * they were (HEAD does not move during a conflicted merge). It REFUSES, listing the paths and
 * saying why, when doing so would destroy something done since the operation started: a conflicted
 * file the user edited or staged, or any touched file whose content or index entry is neither what
 * the operation left nor what HEAD had. [discardLocalChanges] is the explicit opt-in to throw those
 * edits away. Without the engine's journal (an operation begun by another git client) the engine
 * cannot prove nothing would be lost, so it refuses unless [discardLocalChanges] is set.
 */
fun Repository.abortMerge(discardLocalChanges: Boolean = false): AbortResult {
    val st = mergeState() ?: throw GitException(GitErrorKind.INVALID_ARGUMENT, "no merge or cherry-pick in progress")
    return abortOperation(discardLocalChanges, listOf(StateFiles.MERGE_HEAD, StateFiles.CHERRY_PICK_HEAD, StateFiles.MERGE_MSG, StateFiles.MERGE_MODE), st.kind.name.lowercase())
}

internal fun Repository.abortOperation(discardLocalChanges: Boolean, stateFiles: List<String>, what: String): AbortResult {
    val wt = workTreeFs()
    val ix = readIndex()
    val head = db.flattenTree(headTreeId())
    val journal = Journal.read(this)
    val paths: List<String>
    if (journal == null) {
        // not our operation: the unmerged and staged paths are the best we can name
        paths = ix.entries().filter { it.stage != 0 || !sameEntry(it.flat(), head[it.path]) }.map { it.path }.distinct().sorted()
        if (!discardLocalChanges) {
            throw GitException(GitErrorKind.DIRTY_TREE, "this $what was not started by this engine, so it cannot be proven that abort loses nothing; pass discardLocalChanges to reset ${paths.size} path(s)", paths)
        }
    } else {
        paths = journal.entries.map { it.path }
        if (!discardLocalChanges) {
            val lost = ArrayList<String>()
            val indexMtime = indexMtimeSeconds()
            for (e in journal.entries) {
                val now = currentWorkId(wt, e.path)
                val workOk = now == e.workId || now == e.priorId
                val idxNow = idxState(ix, e.path)
                val idxOk = idxNow == e.idxNow || idxNow == e.idxPrior
                // an entry whose stat says unchanged is accepted without hashing only via the id compare above
                if (!workOk || !idxOk) lost.add(e.path)
            }
            if (lost.isNotEmpty()) {
                val l = lost.sorted()
                throw GitException(GitErrorKind.DIRTY_TREE, "abort would discard your edits to: ${l.joinToString(", ") { PathSafety.display(it) }} (they were changed or staged after the $what started); pass discardLocalChanges to throw them away", l)
            }
        }
    }
    // restore every named path from HEAD
    val newIx = ix.copy()
    for (p in paths) {
        newIx.remove(p)
    }
    // delete first (a file may give way to a directory), then write
    for (p in paths) if (head[p] == null) {
        val s = wt.lstat(p)
        if (s != null && s.kind != FileKind.DIRECTORY) wt.delete(p)
    }
    for (p in paths) {
        val h = head[p] ?: continue
        val s = wt.lstat(p)
        if (s?.kind == FileKind.DIRECTORY && !FileMode.isGitlink(h.mode)) fs.deleteRecursively(wt.abs(p))
        writeEntry(wt, p, h)
        newIx.put(statEntry(wt, p, h))
    }
    newIx.write(fs, indexPath)
    deleteState(*stateFiles.toTypedArray(), StateFiles.JOURNAL)
    return AbortResult(paths.sorted())
}
