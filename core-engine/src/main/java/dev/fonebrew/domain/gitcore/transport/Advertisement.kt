package dev.fonebrew.domain.gitcore.transport

import dev.fonebrew.domain.gitcore.ObjectId
import java.io.InputStream

/** A ref the server lists. [peeled] is set when it is an annotated tag; [symrefTarget] for symrefs (v2). */
data class AdvertisedRef(val name: String, val id: ObjectId, val peeled: ObjectId? = null, val symrefTarget: String? = null)

/** What the server said first. */
sealed interface Advertisement {
    val capabilities: Capabilities

    /** Protocol v0 / v1: refs and capabilities arrive together. */
    class V0(val refs: List<AdvertisedRef>, override val capabilities: Capabilities, val announcedVersion1: Boolean = false) : Advertisement

    /** Protocol v2: only capabilities; refs come from `ls-refs`. */
    class V2(override val capabilities: Capabilities) : Advertisement
}

object AdvertisementParser {
    const val MAX_REFS = 1_000_000

    /** Parses a discovery response (HTTP info/refs body or an SSH greeting). Hostile input throws PROTOCOL. */
    fun parse(input: InputStream): Advertisement {
        val r = PktLineReader(input)
        var first = r.read() ?: protocol("the server sent an empty answer")
        if (first is Pkt.Data && first.text().startsWith("# service=")) {
            // Smart-HTTP prologue: "# service=git-upload-pack" then a flush.
            when (val f = r.require()) { Pkt.Flush -> {} else -> protocol("malformed service announcement ($f)") }
            first = r.read() ?: return Advertisement.V0(emptyList(), Capabilities.EMPTY)
        }
        when (first) {
            Pkt.Flush -> return Advertisement.V0(emptyList(), Capabilities.EMPTY)
            Pkt.Delim, Pkt.ResponseEnd -> protocol("unexpected $first at the start of the advertisement")
            is Pkt.Data -> {}
        }
        first as Pkt.Data
        checkErr(first)
        val t = first.text()
        if (t == "version 2") return Advertisement.V2(parseV2Caps(r))
        var v1 = false
        var pendingFirst: Pkt.Data? = first
        if (t == "version 1") { v1 = true; pendingFirst = null }
        return parseV0(r, pendingFirst, v1)
    }

    private fun parseV2Caps(r: PktLineReader): Capabilities {
        val lines = r.readUntilFlush(10_000).map { checkErr(it); it.text() }
        return Capabilities.fromLines(lines)
    }

    private fun parseV0(r: PktLineReader, firstPkt: Pkt.Data?, v1: Boolean): Advertisement.V0 {
        val refs = ArrayList<AdvertisedRef>()
        var caps = Capabilities.EMPTY
        var seenCaps = false
        var pkt: Pkt = firstPkt ?: r.require()
        while (true) {
            when (pkt) {
                Pkt.Flush -> break
                Pkt.Delim, Pkt.ResponseEnd -> protocol("unexpected $pkt in the ref advertisement")
                is Pkt.Data -> {
                    checkErr(pkt)
                    var line = String(pkt.bytes, Charsets.UTF_8).removeSuffix("\n")
                    if (!seenCaps) {
                        val nul = line.indexOf('\u0000')
                        if (nul >= 0) { caps = Capabilities.parse(line.substring(nul + 1)); line = line.substring(0, nul) }
                        seenCaps = true
                    } else if (line.indexOf('\u0000') >= 0) protocol("capabilities repeated in the ref advertisement")
                    if (line.startsWith("shallow ")) { pkt = r.require(); continue }
                    val sp = line.indexOf(' ')
                    if (sp != 40) protocol("malformed ref line in the advertisement")
                    val id = ObjectId.fromHexOrNull(line.substring(0, 40)) ?: protocol("malformed object id in the advertisement")
                    val name = line.substring(41)
                    if (name == "capabilities^{}") { pkt = r.require(); continue }
                    if (name.isEmpty() || name.length > 4096) protocol("bad ref name in the advertisement")
                    if (name.endsWith("^{}")) {
                        val base = name.removeSuffix("^{}")
                        val prev = refs.lastOrNull()
                        if (prev == null || prev.name != base) protocol("peeled ref without its tag")
                        refs[refs.size - 1] = prev.copy(peeled = id)
                    } else {
                        if (refs.size >= MAX_REFS) protocol("the server advertised more than $MAX_REFS refs")
                        refs.add(AdvertisedRef(name, id))
                    }
                }
            }
            pkt = r.require()
        }
        return Advertisement.V0(refs, caps, v1)
    }

    internal fun checkErr(p: Pkt.Data) {
        if (p.bytes.size >= 4 && String(p.bytes, 0, 4, Charsets.ISO_8859_1) == "ERR ") {
            throw TransportException(TransportErrorKind.REMOTE_ERROR, "the server reported: ${SideBandInputStream.clean(String(p.bytes, 4, p.bytes.size - 4, Charsets.UTF_8))}")
        }
    }
}
