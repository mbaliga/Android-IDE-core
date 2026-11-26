package dev.fonebrew.data.loop

import dev.fonebrew.domain.loop.HttpHop
import dev.fonebrew.domain.loop.HttpHopResponse
import dev.fonebrew.domain.loop.LoopHttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The real network edge for the `http_request` loop tool (docs/design/loop-tools.md). It carries
 * no credentials, cookies or custom headers, never follows redirects (the tool decides, same host
 * only), and reads at most the caller's byte cap. A hop whose URL OkHttp parses to a different
 * host than the policy approved is refused (parser-differential guard).
 *
 * Owner-verified only: this container has no network host, so TLS, timeouts and the caps against
 * a real server are not claimed. The policy around it is JVM-tested with a fake transport.
 */
class OkHttpLoopTransport(
    base: OkHttpClient = OkHttpClient(),
) : LoopHttpTransport {

    private val client: OkHttpClient = base.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun send(hop: HttpHop, timeoutMs: Long, maxBodyBytes: Int): HttpHopResponse =
        withContext(Dispatchers.IO) {
            val http = Request.Builder().url(hop.url).build().url
            val hostKey = if (http.port != 443) "${http.host.lowercase()}:${http.port}" else http.host.lowercase()
            if (http.scheme != "https" || hostKey != hop.host) throw IOException("url host mismatch")
            val body = hop.body?.toRequestBody((hop.contentType ?: "text/plain").plus("; charset=utf-8").toMediaType())
            val request = Request.Builder()
                .url(http)
                .header("User-Agent", "Fonebrew-Loop/1")
                .method(hop.method, if (hop.method == "POST") (body ?: "".toRequestBody()) else null)
                .build()
            val call = client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build().newCall(request)
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { call.cancel() }
                try {
                    call.execute().use { resp ->
                        val src = resp.body?.source()
                        val buf = okio.Buffer()
                        var truncated = false
                        if (src != null) {
                            while (buf.size <= maxBodyBytes) {
                                val n = src.read(buf, (maxBodyBytes + 1L) - buf.size)
                                if (n == -1L) break
                            }
                            if (buf.size > maxBodyBytes) truncated = true
                        }
                        val bytes = buf.readByteArray(minOf(buf.size, maxBodyBytes.toLong()))
                        cont.resumeWith(Result.success(HttpHopResponse(resp.code, resp.header("Location"), bytes, truncated)))
                    }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWith(Result.failure(e))
                }
            }
        }
}
