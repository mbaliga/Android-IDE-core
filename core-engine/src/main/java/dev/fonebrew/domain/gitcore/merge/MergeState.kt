package dev.fonebrew.domain.gitcore.merge

import dev.fonebrew.domain.gitcore.*

/** What kind of operation left conflict state behind. */
enum class OperationKind { MERGE, CHERRY_PICK, REBASE }

/**
 * Files git itself uses for an unfinished merge (`MERGE_HEAD`, `MERGE_MSG`, `MERGE_MODE`,
 * `ORIG_HEAD`, `CHERRY_PICK_HEAD`) plus one private journal file, `FONEBREW_MERGE_JOURNAL`, which
 * git ignores. The journal records, for every path an operation changed, what it intended to leave
 * in the work tree and index and what was there before, so [abortMerge] can prove no later user
 * edit would be lost, and [continueMerge] can refuse an operation that was interrupted half way.
 */
internal object StateFiles {
    const val MERGE_HEAD = "MERGE_HEAD"
    const val MERGE_MSG = "MERGE_MSG"
    const val MERGE_MODE = "MERGE_MODE"
    const val ORIG_HEAD = "ORIG_HEAD"
    const val CHERRY_PICK_HEAD = "CHERRY_PICK_HEAD"
    const val JOURNAL = "FONEBREW_MERGE_JOURNAL"
}

private fun Repository.statePath(name: String) = "$gitDir/$name"

internal fun Repository.readStateId(name: String): ObjectId? {
    val p = statePath(name)
    if (fs.stat(p)?.kind != FileKind.FILE) return null
    return ObjectId.fromHexOrNull(String(fs.readBytes(p, 1024), Charsets.US_ASCII).trim().take(algorithm.hexLength), algorithm)
}

internal fun Repository.writeStateId(name: String, id: ObjectId) = fs.writeAtomic(statePath(name), (id.hex + "\n").toByteArray())
internal fun Repository.writeStateText(name: String, text: String) = fs.writeAtomic(statePath(name), text.toByteArray(Charsets.UTF_8))
internal fun Repository.readStateText(name: String): String? {
    val p = statePath(name)
    return if (fs.stat(p)?.kind == FileKind.FILE) String(fs.readBytes(p, 8L * 1024 * 1024), Charsets.UTF_8) else null
}
internal fun Repository.deleteState(vararg names: String) { for (n in names) fs.delete(statePath(n)) }
internal fun Repository.hasState(name: String) = fs.stat(statePath(name))?.kind == FileKind.FILE

/** What [mergeState] reports about an unfinished operation. */
class OperationState(
    val kind: OperationKind,
    /** The commit being merged / picked / replayed. */
    val other: ObjectId?,
    val message: String,
    /** Paths still holding conflict stages in the index. */
    val unresolved: List<String>,
    /** False when the operation was interrupted while writing; it can only be aborted. */
    val ready: Boolean,
)

/** The unfinished merge or cherry-pick, or null. (A rebase is reported by [rebaseState].) */
fun Repository.mergeState(): OperationState? {
    val kind = when {
        hasState(StateFiles.MERGE_HEAD) -> OperationKind.MERGE
        hasState(StateFiles.CHERRY_PICK_HEAD) -> OperationKind.CHERRY_PICK
        else -> return null
    }
    val other = readStateId(if (kind == OperationKind.MERGE) StateFiles.MERGE_HEAD else StateFiles.CHERRY_PICK_HEAD)
    val journal = Journal.read(this)
    val unresolved = readIndex().conflictedPaths()
    return OperationState(kind, other, readStateText(StateFiles.MERGE_MSG) ?: "", unresolved, journal?.ready ?: true)
}

// ------------------------------------------------------------------ journal

internal class JournalEntry(
    val path: String,
    val conflicted: Boolean,
    /** Blob id the work tree should hold after the operation (null = file absent). */
    val workId: ObjectId?,
    /** Blob id the work tree held before (the HEAD entry; null = absent). */
    val priorId: ObjectId?,
    val priorMode: Int?,
    /** Intended index state, `stage:hex:mode` joined by commas; empty = no entry. */
    val idxNow: String,
    val idxPrior: String,
)

internal class Journal(val op: String, val ready: Boolean, val orig: ObjectId?, val entries: List<JournalEntry>) {
    fun write(repo: Repository) {
        val sb = StringBuilder("fonebrew-merge-journal v1\nop $op\nstatus ${if (ready) "ready" else "applying"}\norig ${orig?.hex ?: "-"}\n")
        for (e in entries) {
            sb.append("entry\t").append(enc(e.path)).append('\t').append(if (e.conflicted) "C" else "M").append('\t')
                .append(e.workId?.hex ?: "-").append('\t').append(e.priorId?.hex ?: "-").append('\t')
                .append(e.priorMode?.let { Integer.toOctalString(it) } ?: "-").append('\t')
                .append(e.idxNow.ifEmpty { "-" }).append('\t').append(e.idxPrior.ifEmpty { "-" }).append('\n')
        }
        repo.writeStateText(StateFiles.JOURNAL, sb.toString())
    }

    fun withReady() = Journal(op, true, orig, entries)

    companion object {
        fun read(repo: Repository): Journal? {
            val text = repo.readStateText(StateFiles.JOURNAL) ?: return null
            val lines = text.split('\n')
            if (lines.firstOrNull() != "fonebrew-merge-journal v1") return null
            var op = "merge"; var ready = false; var orig: ObjectId? = null
            val entries = ArrayList<JournalEntry>()
            for (l in lines.drop(1)) {
                when {
                    l.startsWith("op ") -> op = l.substring(3)
                    l.startsWith("status ") -> ready = l.substring(7) == "ready"
                    l.startsWith("orig ") -> orig = ObjectId.fromHexOrNull(l.substring(5), repo.algorithm)
                    l.startsWith("entry\t") -> {
                        val f = l.split('\t')
                        if (f.size != 8) return null
                        fun id(s: String) = if (s == "-") null else ObjectId.fromHexOrNull(s, repo.algorithm)
                        entries.add(JournalEntry(dec(f[1]), f[2] == "C", id(f[3]), id(f[4]), if (f[5] == "-") null else f[5].toIntOrNull(8), if (f[6] == "-") "" else f[6], if (f[7] == "-") "" else f[7]))
                    }
                }
            }
            return Journal(op, ready, orig, entries)
        }

        private fun enc(p: String) = p.replace("%", "%25").replace("\t", "%09").replace("\n", "%0A").replace("\r", "%0D")
        private fun dec(p: String) = p.replace("%09", "\t").replace("%0A", "\n").replace("%0D", "\r").replace("%25", "%")
    }
}

internal fun idxState(ix: GitIndex, path: String): String =
    (0..3).mapNotNull { s -> ix.get(path, s)?.let { "$s:${it.id.hex}:${Integer.toOctalString(it.mode)}" } }.joinToString(",")
