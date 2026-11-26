package dev.fonebrew.domain.gitcore.transport

import dev.fonebrew.domain.gitcore.ObjectId
import java.io.OutputStream

/** One ref update sent to receive-pack: [old] null = create, [new] null = delete. */
class PushCommand(val ref: String, val old: ObjectId?, val new: ObjectId?) {
    init { require(old != null || new != null) { "a command must create, update or delete" } }
    val isDelete: Boolean get() = new == null
}

/**
 * The server's verdict. [unpackError] is null when the pack was unpacked; [results] maps each
 * command's ref to null (accepted) or the server's own refusal text, verbatim but sanitised.
 */
class PushReport(val unpackError: String?, val results: Map<String, String?>)

/**
 * The client half of git-receive-pack (protocol v0; push has no v2). Pure: I/O through [GitSession].
 * Never forces anything itself: it sends exactly the old/new pairs it is given, and the old value
 * makes the server compare-and-swap, so a stale view is rejected server-side too.
 */
class ReceivePackConnection private constructor(
    private val session: GitSession,
    val refs: List<AdvertisedRef>,
    private val caps: Capabilities,
    private val cancel: CancelToken,
) : AutoCloseable {
    val supportsDelete: Boolean get() = caps.has("delete-refs")
    val supportsAtomic: Boolean get() = caps.has("atomic")

    override fun close() = session.close()

    fun push(commands: List<PushCommand>, pack: ((OutputStream) -> Unit)?, atomic: Boolean = false, onProgress: (String) -> Unit = {}): PushReport {
        require(commands.isNotEmpty()) { "nothing to push" }
        if (!caps.has("report-status")) throw TransportException(TransportErrorKind.UNSUPPORTED, "this server cannot confirm pushes (no report-status)")
        if (commands.any { it.isDelete } && !supportsDelete) throw TransportException(TransportErrorKind.UNSUPPORTED, "this server does not allow deleting remote refs")
        if (atomic && !supportsAtomic) throw TransportException(TransportErrorKind.UNSUPPORTED, "this server does not support atomic pushes")
        caps.value("object-format")?.let { if (it != "sha1") throw TransportException(TransportErrorKind.UNSUPPORTED, "the repository uses object format '${SideBandInputStream.clean(it)}'; only sha1 is supported") }
        val sideband = if (caps.has("side-band-64k")) "side-band-64k" else null
        val capList = buildList {
            add("report-status")
            if (sideband != null) add(sideband)
            if (atomic) add("atomic")
            if (caps.has("object-format")) add("object-format=sha1")
            add("agent=fonebrew")
        }.joinToString(" ")
        val zero = "0".repeat(40)
        val needsPack = commands.any { !it.isDelete }
        val payload = Payload(small = false) { out ->
            val w = PktLineWriter(out)
            commands.forEachIndexed { i, c ->
                val line = "${c.old?.hex ?: zero} ${c.new?.hex ?: zero} ${c.ref}"
                if (i == 0) w.data((line + "\u0000" + capList).toByteArray(Charsets.UTF_8)) else w.text(line)
            }
            w.flush()
            if (needsPack) pack?.invoke(out) ?: throw IllegalStateException("a pack is required for this push")
        }
        val stream = session.send(payload, false)
        val outer = PktLineReader(stream)
        val inner = if (sideband != null) PktLineReader(SideBandInputStream(outer, onProgress, cancel)) else outer
        var unpackError: String? = null
        val results = LinkedHashMap<String, String?>()
        var sawUnpack = false
        while (true) {
            cancel.check()
            val p = inner.require()
            if (p == Pkt.Flush) break
            if (p !is Pkt.Data) protocol("unexpected $p in the push report")
            AdvertisementParser.checkErr(p)
            val t = p.text()
            when {
                t == "unpack ok" -> sawUnpack = true
                t.startsWith("unpack ") -> { sawUnpack = true; unpackError = SideBandInputStream.clean(t.removePrefix("unpack ")) }
                t.startsWith("ok ") -> results[t.substring(3)] = null
                t.startsWith("ng ") -> {
                    val rest = t.substring(3); val sp = rest.indexOf(' ')
                    if (sp < 0) results[rest] = "rejected" else results[rest.substring(0, sp)] = SideBandInputStream.clean(rest.substring(sp + 1))
                }
                else -> {} // tolerate unknown report lines (e.g. option lines of report-status-v2)
            }
        }
        if (!sawUnpack) protocol("the server's push report had no unpack status")
        for (c in commands) if (c.ref !in results && unpackError == null) protocol("the server did not report on ${c.ref}")
        return PushReport(unpackError, results)
    }

    companion object {
        fun open(transport: GitTransport, cancel: CancelToken = CancelToken.NONE): ReceivePackConnection {
            val session = transport.open(GitService.RECEIVE_PACK, false, cancel)
            try {
                val adv = AdvertisementParser.parse(session.advertisement)
                if (adv !is Advertisement.V0) throw TransportException(TransportErrorKind.UNSUPPORTED, "unexpected protocol version for push")
                return ReceivePackConnection(session, adv.refs, adv.capabilities, cancel)
            } catch (e: Throwable) {
                try { session.close() } catch (_: Exception) {}
                throw e
            }
        }
    }
}
