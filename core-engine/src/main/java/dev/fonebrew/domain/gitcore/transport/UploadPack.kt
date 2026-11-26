package dev.fonebrew.domain.gitcore.transport

import dev.fonebrew.domain.gitcore.ObjectId
import java.io.InputStream

/**
 * What to download. [haves] are objects we already hold (bounded, see [HaveSelector]); [depth] asks
 * for a shallow history; [clientShallow] are the commits our repository is already cut at.
 */
class FetchRequest(
    val wants: List<ObjectId>,
    val haves: List<ObjectId> = emptyList(),
    val depth: Int? = null,
    val clientShallow: List<ObjectId> = emptyList(),
    val thinPack: Boolean = true,
    val includeTag: Boolean = true,
)

/**
 * The server's answer. [pack] is the de-multiplexed pack stream (read it to its end, it is NOT
 * buffered). [shallow]/[unshallow] are the history cut points the server reported when [FetchRequest.depth] was set.
 */
class FetchResponse(val pack: InputStream, val shallow: List<ObjectId>, val unshallow: List<ObjectId>)

/**
 * The client half of git-upload-pack. Speaks protocol v2 when the server does and falls back to
 * v0/v1 (one-round, `done`) otherwise. Pure: all I/O is through the [GitSession] port.
 */
class UploadPackConnection private constructor(
    private val session: GitSession,
    val refs: List<AdvertisedRef>,
    /** The ref HEAD points at (e.g. `refs/heads/main`) when the server said so. */
    val headTarget: String?,
    private val caps: Capabilities,
    val isV2: Boolean,
    private val cancel: CancelToken,
) : AutoCloseable {

    val supportsShallow: Boolean get() = if (isV2) "shallow" in caps.features("fetch") else caps.has("shallow")

    override fun close() = session.close()

    fun fetch(req: FetchRequest, onProgress: (String) -> Unit = {}): FetchResponse {
        require(req.wants.isNotEmpty()) { "nothing to fetch" }
        if (req.depth != null) {
            require(req.depth > 0) { "depth must be positive" }
            if (!supportsShallow) throw TransportException(TransportErrorKind.UNSUPPORTED, "this server does not support shallow clones")
        }
        return if (isV2) fetchV2(req, onProgress) else fetchV0(req, onProgress)
    }

    // ------------------------------------------------------------------ v0 / v1

    private fun fetchV0(req: FetchRequest, onProgress: (String) -> Unit): FetchResponse {
        val sideband = when {
            caps.has("side-band-64k") -> "side-band-64k"
            caps.has("side-band") -> "side-band"
            else -> throw TransportException(TransportErrorKind.UNSUPPORTED, "this server does not offer side-band, which this client requires")
        }
        val want = ArrayList<String>()
        want.add(sideband)
        if (req.thinPack && caps.has("thin-pack")) want.add("thin-pack")
        if (caps.has("ofs-delta")) want.add("ofs-delta")
        if (req.includeTag && caps.has("include-tag")) want.add("include-tag")
        if (req.depth != null || req.clientShallow.isNotEmpty()) want.add("shallow")
        if (caps.has("object-format")) requireSha1(caps.value("object-format"))
        want.add("agent=fonebrew")
        val body = Payload(small = true) { out ->
            val w = PktLineWriter(out)
            req.wants.forEachIndexed { i, id -> w.text(if (i == 0) "want ${id.hex} ${want.joinToString(" ")}" else "want ${id.hex}") }
            for (s in req.clientShallow) w.text("shallow ${s.hex}")
            if (req.depth != null) w.text("deepen ${req.depth}")
            w.flush()
            for (h in req.haves) w.text("have ${h.hex}")
            w.text("done")
        }
        val stream = session.send(body, false)
        val reader = PktLineReader(stream)
        var shallow = emptyList<ObjectId>()
        var unshallow = emptyList<ObjectId>()
        if (req.depth != null) {
            val s = ArrayList<ObjectId>(); val u = ArrayList<ObjectId>()
            for (l in reader.readUntilFlush(1_000_000)) {
                AdvertisementParser.checkErr(l)
                val t = l.text()
                when {
                    t.startsWith("shallow ") -> s.add(oid(t.substring(8)))
                    t.startsWith("unshallow ") -> u.add(oid(t.substring(10)))
                    else -> protocol("unexpected line in the shallow update")
                }
            }
            shallow = s; unshallow = u
        }
        // Without multi_ack a server ACKs every common `have` (or sends one NAK) before the pack. Those lines
        // are plain text; the first side-band frame starts with channel byte 1..3. Skip ACK/NAK, hand that frame on.
        var first: Pkt.Data? = null
        while (true) {
            cancel.check()
            val p = reader.require()
            if (p !is Pkt.Data) protocol("expected an acknowledgement, got $p")
            AdvertisementParser.checkErr(p)
            if (p.bytes.isNotEmpty() && p.bytes[0].toInt() in 1..3) { first = p; break }
            val t = p.text()
            if (t == "NAK" || t.startsWith("ACK ")) continue
            protocol("unexpected line while waiting for the pack")
        }
        val pack: InputStream = SideBandInputStream(reader, onProgress, cancel, first)
        return FetchResponse(pack, shallow, unshallow)
    }

    // ------------------------------------------------------------------ v2

    private fun fetchV2(req: FetchRequest, onProgress: (String) -> Unit): FetchResponse {
        val body = Payload(small = true) { out ->
            val w = PktLineWriter(out)
            w.text("command=fetch")
            w.text("agent=fonebrew")
            if (caps.has("object-format")) w.text("object-format=sha1")
            w.delim()
            if (req.thinPack) w.text("thin-pack")
            w.text("ofs-delta")
            if (req.includeTag) w.text("include-tag")
            for (id in req.wants) w.text("want ${id.hex}")
            for (s in req.clientShallow) w.text("shallow ${s.hex}")
            if (req.depth != null) w.text("deepen ${req.depth}")
            for (h in req.haves) w.text("have ${h.hex}")
            w.text("done")
            w.flush()
        }
        val reader = PktLineReader(session.send(body, true))
        val shallow = ArrayList<ObjectId>(); val unshallow = ArrayList<ObjectId>()
        while (true) {
            cancel.check()
            val p = reader.require()
            if (p !is Pkt.Data) protocol("the fetch answer ended before a pack ($p)")
            AdvertisementParser.checkErr(p)
            when (val section = p.text()) {
                "packfile" -> return FetchResponse(SideBandInputStream(reader, onProgress, cancel), shallow, unshallow)
                "shallow-info" -> readSection(reader) { l ->
                    when {
                        l.startsWith("shallow ") -> shallow.add(oid(l.substring(8)))
                        l.startsWith("unshallow ") -> unshallow.add(oid(l.substring(10)))
                        else -> protocol("unexpected line in shallow-info")
                    }
                }
                // We always send `done`, so acknowledgments should not appear; tolerate and skip anything else.
                else -> if (section.length > 64 || !section.all { it.isLetter() || it == '-' }) protocol("unexpected line in the fetch answer") else readSection(reader) {}
            }
        }
    }

    private fun readSection(reader: PktLineReader, each: (String) -> Unit) {
        var n = 0
        while (true) {
            when (val p = reader.require()) {
                is Pkt.Data -> { if (++n > 1_000_000) protocol("section too long"); AdvertisementParser.checkErr(p); each(p.text()) }
                Pkt.Delim -> return
                Pkt.Flush, Pkt.ResponseEnd -> protocol("the fetch answer ended before a pack")
            }
        }
    }

    private fun oid(hex: String): ObjectId = ObjectId.fromHexOrNull(hex.trim()) ?: protocol("malformed object id from the server")

    companion object {
        /** Default ls-refs scope: the branches, the tags and HEAD (never the pull-request refs and friends). */
        val DEFAULT_PREFIXES = listOf("HEAD", "refs/heads/", "refs/tags/")

        private fun requireSha1(v: String?) {
            if (v != null && v != "sha1") throw TransportException(TransportErrorKind.UNSUPPORTED, "the repository uses object format '${SideBandInputStream.clean(v)}'; only sha1 is supported")
        }

        /**
         * Connects, reads the advertisement, and (v2) runs `ls-refs` limited to [refPrefixes]
         * (`null` = everything). A v0 server lists everything regardless; callers filter.
         */
        fun open(transport: GitTransport, cancel: CancelToken = CancelToken.NONE, refPrefixes: List<String>? = DEFAULT_PREFIXES, preferV2: Boolean = true): UploadPackConnection {
            val session = transport.open(GitService.UPLOAD_PACK, preferV2, cancel)
            try {
                val adv = AdvertisementParser.parse(session.advertisement)
                return when (adv) {
                    is Advertisement.V0 -> {
                        requireSha1(adv.capabilities.value("object-format"))
                        val head = adv.capabilities.values("symref").firstOrNull { it.startsWith("HEAD:") }?.removePrefix("HEAD:")
                        val refs = if (refPrefixes == null) adv.refs else adv.refs.filter { r -> refPrefixes.any { r.name == it || (it.endsWith("/") && r.name.startsWith(it)) } }
                        UploadPackConnection(session, refs, head, adv.capabilities, false, cancel)
                    }
                    is Advertisement.V2 -> {
                        val caps = adv.capabilities
                        if (!caps.has("fetch") || !caps.has("ls-refs")) throw TransportException(TransportErrorKind.UNSUPPORTED, "the server's protocol v2 offers no fetch/ls-refs")
                        requireSha1(caps.value("object-format"))
                        val refs = lsRefs(session, caps, refPrefixes, cancel)
                        val head = refs.firstOrNull { it.name == "HEAD" }?.symrefTarget
                        UploadPackConnection(session, refs, head, caps, true, cancel)
                    }
                }
            } catch (e: Throwable) {
                try { session.close() } catch (_: Exception) {}
                throw e
            }
        }

        private fun lsRefs(session: GitSession, caps: Capabilities, prefixes: List<String>?, cancel: CancelToken): List<AdvertisedRef> {
            val body = Payload(small = true) { out ->
                val w = PktLineWriter(out)
                w.text("command=ls-refs"); w.text("agent=fonebrew")
                if (caps.has("object-format")) w.text("object-format=sha1")
                w.delim(); w.text("peel"); w.text("symrefs")
                prefixes?.forEach { w.text("ref-prefix $it") }
                w.flush()
            }
            val reader = PktLineReader(session.send(body, true))
            val out = ArrayList<AdvertisedRef>()
            for (l in reader.readUntilFlush(AdvertisementParser.MAX_REFS)) {
                cancel.check()
                AdvertisementParser.checkErr(l)
                val parts = l.text().split(' ')
                if (parts.size < 2) protocol("malformed ls-refs line")
                val id = ObjectId.fromHexOrNull(parts[0]) ?: protocol("malformed object id in ls-refs")
                var sym: String? = null; var peeled: ObjectId? = null
                for (attr in parts.drop(2)) when {
                    attr.startsWith("symref-target:") -> sym = attr.removePrefix("symref-target:")
                    attr.startsWith("peeled:") -> peeled = ObjectId.fromHexOrNull(attr.removePrefix("peeled:")) ?: protocol("malformed peeled id")
                }
                out.add(AdvertisedRef(parts[1], id, peeled, sym))
            }
            return out
        }
    }
}
