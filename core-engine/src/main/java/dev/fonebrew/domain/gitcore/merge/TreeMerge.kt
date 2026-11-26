package dev.fonebrew.domain.gitcore.merge

import dev.fonebrew.domain.gitcore.*
import dev.fonebrew.domain.gitcore.textmerge.MarkerStyle
import dev.fonebrew.domain.gitcore.textmerge.TextMerge
import dev.fonebrew.domain.gitcore.textmerge.TextMergeOptions
import dev.fonebrew.domain.gitcore.textmerge.UnmergeableReason

/** Names shown in conflict markers and used for `path~label` files. */
data class MergeLabels(val ours: String, val theirs: String, val base: String)

data class MergeSettings(
    val style: MarkerStyle = MarkerStyle.MERGE,
    val markerSize: Int = 7,
    val detectRenames: Boolean = true,
    val renameMinSimilarity: Int = 50,
    /** Files above this are not text-merged (reported as conflicts without markers). */
    val maxTextBytes: Int = 8 * 1024 * 1024,
)

enum class ConflictKind {
    /** Both sides changed the same file's text and the changes overlap (markers written). */
    CONTENT,
    /** Both sides added the same path with different content (markers written, no base). */
    ADD_ADD,
    /** One side changed a file the other deleted. */
    MODIFY_DELETE,
    /** One side renamed a file the other deleted. */
    RENAME_DELETE,
    /** The two sides renamed one file to two different names. */
    RENAME_RENAME,
    /** A file on one side where the other side has a directory. */
    FILE_DIRECTORY,
    /** Both sides changed a binary or oversized file (no markers; ours is left in the work tree). */
    BINARY,
    /** Both sides changed the file mode differently. */
    MODE,
    /** A regular file on one side and a symlink/submodule on the other, or both changed a symlink/submodule. */
    TYPE_CHANGE,
}

/** An index entry the conflict leaves behind. */
data class StageEntry(val path: String, val stage: Int, val entry: FlatEntry)

/** What the work tree gets for a conflicted path. */
sealed class WorkContent {
    /** Merged text with conflict markers. */
    class Marked(val data: ByteArray, val mode: Int, val blobId: ObjectId, val hasMarkers: Boolean) : WorkContent()
    /** An existing version, written as is. */
    class Version(val entry: FlatEntry) : WorkContent()
}

/**
 * One conflicted path (or, for rename/rename, group of paths). [stages] is exactly what git leaves
 * in the index (`git ls-files -u`); [work] is what is written to the work tree.
 */
class PathConflict(
    val kind: ConflictKind,
    val path: String,
    val stages: List<StageEntry>,
    val work: List<Pair<String, WorkContent>>,
    val message: String,
) {
    val hasMarkers: Boolean get() = work.any { (it.second as? WorkContent.Marked)?.hasMarkers == true }
    val paths: List<String> get() = stages.map { it.path }.distinct()

    /** `git status --porcelain` two-letter code derived from the stages at the primary path. */
    val statusCode: String get() {
        val s = stages.filter { it.path == path }.map { it.stage }.toSet()
        return when {
            s == setOf(1, 2, 3) -> "UU"
            s == setOf(2, 3) -> "AA"
            s == setOf(1, 2) -> "UD"
            s == setOf(1, 3) -> "DU"
            s == setOf(3) -> "UA"
            s == setOf(2) -> "AU"
            s == setOf(1) -> "DD"
            else -> "UU"
        }
    }
}

class TreeMergeResult(
    /** Every path that merged without conflict (stage 0). Conflicted paths are NOT here. */
    val clean: Map<String, FlatEntry>,
    val conflicts: List<PathConflict>,
    /** Renames found on each side (base path to rename), for display and tests. */
    val oursRenames: List<Rename>,
    val theirsRenames: List<Rename>,
    val renameDetectionSkipped: Boolean,
) {
    val isClean get() = conflicts.isEmpty()

}

private sealed class Res {
    class Done(val path: String, val entry: FlatEntry?) : Res()
    class Conf(val c: PathConflict) : Res()
}

/**
 * Three-way merge of the trees [oursTree] and [theirsTree] over [baseTree] (null = empty base).
 * Writes merged blobs to the object database and returns the plan; touches no work tree or index.
 *
 * Per path (after rename pairing, see [detectRenames]): identical on both sides - take it; changed
 * on one side only - take that side (including a delete); changed on both - merge file text with
 * [TextMerge] and merge the mode three-way; anything else (modify/delete, rename/delete,
 * rename/rename, type or mode clashes, binary or oversized content) is a [PathConflict] carrying
 * the three versions. A file where the result also needs a directory of the same name moves to
 * `path~label` as a FILE_DIRECTORY conflict, as git does. Directory-rename inference is NOT done.
 */
fun mergeFlatTrees(repo: Repository, baseTree: ObjectId?, oursTree: ObjectId?, theirsTree: ObjectId?, labels: MergeLabels, settings: MergeSettings = MergeSettings()): TreeMergeResult {
    val base = repo.db.flattenTree(baseTree)
    val ours = repo.db.flattenTree(oursTree)
    val theirs = repo.db.flattenTree(theirsTree)
    return TreeMerger(repo, base, ours, theirs, labels, settings).run()
}

/** Virtual-base view: clean entries plus, for each conflict, its marker blob (or ours / theirs / base). */
internal fun TreeMergeResult.virtualBaseTree(): Map<String, FlatEntry> = virtualBaseTreeImpl(this)

private fun virtualBaseTreeImpl(r: TreeMergeResult): Map<String, FlatEntry> {
    val out = LinkedHashMap(r.clean)
    for (c in r.conflicts) {
        for ((p, w) in c.work) {
            when (w) {
                is WorkContent.Version -> out[p] = w.entry
                is WorkContent.Marked -> out[p] = FlatEntry(w.mode, w.blobId)
            }
        }
    }
    return out
}

private class TreeMerger(
    val repo: Repository,
    val base: Map<String, FlatEntry>, val ours: Map<String, FlatEntry>, val theirs: Map<String, FlatEntry>,
    val labels: MergeLabels, val settings: MergeSettings,
) {
    val db = repo.db

    fun run(): TreeMergeResult {
        var skipped = false
        val ro = if (settings.detectRenames) detectRenames(repo, base, ours, settings.renameMinSimilarity).also { if (it.inexactSkipped) skipped = true } else RenameResult(emptyMap(), false)
        val rt = if (settings.detectRenames) detectRenames(repo, base, theirs, settings.renameMinSimilarity).also { if (it.inexactSkipped) skipped = true } else RenameResult(emptyMap(), false)
        val consumedOurs = ro.renames.values.map { it.to }.toSet()
        val consumedTheirs = rt.renames.values.map { it.to }.toSet()

        // keys: every base path, plus added paths not consumed by a rename
        val keys = java.util.TreeSet<String>()
        keys.addAll(base.keys)
        for (p in ours.keys) if (p !in base && p !in consumedOurs) keys.add(p)
        for (p in theirs.keys) if (p !in base && p !in consumedTheirs) keys.add(p)

        val clean = LinkedHashMap<String, FlatEntry>()
        val conflicts = ArrayList<PathConflict>()
        val provenance = HashMap<String, Int>()      // clean path -> 2 ours / 3 theirs / 0 both
        fun put(path: String, e: FlatEntry?, side: Int) {
            if (e == null) return
            if (clean.containsKey(path)) throw GitException(GitErrorKind.UNSUPPORTED, "rename collides with another path '${PathSafety.display(path)}' (rename/add collision is not built)", listOf(PathSafety.display(path)))
            clean[path] = e; provenance[path] = side
        }

        for (k in keys) {
            val b = base[k]
            val rO = ro.renames[k]; val rT = rt.renames[k]
            val oPath = rO?.to ?: k; val tPath = rT?.to ?: k
            val o = ours[oPath]; val t = theirs[tPath]
            // a path the side removed (not renamed) has no entry; a renamed one reads from its new path
            if (rO != null && rT != null && rO.to != rT.to) {
                // rename/rename 1 to 2. As git does, a content/mode change made on either side travels with
                // the file: when the two versions merge cleanly the merged entry is what both names hold.
                val merged = (mergeEntry(rO.to, b, o, t) as? Res.Done)?.entry
                val eo = merged ?: o!!
                val et = merged ?: t!!
                val stages = listOfNotNull(b?.let { StageEntry(k, 1, it) }, StageEntry(rO.to, 2, eo), StageEntry(rT.to, 3, et))
                conflicts.add(PathConflict(ConflictKind.RENAME_RENAME, k, stages, listOf(rO.to to WorkContent.Version(eo), rT.to to WorkContent.Version(et)),
                    "CONFLICT (rename/rename): ${PathSafety.display(k)} renamed to ${PathSafety.display(rO.to)} in ${labels.ours} and to ${PathSafety.display(rT.to)} in ${labels.theirs}"))
                continue
            }
            val dest = rO?.to ?: rT?.to ?: k
            val renamed = dest != k
            if (renamed && ((rO != null && t == null) || (rT != null && o == null))) {
                // rename on one side, delete on the other
                val keeper = if (rO != null) o!! else t!!
                val stage = if (rO != null) 2 else 3
                conflicts.add(PathConflict(ConflictKind.RENAME_DELETE, dest,
                    listOfNotNull(b?.let { StageEntry(dest, 1, it) }, StageEntry(dest, stage, keeper)),
                    listOf(dest to WorkContent.Version(keeper)),
                    "CONFLICT (rename/delete): ${PathSafety.display(k)} renamed to ${PathSafety.display(dest)} in ${if (rO != null) labels.ours else labels.theirs}, but deleted in ${if (rO != null) labels.theirs else labels.ours}"))
                continue
            }
            when (val r = mergeEntry(dest, b, o, t)) {
                is Res.Done -> put(r.path, r.entry, when { r.entry == null -> 0; r.entry == o && r.entry != t -> 2; r.entry == t && r.entry != o -> 3; else -> 0 })
                is Res.Conf -> conflicts.add(r.c)
            }
        }
        resolveFileDirectory(clean, provenance, conflicts)
        guardCollisions(clean, conflicts)
        return TreeMergeResult(clean, conflicts.sortedBy { it.path }, ro.renames.values.toList(), rt.renames.values.toList(), skipped)
    }

    private fun kind(m: Int) = if (FileMode.isSymlink(m)) 1 else if (FileMode.isGitlink(m)) 2 else 0
    private fun same(a: FlatEntry?, b: FlatEntry?) = sameEntry(a, b)

    private fun mergeEntry(path: String, b: FlatEntry?, o: FlatEntry?, t: FlatEntry?): Res {
        if (same(o, t)) return Res.Done(path, o)
        if (same(b, o)) return Res.Done(path, t)
        if (same(b, t)) return Res.Done(path, o)
        fun st(vararg e: StageEntry?) = e.filterNotNull()
        val sb = b?.let { StageEntry(path, 1, it) }
        val so = o?.let { StageEntry(path, 2, it) }
        val sTh = t?.let { StageEntry(path, 3, it) }
        // modify/delete
        if (o == null || t == null) {
            val keeper = o ?: t!!
            return Res.Conf(PathConflict(ConflictKind.MODIFY_DELETE, path, st(sb, so, sTh), listOf(path to WorkContent.Version(keeper)),
                "CONFLICT (modify/delete): ${PathSafety.display(path)} deleted in ${if (o == null) labels.ours else labels.theirs} and modified in ${if (o == null) labels.theirs else labels.ours}"))
        }
        // type clashes and non-text kinds
        if (kind(o.mode) != 0 || kind(t.mode) != 0 || (b != null && kind(b.mode) != 0 && kind(b.mode) != kind(o.mode) && kind(b.mode) != kind(t.mode))) {
            return Res.Conf(PathConflict(ConflictKind.TYPE_CHANGE, path, st(sb, so, sTh), listOf(path to WorkContent.Version(o)),
                "CONFLICT (distinct types or non-text): ${PathSafety.display(path)}"))
        }
        // both regular files
        val mode: Int? = when {
            o.mode == t.mode -> o.mode
            b != null && b.mode == o.mode -> t.mode
            b != null && b.mode == t.mode -> o.mode
            else -> null
        }
        val contentId: ObjectId? = when {
            o.id == t.id -> o.id
            b != null && b.id == o.id -> t.id
            b != null && b.id == t.id -> o.id
            else -> null
        }
        if (contentId != null && mode != null) return Res.Done(path, FlatEntry(mode, contentId))
        if (contentId != null) {
            // identical or one-sided content but the modes clash
            return Res.Conf(PathConflict(ConflictKind.MODE, path, st(sb, so, sTh), listOf(path to WorkContent.Version(o)),
                "CONFLICT (mode): ${PathSafety.display(path)} has different modes in ${labels.ours} and ${labels.theirs}"))
        }
        // content merge
        val bBytes: ByteArray? = if (b == null) ByteArray(0) else blob(b.id)
        val oBytes = blob(o.id); val tBytes = blob(t.id)
        if (bBytes == null || oBytes == null || tBytes == null) {
            return Res.Conf(PathConflict(if (b == null) ConflictKind.ADD_ADD else ConflictKind.BINARY, path, st(sb, so, sTh), listOf(path to WorkContent.Version(o)),
                "CONFLICT (content): ${PathSafety.display(path)} is too large to merge"))
        }
        val tm = TextMerge.merge(bBytes, oBytes, tBytes, TextMergeOptions(
            settings.style, settings.markerSize, labels.ours, labels.base, labels.theirs, settings.maxTextBytes,
        ))
        if (tm.isUnmergeable) {
            return Res.Conf(PathConflict(ConflictKind.BINARY, path, st(sb, so, sTh), listOf(path to WorkContent.Version(o)),
                "CONFLICT (content): cannot merge ${if (tm.unmergeable!!.reason == UnmergeableReason.BINARY) "binary" else "oversized"} file ${PathSafety.display(path)}"))
        }
        val merged = tm.render()
        val outMode = mode ?: o.mode
        if (tm.isClean && mode != null) {
            return Res.Done(path, FlatEntry(mode, db.write(ObjectType.BLOB, merged)))
        }
        val kind = when {
            !tm.isClean && b == null -> ConflictKind.ADD_ADD
            !tm.isClean -> ConflictKind.CONTENT
            else -> ConflictKind.MODE
        }
        val marked = WorkContent.Marked(merged, outMode, db.write(ObjectType.BLOB, merged), !tm.isClean)
        return Res.Conf(PathConflict(kind, path, st(sb, so, sTh), listOf(path to marked),
            "CONFLICT (${when (kind) { ConflictKind.ADD_ADD -> "add/add"; ConflictKind.MODE -> "mode"; else -> "content" }}): Merge conflict in ${PathSafety.display(path)}"))
    }

    /** Blob bytes, or null when the blob is above the text-merge size cap. */
    private fun blob(id: ObjectId): ByteArray? {
        val peek = db.peek(id) ?: throw GitException(GitErrorKind.NOT_FOUND, "blob ${id.abbrev()} missing")
        if (peek.first != ObjectType.BLOB) malformed("${id.abbrev()} is not a blob")
        if (peek.second > settings.maxTextBytes) return null
        return db.readRaw(id)!!.data
    }

    /** A file that needs to be a directory (or the reverse) moves to `path~label`. */
    private fun resolveFileDirectory(clean: MutableMap<String, FlatEntry>, provenance: Map<String, Int>, conflicts: MutableList<PathConflict>) {
        val occupied = java.util.TreeSet<String>()
        occupied.addAll(clean.keys)
        for (c in conflicts) occupied.addAll(c.paths)
        fun hasDirBelow(p: String): Boolean { val s = occupied.higher("$p/") ?: return false; return s.startsWith("$p/") }
        // clean files
        for (p in clean.keys.toList()) {
            if (!hasDirBelow(p)) continue
            val e = clean.remove(p)!!
            val theirsSide = provenance[p] == 3 || (provenance[p] != 2 && ours[p] == null && theirs[p] != null)
            val label = if (theirsSide) labels.theirs else labels.ours
            val np = uniqueName(occupied, "$p~${labelPart(label)}")
            occupied.add(np)
            conflicts.add(PathConflict(ConflictKind.FILE_DIRECTORY, np, listOf(StageEntry(np, if (theirsSide) 3 else 2, e)), listOf(np to WorkContent.Version(e)),
                "CONFLICT (file/directory): directory in the way of ${PathSafety.display(p)} from $label; moving it to ${PathSafety.display(np)} instead."))
        }
        // conflicted paths whose own path is needed as a directory
        for (i in conflicts.indices) {
            val c = conflicts[i]
            if (c.kind == ConflictKind.FILE_DIRECTORY) continue
            val p = c.path
            if (!hasDirBelow(p)) continue
            val theirsSide = c.stages.none { it.stage == 2 } && c.stages.any { it.stage == 3 }
            val label = if (theirsSide) labels.theirs else labels.ours
            val np = uniqueName(occupied, "$p~${labelPart(label)}")
            occupied.add(np)
            conflicts[i] = PathConflict(ConflictKind.FILE_DIRECTORY, np, c.stages.map { StageEntry(np, it.stage, it.entry) }, c.work.map { np to it.second },
                "CONFLICT (file/directory): directory in the way of ${PathSafety.display(p)} from $label; moving it to ${PathSafety.display(np)} instead.")
        }
    }

    /**
     * Two decisions about one path (a rename landing on a path another change also claims) would let
     * the index silently keep only one of them. Refuse instead of dropping either.
     */
    private fun guardCollisions(clean: Map<String, FlatEntry>, conflicts: List<PathConflict>) {
        val seen = HashMap<Pair<String, Int>, FlatEntry>()
        for (c in conflicts) for (st in c.stages) {
            if (st.path in clean) throw GitException(GitErrorKind.UNSUPPORTED, "'${PathSafety.display(st.path)}' is both merged cleanly and conflicted (rename collision is not built)", listOf(PathSafety.display(st.path)))
            val prev = seen.put(st.path to st.stage, st.entry)
            if (prev != null && prev != st.entry) throw GitException(GitErrorKind.UNSUPPORTED, "two conflicts claim stage ${st.stage} of '${PathSafety.display(st.path)}' (rename collision is not built)", listOf(PathSafety.display(st.path)))
        }
    }

    private fun labelPart(label: String) = label.replace('/', '_').replace(' ', '_')
    private fun uniqueName(occupied: Set<String>, want: String): String { var n = want; var i = 0; while (n in occupied) n = want + "_" + (++i); return n }
}
