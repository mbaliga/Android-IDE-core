package dev.fonebrew.domain.loop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI

/**
 * One HTTP hop as the transport sees it. [host] is the host key the policy approved; a transport
 * MUST refuse to send if its own URL parse disagrees (parser-differential guard).
 */
data class HttpHop(
    val method: String,
    val url: String,
    val host: String,
    val body: String?,
    val contentType: String?,
)

/** One raw response: the transport never follows redirects itself. [body] holds at most the
 *  requested cap; [truncated] is true when the server sent more. */
class HttpHopResponse(
    val status: Int,
    val location: String?,
    val body: ByteArray,
    val truncated: Boolean,
)

/** The injected network edge (OkHttp in the app, a fake in tests). No redirects, no auth. */
fun interface LoopHttpTransport {
    suspend fun send(hop: HttpHop, timeoutMs: Long, maxBodyBytes: Int): HttpHopResponse
}

data class HttpRequestSpec(val method: String, val uri: URI, val hostKey: String, val body: String?, val contentType: String?)

/** Pure validation of an `http_request` node's arguments — the v1 rules, in one place. */
object HttpRequestPolicy {
    const val MAX_URL = 2048
    const val MAX_BODY_CHARS = 64 * 1024
    val METHODS = setOf("GET", "POST")
    val CONTENT_TYPES = setOf("text/plain", "application/json")

    /** Query keys that look like credentials: v1 carries none (best-effort heuristic). */
    private val SECRETISH = setOf(
        "key", "api_key", "apikey", "api-key", "token", "access_token", "auth", "authorization",
        "secret", "password", "passwd", "pwd", "sig", "signature", "client_secret",
    )

    sealed interface Parsed {
        data class Ok(val spec: HttpRequestSpec) : Parsed
        data class Err(val message: String) : Parsed
    }

    fun parse(args: Map<String, String>): Parsed {
        val raw = args["url"]?.trim().orEmpty()
        if (raw.isEmpty()) return Parsed.Err("http_request needs a url")
        if (raw.length > MAX_URL) return Parsed.Err("http_request url is longer than $MAX_URL characters")
        if (raw.any { it.isWhitespace() || it.isISOControl() }) return Parsed.Err("http_request url contains whitespace or control characters")
        val uri = try { URI(raw) } catch (_: Exception) { return Parsed.Err("http_request url is not a valid URL") }
        if (uri.scheme == null || !uri.scheme.equals("https", ignoreCase = true)) {
            return Parsed.Err("http_request allows https only")
        }
        if (uri.rawUserInfo != null) return Parsed.Err("http_request does not accept credentials in the url (v1)")
        val host = uri.host?.lowercase()
        if (host.isNullOrEmpty()) return Parsed.Err("http_request url has no host")
        uri.rawQuery?.split('&')?.forEach { pair ->
            val k = pair.substringBefore('=').lowercase()
            if (k in SECRETISH) return Parsed.Err("http_request does not accept credentials or secret-looking query parameters (v1): \"$k\"")
        }
        val method = (args["method"]?.trim()?.ifEmpty { null } ?: "GET").uppercase()
        if (method !in METHODS) return Parsed.Err("http_request allows GET and POST only")
        val body = args["body"]?.takeIf { it.isNotEmpty() }
        if (body != null && method != "POST") return Parsed.Err("http_request: a body needs method POST")
        if (body != null && body.length > MAX_BODY_CHARS) return Parsed.Err("http_request body is longer than $MAX_BODY_CHARS characters")
        val ct = if (body == null) null else (args["contentType"]?.trim()?.lowercase()?.ifEmpty { null } ?: "text/plain")
        if (ct != null && ct !in CONTENT_TYPES) return Parsed.Err("http_request contentType must be one of ${CONTENT_TYPES.joinToString()}")
        return Parsed.Ok(HttpRequestSpec(method, uri, hostKey(uri), body, ct))
    }

    /** `host` or `host:port` when the port is not the https default. */
    fun hostKey(uri: URI): String {
        val h = uri.host.lowercase()
        return if (uri.port != -1 && uri.port != 443) "$h:${uri.port}" else h
    }

    /** `GET https://host/path` with the query elided: queries are where pasted tokens live. */
    fun safeSummary(spec: HttpRequestSpec): String {
        val path = spec.uri.rawPath?.ifEmpty { "/" } ?: "/"
        val q = if (spec.uri.rawQuery != null) "?…" else ""
        return "${spec.method} https://${spec.hostKey}${path.take(120)}$q"
    }
}

/**
 * `http_request` (docs/design/loop-tools.md). https, GET/POST, no credentials, 15 s, 256 KB,
 * same-host redirects only. The response is UNTRUSTED text; the runner delimits it.
 */
class HttpRequestTool(private val transport: LoopHttpTransport) : LoopTool {
    override val id = ID
    override val label = "HTTP request"
    override val sideEffect = ToolSideEffect.NETWORK
    override val args = listOf(
        ToolArg("url", true, "https:// address to contact."),
        ToolArg("method", false, "GET (default) or POST."),
        ToolArg("body", false, "POST body text."),
        ToolArg("contentType", false, "text/plain (default) or application/json."),
    )
    override val maxResultChars = MAX_RESPONSE_BYTES + 128

    override fun validate(args: Map<String, String>): String? =
        (HttpRequestPolicy.parse(args) as? HttpRequestPolicy.Parsed.Err)?.message

    override fun hosts(args: Map<String, String>): List<String> =
        (HttpRequestPolicy.parse(args) as? HttpRequestPolicy.Parsed.Ok)?.let { listOf(it.spec.hostKey) } ?: emptyList()

    override fun summarize(args: Map<String, String>): String =
        when (val p = HttpRequestPolicy.parse(args)) {
            is HttpRequestPolicy.Parsed.Ok -> HttpRequestPolicy.safeSummary(p.spec)
            is HttpRequestPolicy.Parsed.Err -> "invalid request"
        }

    override suspend fun invoke(args: Map<String, String>): ToolOutcome {
        val spec = (HttpRequestPolicy.parse(args) as? HttpRequestPolicy.Parsed.Ok)?.spec
            ?: return ToolOutcome.Failed("invalid http_request arguments")
        var method = spec.method
        var uri = spec.uri
        var body = spec.body
        var contentType = spec.contentType
        var hops = 0
        while (true) {
            val hop = HttpHop(method, uri.toString(), spec.hostKey, body, contentType)
            val response = try {
                withTimeoutOrNull(TIMEOUT_MS) { transport.send(hop, TIMEOUT_MS, MAX_RESPONSE_BYTES) }
                    ?: return ToolOutcome.Failed("timed out after ${TIMEOUT_MS / 1000} s")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return ToolOutcome.Failed("request failed (${e.javaClass.simpleName})")
            }
            when (response.status) {
                in 200..299 -> {
                    val text = String(response.body, Charsets.UTF_8)
                    val note = if (response.truncated) "\n[response truncated at ${MAX_RESPONSE_BYTES / 1024} KB]" else ""
                    return ToolOutcome.Ok(text + note)
                }
                301, 302, 303, 307, 308 -> {
                    if (++hops > MAX_REDIRECTS) return ToolOutcome.Failed("too many redirects")
                    val loc = response.location ?: return ToolOutcome.Failed("redirect without a Location")
                    val next = try { uri.resolve(loc) } catch (_: Exception) { return ToolOutcome.Failed("redirect to an invalid address refused") }
                    if (next.scheme == null || !next.scheme.equals("https", ignoreCase = true)) return ToolOutcome.Failed("redirect to a non-https address refused")
                    if (next.rawUserInfo != null || next.host == null) return ToolOutcome.Failed("redirect refused")
                    if (HttpRequestPolicy.hostKey(next) != spec.hostKey) {
                        return ToolOutcome.Failed("redirect to another host refused (${spec.hostKey} -> ${HttpRequestPolicy.hostKey(next)})")
                    }
                    uri = next
                    if (response.status != 307 && response.status != 308 && method == "POST") {
                        method = "GET"; body = null; contentType = null
                    }
                }
                else -> return ToolOutcome.Failed("HTTP ${response.status}")
            }
        }
    }

    companion object {
        const val ID = "http_request"
        const val TIMEOUT_MS = 15_000L
        const val MAX_RESPONSE_BYTES = 256 * 1024
        const val MAX_REDIRECTS = 3
    }
}
