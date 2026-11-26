package dev.fonebrew.domain.gitcore.client

import dev.fonebrew.domain.gitcore.Expect
import dev.fonebrew.domain.gitcore.GitErrorKind
import dev.fonebrew.domain.gitcore.GitException
import dev.fonebrew.domain.gitcore.ObjectId
import dev.fonebrew.domain.gitcore.RefName
import dev.fonebrew.domain.gitcore.Repository
import dev.fonebrew.domain.gitcore.peel
import dev.fonebrew.domain.gitcore.shallowCommits
import dev.fonebrew.domain.gitcore.transport.CancelToken
import dev.fonebrew.domain.gitcore.transport.Connectivity
import dev.fonebrew.domain.gitcore.transport.FetchRequest
import dev.fonebrew.domain.gitcore.transport.HaveSelector
import dev.fonebrew.domain.gitcore.transport.TransportErrorKind
import dev.fonebrew.domain.gitcore.transport.TransportException
import dev.fonebrew.domain.gitcore.transport.UploadPackConnection
import dev.fonebrew.domain.gitcore.writeShallow
import java.io.InputStream

/** One ref the fetch created or moved. */
data class RefUpdate(val ref: String, val old: ObjectId?, val new: ObjectId)

class FetchOutcome(
    val updates: List<RefUpdate>,
    /** Tags the server has that differ from our same-named local tag; we never overwrite a tag. */
    val tagsNotUpdated: List<String>,
    val bytesReceived: Long,
    val shallowNow: Boolean,
) {
    val upToDate: Boolean get() = updates.isEmpty()
}

/** Which refs a fetch selects. */
class FetchSelection(
    val spec: Refspec,
    /** Only this branch (single-branch semantics); null = every branch the spec matches. */
    val branch: String?,
    /** Fetch every advertised tag; false = only tags that point at what was fetched (auto-follow). */
    val allTags: Boolean,
    val depth: Int?,
)

/**
 * The shared fetch pipeline for clone / fetch / pull. The ORDER is the safety property:
 * receive and index the pack (refused on bad checksum, truncation or a missing base) -> verify
 * connectivity from every tip -> write the shallow marker -> only then move refs, each with
 * compare-and-swap. A failure anywhere before the last step leaves every ref exactly as it was.
 */
internal class FetchEngine(
    private val repo: Repository,
    private val cancel: CancelToken,
    private val emit: (TransferEvent) -> Unit,
) {
    fun run(conn: UploadPackConnection, remote: String, sel: FetchSelection): FetchOutcome {
        val planned = LinkedHashMap<String, ObjectId>()     // local ref -> id (branches)
        val tagsAll = LinkedHashMap<String, ObjectId>()     // refs/tags/x -> id
        val tagCandidates = LinkedHashMap<String, Pair<ObjectId, ObjectId>>() // auto-follow: ref -> (id, peeled-or-id)
        for (r in conn.refs) {
            if (r.name == "HEAD") continue
            if (!RefName.isValid(r.name)) continue // a hostile name is skipped, never written
            when {
                r.name.startsWith("refs/heads/") -> {
                    if (sel.branch != null && r.name != "refs/heads/${sel.branch}") continue
                    val dst = sel.spec.map(r.name) ?: continue
                    if (!RefName.isValid(dst)) continue
                    planned[dst] = r.id
                }
                r.name.startsWith("refs/tags/") -> if (sel.allTags) tagsAll[r.name] = r.id else tagCandidates[r.name] = r.id to (r.peeled ?: r.id)
            }
        }
        val local = repo.refs.list("refs/").values.toHashSet()
        val shallowBefore = repo.shallowCommits()
        val wants = (planned.values + tagsAll.values).filter { it !in local }.distinct()

        var bytes = 0L
        var haveSet = emptySet<ObjectId>()
        var shallowAfter = shallowBefore
        if (wants.isNotEmpty() || sel.depth != null) {
            val haves = HaveSelector.select(repo)
            haveSet = haves.toHashSet()
            val toWant = if (wants.isEmpty()) planned.values.distinct() else wants
            val resp = conn.fetch(FetchRequest(toWant, haves, sel.depth, shallowBefore.toList())) { handleServerText(it) }
            val counting = CountingStream(resp.pack, cancel, { b, total -> bytes = b; emit(TransferEvent.Receiving(b, total)) }, { total -> emit(TransferEvent.ResolvingDeltas(total)) })
            try {
                repo.db.indexPack(counting)
            } catch (e: GitException) {
                // A pack the indexer refuses (bad trailer, bad base, malformed entry) is the SERVER's fault here.
                if (e.kind == GitErrorKind.IO || e.kind == GitErrorKind.LOCKED) throw e
                throw TransportException(TransportErrorKind.INTEGRITY, "the received pack was refused: ${e.message}", cause = e)
            }
            if (sel.depth != null) shallowAfter = (shallowBefore + resp.shallow) - resp.unshallow.toSet()
        }

        emit(TransferEvent.Verifying)
        // Auto-followed tags: keep those whose target we now hold AND that are connected.
        val tagsToCreate = LinkedHashMap<String, ObjectId>(tagsAll)
        val verifyTips = LinkedHashSet<ObjectId>().also { it.addAll(planned.values); it.addAll(tagsAll.values) }
        Connectivity.verify(repo.db, verifyTips, haveSet + local.filter { repo.db.has(it) }, shallowAfter, cancel)
        for ((name, pair) in tagCandidates) {
            val (id, target) = pair
            if (!repo.db.has(id) || !repo.db.has(target)) continue
            try { Connectivity.verify(repo.db, listOf(id), haveSet + local, shallowAfter, cancel) } catch (e: TransportException) {
                if (e.kind == TransportErrorKind.INTEGRITY) continue else throw e
            }
            tagsToCreate[name] = id
        }
        if (shallowAfter != shallowBefore) repo.writeShallow(shallowAfter)

        // The only place refs move.
        val updates = ArrayList<RefUpdate>()
        val ident = repo.reflogIdentity()
        for ((dst, id) in planned) {
            cancel.check()
            val cur = repo.refs.resolve(dst)
            if (cur == id) continue
            repo.refs.update(dst, id, if (cur == null) Expect.Absent else Expect.Id(cur), ident, "fetch $remote: ${if (cur == null) "storing head" else "fast-forward or forced update"}")
            updates.add(RefUpdate(dst, cur, id))
        }
        val tagConflicts = ArrayList<String>()
        for ((name, id) in tagsToCreate) {
            val cur = repo.refs.resolve(name)
            when {
                cur == id -> {}
                cur == null -> { repo.refs.update(name, id, Expect.Absent, ident, "fetch $remote: storing tag"); updates.add(RefUpdate(name, null, id)) }
                else -> tagConflicts.add(name)
            }
        }
        return FetchOutcome(updates, tagConflicts, bytes, shallowAfter.isNotEmpty())
    }

    private val stageRe = Regex("^(Enumerating|Counting|Compressing) objects:\\s*(?:(\\d+)%)?")

    private fun handleServerText(line: String) {
        val m = stageRe.find(line)
        if (m != null) emit(TransferEvent.Counting(m.groupValues[1].lowercase(), m.groupValues[2].toIntOrNull(), line.take(200)))
        else emit(TransferEvent.RemoteMessage(line.replace(Regex("\\p{Cntrl}"), "?").take(200)))
    }

    /** Counts pack bytes, reads the declared object count from the 12-byte header, throttles events. */
    private class CountingStream(
        private val inner: InputStream,
        private val cancel: CancelToken,
        private val onBytes: (Long, Long?) -> Unit,
        private val onEof: (Long?) -> Unit,
    ) : InputStream() {
        private var count = 0L
        private var lastReported = 0L
        private val head = ByteArray(12)
        private var total: Long? = null
        private var eof = false

        override fun read(): Int { val b = ByteArray(1); val n = read(b, 0, 1); return if (n < 0) -1 else b[0].toInt() and 0xff }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            cancel.check()
            val n = inner.read(b, off, len)
            if (n < 0) {
                if (!eof) { eof = true; onBytes(count, total); onEof(total) }
                return -1
            }
            if (count < 12) {
                val take = minOf(n.toLong(), 12 - count).toInt()
                System.arraycopy(b, off, head, count.toInt(), take)
                if (count + take >= 12 && head[0] == 'P'.code.toByte() && head[1] == 'A'.code.toByte()) {
                    total = ((head[8].toLong() and 0xff) shl 24) or ((head[9].toLong() and 0xff) shl 16) or ((head[10].toLong() and 0xff) shl 8) or (head[11].toLong() and 0xff)
                }
            }
            count += n
            if (count - lastReported >= 256 * 1024) { lastReported = count; onBytes(count, total) }
            return n
        }
    }
}
