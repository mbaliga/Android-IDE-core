package dev.fonebrew.domain.gitcore

data class CommitOptions(
    val message: String,
    val author: Identity? = null,
    val committer: Identity? = null,
    val amend: Boolean = false,
    val allowEmpty: Boolean = false,
)

/**
 * Commits the index. Tree from the index; parents = HEAD (plus MERGE_HEAD when a merge is in
 * progress; it is cleared after). Identity comes from the arguments or user.name/user.email; if
 * neither exists the call fails (INVALID_ARGUMENT) rather than inventing one. The branch ref moves
 * by compare-and-swap, so a concurrent writer makes this fail with REF_CONFLICT, never overwrite.
 */
fun Repository.commit(opts: CommitOptions): ObjectId {
    val ix = readIndex()
    if (ix.hasConflicts) throw GitException(GitErrorKind.INVALID_ARGUMENT, "cannot commit: unresolved conflicts", ix.conflictedPaths())
    if (opts.message.isBlank()) throw GitException(GitErrorKind.INVALID_ARGUMENT, "empty commit message")
    val tree = ix.writeTree(db)
    val headId = headCommitId()
    val headCommit = headId?.let { db.readCommit(it) }
    if (opts.amend && headCommit == null) throw GitException(GitErrorKind.INVALID_ARGUMENT, "nothing to amend")
    val mergeHeadPath = "$gitDir/MERGE_HEAD"
    val mergeHead = if (fs.stat(mergeHeadPath)?.kind == FileKind.FILE)
        ObjectId.fromHexOrNull(String(fs.readBytes(mergeHeadPath, 1024), Charsets.US_ASCII).trim().take(algorithm.hexLength), algorithm) else null
    val parents = if (opts.amend) headCommit!!.parents else listOfNotNull(headId, mergeHead)
    val emptyAgainst = if (opts.amend) headCommit!!.parents.firstOrNull()?.let { db.readCommit(it).tree } else headCommit?.tree
    if (!opts.allowEmpty && mergeHead == null && tree == (emptyAgainst ?: db.write(Tree(emptyList())))) {
        throw GitException(GitErrorKind.INVALID_ARGUMENT, "nothing to commit")
    }
    val now = clock()
    val author = if (opts.amend && opts.author == null) headCommit!!.author else signature(identity(opts.author), now)
    val committer = signature(identity(opts.committer ?: opts.author), now)
    val message = opts.message.trimEnd('\n') + "\n"
    val extra = if (opts.amend) headCommit!!.extraHeaders.filter { it.first == "encoding" } else emptyList()
    val commit = Commit(tree, parents, author, committer, message, extra)
    val id = db.write(commit)

    val label = when {
        opts.amend -> "commit (amend)"
        headId == null -> "commit (initial)"
        mergeHead != null -> "commit (merge)"
        else -> "commit"
    }
    val msg = "$label: ${commit.summary}"
    val branch = refs.headBranch()
    val expect = if (headId == null) Expect.Absent else Expect.Id(headId)
    val who = reflogIdentity(opts.committer ?: opts.author)
    if (branch != null) {
        refs.update(branch, id, if (headId == null) Expect.Absent else expect, who, msg)
        refs.logOnly("HEAD", headId, id, who, msg)
    } else {
        refs.detach("HEAD", id)
        refs.logOnly("HEAD", headId, id, who, msg)
    }
    if (mergeHead != null) fs.delete(mergeHeadPath)
    return id
}

fun Repository.currentBranch(): String? = refs.headBranch()?.removePrefix("refs/heads/")

fun Repository.branches(): Map<String, ObjectId> = refs.list("refs/heads/").mapKeys { it.key.removePrefix("refs/heads/") }

fun Repository.createBranch(name: String, startPoint: String = "HEAD", force: Boolean = false): ObjectId {
    RefName.requireValidShort(name)
    val id = peel(resolve(startPoint)).also { if (db.require(it) !is Commit) throw GitException(GitErrorKind.INVALID_ARGUMENT, "branch start point is not a commit") }
    refs.update("refs/heads/$name", id, if (force) Expect.Any else Expect.Absent, reflogIdentity(), "branch: Created from $startPoint")
    return id
}

/** Deletes a branch. The current branch is never deletable; an unmerged one needs [force]. */
fun Repository.deleteBranch(name: String, force: Boolean = false) {
    RefName.requireValidShort(name)
    val ref = "refs/heads/$name"
    if (refs.headBranch() == ref) throw GitException(GitErrorKind.INVALID_ARGUMENT, "cannot delete the branch you are on")
    val id = refs.resolve(ref) ?: throw GitException(GitErrorKind.NOT_FOUND, "branch '$name' not found")
    if (!force) {
        val head = headCommitId()
        if (head == null || !isAncestor(id, head)) throw GitException(GitErrorKind.DIRTY_TREE, "branch '$name' is not fully merged (use force)", listOf(name))
    }
    refs.delete(ref, Expect.Id(id))
    config.removeSection("branch", name)
    saveConfig()
}

fun Repository.renameBranch(old: String, new: String, force: Boolean = false) {
    RefName.requireValidShort(old); RefName.requireValidShort(new)
    val oldRef = "refs/heads/$old"; val newRef = "refs/heads/$new"
    val id = refs.resolve(oldRef) ?: throw GitException(GitErrorKind.NOT_FOUND, "branch '$old' not found")
    if (old == new) return
    val who = reflogIdentity()
    refs.update(newRef, id, if (force) Expect.Any else Expect.Absent, who, "branch: renamed $oldRef to $newRef")
    val wasCurrent = refs.headBranch() == oldRef
    if (wasCurrent) refs.setSymbolic("HEAD", newRef)
    refs.delete(oldRef, Expect.Id(id))
    for (k in listOf("remote", "merge")) config.get("branch", old, k)?.let { config.set("branch", new, k, it) }
    config.removeSection("branch", old)
    saveConfig()
}

fun Repository.tags(): Map<String, ObjectId> = refs.list("refs/tags/").mapKeys { it.key.removePrefix("refs/tags/") }

/**
 * Creates a tag. With [message] it is an annotated tag object (needs an identity); without, a
 * lightweight ref. Existing tags are never replaced unless [force].
 */
fun Repository.createTag(name: String, target: String = "HEAD", message: String? = null, tagger: Identity? = null, force: Boolean = false): ObjectId {
    RefName.requireValidShort(name)
    val targetId = resolve(target)
    val obj = db.require(targetId)
    val refValue = if (message != null) {
        val sig = signature(identity(tagger))
        val type = when (obj) { is Commit -> ObjectType.COMMIT; is Tree -> ObjectType.TREE; is Blob -> ObjectType.BLOB; is Tag -> ObjectType.TAG }
        db.write(Tag(targetId, type, name, sig, message.trimEnd('\n') + "\n"))
    } else targetId
    refs.update("refs/tags/$name", refValue, if (force) Expect.Any else Expect.Absent)
    return refValue
}

fun Repository.deleteTag(name: String) {
    RefName.requireValidShort(name)
    val ref = "refs/tags/$name"
    val id = refs.resolve(ref) ?: throw GitException(GitErrorKind.NOT_FOUND, "tag '$name' not found")
    refs.delete(ref, Expect.Id(id))
}

/** Adds or updates `remote.<name>.url` (and the default fetch refspec when the remote is new). */
fun Repository.setRemote(name: String, url: String) {
    RefName.requireValidShort(name)
    if (url.isBlank() || url.any { it == '\n' || it == '\u0000' }) throw GitException(GitErrorKind.INVALID_ARGUMENT, "bad remote url")
    val isNew = config.remoteUrl(name) == null
    config.set("remote", name, "url", url)
    if (isNew) config.set("remote", name, "fetch", "+refs/heads/*:refs/remotes/$name/*")
    saveConfig()
}

fun Repository.removeRemote(name: String) {
    config.removeSection("remote", name)
    saveConfig()
}

/** Records `branch.<name>.remote` / `.merge` so later pull/push know the upstream. */
fun Repository.setUpstream(branch: String, remote: String, mergeRef: String) {
    RefName.requireValidShort(branch); RefName.requireValidShort(remote); RefName.requireValid(mergeRef)
    config.set("branch", branch, "remote", remote)
    config.set("branch", branch, "merge", mergeRef)
    saveConfig()
}
