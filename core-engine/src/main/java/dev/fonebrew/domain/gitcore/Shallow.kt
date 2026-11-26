package dev.fonebrew.domain.gitcore

/**
 * `.git/shallow`: the commits whose parents were deliberately not downloaded (a depth-limited
 * clone). One lower-case hex id per line, sorted. History walks treat these commits as roots
 * ([Repository.parentsOf]) instead of failing on the absent parents.
 */
fun Repository.shallowCommits(): Set<ObjectId> {
    val path = "$gitDir/shallow"
    if (fs.stat(path) == null) return emptySet()
    val out = LinkedHashSet<ObjectId>()
    for (line in String(fs.readBytes(path, 64L * 1024 * 1024), Charsets.US_ASCII).lineSequence()) {
        val t = line.trim()
        if (t.isEmpty()) continue
        out.add(ObjectId.fromHexOrNull(t, algorithm) ?: throw GitException(GitErrorKind.MALFORMED, "malformed .git/shallow"))
    }
    return out
}

/** Replaces the shallow set atomically; an empty set removes the file (the repository is complete). */
fun Repository.writeShallow(ids: Set<ObjectId>) {
    val path = "$gitDir/shallow"
    if (ids.isEmpty()) { fs.delete(path); return }
    fs.writeAtomic(path, ids.map { it.hex }.sorted().joinToString("\n", postfix = "\n").toByteArray(Charsets.US_ASCII))
}

/** [commit]'s parents as history walks must see them: none when [id] is a shallow boundary. */
internal fun parentsOf(id: ObjectId, commit: Commit, shallow: Set<ObjectId>): List<ObjectId> =
    if (id in shallow) emptyList() else commit.parents
