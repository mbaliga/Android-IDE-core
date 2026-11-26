package dev.fonebrew.data.gitcore

import dev.fonebrew.domain.gitcore.transport.CancelToken
import dev.fonebrew.domain.gitcore.transport.Credential
import dev.fonebrew.domain.gitcore.transport.CredentialProvider
import dev.fonebrew.domain.gitcore.transport.CredentialRequest
import dev.fonebrew.domain.gitcore.transport.GitService
import dev.fonebrew.domain.gitcore.transport.GitSession
import dev.fonebrew.domain.gitcore.transport.GitTransport
import dev.fonebrew.domain.gitcore.transport.Payload
import dev.fonebrew.domain.gitcore.transport.RemoteUrl
import dev.fonebrew.domain.gitcore.transport.TransportErrorKind
import dev.fonebrew.domain.gitcore.transport.TransportException
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/**
 * Smart HTTP(S) for git on OkHttp. The credential rules are the point of this class:
 *  - the first request of every origin is anonymous; credentials are asked for ONLY after a 401
 *    carrying a Basic challenge, and only for that origin (scheme+host+port);
 *  - a credential is attached solely to requests for the origin it was issued for, so a redirect
 *    to another host (or port, or scheme) never carries it, and that host's own 401 asks the
 *    provider again, by host;
 *  - redirects are followed by us (OkHttp's are off): at most [MAX_REDIRECTS], https -> http is
 *    refused, and the discovery redirect must still end in `/info/refs`;
 *  - nothing here puts a credential in a message, a header log or a toString.
 * Runtime against real hosts is owner-verified; the JVM suite drives it against `git http-backend`.
 */
class OkHttpGitTransport(
    private val url: RemoteUrl.Http,
    private val credentials: CredentialProvider,
    baseClient: OkHttpClient = defaultClient(),
) : GitTransport {

    private val http: OkHttpClient = baseClient.newBuilder().followRedirects(false).followSslRedirects(false).build()

    override val displayUrl: String get() = url.display

    override fun open(service: GitService, requestV2: Boolean, cancel: CancelToken): GitSession = Session(service, requestV2 && service == GitService.UPLOAD_PACK, cancel)

    private data class Origin(val scheme: String, val host: String, val port: Int)

    private fun HttpUrl.origin() = Origin(scheme, host, port)

    private inner class Session(val service: GitService, val v2: Boolean, val cancel: CancelToken) : GitSession {
        private var base: HttpUrl = url.base.toHttpUrlOrNull() ?: throw TransportException(TransportErrorKind.PROTOCOL, "bad address")
        private var cred: Credential? = null
        private var credOrigin: Origin? = null
        private var lastResponse: Response? = null
        private var discovered: InputStream? = null

        override val advertisement: InputStream
            get() = discovered ?: discover().also { discovered = it }

        private fun discover(): InputStream {
            val target = base.newBuilder().addPathSegments("info/refs").addQueryParameter("service", service.wire).build()
            val resp = execute(target, followRedirects = true) { b ->
                b.get().header("Accept", "*/*").header("Pragma", "no-cache")
                if (v2) b.header("Git-Protocol", "version=2")
            }
            val final = resp.request.url
            val seg = final.pathSegments
            if (seg.size < 2 || seg[seg.size - 1] != "refs" || seg[seg.size - 2] != "info") {
                resp.close()
                throw TransportException(TransportErrorKind.REDIRECT_REFUSED, "the redirect did not end at an info/refs address", host = final.host)
            }
            base = final.newBuilder().removePathSegment(seg.size - 1).removePathSegment(seg.size - 2).query(null).build()
            val ct = resp.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
            if (ct != "application/x-${service.wire}-advertisement") {
                resp.close()
                throw TransportException(TransportErrorKind.PROTOCOL, "the address does not speak the smart git protocol (dumb HTTP is not supported)", host = final.host)
            }
            return wrap(resp)
        }

        override fun send(payload: Payload, protocolV2: Boolean): InputStream {
            lastResponse?.close()
            val target = base.newBuilder().addPathSegment(service.wire).build()
            val resp = execute(target, followRedirects = false) { b ->
                b.header("Accept", "application/x-${service.wire}-result")
                if (protocolV2) b.header("Git-Protocol", "version=2")
                if (payload.small) {
                    val raw = ByteArrayOutputStream().also { payload.write(it) }.toByteArray()
                    val mt = "application/x-${service.wire}-request".toMediaType()
                    if (raw.size > GZIP_THRESHOLD) {
                        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(raw) } }.toByteArray()
                        b.header("Content-Encoding", "gzip").post(gz.toRequestBody(mt))
                    } else b.post(raw.toRequestBody(mt))
                } else {
                    b.post(object : RequestBody() {
                        override fun contentType() = "application/x-${service.wire}-request".toMediaType()
                        override fun writeTo(sink: BufferedSink) { sink.outputStream().let { payload.write(it); it.flush() } }
                        override fun isOneShot() = false
                    })
                }
            }
            val ct = resp.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
            if (ct != "application/x-${service.wire}-result") {
                resp.close()
                throw TransportException(TransportErrorKind.PROTOCOL, "unexpected content type from the server", host = resp.request.url.host)
            }
            return wrap(resp)
        }

        override fun close() { lastResponse?.close(); lastResponse = null }

        private fun wrap(resp: Response): InputStream {
            lastResponse = resp
            val body = resp.body ?: throw TransportException(TransportErrorKind.PROTOCOL, "empty response")
            val raw = body.byteStream()
            return object : FilterInputStream(raw) {
                override fun read(): Int = guard { super.read() }
                override fun read(b: ByteArray, off: Int, len: Int): Int = guard { super.read(b, off, len) }
                private fun <T> guard(f: () -> T): T = try { f() } catch (e: IOException) { throw netError(e) }
            }
        }

        private fun netError(e: IOException): TransportException =
            if (cancel.cancelled) TransportException(TransportErrorKind.CANCELLED, "cancelled")
            else TransportException(TransportErrorKind.NETWORK, "connection failed (${e.javaClass.simpleName})", cause = e)

        /** One logical request: handles the 401 retry and (when allowed) redirects. Returns a 200 response. */
        private fun execute(start: HttpUrl, followRedirects: Boolean, build: (Request.Builder) -> Unit): Response {
            var cur = start
            var redirects = 0
            var triedAuthFor: Origin? = null
            while (true) {
                cancel.check()
                val rb = Request.Builder().url(cur).header("User-Agent", "fonebrew-git")
                build(rb)
                val o = cur.origin()
                val c = cred
                if (c != null && credOrigin == o) {
                    rb.header("Authorization", okhttp3.Credentials.basic(c.username, c.password(), Charsets.UTF_8))
                }
                val call: Call = http.newCall(rb.build())
                cancel.register(AutoCloseable { call.cancel() })
                val resp = try { call.execute() } catch (e: IOException) { throw netError(e) }
                when (val code = resp.code) {
                    200 -> return resp
                    301, 302, 303, 307, 308 -> {
                        val loc = resp.header("Location")
                        resp.close()
                        if (!followRedirects) throw TransportException(TransportErrorKind.REDIRECT_REFUSED, "the server redirected a data request", host = cur.host)
                        if (++redirects > MAX_REDIRECTS) throw TransportException(TransportErrorKind.REDIRECT_REFUSED, "too many redirects", host = cur.host)
                        val next = loc?.let { cur.resolve(it) } ?: throw TransportException(TransportErrorKind.REDIRECT_REFUSED, "redirect without a usable address", host = cur.host)
                        if (cur.isHttps && !next.isHttps) throw TransportException(TransportErrorKind.REDIRECT_REFUSED, "redirect from https to http refused", host = cur.host)
                        cur = next
                    }
                    401 -> {
                        val challenge = resp.headers("WWW-Authenticate")
                        resp.close()
                        if (c != null && credOrigin == o) throw TransportException(TransportErrorKind.AUTH_FAILED, "the server refused the saved sign-in", host = cur.host)
                        if (triedAuthFor == o) throw TransportException(TransportErrorKind.AUTH_FAILED, "the server refused the sign-in", host = cur.host)
                        if (challenge.isNotEmpty() && challenge.none { it.trim().startsWith("Basic", ignoreCase = true) }) {
                            throw TransportException(TransportErrorKind.AUTH_REQUIRED, "the server wants a sign-in method this app does not support", host = cur.host)
                        }
                        val got = credentials.credentialFor(CredentialRequest(cur.scheme, cur.host, cur.port, url.usernameHint.takeIf { cur.host.equals(url.host, ignoreCase = true) }))
                            ?: throw TransportException(TransportErrorKind.AUTH_REQUIRED, "sign-in required for ${cur.host}", host = cur.host)
                        cred = got; credOrigin = o; triedAuthFor = o
                    }
                    403 -> {
                        resp.close()
                        throw TransportException(if (c != null && credOrigin == o) TransportErrorKind.AUTH_FAILED else TransportErrorKind.AUTH_REQUIRED, "the server denied access (HTTP 403)", host = cur.host)
                    }
                    404, 410 -> { resp.close(); throw TransportException(TransportErrorKind.NOT_FOUND, "repository not found", host = cur.host) }
                    else -> { resp.close(); throw TransportException(TransportErrorKind.REMOTE_ERROR, "the server answered HTTP $code", host = cur.host) }
                }
            }
        }
    }

    companion object {
        const val MAX_REDIRECTS = 5
        const val GZIP_THRESHOLD = 1024

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}
