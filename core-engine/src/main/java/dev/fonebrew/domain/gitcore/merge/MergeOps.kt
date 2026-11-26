package dev.fonebrew.domain.gitcore.merge

import dev.fonebrew.domain.gitcore.*
import dev.fonebrew.domain.gitcore.textmerge.ConflictMarkers
import dev.fonebrew.domain.gitcore.textmerge.MarkerStyle
import dev.fonebrew.domain.gitcore.textmerge.RawText

enum class FastForward {
    /** Fast-forward when possible, else make a merge commit (git's default). */
    ALLOW,
    /** Always make a merge commit (`--no-ff`). */
    NEVER,
    /** Fast-forward or fail (`--ff-only`). */
    ONLY,
}

data class MergeOptions(
    val fastForward: FastForward = FastForward.ALLOW,
    /** Message of the merge commit; null = git's `Merge branch 'x'`. */
    val message: String? = null,
    /** False = stop with the merged state staged and MERGE_HEAD set (`--no-commit`). */
    val commit: Boolean = true,
    val identity: Identity? = null,
    val allowUnrelatedHistories: Boolean = false,
    /** Marker style; null = `merge.conflictStyle` from the repository config, else MERGE. */
    val style: MarkerStyle? = null,
    val settings: MergeSettings = MergeSettings(),
)

enum class MergeOutcomeKind { UP_TO_DATE, FAST_FORWARD, CLEAN, CONFLICTED }

sealed class MergeOutcome(val kind: MergeOutcomeKind) {
    object UpToDate : MergeOutcome(MergeOutcomeKind.UP_TO_DATE)
    class FastForward(val head: ObjectId, val updated: List<String>, val removed: List<String>) : MergeOutcome(MergeOutcomeKind.FAST_FORWARD)
    /** Merged without conflicts; [commit] is null only when [MergeOptions.commit] was false. */
    class Clean(val commit: ObjectId?, val renameDetectionSkipped: Boolean) : MergeOutcome(MergeOutcomeKind.CLEAN)
    class Conflicted(val conflicts: List<PathConflict>, val renameDetectionSkipped: Boolean) : MergeOutcome(MergeOutcomeKind.CONFLICTED)
}

internal fun Repository.conflictStyle(explicit: MarkerStyle?): MarkerStyle = explicit ?: when (config.get("merge", null, "conflictstyle")?.lowercase()) {
    "diff3" -> MarkerStyle.DIFF3
    "zdiff3" -> MarkerStyle.ZDIFF3
    else -> MarkerStyle.MERGE
}

internal fun Repository.rejectIfBusy() {
    if (hasState(StateFiles.MERGE_HEAD) || hasState(StateFiles.CHERRY_PICK_HEAD)) throw GitException(GitErrorKind.INVALID_ARGUMENT, "a merge or cherry-pick is already in progress; continue or abort it first")
    if (fs.stat("$gitDir/rebase-merge")?.kind == FileKind.DIRECTORY) throw GitException(GitErrorKind.INVALID_ARGUMENT, "a rebase is in progress; continue or abort it first")
}

/** Creates a commit on HEAD's branch (or detached HEAD) by compare-and-swap, with reflog. */
internal fun Repository.commitOnHead(tree: ObjectId, parents: List<ObjectId>, message: String, author: Signature, committer: Signature, reflogMessage: String): ObjectId {
    val headId = headCommitId()
    val id = db.write(Commit(tree, parents, author, committer, message.trimEnd('\n') + "\n"))
    val who = ReflogIdentity(committer.name, committer.email, committer.epochSeconds, committer.tzMinutes)
    val branch = refs.headBranch()
    if (branch != null) {
        refs.update(branch, id, if (headId == null) Expect.Absent else Expect.Id(headId), who, reflogMessage)
        refs.logOnly("HEAD", headId, id, who, reflogMessage)
    } else {
        refs.detach("HEAD", id)
        refs.logOnly("HEAD", headId, id, who, reflogMessage)
    }
    return id
}

private fun mergeSubject(repo: Repository, target: String, id: ObjectId): String {
    val into = repo.refs.headBranch()?.removePrefix("refs/heads/")
    val suffix = if (into != null && into != "main" && into != "master") " into $into" else ""
    fun has(ref: String) = RefName.isValid(ref) && repo.refs.read(ref) != null
    val what = when {
        has("refs/heads/$target") -> "branch '$target'"
        has("refs/remotes/$target") -> "remote-tracking branch '$target'"
        has("refs/tags/$target") -> "tag '$target'"
        else -> "commit '${id.hex}'"
    }
    return "Merge $what$suffix"
}

internal fun conflictsComment(conflicts: List<PathConflict>): String =
    if (conflicts.isEmpty()) "" else "\n# Conflicts:\n" + conflicts.flatMap { it.paths }.distinct().sorted().joinToString("") { "#\t${PathSafety.display(it)}\n" }

private fun Repository.moveHeadTo(id: ObjectId, reflogMessage: String) {
    val old = headCommitId()
    val who = reflogIdentity()
    val branch = refs.headBranch()
    if (branch != null) {
        refs.update(branch, id, if (old == null) Expect.Absent else Expect.Id(old), who, reflogMessage)
        refs.logOnly("HEAD", old, id, who, reflogMessage)
    } else {
        refs.detach("HEAD", id)
        refs.logOnly("HEAD", old, id, who, reflogMessage)
    }
}

/**
 * `git merge <target>`: up to date, fast-forward, or a three-way merge (with a virtual base for a
 * criss-cross history). On conflicts the index holds stages 1/2/3 exactly as git leaves them, the
 * work tree holds marker files, and MERGE_HEAD / MERGE_MSG / MERGE_MODE / ORIG_HEAD are written;
 * resolve with [ConflictSession] or by hand and finish with [continueMerge], or [abortMerge].
 * Refuses with DIRTY_TREE (before writing anything) when staged changes exist or a path the merge
 * changes has local edits.
 */
fun Repository.merge(target: String, opts: MergeOptions = MergeOptions()): MergeOutcome {
    requireWorkTree()
    rejectIfBusy()
    val (theirsId, theirsCommit) = resolveCommit(target)
    val headId = headCommitId()
    val label = target
    val theirsFlat = db.flattenTree(theirsCommit.tree)

    if (headId == null) {
        // unborn branch: git checks the target out
        applyMergePlan(theirsFlat, emptyList(), "merge", null) {}
        deleteState(StateFiles.JOURNAL)
        moveHeadTo(theirsId, "merge $target: Fast-forward")
        return MergeOutcome.FastForward(theirsId, theirsFlat.keys.toList(), emptyList())
    }
    if (isAncestor(theirsId, headId)) return MergeOutcome.UpToDate
    val canFf = isAncestor(headId, theirsId)
    if (canFf && opts.fastForward != FastForward.NEVER) {
        val before = db.flattenTree(headTreeId())
        applyMergePlan(theirsFlat, emptyList(), "merge", headId) {}
        deleteState(StateFiles.JOURNAL)
        moveHeadTo(theirsId, "merge $target: Fast-forward")
        val upd = theirsFlat.filter { !sameEntry(before[it.key], it.value) }.keys.toList()
        return MergeOutcome.FastForward(theirsId, upd, before.keys.filter { it !in theirsFlat }.toList())
    }
    if (opts.fastForward == FastForward.ONLY) throw GitException(GitErrorKind.INVALID_ARGUMENT, "not possible to fast-forward, aborting")

    val base = baseTreeFor(headId, theirsId)
    if (base.commits.isEmpty() && !opts.allowUnrelatedHistories) throw GitException(GitErrorKind.INVALID_ARGUMENT, "refusing to merge unrelated histories")
    val baseLabel = if (base.virtual) "merged common ancestors" else base.commits.firstOrNull()?.abbrev() ?: "empty tree"
    val headTree = db.readCommit(headId).tree
    val settings = opts.settings.copy(style = conflictStyle(opts.style))
    val result = mergeFlatTrees(this, base.tree, headTree, theirsCommit.tree, MergeLabels("HEAD", label, baseLabel), settings)
    val subject = opts.message ?: mergeSubject(this, target, theirsId)
    val msg = subject.trimEnd('\n') + "\n" + conflictsComment(result.conflicts)

    applyMergePlan(result.clean, result.conflicts, "merge", headId) {
        writeStateId(StateFiles.ORIG_HEAD, headId)
        writeStateId(StateFiles.MERGE_HEAD, theirsId)
        writeStateText(StateFiles.MERGE_MODE, if (opts.fastForward == FastForward.NEVER) "no-ff" else "")
        writeStateText(StateFiles.MERGE_MSG, msg)
    }
    if (!result.isClean) return MergeOutcome.Conflicted(result.conflicts, result.renameDetectionSkipped)
    if (!opts.commit) return MergeOutcome.Clean(null, result.renameDetectionSkipped)
    val id = finishCommit(opts.identity, null, null, listOf(headId, theirsId), "merge $target: Merge made by the 'ort' strategy.", subject)
    return MergeOutcome.Clean(id, result.renameDetectionSkipped)
}

class ContinueOptions(
    /** Commit message; null = MERGE_MSG without its comment lines. */
    val message: String? = null,
    val identity: Identity? = null,
    /** Commit even if a resolved file still contains conflict-marker lines (a file that legitimately contains them). */
    val allowMarkers: Boolean = false,
)

/**
 * Commits the resolved state of an unfinished merge or cherry-pick. REFUSES (writing nothing) while
 * any path still has conflict stages, while the operation was interrupted before it finished
 * writing, and - unless [ContinueOptions.allowMarkers] - while a path that was conflicted still
 * has marker lines in the content about to be committed.
 */
fun Repository.continueMerge(opts: ContinueOptions = ContinueOptions()): ObjectId {
    val st = mergeState() ?: throw GitException(GitErrorKind.INVALID_ARGUMENT, "no merge or cherry-pick in progress")
    if (!st.ready) throw GitException(GitErrorKind.INVALID_ARGUMENT, "the operation was interrupted while writing; abort it and start again")
    val ix = readIndex()
    if (ix.hasConflicts) throw GitException(GitErrorKind.INVALID_ARGUMENT, "cannot continue: unresolved conflicts in ${ix.conflictedPaths().joinToString(", ") { PathSafety.display(it) }}", ix.conflictedPaths())
    if (!opts.allowMarkers) {
        val journal = Journal.read(this)
        val stillMarked = ArrayList<String>()
        for (e in journal?.entries.orEmpty().filter { it.conflicted }) {
            val ent = ix.get(e.path) ?: continue
            if (FileMode.isGitlink(ent.mode) || FileMode.isSymlink(ent.mode)) continue
            val peek = db.peek(ent.id) ?: continue
            if (peek.second > 8L * 1024 * 1024) continue
            val data = db.readRaw(ent.id)?.data ?: continue
            if (RawText.looksBinary(data)) continue
            if (ConflictMarkers.findMarkers(RawText.fromBytes(data)).isNotEmpty()) stillMarked.add(e.path)
        }
        if (stillMarked.isNotEmpty()) throw GitException(GitErrorKind.INVALID_ARGUMENT, "cannot continue: conflict markers remain in ${stillMarked.joinToString(", ") { PathSafety.display(it) }} (pass allowMarkers if they are intended)", stillMarked)
    }
    val headId = headCommitId()
    val parents: List<ObjectId>
    var author: Signature? = null
    if (st.kind == OperationKind.MERGE) parents = listOfNotNull(headId, st.other)
    else { parents = listOfNotNull(headId); author = st.other?.let { db.readCommit(it).author } }
    val raw = opts.message ?: st.message.lines().filter { !it.startsWith("#") }.joinToString("\n").trim()
    if (raw.isBlank()) throw GitException(GitErrorKind.INVALID_ARGUMENT, "empty commit message")
    val label = if (st.kind == OperationKind.MERGE) "merge: ${raw.lineSequence().first()}" else "cherry-pick: ${raw.lineSequence().first()}"
    return finishCommit(opts.identity, author, null, parents, label, raw)
}

/** Writes the commit from the index and clears every state file. */
internal fun Repository.finishCommit(identity: Identity?, author: Signature?, committerOverride: Signature?, parents: List<ObjectId>, reflogMessage: String, message: String): ObjectId {
    val ix = readIndex()
    val tree = ix.writeTree(db)
    val now = clock()
    val committer = committerOverride ?: signature(identity(identity), now)
    val id = commitOnHead(tree, parents, message, author ?: committer, committer, reflogMessage)
    deleteState(StateFiles.MERGE_HEAD, StateFiles.CHERRY_PICK_HEAD, StateFiles.MERGE_MSG, StateFiles.MERGE_MODE, StateFiles.JOURNAL)
    return id
}

// ------------------------------------------------------------------ cherry-pick

enum class CherryPickOutcomeKind { CLEAN, CONFLICTED, EMPTY }

sealed class CherryPickOutcome(val kind: CherryPickOutcomeKind) {
    /** Applied; [commit] is null only when [CherryPickOptions.commit] was false. */
    class Clean(val commit: ObjectId?) : CherryPickOutcome(CherryPickOutcomeKind.CLEAN)
    class Conflicted(val conflicts: List<PathConflict>) : CherryPickOutcome(CherryPickOutcomeKind.CONFLICTED)
    /** The change is already present: nothing to commit, nothing left behind. */
    object Empty : CherryPickOutcome(CherryPickOutcomeKind.EMPTY)
}

data class CherryPickOptions(
    val commit: Boolean = true,
    val identity: Identity? = null,
    val style: MarkerStyle? = null,
    val settings: MergeSettings = MergeSettings(),
)

/**
 * `git cherry-pick <commit>`: replays one commit's change onto HEAD as a three-way merge with the
 * commit's parent as base. The new commit keeps the original author, message and a fresh
 * committer. A merge commit is refused (it needs a mainline choice; not built). On conflicts the
 * state is CHERRY_PICK_HEAD + MERGE_MSG, finished by [continueMerge] or abandoned by [abortMerge].
 */
fun Repository.cherryPick(rev: String, opts: CherryPickOptions = CherryPickOptions()): CherryPickOutcome {
    requireWorkTree()
    rejectIfBusy()
    val (pickId, pick) = resolveCommit(rev)
    if (pick.parents.size > 1) throw GitException(GitErrorKind.UNSUPPORTED, "cherry-picking a merge commit needs a mainline parent (not built)")
    val headId = headCommitId() ?: throw GitException(GitErrorKind.INVALID_ARGUMENT, "cannot cherry-pick onto an unborn branch")
    val outcome = replayCommit(pickId, pick, headId, opts.style, opts.settings, "cherry-pick") {
        writeStateId(StateFiles.ORIG_HEAD, headId)
        writeStateId(StateFiles.CHERRY_PICK_HEAD, pickId)
    }
    return when (outcome) {
        is Replay.Conflicted -> CherryPickOutcome.Conflicted(outcome.conflicts)
        is Replay.Clean -> {
            if (!opts.commit) CherryPickOutcome.Clean(null)
            else {
                val ix = readIndex()
                if (ix.writeTree(db) == db.readCommit(headId).tree) {
                    // nothing changed: already applied
                    deleteState(StateFiles.CHERRY_PICK_HEAD, StateFiles.MERGE_MSG, StateFiles.JOURNAL)
                    CherryPickOutcome.Empty
                } else {
                    val id = finishCommit(opts.identity, pick.author, null, listOf(headId), "cherry-pick: ${pick.summary}", pick.message)
                    CherryPickOutcome.Clean(id)
                }
            }
        }
    }
}

internal sealed class Replay {
    class Clean(val result: TreeMergeResult) : Replay()
    class Conflicted(val conflicts: List<PathConflict>, val result: TreeMergeResult) : Replay()
}

/** Three-way applies commit [pick] (against its parent) onto [headId] in the work tree; writes state via [state]. */
internal fun Repository.replayCommit(pickId: ObjectId, pick: Commit, headId: ObjectId, style: MarkerStyle?, settings: MergeSettings, op: String, state: () -> Unit): Replay {
    val baseTree = pick.parents.firstOrNull()?.let { db.readCommit(it).tree }
    val short = "${pickId.abbrev()} (${pick.summary})"
    val result = mergeFlatTrees(this, baseTree, db.readCommit(headId).tree, pick.tree,
        MergeLabels("HEAD", short, "parent of $short"), settings.copy(style = conflictStyle(style)))
    val msg = pick.message.trimEnd('\n') + "\n" + conflictsComment(result.conflicts)
    applyMergePlan(result.clean, result.conflicts, op, headId) {
        state()
        writeStateText(StateFiles.MERGE_MSG, msg)
    }
    return if (result.isClean) Replay.Clean(result) else Replay.Conflicted(result.conflicts, result)
}
