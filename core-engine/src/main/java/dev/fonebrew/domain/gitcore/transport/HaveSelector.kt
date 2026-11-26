package dev.fonebrew.domain.gitcore.transport

import dev.fonebrew.domain.gitcore.GitErrorKind
import dev.fonebrew.domain.gitcore.GitException
import dev.fonebrew.domain.gitcore.ObjectId
import dev.fonebrew.domain.gitcore.Repository
import dev.fonebrew.domain.gitcore.peel
import java.util.PriorityQueue

/**
 * The bounded set of `have` lines for one negotiation round: every local ref tip first (capped),
 * then the most recent commits reachable from them (newest committer time first), up to [limit]
 * ids in total. One round with `done`: no multi-ack. Missing objects (a shallow boundary, a
 * damaged store) are skipped, never fatal.
 */
object HaveSelector {
    const val DEFAULT_LIMIT = 256
    private const val MAX_TIPS = 128

    fun select(repo: Repository, limit: Int = DEFAULT_LIMIT): List<ObjectId> {
        val out = LinkedHashSet<ObjectId>()
        val pq = PriorityQueue<Pair<Long, ObjectId>>(compareByDescending { it.first })
        val seen = HashSet<ObjectId>()
        fun commitOrNull(id: ObjectId) = try { repo.db.readCommit(id) } catch (e: GitException) {
            if (e.kind == GitErrorKind.NOT_FOUND || e.kind == GitErrorKind.MALFORMED || e.kind == GitErrorKind.CHECKSUM) null else throw e
        }
        fun push(id: ObjectId) {
            if (!seen.add(id)) return
            val c = commitOrNull(id) ?: return
            pq.add(c.committer.epochSeconds to id)
        }
        val tips = repo.refs.list("refs/").values.toList()
        var tipCount = 0
        for (t in tips) {
            if (tipCount >= MAX_TIPS || out.size >= limit) break
            val id = try { repo.peel(t) } catch (_: GitException) { continue }
            if (commitOrNull(id) == null) continue
            if (seen.add(id)) { out.add(id); tipCount++; commitOrNull(id)?.parents?.forEach(::push) }
        }
        while (pq.isNotEmpty() && out.size < limit) {
            val (_, id) = pq.poll()
            out.add(id)
            commitOrNull(id)?.parents?.forEach(::push)
        }
        return out.toList()
    }
}
