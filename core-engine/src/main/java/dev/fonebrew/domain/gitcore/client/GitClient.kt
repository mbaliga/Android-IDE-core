package dev.fonebrew.domain.gitcore.client

import dev.fonebrew.domain.gitcore.Expect
import dev.fonebrew.domain.gitcore.FileKind
import dev.fonebrew.domain.gitcore.FileSystemPort
import dev.fonebrew.domain.gitcore.GitErrorKind
import dev.fonebrew.domain.gitcore.GitException
import dev.fonebrew.domain.gitcore.GitLimits
import dev.fonebrew.domain.gitcore.ObjectId
import dev.fonebrew.domain.gitcore.PackWriter
import dev.fonebrew.domain.gitcore.RefName
import dev.fonebrew.domain.gitcore.Repository
import dev.fonebrew.domain.gitcore.ResetMode
import dev.fonebrew.domain.gitcore.Tag
import dev.fonebrew.domain.gitcore.checkout
import dev.fonebrew.domain.gitcore.currentBranch
import dev.fonebrew.domain.gitcore.headCommitId
import dev.fonebrew.domain.gitcore.isAncestor
import dev.fonebrew.domain.gitcore.mergeBase
import dev.fonebrew.domain.gitcore.objectsToSend
import dev.fonebrew.domain.gitcore.peel
import dev.fonebrew.domain.gitcore.removeRemote
import dev.fonebrew.domain.gitcore.reset
import dev.fonebrew.domain.gitcore.setRemote
import dev.fonebrew.domain.gitcore.setUpstream
import dev.fonebrew.domain.gitcore.shallowCommits
import dev.fonebrew.domain.gitcore.transport.CancelToken
import dev.fonebrew.domain.gitcore.transport.GitTransport
import dev.fonebrew.domain.gitcore.transport.PushCommand
import dev.fonebrew.domain.gitcore.transport.ReceivePackConnection
import dev.fonebrew.domain.gitcore.transport.RemoteUrl
import dev.fonebrew.domain.gitcore.transport.TransportErrorKind
import dev.fonebrew.domain.gitcore.transport.TransportException
import dev.fonebrew.domain.gitcore.transport.UploadPackConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

/** Builds the transport for a parsed address. The data layer supplies it (OkHttp for http(s), sshj for ssh). */
fun interface TransportFactory {
    fun create(url: RemoteUrl): GitTransport
}

data class CloneOptions(
    /** Shallow history of this many commits (implies single branch). */
    val depth: Int? = null,
    /** Branch to check out and track; null = whatever the server's HEAD is. */
    val branch: String? = null,
    val bare: Boolean = false,
    val remoteName: String = "origin",
)

data class CloneResult(val directory: String, val branch: String?, val head: ObjectId?, val shallow: Boolean)

sealed interface CloneEvent {
    data class Progress(val event: TransferEvent) : CloneEvent
    data class Done(val result: CloneResult) : CloneEvent
}

data class FetchOptions(val depth: Int? = null, val allTags: Boolean = true)

data class FetchResult(val outcome: FetchOutcome)

/** How a forced update is (not) allowed. [None] is the default and is never overridden implicitly. */
sealed interface ForceMode {
    object None : ForceMode
    /** Overwrite whatever is there. The explicit, unconditional flag. */
    object Force : ForceMode
    /** Overwrite only if the remote ref still equals [expectedOld] (null = it must not exist). */
    data class WithLease(val expectedOld: ObjectId?) : ForceMode
}

/** One ref to push: [src] local ref/branch/tag name (null deletes [dst]); [dst] full remote ref name. */
data class PushSpec(val src: String?, val dst: String) {
    companion object {
        fun branch(name: String) = PushSpec("refs/heads/$name", "refs/heads/$name")
        fun tag(name: String) = PushSpec("refs/tags/$name", "refs/tags/$name")
        fun delete(fullRef: String) = PushSpec(null, fullRef)
    }
}

data class PushOptions(
    val force: ForceMode = ForceMode.None,
    val atomic: Boolean = false,
    /** Record branch.<b>.remote/merge for pushed branches (like `push -u`). */
    val setUpstream: Boolean = false,
)

sealed interface RefPushOutcome {
    val ref: String
    data class Created(override val ref: String, val new: ObjectId) : RefPushOutcome
    data class Updated(override val ref: String, val old: ObjectId, val new: ObjectId, val forced: Boolean) : RefPushOutcome
    data class Deleted(override val ref: String, val old: ObjectId) : RefPushOutcome
    data class UpToDate(override val ref: String) : RefPushOutcome
    data class Rejected(override val ref: String, val failure: GitFailure) : RefPushOutcome
}

data class PushResult(val outcomes: List<RefPushOutcome>) {
    val rejected: List<RefPushOutcome.Rejected> get() = outcomes.filterIsInstance<RefPushOutcome.Rejected>()
    val allOk: Boolean get() = rejected.isEmpty()
    /** Throws the first rejection as a [GitClientException]; convenience for single-ref pushes. */
    fun requireOk(): PushResult { rejected.firstOrNull()?.let { throw GitClientException(it.failure) }; return this }
}

sealed interface PullOutcome {
    object UpToDate : PullOutcome
    data class FastForwarded(val from: ObjectId?, val to: ObjectId) : PullOutcome
    /** Histories diverged. The part-2 merge engine decides what happens next; nothing was changed locally except the fetch. */
    data class NeedsMerge(val ours: ObjectId, val theirs: ObjectId, val base: ObjectId?) : PullOutcome
}

data class RemoteInfo(val name: String, val url: String)

data class RemoteRef(val name: String, val id: ObjectId, val peeled: ObjectId?)
data class RemoteListing(val head: String?, val refs: List<RemoteRef>, val protocolV2: Boolean)

/**
 * The network facade over the part-1 engine: clone, fetch, pull, push, remotes, ls-remote. Pure
 * domain code; transports and credentials arrive through [transports]. Every failure is a
 * [GitClientException] carrying a [GitFailure]; cancellation is a [CancellationException].
 *
 * Cancelling a clone deletes everything it wrote (it works in a sibling staging directory that is
 * renamed onto the target only when complete), so the target path never holds a repository that
 * looks valid but is incomplete. A cancelled or failed fetch leaves refs untouched (objects it
 * already stored stay, unreferenced).
 */
class GitClient(
    private val fs: FileSystemPort,
    private val transports: TransportFactory,
    private val limits: GitLimits = GitLimits(),
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val tzMinutes: Int = 0,
) {
    // ------------------------------------------------------------------ clone

    fun clone(url: String, directory: String, options: CloneOptions = CloneOptions()): Flow<CloneEvent> = channelFlow {
        val token = CancelToken()
        val watcher = launch(Dispatchers.Default) { try { awaitCancellation() } finally { token.cancel() } }
        try {
            val result = withContext(Dispatchers.IO) {
                runGuarded(token) { doClone(url, directory, options, token) { trySendBlocking(CloneEvent.Progress(it)) } }
            }
            send(CloneEvent.Done(result))
        } finally { watcher.cancel() }
    }

    private fun doClone(url: String, directory: String, o: CloneOptions, cancel: CancelToken, emit: (TransferEvent) -> Unit): CloneResult {
        val remote = RemoteUrl.parse(url)
        RefName.requireValidShort(o.remoteName)
        if (o.depth != null && o.depth <= 0) throw GitException(GitErrorKind.INVALID_ARGUMENT, "depth must be at least 1")
        o.branch?.let { RefName.requireValidShort(it) }
        val target = directory.trimEnd('/')
        val st = fs.stat(target)
        val preexistingEmpty = st != null
        if (st != null && (st.kind != FileKind.DIRECTORY || fs.list(target).isNotEmpty())) {
            throw GitException(GitErrorKind.INVALID_ARGUMENT, "the destination already exists and is not an empty directory")
        }
        val parent = target.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) fs.mkdirs(parent)
        val staging = "$target.fonebrew-clone-${java.lang.Long.toHexString(System.nanoTime())}"
        var repo: Repository? = null
        var ok = false
        try {
            repo = Repository.init(fs, staging, o.bare, "main", limits, clock = clock, tzMinutes = tzMinutes)
            repo.setRemote(o.remoteName, remote.display)
            val singleBranch = o.depth != null || o.branch != null
            val spec = if (o.bare) Refspec("refs/heads/*", "refs/heads/*") else Refspec.defaultFor(o.remoteName)
            emit(TransferEvent.Connecting(remote.display))
            val conn = UploadPackConnection.open(transports.create(remote), cancel)
            val outcome: FetchOutcome
            val branch: String?
            conn.use { c ->
                val heads = c.refs.filter { it.name.startsWith("refs/heads/") && RefName.isValid(it.name) }
                branch = when {
                    o.branch != null -> {
                        if (heads.none { it.name == "refs/heads/${o.branch}" }) throw GitException(GitErrorKind.NOT_FOUND, "branch '${o.branch}' does not exist on the remote")
                        o.branch
                    }
                    else -> pickDefaultBranch(c, heads.map { it.name to it.id })
                }
                val sel = FetchSelection(
                    spec = if (singleBranch && branch != null && !o.bare) Refspec("refs/heads/$branch", "refs/remotes/${o.remoteName}/$branch") else spec,
                    branch = if (singleBranch) branch else null,
                    allTags = !singleBranch,
                    depth = o.depth,
                )
                if (singleBranch && branch != null) {
                    repo.config.set("remote", o.remoteName, "fetch", sel.spec.text())
                }
                outcome = FetchEngine(repo, cancel, emit).run(c, o.remoteName, sel)
            }
            var head: ObjectId? = null
            if (branch != null && (heads(repo, o, branch) != null)) {
                val tip = heads(repo, o, branch)!!
                head = tip
                if (!o.bare) repo.refs.update("refs/heads/$branch", tip, Expect.Absent, repo.reflogIdentity(), "clone: from ${remote.display}")
                repo.refs.setSymbolic("HEAD", "refs/heads/$branch")
                if (!o.bare) repo.setUpstream(branch, o.remoteName, "refs/heads/$branch")
                if (!o.bare) {
                    cancel.check()
                    emit(TransferEvent.CheckingOut(done = false, files = null))
                    repo.reset(ResetMode.HARD, "HEAD", discardLocalChanges = true)
                    emit(TransferEvent.CheckingOut(done = true, files = null))
                }
            } else if (branch != null) {
                repo.refs.setSymbolic("HEAD", "refs/heads/$branch") // empty remote: unborn branch
            }
            repo.saveConfig()
            cancel.check()
            repo.close(); repo = null
            if (preexistingEmpty) fs.delete(target)
            fs.rename(staging, target)
            ok = true
            return CloneResult(target, branch, head, outcome.shallowNow)
        } finally {
            try { repo?.close() } catch (_: Exception) {}
            if (!ok) try { fs.deleteRecursively(staging) } catch (_: Exception) {}
        }
    }

    private fun heads(repo: Repository, o: CloneOptions, branch: String): ObjectId? =
        repo.refs.resolve(if (o.bare) "refs/heads/$branch" else "refs/remotes/${o.remoteName}/$branch")

    private fun pickDefaultBranch(c: UploadPackConnection, heads: List<Pair<String, ObjectId>>): String? {
        if (heads.isEmpty()) return c.headTarget?.removePrefix("refs/heads/") ?: "main"
        c.headTarget?.takeIf { t -> heads.any { it.first == t } }?.let { return it.removePrefix("refs/heads/") }
        val headId = c.refs.firstOrNull { it.name == "HEAD" }?.id
        val byId = headId?.let { id -> heads.filter { it.second == id }.map { it.first } }.orEmpty()
        for (pref in listOf("refs/heads/main", "refs/heads/master")) if (pref in byId || (headId == null && heads.any { it.first == pref })) return pref.removePrefix("refs/heads/")
        return (byId.firstOrNull() ?: heads.first().first).removePrefix("refs/heads/")
    }

    // ------------------------------------------------------------------ fetch / pull

    suspend fun fetch(repoPath: String, remote: String = "origin", options: FetchOptions = FetchOptions(), onEvent: (TransferEvent) -> Unit = {}): FetchResult =
        blocking { token -> FetchResult(doFetch(repoPath, remote, options, token, onEvent)) }

    private fun doFetch(repoPath: String, remote: String, o: FetchOptions, cancel: CancelToken, emit: (TransferEvent) -> Unit): FetchOutcome {
        val repo = Repository.open(fs, repoPath, limits, clock = clock, tzMinutes = tzMinutes)
        repo.use {
            val url = repo.config.remoteUrl(remote) ?: throw GitException(GitErrorKind.NOT_FOUND, "no remote named '$remote'")
            val ru = RemoteUrl.parse(url)
            val spec = repo.config.getAll("remote", remote, "fetch").firstOrNull()?.let { Refspec.parse(it) } ?: Refspec.defaultFor(remote)
            emit(TransferEvent.Connecting(ru.display))
            UploadPackConnection.open(transports.create(ru), cancel).use { c ->
                // Every tag only for a complete, all-branches repository: a shallow or single-branch one follows tags instead.
                val everyTag = o.allTags && spec.wildcard && repo.shallowCommits().isEmpty() && o.depth == null
                return FetchEngine(repo, cancel, emit).run(c, remote, FetchSelection(spec, null, everyTag, o.depth))
            }
        }
    }

    /**
     * Fetches, then fast-forwards the current branch to its upstream. Never creates a merge commit:
     * diverged histories return [PullOutcome.NeedsMerge] (the merge engine's job), and uncommitted
     * work that the move would overwrite throws [GitFailure.LocalChanges] with nothing changed.
     */
    suspend fun pull(repoPath: String, remote: String = "origin", onEvent: (TransferEvent) -> Unit = {}): PullOutcome =
        blocking { token ->
            val branchName: String
            Repository.open(fs, repoPath, limits, clock = clock, tzMinutes = tzMinutes).use { r ->
                branchName = r.currentBranch() ?: throw GitException(GitErrorKind.INVALID_ARGUMENT, "HEAD is detached; check out a branch to pull into")
            }
            val repoRemote = remote
            doFetch(repoPath, repoRemote, FetchOptions(), token, onEvent)
            Repository.open(fs, repoPath, limits, clock = clock, tzMinutes = tzMinutes).use { repo ->
                val upRemote = repo.config.branchRemote(branchName) ?: repoRemote
                val mergeRef = repo.config.branchMerge(branchName) ?: "refs/heads/$branchName"
                val theirs = repo.refs.resolve("refs/remotes/$upRemote/${mergeRef.removePrefix("refs/heads/")}")
                    ?: throw GitException(GitErrorKind.NOT_FOUND, "the remote has no branch '${mergeRef.removePrefix("refs/heads/")}'")
                val ref = "refs/heads/$branchName"
                val ours = repo.refs.resolve(ref)
                when {
                    ours == null -> {
                        // Unborn branch: adopt the remote's commit.
                        repo.refs.update(ref, theirs, Expect.Absent, repo.reflogIdentity(), "pull: initial")
                        repo.refs.setSymbolic("HEAD", ref)
                        repo.reset(ResetMode.HARD, "HEAD", discardLocalChanges = false)
                        PullOutcome.FastForwarded(null, theirs)
                    }
                    ours == theirs || repo.isAncestor(theirs, ours) -> PullOutcome.UpToDate
                    repo.isAncestor(ours, theirs) -> {
                        // Guarded move: checkout refuses (typed) over local work, then the branch ref and HEAD follow.
                        repo.checkout(theirs.hex)
                        repo.refs.update(ref, theirs, Expect.Id(ours), repo.reflogIdentity(), "pull: Fast-forward")
                        repo.refs.setSymbolic("HEAD", ref)
                        PullOutcome.FastForwarded(ours, theirs)
                    }
                    else -> PullOutcome.NeedsMerge(ours, theirs, repo.mergeBase(ours, theirs))
                }
            }
        }

    // ------------------------------------------------------------------ push

    suspend fun push(repoPath: String, remote: String = "origin", specs: List<PushSpec>, options: PushOptions = PushOptions(), onEvent: (TransferEvent) -> Unit = {}): PushResult =
        blocking { token -> doPush(repoPath, remote, specs, options, token, onEvent) }

    private fun doPush(repoPath: String, remote: String, specs: List<PushSpec>, o: PushOptions, cancel: CancelToken, emit: (TransferEvent) -> Unit): PushResult {
        if (specs.isEmpty()) throw GitException(GitErrorKind.INVALID_ARGUMENT, "nothing to push")
        Repository.open(fs, repoPath, limits, clock = clock, tzMinutes = tzMinutes).use { repo ->
            val url = repo.config.remoteUrl(remote) ?: throw GitException(GitErrorKind.NOT_FOUND, "no remote named '$remote'")
            val ru = RemoteUrl.parse(url)
            emit(TransferEvent.Connecting(ru.display))
            ReceivePackConnection.open(transports.create(ru), cancel).use { conn ->
                val advertised = conn.refs.associate { it.name to it.id }
                val rejected = LinkedHashMap<String, RefPushOutcome.Rejected>()
                val noop = LinkedHashMap<String, RefPushOutcome>()
                val planned = ArrayList<Triple<PushSpec, PushCommand, Boolean>>() // spec, command, forced
                for (s in specs) {
                    RefName.requireValid(s.dst)
                    val old = advertised[s.dst]
                    val new: ObjectId? = s.src?.let { src ->
                        repo.refs.resolve(if (src.startsWith("refs/")) src else "refs/heads/$src")
                            ?: throw GitException(GitErrorKind.NOT_FOUND, "'$src' does not exist locally")
                    }
                    if (new == null) {
                        if (old == null) { rejected[s.dst] = RefPushOutcome.Rejected(s.dst, GitFailure.InvalidRequest("the remote has no ${s.dst} to delete")); continue }
                        planned.add(Triple(s, PushCommand(s.dst, old, null), false)); continue
                    }
                    if (old == new) { noop[s.dst] = RefPushOutcome.UpToDate(s.dst); continue }
                    var forced = false
                    if (old != null) {
                        val ff = !s.dst.startsWith("refs/tags/") && repo.db.has(old) && try { repo.isAncestor(old, repo.peel(new)) } catch (_: GitException) { false }
                        if (!ff) {
                            when (val f = o.force) {
                                ForceMode.None -> { rejected[s.dst] = RefPushOutcome.Rejected(s.dst, GitFailure.NonFastForward(s.dst)); continue }
                                ForceMode.Force -> forced = true
                                is ForceMode.WithLease -> {
                                    if (f.expectedOld != old) { rejected[s.dst] = RefPushOutcome.Rejected(s.dst, GitFailure.LeaseStale(s.dst)); continue }
                                    forced = true
                                }
                            }
                        }
                    } else if (o.force is ForceMode.WithLease && (o.force as ForceMode.WithLease).expectedOld != null) {
                        rejected[s.dst] = RefPushOutcome.Rejected(s.dst, GitFailure.LeaseStale(s.dst)); continue
                    }
                    planned.add(Triple(s, PushCommand(s.dst, old, new), forced))
                }
                if (o.atomic && rejected.isNotEmpty()) {
                    // All or nothing: nothing is sent, every ref is reported as not pushed.
                    val all = specs.map { s -> rejected[s.dst] ?: RefPushOutcome.Rejected(s.dst, GitFailure.InvalidRequest("not pushed because the atomic push was refused")) }
                    return PushResult(all)
                }
                val results = LinkedHashMap<String, RefPushOutcome>()
                if (planned.isNotEmpty()) {
                    val commands = planned.map { it.second }
                    val pack: ((OutputStream) -> Unit)? = if (commands.any { !it.isDelete }) {
                        val ids = packObjects(repo, commands.mapNotNull { it.new }, conn.refs.flatMap { listOfNotNull(it.id, it.peeled) })
                        emit(TransferEvent.Packing(ids.size));
                        { out -> var sent = 0L; PackWriter.write(repo.db, ids, CountingOut(out, cancel) { sent = it; emit(TransferEvent.Sending(sent)) }) }
                    } else null
                    val report = conn.push(commands, pack, o.atomic) { emit(TransferEvent.RemoteMessage(it)) }
                    for ((s, cmd, forced) in planned) {
                        val reason = if (report.unpackError != null) "the server could not unpack the data: ${report.unpackError}" else if (cmd.ref in report.results) report.results[cmd.ref] else "no status reported"
                        results[s.dst] = if (reason != null) {
                            val nff = reason.contains("non-fast-forward", true) || reason.contains("fetch first", true) || reason.contains("stale info", true)
                            RefPushOutcome.Rejected(s.dst, if (nff) GitFailure.NonFastForward(s.dst, reason) else GitFailure.Rejected(s.dst, reason))
                        } else when {
                            cmd.isDelete -> RefPushOutcome.Deleted(s.dst, cmd.old!!)
                            cmd.old == null -> RefPushOutcome.Created(s.dst, cmd.new!!)
                            else -> RefPushOutcome.Updated(s.dst, cmd.old, cmd.new!!, forced)
                        }
                    }
                }
                // Remote-tracking bookkeeping for what the server accepted.
                val ident = repo.reflogIdentity()
                for ((_, out) in results) {
                    val ref = out.ref
                    if (!ref.startsWith("refs/heads/")) continue
                    val tracking = "refs/remotes/$remote/${ref.removePrefix("refs/heads/")}"
                    when (out) {
                        is RefPushOutcome.Created -> repo.refs.update(tracking, out.new, Expect.Any, ident, "push: $remote")
                        is RefPushOutcome.Updated -> repo.refs.update(tracking, out.new, Expect.Any, ident, "push: $remote")
                        is RefPushOutcome.Deleted -> if (repo.refs.read(tracking) != null) repo.refs.delete(tracking)
                        else -> {}
                    }
                    if (o.setUpstream && (out is RefPushOutcome.Created || out is RefPushOutcome.Updated)) {
                        val b = ref.removePrefix("refs/heads/")
                        if (repo.refs.read("refs/heads/$b") != null) repo.setUpstream(b, remote, ref)
                    }
                }
                // Preserve the caller's order.
                val ordered = specs.map { s -> results[s.dst] ?: rejected[s.dst] ?: noop[s.dst] ?: RefPushOutcome.UpToDate(s.dst) }
                return PushResult(ordered)
            }
        }
    }

    /** Objects the remote lacks: new tips' closure minus what the remote's advertised tips (that we hold) already reach. */
    private fun packObjects(repo: Repository, tips: List<ObjectId>, remoteTips: List<ObjectId>): List<ObjectId> {
        val have = remoteTips.filter { id -> repo.db.has(id) && (try { repo.db.readCommit(repo.peel(id)); true } catch (_: GitException) { false }) }
        val out = LinkedHashSet<ObjectId>()
        val commitTips = ArrayList<ObjectId>()
        for (t in tips) {
            var cur = t
            var hops = 0
            while (true) {
                val o = repo.db.require(cur)
                if (o is Tag) { if (hops++ > 16) throw GitException(GitErrorKind.MALFORMED, "tag chain too deep"); out.add(cur); cur = o.target } else break
            }
            commitTips.add(cur)
        }
        out.addAll(repo.objectsToSend(commitTips, have))
        return out.toList()
    }

    private class CountingOut(private val inner: OutputStream, private val cancel: CancelToken, private val report: (Long) -> Unit) : OutputStream() {
        private var n = 0L
        private var last = 0L
        override fun write(b: Int) { inner.write(b); tick(1) }
        override fun write(b: ByteArray, off: Int, len: Int) { cancel.check(); inner.write(b, off, len); tick(len) }
        override fun flush() = inner.flush()
        private fun tick(k: Int) { n += k; if (n - last >= 256 * 1024) { last = n; report(n) } }
        override fun close() { report(n); inner.flush() }
    }

    // ------------------------------------------------------------------ remotes

    fun remotes(repoPath: String): List<RemoteInfo> =
        guarded { Repository.open(fs, repoPath, limits).use { r -> r.config.remoteNames().map { RemoteInfo(it, r.config.remoteUrl(it) ?: "") } } }

    fun addRemote(repoPath: String, name: String, url: String) = guarded {
        Repository.open(fs, repoPath, limits).use { r ->
            RefName.requireValidShort(name)
            if (r.config.remoteUrl(name) != null) throw GitException(GitErrorKind.INVALID_ARGUMENT, "remote '$name' already exists")
            r.setRemote(name, RemoteUrl.parse(url).display)
        }
    }

    fun removeRemote(repoPath: String, name: String) = guarded {
        Repository.open(fs, repoPath, limits).use { r ->
            if (r.config.remoteUrl(name) == null) throw GitException(GitErrorKind.NOT_FOUND, "no remote named '$name'")
            r.removeRemote(name)
            for (ref in r.refs.list("refs/remotes/$name/").keys) r.refs.delete(ref)
        }
    }

    // ------------------------------------------------------------------ ls-remote

    suspend fun lsRemote(url: String, allRefs: Boolean = false): RemoteListing = blocking { token ->
        val ru = RemoteUrl.parse(url)
        UploadPackConnection.open(transports.create(ru), token, if (allRefs) null else UploadPackConnection.DEFAULT_PREFIXES).use { c ->
            RemoteListing(c.headTarget, c.refs.map { RemoteRef(it.name, it.id, it.peeled) }, c.isV2)
        }
    }

    // ------------------------------------------------------------------ plumbing

    private suspend fun <T> blocking(body: (CancelToken) -> T): T {
        val token = CancelToken()
        return kotlinx.coroutines.coroutineScope {
            val watcher = launch(Dispatchers.Default) { try { awaitCancellation() } finally { token.cancel() } }
            try { withContext(Dispatchers.IO) { runGuarded(token) { body(token) } } } finally { watcher.cancel() }
        }
    }

    private inline fun <T> runGuarded(token: CancelToken, body: () -> T): T = try {
        body()
    } catch (e: Throwable) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        if (token.cancelled) throw CancellationException("cancelled")
        if (e is GitClientException) throw e
        if (e is VirtualMachineError && e !is StackOverflowError) throw e
        throw GitClientException(e.toGitFailure(), e)
    }

    private inline fun <T> guarded(body: () -> T): T = runGuarded(CancelToken.NONE, body)
}
