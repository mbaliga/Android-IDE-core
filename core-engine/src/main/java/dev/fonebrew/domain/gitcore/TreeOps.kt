package dev.fonebrew.domain.gitcore

/**
 * Flattens a tree to `path -> (mode, id)` with every path vetted by [PathSafety] and the tree
 * depth / total-entry ceilings enforced. A hostile tree (`..`, `.git`, backslash, duplicate
 * names, a file shadowing a directory, non-UTF-8 names) fails with a typed error before anything
 * is acted on. [treeId] null means the empty tree.
 */
fun ObjectDatabase.flattenTree(treeId: ObjectId?): Map<String, FlatEntry> {
    val out = LinkedHashMap<String, FlatEntry>()
    if (treeId == null) return out
    fun walk(id: ObjectId, prefix: String, depth: Int) {
        if (depth > limits.maxTreeDepth) limit("tree nested deeper than ${limits.maxTreeDepth}")
        val tree = readTree(id)
        val seen = HashSet<String>()
        for (e in tree.entries) {
            val nb = e.nameBytes
            val name = String(nb, Charsets.UTF_8)
            if (!name.toByteArray(Charsets.UTF_8).contentEquals(nb)) throw GitException(GitErrorKind.UNSUPPORTED, "tree entry name is not valid UTF-8")
            if (name.indexOf('/') >= 0) throw GitException(GitErrorKind.UNSAFE_PATH, "tree entry name contains '/'", listOf(PathSafety.display(name)))
            if (!seen.add(name)) malformed("duplicate tree entry '${PathSafety.display(name)}'")
            val full = prefix + name
            PathSafety.check(full, limits)
            if (e.isTree) walk(e.id, "$full/", depth + 1)
            else {
                if (!FileMode.isFile(e.mode) && !FileMode.isSymlink(e.mode) && !FileMode.isGitlink(e.mode)) {
                    malformed("tree entry '${PathSafety.display(full)}' has unsupported mode ${java.lang.Integer.toOctalString(e.mode)}")
                }
                out[full] = FlatEntry(canonMode(e.mode), e.id)
                if (out.size > limits.maxIndexEntries) limit("tree has more than ${limits.maxIndexEntries} files")
            }
        }
    }
    walk(treeId, "", 0)
    return out
}

/** Collapses legacy modes (`100664`, `100600`) to what git would write; keeps the exec bit. */
internal fun canonMode(mode: Int): Int = when {
    FileMode.isSymlink(mode) -> FileMode.SYMLINK
    FileMode.isGitlink(mode) -> FileMode.GITLINK
    FileMode.isFile(mode) -> if (mode and 0b001_000_000 != 0) FileMode.EXECUTABLE else FileMode.REGULAR
    else -> mode
}

/** The tree id of the commit HEAD points at, or null on an unborn branch. */
fun Repository.headCommitId(): ObjectId? = refs.resolve("HEAD")

fun Repository.headTreeId(): ObjectId? = headCommitId()?.let { db.readCommit(it).tree }

/** Tree id for a commit-ish id (commit -> its tree; tree -> itself). */
fun Repository.treeOf(id: ObjectId): ObjectId = when (val o = db.require(id)) {
    is Commit -> o.tree
    is Tree -> id
    is Tag -> treeOf(peel(id))
    else -> throw GitException(GitErrorKind.INVALID_ARGUMENT, "${id.abbrev()} is a blob, not a tree-ish")
}

/** Follows annotated tags to the first non-tag object. */
fun Repository.peel(id: ObjectId): ObjectId {
    var cur = id
    repeat(16) {
        val o = db.require(cur)
        if (o is Tag) cur = o.target else return cur
    }
    malformed("tag chain too deep")
}
