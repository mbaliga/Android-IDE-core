package dev.fonebrew.inference.cloud

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Loopback-only HTTP stand-in for a cloud provider, used by the always-on contract tests.
 * It binds 127.0.0.1 on an ephemeral port and answers each request with a scripted
 * [Reply]; nothing here ever dials out, so the tests need no network and no key.
 * Raw sockets (not a mock-server library) so a test controls the bytes exactly: status,
 * headers, chunk framing, mid-codepoint TCP splits and abrupt connection drops.
 */
class FakeProviderServer(private val reply: Reply) : AutoCloseable {

    /** What to send back. [body] is written as one chunked-encoding chunk, in [splitAt] pieces. */
    class Reply(
        val status: Int = 200,
        val headers: List<Pair<String, String>> = listOf("Content-Type" to "text/event-stream"),
        val body: ByteArray = ByteArray(0),
        /** Byte offsets in [body] at which to flush a separate TCP write (simulates chunk splits). */
        val splitAt: List<Int> = emptyList(),
        /** Declare the chunk longer than what is sent, then drop the connection (a cut stream). */
        val abruptDrop: Boolean = false,
    )

    /** One captured request: the raw header block (request line + headers) and body. */
    class Captured(val head: String, val body: String) {
        val requestLine: String get() = head.lineSequence().first()
        fun header(name: String): String? = head.lineSequence().drop(1)
            .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        val headerNames: List<String> get() = head.lineSequence().drop(1)
            .filter { ':' in it }.map { it.substringBefore(':').lowercase() }.toList()
    }

    val requests = CopyOnWriteArrayList<Captured>()
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val baseUrl: String get() = "http://127.0.0.1:${server.localPort}"

    private val acceptor = thread(isDaemon = true, name = "fake-provider") {
        while (!server.isClosed) {
            val s = runCatching { server.accept() }.getOrNull() ?: break
            runCatching { serve(s) }
            runCatching { s.close() }
        }
    }

    private fun serve(s: Socket) {
        val input = s.getInputStream().buffered()
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) return
            head.append(b.toChar())
        }
        val len = head.lineSequence().firstOrNull { it.startsWith("content-length:", true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        val body = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(body, read, len - read)
            if (n < 0) break
            read += n
        }
        requests.add(Captured(head.toString().trimEnd(), String(body, Charsets.UTF_8)))

        val out = s.getOutputStream()
        val reason = when (reply.status) { 200 -> "OK"; 401 -> "Unauthorized"; 429 -> "Too Many Requests"; else -> "Error" }
        val sb = StringBuilder("HTTP/1.1 ${reply.status} $reason\r\n")
        for ((k, v) in reply.headers) sb.append("$k: $v\r\n")
        sb.append("Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n")
        out.write(sb.toString().toByteArray()); out.flush()

        val b = reply.body
        val declared = if (reply.abruptDrop) b.size + 64 else b.size
        out.write("${declared.toString(16)}\r\n".toByteArray())
        var from = 0
        for (cut in reply.splitAt.filter { it in 1 until b.size }.sorted() + b.size) {
            out.write(b, from, cut - from); out.flush()
            from = cut
            if (cut < b.size) Thread.sleep(40)
        }
        if (reply.abruptDrop) return // socket closed by the caller mid-chunk
        out.write("\r\n0\r\n\r\n".toByteArray()); out.flush()
    }

    override fun close() {
        runCatching { server.close() }
    }
}
