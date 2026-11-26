package dev.fonebrew.domain.gitcore.transport

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SSLContext

class HttpReq(val method: String, val target: String, val headers: Map<String, String>, val body: ByteArray) {
    val path: String get() = target.substringBefore('?')
    val query: String? get() = target.substringAfter('?', "").ifEmpty { null }
    fun header(name: String): String? = headers[name.lowercase()]
}

class HttpResp(val status: Int, val headers: List<Pair<String, String>> = emptyList(), val body: ByteArray = ByteArray(0)) {
    companion object {
        fun redirect(to: String, code: Int = 302) = HttpResp(code, listOf("Location" to to))
    }
}

/**
 * A tiny blocking HTTP/1.1 server on plain sockets (optionally TLS). The Android unit-test
 * classpath has no `com.sun.net.httpserver`, and this keeps the git fixtures free of any test
 * dependency. One request per connection (`Connection: close`); request bodies may be
 * Content-Length or chunked (OkHttp streams pushes chunked).
 */
class MiniHttpServer(
    bindHost: String = "127.0.0.1",
    ssl: SSLContext? = null,
    private val handler: (HttpReq) -> HttpResp,
) : AutoCloseable {
    private val server: ServerSocket = (ssl?.serverSocketFactory?.createServerSocket(0, 50, InetAddress.getByName(bindHost)) ?: ServerSocket(0, 50, InetAddress.getByName(bindHost)))
    val port: Int get() = server.localPort
    @Volatile private var closed = false

    init {
        Thread {
            while (!closed) {
                val s = try { server.accept() } catch (_: Exception) { break }
                Thread { serve(s) }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }
    }

    override fun close() { closed = true; try { server.close() } catch (_: Exception) {} }

    private fun serve(sock: Socket) {
        try {
            sock.use { s ->
                s.soTimeout = 120_000
                val input = BufferedInputStream(s.getInputStream())
                val req = readRequest(input, s) ?: return
                val resp = try { handler(req) } catch (t: Throwable) { HttpResp(500) }
                val out = s.getOutputStream()
                val head = StringBuilder("HTTP/1.1 ${resp.status} ${if (resp.status == 200) "OK" else "Status"}\r\n")
                head.append("Content-Length: ${resp.body.size}\r\nConnection: close\r\n")
                for ((k, v) in resp.headers) head.append("$k: $v\r\n")
                head.append("\r\n")
                out.write(head.toString().toByteArray(Charsets.ISO_8859_1)); out.write(resp.body); out.flush()
            }
        } catch (_: Exception) { /* the client went away (cancel tests rely on this being harmless) */ }
    }

    private fun readLine(i: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = i.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("ISO-8859-1")
            if (c == '\n'.code) return b.toString("ISO-8859-1").trimEnd('\r')
            b.write(c)
            if (b.size() > 64 * 1024) throw IllegalStateException("header too long")
        }
    }

    private fun readRequest(i: InputStream, s: Socket): HttpReq? {
        val line = readLine(i) ?: return null
        val parts = line.split(' ')
        if (parts.size < 2) return null
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val h = readLine(i) ?: return null
            if (h.isEmpty()) break
            val c = h.indexOf(':'); if (c > 0) headers[h.substring(0, c).trim().lowercase()] = h.substring(c + 1).trim()
        }
        if (headers["expect"]?.equals("100-continue", true) == true) { s.getOutputStream().write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray()); s.getOutputStream().flush() }
        val body = ByteArrayOutputStream()
        if (headers["transfer-encoding"]?.contains("chunked", true) == true) {
            while (true) {
                val n = (readLine(i) ?: return null).substringBefore(';').trim().toInt(16)
                if (n == 0) { while ((readLine(i) ?: "").isNotEmpty()) {}; break }
                val buf = ByteArray(n); var off = 0
                while (off < n) { val r = i.read(buf, off, n - off); if (r < 0) return null; off += r }
                body.write(buf); readLine(i)
            }
        } else headers["content-length"]?.toIntOrNull()?.let { n ->
            val buf = ByteArray(n); var off = 0
            while (off < n) { val r = i.read(buf, off, n - off); if (r < 0) return null; off += r }
            body.write(buf)
        }
        return HttpReq(parts[0], parts[1], headers, body.toByteArray())
    }
}
