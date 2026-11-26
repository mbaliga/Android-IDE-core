package dev.fonebrew.domain.gitcore.transport

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** A scripted [GitTransport]: serves a fixed advertisement and canned responses, records every request body. */
class FakeTransport(private val advertisement: ByteArray, private val responses: List<ByteArray>) : GitTransport {
    val requests = ArrayList<ByteArray>()
    val requestV2Flags = ArrayList<Boolean>()
    var closed = false
    override val displayUrl = "fake://host/repo"
    override fun open(service: GitService, requestV2: Boolean, cancel: CancelToken): GitSession = object : GitSession {
        private var i = 0
        override val advertisement: InputStream = ByteArrayInputStream(this@FakeTransport.advertisement)
        override fun send(payload: Payload, protocolV2: Boolean): InputStream {
            requests.add(ByteArrayOutputStream().also { payload.write(it) }.toByteArray())
            requestV2Flags.add(protocolV2)
            return ByteArrayInputStream(responses[i++])
        }
        override fun close() { closed = true }
    }
}

/** Splits a request into readable pkt-line strings ("<flush>", "<delim>", text) and the raw trailing bytes. */
fun describe(request: ByteArray): List<String> {
    val out = ArrayList<String>()
    val r = PktLineReader(ByteArrayInputStream(request))
    while (true) {
        val p = try { r.read() } catch (_: TransportException) { null } ?: break
        out.add(when (p) { is Pkt.Data -> String(p.bytes, Charsets.UTF_8).replace("\u0000", "<NUL>").removeSuffix("\n"); Pkt.Flush -> "<flush>"; Pkt.Delim -> "<delim>"; Pkt.ResponseEnd -> "<end>" })
    }
    return out
}
