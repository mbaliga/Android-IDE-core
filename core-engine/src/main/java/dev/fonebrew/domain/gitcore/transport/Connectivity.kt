package dev.fonebrew.domain.gitcore.transport

import dev.fonebrew.domain.gitcore.Commit
import dev.fonebrew.domain.gitcore.FileMode
import dev.fonebrew.domain.gitcore.GitErrorKind
import dev.fonebrew.domain.gitcore.GitException
import dev.fonebrew.domain.gitcore.ObjectDatabase
import dev.fonebrew.domain.gitcore.ObjectId
import dev.fonebrew.domain.gitcore.Tag
import dev.fonebrew.domain.gitcore.Tree

/**
 * The gate between "a pack was received" and "refs may move": proves that everything reachable from
 * the wanted tips is present and parses, so a hostile or truncated fetch can never leave a ref
 * pointing at an incomplete history. Failure is INTEGRITY (never silently ignored).
 *
 * [knownGood] are commits whose closure we already trust (the `have` set: reachable from refs that
 * only ever moved after this same check); the walk stops there. [shallow] commits are treated as
 * root commits. Memory: ids of visited commits and trees are kept (blobs are only existence-checked,
 * not remembered), so a very large first clone holds roughly one id per commit and per tree.
 */
object Connectivity {
    private const val MAX_BOUNDARY_TREES = 16

    fun verify(db: ObjectDatabase, tips: Collection<ObjectId>, knownGood: Set<ObjectId>, shallow: Set<ObjectId>, cancel: CancelToken = CancelToken.NONE) {
        val commits = HashSet<ObjectId>()
        val trees = HashSet<ObjectId>()
        val boundary = LinkedHashSet<ObjectId>()
        val commitList = ArrayList<Commit>()
        val stack = ArrayDeque<ObjectId>()

        fun missing(id: ObjectId, what: String): Nothing =
            throw TransportException(TransportErrorKind.INTEGRITY, "the server's data is incomplete: $what ${id.abbrev()} is missing")

        fun read(id: ObjectId, what: String) = try {
            db.read(id) ?: missing(id, what)
        } catch (e: GitException) {
            if (e.kind == GitErrorKind.NOT_FOUND) missing(id, what)
            throw TransportException(TransportErrorKind.INTEGRITY, "the server's data is damaged: $what ${id.abbrev()} (${e.kind.name.lowercase()})", cause = e)
        }

        for (t in tips) {
            var cur = t
            var hops = 0
            while (true) {
                if (hops++ > 16) throw TransportException(TransportErrorKind.INTEGRITY, "tag chain too deep")
                when (val o = read(cur, "object")) {
                    is Tag -> cur = o.target
                    is Commit -> { stack.add(cur); break }
                    is Tree -> { verifyTree(db, cur, trees, emptySet(), cancel); break }
                    else -> break // a blob tag target: read() already proved it exists
                }
            }
        }
        while (stack.isNotEmpty()) {
            cancel.check()
            val id = stack.removeLast()
            if (id in knownGood) { if (boundary.size < MAX_BOUNDARY_TREES) boundary.add(id); continue }
            if (!commits.add(id)) continue
            val c = read(id, "commit") as? Commit ?: throw TransportException(TransportErrorKind.INTEGRITY, "object ${id.abbrev()} is not a commit")
            commitList.add(c)
            if (id !in shallow) c.parents.forEach(stack::add)
        }
        // Trees already trusted: the trees of the boundary commits (the natural bases of the new work).
        val trusted = HashSet<ObjectId>()
        for (b in boundary) {
            val c = try { db.readCommit(b) } catch (_: GitException) { continue }
            markTrusted(db, c.tree, trusted, cancel)
        }
        for (c in commitList) verifyTree(db, c.tree, trees, trusted, cancel)
    }

    private fun markTrusted(db: ObjectDatabase, root: ObjectId, into: MutableSet<ObjectId>, cancel: CancelToken) {
        val stack = ArrayDeque<ObjectId>(); stack.add(root)
        while (stack.isNotEmpty()) {
            cancel.check()
            val id = stack.removeLast()
            if (!into.add(id)) continue
            val t = try { db.readTree(id) } catch (_: GitException) { continue }
            for (e in t.entries) if (e.isTree) stack.add(e.id)
        }
    }

    private fun verifyTree(db: ObjectDatabase, root: ObjectId, seen: MutableSet<ObjectId>, trusted: Set<ObjectId>, cancel: CancelToken) {
        val stack = ArrayDeque<ObjectId>(); stack.add(root)
        while (stack.isNotEmpty()) {
            cancel.check()
            val id = stack.removeLast()
            if (id in trusted || !seen.add(id)) continue
            val t = try {
                db.read(id) as? Tree ?: throw TransportException(TransportErrorKind.INTEGRITY, "object ${id.abbrev()} is not a tree")
            } catch (e: GitException) {
                val what = if (e.kind == GitErrorKind.NOT_FOUND) "is missing" else "is damaged (${e.kind.name.lowercase()})"
                throw TransportException(TransportErrorKind.INTEGRITY, "the server's data is incomplete: tree ${id.abbrev()} $what", cause = e)
            }
            for (e in t.entries) when {
                e.isTree -> stack.add(e.id)
                FileMode.isGitlink(e.mode) -> {}
                else -> if (!db.has(e.id)) throw TransportException(TransportErrorKind.INTEGRITY, "the server's data is incomplete: file object ${e.id.abbrev()} is missing")
            }
        }
    }
}
