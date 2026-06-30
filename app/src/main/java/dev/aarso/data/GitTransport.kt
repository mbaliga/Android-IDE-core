package dev.aarso.data

import dev.aarso.domain.git.GitContentsApi
import dev.aarso.domain.git.GitHost
import dev.aarso.domain.git.GitRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray

/**
 * Thin network adapter that executes the pure [GitRequest]s from [GitContentsApi]
 * against the user's host (OkHttp, as the cloud engines use). The app talks ONLY to
 * the configured host. Owner-verified on device — there's no host to hit in CI.
 */
open class GitTransport(private val client: OkHttpClient = OkHttpClient()) {

    data class Resp(val code: Int, val body: String)

    open suspend fun execute(req: GitRequest): Resp = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(req.url)
        req.headers.forEach { (k, v) -> builder.header(k, v) }
        when (req.method) {
            "GET" -> builder.get()
            "PUT" -> builder.put((req.body ?: "").toRequestBody(JSON))
            "POST" -> builder.post((req.body ?: "").toRequestBody(JSON))
            else -> builder.method(req.method, req.body?.toRequestBody(JSON))
        }
        client.newCall(builder.build()).execute().use { r ->
            Resp(r.code, r.body?.string().orEmpty())
        }
    }

    /** Cheap probe: list branches. Returns the branch count on success. */
    suspend fun testConnection(host: GitHost, token: String): Result<Int> = runCatching {
        val r = execute(GitContentsApi.listBranches(host, token))
        if (r.code !in 200..299) error("HTTP ${r.code}${shortError(r.body)}")
        runCatching { JSONArray(r.body).length() }.getOrDefault(0)
    }

    private fun shortError(body: String): String {
        val msg = runCatching { org.json.JSONObject(body).optString("message") }.getOrNull()
        return if (!msg.isNullOrBlank()) " — $msg" else ""
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

/** A contents-API file response carries base64 in "content" (GitHub wraps lines). */
internal fun decodeContentsBody(body: String): String? = runCatching {
    val b64 = org.json.JSONObject(body).optString("content").replace("\n", "").replace("\r", "")
    String(java.util.Base64.getDecoder().decode(b64), Charsets.UTF_8)
}.getOrNull()
