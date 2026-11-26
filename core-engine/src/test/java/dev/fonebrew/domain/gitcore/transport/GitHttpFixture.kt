package dev.fonebrew.domain.gitcore.transport

import dev.fonebrew.domain.gitcore.GitTestSupport
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A REAL git server for tests: the JDK HTTP server bridged to `git http-backend` over CGI, serving
 * bare repositories under [root]. Nothing is mocked: every protocol byte the client sees comes from
 * git 2.43. Knobs: Basic auth ([requireAuth]), a v0-only server ([v0Only]), and a [tamper] hook
 * that can replace a response (hostile server) or answer without invoking git.
 */
class GitHttpFixture(val root: File = GitTestSupport.tempDir("gitsrv"), val bindHost: String = "127.0.0.1") : AutoCloseable {
    class Logged(val method: String, val path: String, val query: String?, val authorization: String?, val gitProtocol: String?, val contentEncoding: String?, val bodyBytes: Int)

    /** (user, password) or null for anonymous access. */
    @Volatile var requireAuth: Pair<String, String>? = null
    @Volatile var v0Only = false
    /** Return a replacement answer (status, content-type, body) or null to proxy to git. */
    @Volatile var tamper: ((method: String, path: String, body: ByteArray, proxy: () -> Answer) -> Answer?)? = null
    /** When set, every request is answered with a redirect to this base (scheme://host:port). */
    @Volatile var redirectTo: String? = null
    val log = CopyOnWriteArrayList<Logged>()

    class Answer(val status: Int, val contentType: String, val body: ByteArray)

    private val server = MiniHttpServer(bindHost) { req -> handle(req) }
    val port: Int get() = server.port
    fun url(repo: String, host: String = bindHost) = "http://$host:$port/$repo"

    override fun close() { server.close() }

    fun bare(name: String) = File(root, name)

    /** Creates an empty bare repo with push enabled (like a host that accepted this user). */
    fun createBare(name: String): File {
        val d = bare(name); d.mkdirs()
        GitTestSupport.git(d, "init", "--bare", "-q", "-b", "main")
        GitTestSupport.git(d, "config", "http.receivepack", "true")
        GitTestSupport.git(d, "config", "uploadpack.allowFilter", "false")
        return d
    }

    private fun handle(req: HttpReq): HttpResp {
        val path = req.path
        val body = req.body
        log.add(Logged(req.method, path, req.query, req.header("Authorization"), req.header("Git-Protocol"), req.header("Content-Encoding"), body.size))
        requireAuth?.let { (u, p) ->
            val expect = "Basic " + Base64.getEncoder().encodeToString("$u:$p".toByteArray())
            if (req.header("Authorization") != expect) return HttpResp(401, listOf("WWW-Authenticate" to "Basic realm=\"git\""))
        }
        redirectTo?.let { base -> return HttpResp.redirect(base + path + (req.query?.let { "?$it" } ?: "")) }
        val proxy = { cgi(req, path, body) }
        val ans = tamper?.invoke(req.method, path, body, proxy) ?: proxy()
        return HttpResp(ans.status, listOf("Content-Type" to ans.contentType), ans.body)
    }

    private fun cgi(req: HttpReq, path: String, body: ByteArray): Answer {
        val pb = ProcessBuilder("git", "http-backend")
        val env = pb.environment()
        env["GIT_CONFIG_NOSYSTEM"] = "1"; env["GIT_CONFIG_GLOBAL"] = "/dev/null"; env["HOME"] = root.absolutePath
        env["GIT_PROJECT_ROOT"] = root.absolutePath; env["GIT_HTTP_EXPORT_ALL"] = "1"
        env["REQUEST_METHOD"] = req.method; env["PATH_INFO"] = java.net.URLDecoder.decode(path, "UTF-8")
        env["QUERY_STRING"] = req.query ?: ""
        env["CONTENT_TYPE"] = req.header("Content-Type") ?: ""
        env["CONTENT_LENGTH"] = body.size.toString()
        env["REMOTE_ADDR"] = "127.0.0.1"
        req.header("Content-Encoding")?.let { env["HTTP_CONTENT_ENCODING"] = it }
        if (!v0Only) req.header("Git-Protocol")?.let { env["GIT_PROTOCOL"] = it; env["HTTP_GIT_PROTOCOL"] = it }
        val p = pb.start()
        val errT = Thread { p.errorStream.readBytes() }.apply { isDaemon = true; start() }
        Thread { p.outputStream.use { it.write(body) } }.apply { isDaemon = true; start() }
        val out = p.inputStream.readBytes()
        p.waitFor(); errT.join(2000)
        // CGI response: headers, blank line, body.
        var i = 0; var status = 200; var ct = "application/octet-stream"
        while (true) {
            val e = out.indexOf('\n'.code.toByte(), i)
            if (e < 0) return Answer(500, "text/plain", ByteArray(0))
            val line = String(out, i, e - i, Charsets.ISO_8859_1).trimEnd('\r')
            i = e + 1
            if (line.isEmpty()) break
            val c = line.indexOf(':')
            if (c > 0) when (line.substring(0, c).lowercase()) {
                "status" -> status = line.substring(c + 1).trim().substringBefore(' ').toInt()
                "content-type" -> ct = line.substring(c + 1).trim()
            }
        }
        return Answer(status, ct, out.copyOfRange(i, out.size))
    }

    private fun ByteArray.indexOf(b: Byte, from: Int): Int { for (k in from until size) if (this[k] == b) return k; return -1 }

    companion object {
        fun gitHttpBackendAvailable(): Boolean = GitTestSupport.gitAvailable() && try {
            ProcessBuilder("git", "http-backend").redirectErrorStream(true).also { it.environment()["GIT_PROJECT_ROOT"] = "/nonexistent"; it.environment()["REQUEST_METHOD"] = "GET"; it.environment()["PATH_INFO"] = "/x/info/refs" }
                .start().let { it.inputStream.readBytes(); it.waitFor(); true }
        } catch (_: Exception) { false }
    }
}
