package dev.fonebrew.domain.gitcore.merge

import dev.fonebrew.domain.gitcore.*
import dev.fonebrew.domain.gitcore.textmerge.ConflictMarkers
import dev.fonebrew.domain.gitcore.textmerge.MarkerStyle
import dev.fonebrew.domain.gitcore.textmerge.RawText

/**
 * Test seam: a hook called at named points of a rebase step so a test can simulate the process
 * dying there (by throwing). Never set in production code.
 */
internal object RebaseTestHooks {
    @Volatile var hook: ((String) -> Unit)? = null
    fun at(point: String) { hook?.invoke(point) }
}

data class RebaseOptions(
    /** Replay onto this instead of the upstream (`--onto`). */
    val onto: String? = null,
    val identity: Identity? = null,
    val style: MarkerStyle? = null,
    val settings: MergeSettings = MergeSettings(),
)

enum class RebaseOutcomeKind { UP_TO_DATE, FAST_FORWARD, COMPLETED, STOPPED }

sealed class RebaseOutcome(val kind: RebaseOutcomeKind) {
    object UpToDate : RebaseOutcome(RebaseOutcomeKind.UP_TO_DATE)
    /** The branch had no commits of its own; it now points at the new base. */
    class FastForward(val head: ObjectId) : RebaseOutcome(RebaseOutcomeKind.FAST_FORWARD)
    class Completed(val head: ObjectId, val applied: Int) : RebaseOutcome(RebaseOutcomeKind.COMPLETED)
    /** Stopped at conflicts in commit [commit] (step [step] of [total]); resolve, then [rebaseContinue]. */
    class Stopped(val commit: ObjectId, val step: Int, val total: Int, val conflicts: List<PathConflict>) : RebaseOutcome(RebaseOutcomeKind.STOPPED)
}

class RebaseState(
    val branch: String?,
    val onto: ObjectId,
    val origHead: ObjectId,
    val total: Int,
    /** Steps fully applied. */
    val done: Int,
    /** The commit whose replay stopped on conflicts, if stopped. */
    val stoppedAt: ObjectId?,
    val unresolved: List<String>,
)

/*
 * On-disk layout (git's `rebase-merge/` names where git has them, so `git status` recognises a
 * rebase in progress):
 *   head-name, onto, orig-head, git-rebase-todo, done, end, msgnum, stopped-sha, message,
 *   author-script, interactive (empty), and REBASE_HEAD at the git dir root.
 * Private to this engine (git ignores them):
 *   fb-todo      every commit to replay, one full id per line, never rewritten
 *   fb-state     "tip <hex>" / "next <n>": THE commit point of a step - one atomic write
 *   fb-applying  "<i> pick" then "<i> pick <newCommit>" while a step is in flight
 * Everything git-visible except the above is DERIVED from fb-todo + fb-state and regenerated on
 * resume, so a crash between any two file writes never loses or repeats a step.
 */
private const val DIR = "rebase-merge"

private fun Repository.rb(name: String) = "$gitDir/$DIR/$name"
private fun Repository.rbRead(name: String): String? = if (fs.stat(rb(name))?.kind == FileKind.FILE) String(fs.readBytes(rb(name), 8L * 1024 * 1024), Charsets.UTF_8) else null
private fun Repository.rbWrite(name: String, text: String) = fs.writeAtomic(rb(name), text.toByteArray(Charsets.UTF_8))

private class RbInfo(val branchRef: String?, val onto: ObjectId, val orig: ObjectId, val todo: List<ObjectId>, val tip: ObjectId, val next: Int)

/**
 * A rebase begun by the git CLI has no `fb-*` files. Adopt it: the plain `pick` lines of `done` and
 * `git-rebase-todo` become the engine's todo, and HEAD is the tip. Any other todo command
 * (squash, edit, ...) is refused, never guessed at.
 */
private fun Repository.adoptGitRebase() {
    if (rbRead("fb-todo") != null) return
    val done = rbRead("done")?.lines()?.filter { it.isNotBlank() } ?: return
    val todo = rbRead("git-rebase-todo")?.lines()?.filter { it.isNotBlank() && !it.startsWith("#") } ?: return
    val stopped = rbRead("stopped-sha") != null
    fun parse(l: String): ObjectId {
        val parts = l.trim().split(' ')
        if (parts[0] != "pick" && parts[0] != "p") throw GitException(GitErrorKind.UNSUPPORTED, "rebase todo command '${PathSafety.display(parts[0])}' is not built (only pick)")
        return ObjectId.fromHexOrNull(parts[1], algorithm) ?: db.resolvePrefix(parts[1])
    }
    val all = (done + todo).map(::parse)
    val tip = headCommitId() ?: return
    rbWrite("fb-todo", all.joinToString("") { it.hex + "\n" })
    writeTip(tip, done.size - if (stopped) 1 else 0)
}

private fun Repository.readRb(): RbInfo? {
    if (fs.stat("$gitDir/$DIR")?.kind != FileKind.DIRECTORY) return null
    if (fs.stat(rb("fb-state")) == null) adoptGitRebase()
    val headName = rbRead("head-name")?.trim() ?: return null
    val onto = ObjectId.fromHexOrNull(rbRead("onto")?.trim() ?: return null, algorithm) ?: return null
    val orig = ObjectId.fromHexOrNull(rbRead("orig-head")?.trim() ?: return null, algorithm) ?: return null
    val todo = (rbRead("fb-todo") ?: return null).lines().filter { it.isNotBlank() }.map { ObjectId.fromHex(it.trim(), algorithm) }
    val st = (rbRead("fb-state") ?: return null).lines()
    val tip = ObjectId.fromHexOrNull(st.firstOrNull { it.startsWith("tip ") }?.substring(4)?.trim() ?: return null, algorithm) ?: return null
    val next = st.firstOrNull { it.startsWith("next ") }?.substring(5)?.trim()?.toIntOrNull() ?: return null
    return RbInfo(if (headName == "detached HEAD") null else headName, onto, orig, todo, tip, next)
}

/** The rebase in progress, or null. */
fun Repository.rebaseState(): RebaseState? {
    val i = readRb() ?: return null
    val stopped = rbRead("stopped-sha")?.trim()?.let { ObjectId.fromHexOrNull(it, algorithm) }
    return RebaseState(i.branchRef?.removePrefix("refs/heads/"), i.onto, i.orig, i.todo.size, i.next, stopped, readIndex().conflictedPaths())
}

private fun Repository.writeDerived(i: RbInfo, stopped: ObjectId?) {
    fun line(id: ObjectId) = "pick ${id.hex} ${db.readCommit(id).summary}\n"
    val remaining = i.todo.drop(i.next + if (stopped != null) 1 else 0)
    rbWrite("git-rebase-todo", remaining.joinToString("") { line(it) })
    rbWrite("done", i.todo.take(i.next + if (stopped != null) 1 else 0).joinToString("") { line(it) })
    rbWrite("end", "${i.todo.size}\n")
    rbWrite("msgnum", "${i.next + if (stopped != null) 1 else 0}\n")
    rbWrite("interactive", "")
    rbWrite("head-name", (i.branchRef ?: "detached HEAD") + "\n")
    rbWrite("onto", i.onto.hex + "\n")
    rbWrite("orig-head", i.orig.hex + "\n")
    if (stopped == null) { fs.delete(rb("stopped-sha")); fs.delete("$gitDir/REBASE_HEAD") }
}

private fun Repository.writeTip(tip: ObjectId, next: Int) = rbWrite("fb-state", "tip ${tip.hex}\nnext $next\n")

private fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

/**
 * `git rebase <upstream>` for a LINEAR branch: the commits on the current branch that are not in
 * [upstream] are replayed one by one on top of `onto` (default [upstream]). Merge commits in the
 * range are refused (UNSUPPORTED), not silently flattened. The work tree and index must have no
 * tracked changes. HEAD is detached while replaying and the branch moves once, at the end.
 *
 * The run is a resumable state machine (see the layout comment): [rebaseContinue] after a
 * conflict, after a crash, or after the process was killed between any two writes.
 */
fun Repository.rebase(upstream: String, opts: RebaseOptions = RebaseOptions()): RebaseOutcome {
    requireWorkTree()
    rejectIfBusy()
    val branchRef = refs.headBranch() ?: throw GitException(GitErrorKind.UNSUPPORTED, "rebase needs a branch checked out (detached HEAD is not built)")
    val origId = headCommitId() ?: throw GitException(GitErrorKind.INVALID_ARGUMENT, "cannot rebase an unborn branch")
    val dirty = localChanges()
    if (dirty.isNotEmpty()) throw GitException(GitErrorKind.DIRTY_TREE, "cannot rebase: you have uncommitted changes: ${dirty.joinToString(", ") { PathSafety.display(it) }}", dirty)
    val (upId, _) = resolveCommit(upstream)
    val (ontoId, onto) = resolveCommit(opts.onto ?: upstream)

    val upAnc = ancestors(upId)
    val chain = ArrayList<ObjectId>()
    var cur: ObjectId? = origId
    while (cur != null && cur !in upAnc) {
        val c = db.readCommit(cur)
        if (c.parents.size > 1) throw GitException(GitErrorKind.UNSUPPORTED, "commit ${cur.abbrev()} is a merge; rebasing merge commits is not built (it would silently flatten history)", listOf(cur.hex))
        chain.add(cur)
        cur = c.parents.firstOrNull()
    }
    chain.reverse()
    if (chain.isEmpty()) {
        // nothing of our own: the branch just takes the new base
        if (ontoId == origId) return RebaseOutcome.UpToDate
        applyMergePlan(db.flattenTree(onto.tree), emptyList(), "rebase", origId) {}
        deleteState(StateFiles.JOURNAL)
        writeStateId(StateFiles.ORIG_HEAD, origId)
        val who = reflogIdentity(opts.identity)
        refs.update(branchRef, ontoId, Expect.Id(origId), who, "rebase (finish): $branchRef onto ${ontoId.hex}")
        refs.logOnly("HEAD", origId, ontoId, who, "rebase (finish): returning to $branchRef")
        return RebaseOutcome.FastForward(ontoId)
    }
    if (db.readCommit(chain.first()).parents.firstOrNull() == ontoId) return RebaseOutcome.UpToDate

    // state first, then move HEAD
    val info = RbInfo(branchRef, ontoId, origId, chain, ontoId, 0)
    fs.mkdirs("$gitDir/$DIR")
    rbWrite("fb-todo", chain.joinToString("") { it.hex + "\n" })
    rbWrite("fb-style", (opts.style ?: MarkerStyle.MERGE).name + "\n")
    writeTip(ontoId, 0)
    writeDerived(info, null)
    writeStateId(StateFiles.ORIG_HEAD, origId)
    RebaseTestHooks.at("started-state")
    enterDetached(info)
    return runLoop(info, opts)
}

/** Moves the work tree and HEAD onto the base (idempotent: safe to repeat after a crash). */
private fun Repository.enterDetached(i: RbInfo) {
    if (refs.headBranch() == null && headCommitId() == i.onto) return
    val ontoTree = db.readCommit(i.onto).tree
    val ix0 = readIndex()
    val alreadyThere = !ix0.hasConflicts && ix0.writeTree(db) == ontoTree
    if (!alreadyThere) {
        // a crash may have left a half-written checkout: put the original state back first
        if (hasState(StateFiles.JOURNAL)) abortOperation(true, emptyList(), "rebase start")
        applyMergePlan(db.flattenTree(ontoTree), emptyList(), "rebase", i.orig) {}
        deleteState(StateFiles.JOURNAL)
    }
    val old = headCommitId()
    refs.detach("HEAD", i.onto)
    refs.logOnly("HEAD", old, i.onto, reflogIdentity(), "rebase (start): checkout ${i.onto.hex}")
}

private fun Repository.runLoop(i0: RbInfo, opts: RebaseOptions): RebaseOutcome {
    var info = readRb() ?: i0
    val style = rbRead("fb-style")?.trim()?.let { runCatching { MarkerStyle.valueOf(it) }.getOrNull() } ?: MarkerStyle.MERGE
    while (info.next < info.todo.size) {
        val pickId = info.todo[info.next]
        val pick = db.readCommit(pickId)
        rbWrite("fb-applying", "${info.next} pick\n")
        RebaseTestHooks.at("after-marker")
        val tip = info.tip
        val replay = replayCommit(pickId, pick, tip, opts.style ?: style, opts.settings, "rebase") {}
        RebaseTestHooks.at("after-plan")
        if (replay is Replay.Conflicted) {
            // the stop record goes down BEFORE the in-flight marker is cleared, so a crash here still resumes as "stopped"
            rbWrite("stopped-sha", pickId.hex + "\n")
            rbWrite("message", pick.message)
            rbWrite("author-script", "GIT_AUTHOR_NAME=${shellQuote(pick.author.name)}\nGIT_AUTHOR_EMAIL=${shellQuote(pick.author.email)}\nGIT_AUTHOR_DATE=${shellQuote("@${pick.author.epochSeconds} ${tzString(pick.author.tzMinutes)}")}\n")
            fs.writeAtomic("$gitDir/REBASE_HEAD", (pickId.hex + "\n").toByteArray())
            RebaseTestHooks.at("before-stop-cleanup")
            fs.delete(rb("fb-applying"))
            writeDerived(info, pickId)
            return RebaseOutcome.Stopped(pickId, info.next + 1, info.todo.size, replay.conflicts)
        }
        val ix = readIndex()
        val tree = ix.writeTree(db)
        if (tree == db.readCommit(tip).tree) {
            // the change is already in the base: git drops such a commit
            info = completeStep(info, tip, tip, opts)
            continue
        }
        val committer = signature(identity(opts.identity), clock())
        val n = db.write(Commit(tree, listOf(tip), pick.author, committer, pick.message.trimEnd('\n') + "\n"))
        rbWrite("fb-applying", "${info.next} pick ${n.hex}\n")
        RebaseTestHooks.at("after-commit-object")
        info = completeStep(info, tip, n, opts)
    }
    return finalizeRebase(info, opts)
}

private fun tzString(minutes: Int): String { val sign = if (minutes < 0) "-" else "+"; val m = Math.abs(minutes); return "%s%02d%02d".format(sign, m / 60, m % 60) }

/** Steps 5-7: move HEAD to [n], commit the step (atomic fb-state), regenerate derived files. */
private fun Repository.completeStep(info: RbInfo, tip: ObjectId, n: ObjectId, opts: RebaseOptions): RbInfo {
    if (headCommitId() != n) {
        val old = headCommitId()
        refs.detach("HEAD", n)
        if (n != tip) refs.logOnly("HEAD", old, n, reflogIdentity(opts.identity), "rebase (pick): ${db.readCommit(n).summary}")
    }
    RebaseTestHooks.at("after-head-move")
    writeTip(n, info.next + 1)                                      // THE commit point
    RebaseTestHooks.at("after-commit-point")
    val next = RbInfo(info.branchRef, info.onto, info.orig, info.todo, n, info.next + 1)
    deleteState(StateFiles.JOURNAL, StateFiles.MERGE_MSG)
    fs.delete(rb("fb-applying")); fs.delete(rb("stopped-sha")); fs.delete(rb("message")); fs.delete(rb("author-script"))
    writeDerived(next, null)
    return next
}

private fun Repository.finalizeRebase(info: RbInfo, opts: RebaseOptions): RebaseOutcome {
    val branch = info.branchRef
    val tip = info.tip
    if (branch != null) {
        val who = reflogIdentity(opts.identity)
        val cur = refs.resolve(branch)
        if (cur != tip) {
            if (cur != info.orig) throw GitException(GitErrorKind.REF_CONFLICT, "branch '${branch.removePrefix("refs/heads/")}' moved during the rebase; not overwriting it")
            refs.update(branch, tip, Expect.Id(info.orig), who, "rebase (finish): $branch onto ${info.onto.hex}")
        }
        val old = headCommitId()
        refs.setSymbolic("HEAD", branch)
        refs.logOnly("HEAD", old, tip, who, "rebase (finish): returning to $branch")
    }
    fs.deleteRecursively("$gitDir/$DIR")
    fs.delete("$gitDir/REBASE_HEAD")
    return RebaseOutcome.Completed(tip, info.todo.size)
}

/**
 * Continues a rebase: after resolving a conflicted step (commits the resolved index with the
 * original author and message), or after the process died (finishes the half-done step first).
 * Refuses, writing nothing, while conflicts remain, and - unless allowMarkers - while a resolved
 * file still carries marker lines.
 */
fun Repository.rebaseContinue(message: String? = null, allowMarkers: Boolean = false, opts: RebaseOptions = RebaseOptions()): RebaseOutcome {
    val info = readRb() ?: throw GitException(GitErrorKind.INVALID_ARGUMENT, "no rebase in progress")
    val stopped = rbRead("stopped-sha")?.trim()?.let { ObjectId.fromHexOrNull(it, algorithm) }
    var cur = info
    if (stopped != null) {
        val ix = readIndex()
        if (ix.hasConflicts) throw GitException(GitErrorKind.INVALID_ARGUMENT, "cannot continue: unresolved conflicts in ${ix.conflictedPaths().joinToString(", ") { PathSafety.display(it) }}", ix.conflictedPaths())
        val journal = Journal.read(this)
        if (journal != null && !journal.ready) throw GitException(GitErrorKind.INVALID_ARGUMENT, "the step was interrupted while writing; abort the rebase and start again")
        if (!allowMarkers) {
            val marked = ArrayList<String>()
            for (e in journal?.entries.orEmpty().filter { it.conflicted }) {
                val ent = ix.get(e.path) ?: continue
                if (FileMode.isGitlink(ent.mode) || FileMode.isSymlink(ent.mode)) continue
                val data = db.readRaw(ent.id)?.data ?: continue
                if (data.size <= 8 * 1024 * 1024 && !RawText.looksBinary(data) && ConflictMarkers.findMarkers(RawText.fromBytes(data)).isNotEmpty()) marked.add(e.path)
            }
            if (marked.isNotEmpty()) throw GitException(GitErrorKind.INVALID_ARGUMENT, "cannot continue: conflict markers remain in ${marked.joinToString(", ") { PathSafety.display(it) }}", marked)
        }
        val tip = info.tip
        val tree = ix.writeTree(db)
        if (tree == db.readCommit(tip).tree) throw GitException(GitErrorKind.INVALID_ARGUMENT, "no changes: nothing to commit for ${stopped.abbrev()} (resolve and stage, or skip it with rebaseSkip)")
        val pick = db.readCommit(stopped)
        val committer = signature(identity(opts.identity), clock())
        val n = db.write(Commit(tree, listOf(tip), pick.author, committer, (message ?: pick.message).trimEnd('\n') + "\n"))
        rbWrite("fb-applying", "${info.next} pick ${n.hex}\n")
        cur = completeStep(info, tip, n, opts)
        return runLoop(cur, opts)
    }
    // crash recovery: look at the in-flight marker
    val marker = rbRead("fb-applying")?.trim()?.split(' ')
    if (marker != null && marker.size >= 2 && marker[0].toIntOrNull() == info.next) {
        val n = marker.getOrNull(2)?.let { ObjectId.fromHexOrNull(it, algorithm) }
        if (n != null) {
            val head = headCommitId()
            if (head == n) { cur = completeStep(info, info.tip, n, opts) }
            else if (head == info.tip && readIndex().let { !it.hasConflicts && it.writeTree(db) == db.readCommit(n).tree }) { cur = completeStep(info, info.tip, n, opts) }
            else { restoreToTip(info); fs.delete(rb("fb-applying")) }
        } else {
            restoreToTip(info); fs.delete(rb("fb-applying"))
        }
    } else if (marker != null) {
        // stale marker from a step that already committed: regenerate and move on
        writeDerived(info, null); deleteState(StateFiles.JOURNAL, StateFiles.MERGE_MSG); fs.delete(rb("fb-applying"))
    }
    cur = readRb() ?: cur
    // the very first move onto the base may not have happened yet
    if (cur.next == 0 && (refs.headBranch() != null || headCommitId() != cur.onto) && headCommitId() == cur.orig) enterDetached(cur)
    else if (headCommitId() != cur.tip) {
        throw GitException(GitErrorKind.INVALID_ARGUMENT, "HEAD is ${headCommitId()?.abbrev()} but the rebase expects ${cur.tip.abbrev()}; refusing to guess (abort the rebase)")
    }
    return runLoop(cur, opts)
}

/** Puts index and work tree back to the rebase tip (the state before the in-flight step began). */
private fun Repository.restoreToTip(info: RbInfo) {
    val journal = Journal.read(this)
    if (journal != null) abortOperation(true, listOf(StateFiles.MERGE_MSG), "rebase step")
    else deleteState(StateFiles.MERGE_MSG)
    if (headCommitId() != info.tip) throw GitException(GitErrorKind.INVALID_ARGUMENT, "HEAD is not at the rebase tip; refusing to guess (abort the rebase)")
}

/** Drops the stopped commit and carries on (`git rebase --skip`); the discard guard is the same as [rebaseAbort]. */
fun Repository.rebaseSkip(discardLocalChanges: Boolean = false, opts: RebaseOptions = RebaseOptions()): RebaseOutcome {
    val info = readRb() ?: throw GitException(GitErrorKind.INVALID_ARGUMENT, "no rebase in progress")
    val stopped = rbRead("stopped-sha")?.trim() ?: throw GitException(GitErrorKind.INVALID_ARGUMENT, "the rebase is not stopped on a commit")
    abortOperation(discardLocalChanges, listOf(StateFiles.MERGE_MSG), "rebase step")
    val next = completeStep(info, info.tip, info.tip, opts)
    return runLoop(next, opts)
}

/**
 * Abandons the rebase: the work tree, index and branch go back to where they were before it began.
 * Refuses (DIRTY_TREE, naming the paths) when that would discard edits made since the stop, unless
 * [discardLocalChanges].
 */
fun Repository.rebaseAbort(discardLocalChanges: Boolean = false): AbortResult {
    val info = readRb() ?: throw GitException(GitErrorKind.INVALID_ARGUMENT, "no rebase in progress")
    val restored = ArrayList<String>()
    if (hasState(StateFiles.JOURNAL) || readIndex().hasConflicts) {
        restored.addAll(abortOperation(discardLocalChanges, listOf(StateFiles.MERGE_MSG), "rebase").restored)
    }
    // back to the original branch and tree
    val branch = info.branchRef
    val origTree = db.readCommit(info.orig).tree
    val head = headCommitId()
    if (head != info.orig) {
        applyMergePlan(db.flattenTree(origTree), emptyList(), "rebase", info.orig) {}
        deleteState(StateFiles.JOURNAL)
    }
    val who = reflogIdentity()
    if (branch != null) {
        refs.setSymbolic("HEAD", branch)
        refs.logOnly("HEAD", head, info.orig, who, "rebase (abort): returning to $branch")
    } else refs.detach("HEAD", info.orig)
    fs.deleteRecursively("$gitDir/$DIR")
    fs.delete("$gitDir/REBASE_HEAD")
    deleteState(StateFiles.MERGE_MSG)
    return AbortResult(restored.distinct().sorted())
}
